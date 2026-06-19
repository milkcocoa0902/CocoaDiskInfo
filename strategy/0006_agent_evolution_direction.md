# diskinfo-agent Evolution Direction

## Summary
`diskinfo-agent` は、単体マシンのSMART確認ツールから、複数ノードのディスク状態を継続監視するための小さなObservability基盤へ発展させる。

この文書は詳細設計ではなく、今後のstrategyや実装PRが方針を外さないための上位方針を定義する。個別機能はこの文書の分離方針と実装順に沿って、別strategyまたはPRで具体化する。

## Direction
当面の中心方針は、機能を増やす前に `収集`, `保存`, `公開`, `表示`, `判定` の責務を分けること。

現状の `agent` は、smartctl実行、定期ループ、SQLite保存、HTTP API公開が近い場所にまとまっている。このままDB対応、複数agent集約、Prometheus、設定ファイル、パッケージングを追加すると、実行モードごとの差分が読みにくくなる。

そのため、最初の大きなマイルストーンは **内部境界の整理** とする。

## Terms
- `Collector`: ローカルの `smartctl` 実行から `DiskSnapshot` を生成する責務。DB、Ktor、Colotok、リモート送信を知らない。
- `Sink`: 生成された `DiskSnapshot` の出力先。stdout、local repository、remote HTTP などを差し替える境界。
- `Repository`: スナップショットの保存と検索を扱う責務。SQLite、PostgreSQL、MySQLなどの違いをここに閉じ込める。
- `Standalone`: 1プロセス内でローカル収集、保存、API公開を行うモード。
- `Node Agent`: 各マシンで動き、ローカル収集結果を中央へ送るプロセス。既存strategyの `collector` に近い。
- `Hub` / `Master`: 複数のNode Agentから受け取り、集約キャッシュ、履歴、Client APIを提供する中央側プロセス。
- `Client`: AgentまたはHubのAPIを読み、現在状態と履歴を表示するUI。

今後は、中央側を `collector` と呼ばない。`collector` は収集側の概念に寄せ、集約側は `Hub` または `Master` と呼ぶ。

## Architecture Principles
1. 実行モードごとの判断は、できるだけCLI/Executor組み立て層に集める。
2. `Collector` は `DiskSnapshot` を作るだけにする。保存、API、ログ出力、HTTP送信を持たせない。
3. 永続化は `Repository` に集約する。DB種別の違いをドメインモデルやAPI層に漏らさない。
4. APIの通常応答は history/cache-first とする。live取得は明示指定されたときだけ行う。
5. Clientはまず現在状態を見やすく保ち、履歴やグラフは選択デバイスの詳細内に閉じる。
6. Health判定は固定ロジックではなく、名前付きの判定ポリシーとして扱えるようにする。
7. apt/systemd/Prometheus対応は、コマンド、設定ファイル、データ配置が安定してから進める。

## Target Execution Model
将来的なCLIはフラグ中心ではなく、サブコマンド中心にする。

```text
cocoadiskinfo-agent oneshot --scan
cocoadiskinfo-agent standalone --scan
cocoadiskinfo-agent node-agent --config /etc/cocoadiskinfo/agent.toml
cocoadiskinfo-agent hub --config /etc/cocoadiskinfo/hub.toml
cocoadiskinfo-agent db migrate
cocoadiskinfo-agent db cleanup
```

暫定的に既存の `--oneshot`, `--agent`, `--migration` を維持してもよいが、最終形はサブコマンドへ寄せる。

### Oneshot
- 1回だけ収集して出力する。
- DB接続は既定では行わない。
- `--persist` がある場合のみ保存してよい。

### Standalone
- ローカル収集、ローカル保存、API公開を1プロセスで行う。
- 小規模運用と開発時の基準モードにする。
- Hub導入前でもClientが履歴を読める状態を目指す。

### Node Agent
- ローカル収集結果をHubへ送る。
- 初期実装では送信失敗時の永続キューは必須にしない。
- 将来 `SqliteSnapshotBuffer` などを追加できるよう、buffer interfaceだけ意識する。

### Hub / Master
- Node Agentからsnapshotを受け取り、保存し、Client向けAPIを提供する。
- 一部Node Agentが停止しても、前回キャッシュとstale/error情報で部分成功を返す。
- `source=live` のような同期問い合わせは初期必須にしない。

### DB Commands
- migrate、cleanup、vacuumなどのスキーマ/メンテナンス操作だけを担当する。
- 収集処理を持たせない。

## Configuration Policy
設定は次の優先順位にする。

```text
default < config file < environment variables < CLI arguments
```

設定ファイルで扱う候補:
- node identity: `nodeId`, `nodeName`
- smartctl: `smartctlPath`, `devices`, `scan`
- runtime: `mode`, `interval`, `retentionDays`
- HTTP: `host`, `port`, `baseUrl`
- storage: `type`, `jdbcUrl`, `username`, `password`
- hub: `endpoint`, `collectorId`, `heartbeatInterval`
- health: `policy`, `policyVersion`

設定ファイルの形式は未決定。TOMLまたはYAMLを候補とし、パッケージング時の `/etc/cocoadiskinfo/*.toml` 配置を想定して検討する。

## Storage Direction
最初はSQLiteを基準実装とする。PostgreSQL/MySQL対応は、JDBC URLを増やすだけではなく、`SnapshotRepository` のbackend追加として扱う。

DBデータのライフタイムは、DB backend追加より前に明文化する。raw snapshot、current cache、aggregated history、runtime event、raw smartctl JSONを同じ保持期間で扱わない。詳細は `0007_db_data_lifetime_policy.md` に従う。

DBごとに次の差分があるため、storage層に閉じ込める。
- JSON/JSONBの扱い
- timestamp/timezoneの扱い
- UUID型の扱い
- migration構文
- indexとquery plan
- VACUUM/cleanupの意味

実装順は次を基本にする。

1. SQLite repositoryを明示的な基準実装にする。
2. Repository interfaceとinsert/history/latest queryを固定する。
3. PostgreSQL backendを追加する。
4. MySQL backendはPostgreSQL対応後に検討する。

## API Direction
現在状態のAPIと履歴APIを分ける。

- current-state:
    - `GET /api/v1/snapshots/latest`
    - low latency、bounded response、history/cache-first
- device history:
    - `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots`
    - `limit`, `from`, `to`, `order` を持つ
    - unbounded responseは禁止
- ingest:
    - `POST /api/v1/snapshots`
    - Hub/AgentがNode Agentからsnapshotを受け取る
- metrics:
    - `GET /metrics`
    - Prometheus export用。履歴保存の主経路にはしない。

APIレスポンスには、将来のHub集約に備えて freshness metadata を追加できる余地を残す。

候補:
- `collectedAt`
- `receivedAt`
- `ageMs`
- `stale`
- `collectorId`
- `errors[]`
- `partial`

## Client Direction
Clientは、まず現在状態を読みやすく保つ。

- 一覧は現在状態を中心にする。
- 履歴は選択デバイスの詳細内に `Current | History` のような切替で置く。
- 初期のHistoryは、複雑なグラフよりも、summary tiles と timeline を優先する。
- グラフはAPI shapeが安定してから追加する。

グラフ候補:
- temperature over time
- percentage used / lifetime remaining over time
- warning count over time
- media error / reallocated sector count over time

## Health Policy Direction
`GOOD`, `CAUTION`, `BAD`, `UNKNOWN` はUIに出るため、互換性と説明可能性を重視する。

今後は、判定を単なる固定しきい値ではなく、次の情報を持つ policy として扱う。

- `policyName`
- `policyVersion`
- `ruleKey`
- `severity`
- `threshold`
- `value`
- `reason`
- `confidence` または `source` は必要になった時点で検討

初期候補:
- `default`: 現在の保守的な判定
- `strict`: 早めにCAUTION/BADへ寄せる
- `relaxed`: 一部の増加カウンタを即BADにしない

ただし、最初から複数policyを実装する必要はない。先に `AttributeEvaluation` の説明性とpolicy識別子を整える。

## Packaging Direction
aptなどのパッケージングは、次が安定してから進める。

- サブコマンド
- 設定ファイル形式
- DB配置
- systemd unit
- 実行ユーザー/権限方針
- ログ出力方針

想定配置:

```text
/usr/bin/cocoadiskinfo-agent
/etc/cocoadiskinfo/agent.toml
/etc/cocoadiskinfo/hub.toml
/var/lib/cocoadiskinfo/sapphire.db
/var/log/cocoadiskinfo/
```

`smartctl` は環境によってroot権限が必要なため、専用ユーザー運用を急がず、まずはroot実行のsystemd unitを安全に制限する。

## Prometheus Direction
Prometheus対応は、CocoaDiskInfoの履歴DBを置き換えるものではなく、外部監視へ現在値をexportする追加インターフェースとして扱う。

初期の `/metrics` はHubまたはStandaloneから公開する。

注意点:
- labelを増やしすぎない。
- serial numberの露出は設定で制御する。
- history queryをPrometheusに依存させない。
- metric nameは安定させる。

## Implementation Phases
### Phase 1: Internal Boundaries
- `SmartctlCollector` を追加する。
- `SnapshotSink` を追加する。
- `SnapshotRepository.insert(...)` を追加する。
- `SapphireAgentServer` をExecutorから切り離す。
- 既存Oneshot/Agentの挙動をできるだけ変えず、内部だけ整理する。

### Phase 2: Subcommands and Config
Phase 2は、前半でCLI形状を安定させ、後半で設定ファイルと運用設定の扱いを固める。

#### Phase 2A: Subcommand Migration
- Cliktサブコマンドへ移行する。
- 既存のmode flagsは、未リリースであれば互換維持しなくてよい。
- 最低限、次のサブコマンドを提供する。
    - `oneshot`
    - `standalone`
    - `db migrate`
- 既存のoneshot/agent/migration相当の挙動を、新サブコマンドで再現する。
- README、AGENTS、systemdテンプレートの実行例を新CLIへ更新する。
- 代表的なhelp/error/migrationコマンドを確認する。

#### Phase 2B: Config and Runtime Settings
- 設定ファイル形式を固定する。初期方針はTOML。
- 設定の優先順位を実装で明確にする。
    - `default < config file < environment variables < CLI arguments`
- 設定ファイルの読み込み対象を整理する。
    - `smartctl`: `scan`, `device`, 将来 `smartctlPath`
    - `runtime`: `intervalSeconds`, `persist`
    - `storage`: `jdbcUrl`, 将来 `type`, `username`, `password`
    - `http`: `host`, `port`
    - `output`: `mode`
    - 将来 `retention`, `maintenance`, `health`
- 設定値のvalidationを明示する。
    - device指定とscan指定の競合
    - interval/portの範囲
    - output modeの列挙値
    - DB URLの空文字
- 運用配置を意識したdefault pathを決める。
    - 開発時: 明示 `--config`
    - systemd/package想定: `/etc/cocoadiskinfo/agent.toml`
- 設定ファイルのサンプルを管理する。
    - repository内に example TOML を置く。
    - READMEから参照する。
- 設定ファイル由来の挙動を代表コマンドで確認する。
    - `oneshot --config ...`
    - `standalone --config ...`
    - `db migrate --config ...`
- Phase 2Bでは、DB backend追加、retention実装、Hub/Node Agent設定はまだ必須にしない。

### Phase 3: History API and Client History
- node/device単位のbounded history APIを追加する。
- ClientにHistory tabを追加する。
- 初期はtimelineとsummaryを優先し、グラフは後続にする。

### Phase 4: Storage Backends
- Repository interfaceを固める。
- SQLite backendを基準実装として整理する。
- PostgreSQL backendを追加する。
- MySQL backendは必要性と差分を確認して追加する。

### Phase 5: Hub and Node Agent
- ingest APIを追加する。
- Node AgentからHubへsnapshotを送信する。
- Hubはcache-firstで集約APIを返す。
- stale/partial/error metadataをAPIに入れる。

### Phase 6: Health Policy
- policy識別子を導入する。
- rule keyとreasonを整理する。
- しきい値の設定ファイル化を検討する。

### Phase 7: Packaging and Operations
- systemd unitをサブコマンド/設定ファイル前提へ更新する。
- `/etc`, `/var/lib`, `/var/log` 配置を固定する。
- `.deb` パッケージを作る。

### Phase 8: Prometheus Export
- `/metrics` を追加する。
- Standalone/Hubで現在値をexportする。
- READMEにPrometheus scrape例を追加する。

## Non-Goals For Now
- 最初から分散DBや高可用Hubを作らない。
- Node Agentの永続retry queueを初期必須にしない。
- Prometheusを主履歴DBとして扱わない。
- Clientのグラフを先に作り込まない。
- DB backend追加前に各層へDB依存を広げない。
- Health判定を説明不能なスコアに置き換えない。

## Relationship To Existing Strategy Docs
- `0001_api_response_strategy.md`: history/live切替方針として維持する。
- `0002_future_architecture.md`: Hub/Master集約方針として維持する。ただし中央側の呼称はHub/Masterへ寄せる。
- `0003_db_cleanup_strategy.md`: storage maintenance方針として維持する。
- `0004_future_architecture_mode.md`: Phase 1/2の具体化に近い。最初に実装する候補。
- `0005_device_history_ui_api_strategy.md`: Phase 3の具体化に近い。
- `0007_db_data_lifetime_policy.md`: DB内データ種別ごとの保持期間とcleanup方針として維持する。

## Decision Rule
新しい要望が出たら、次の順で判断する。

1. その変更は `Collector`, `Sink`, `Repository`, `Server`, `Client`, `Health Policy` のどこに属するか。
2. 複数の責務をまたぐ場合、先に境界を作れないか。
3. 既存CLI/API互換を壊す必要があるか。
4. 設定ファイル、systemd、パッケージングに影響するか。
5. SQLite以外のDBやHub構成でも同じ設計で説明できるか。
6. Clientで現在状態の見やすさを損なわないか。

この判断で説明しにくい変更は、実装前に別strategyとして整理する。
