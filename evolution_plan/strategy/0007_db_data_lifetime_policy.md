# DB Data Lifetime Policy

## Summary
DBに保存するデータは、すべて同じ寿命で扱わない。

`diskinfo-agent` / `Hub` はディスク状態の履歴を扱うが、保存対象には現在状態、短期の高解像度履歴、長期傾向、Node Agent状態、エラー情報などが混在する。これらを一律に無期限保存するとDBが肥大化し、逆に一律に短期削除するとグラフや傾向分析ができなくなる。

この文書では、DBデータの種類ごとにライフタイム方針を定義する。

## Principles
1. 無期限に増え続けるテーブルを作らない。
2. 高頻度で増えるraw snapshotは短期保存にする。
3. 長期傾向が必要な場合は、raw snapshotを延命するのではなく集約データを追加する。
4. APIは常にbounded queryを前提にする。
5. データ削除は実行モードに応じて明示的に行う。
6. policy変更やDB backend追加で過去データの意味が変わらないよう、評価ポリシーの識別子を残せる設計にする。

## Data Classes
### Current State
現在状態は、ClientやPrometheusが読むための最新値。

- 例:
    - deviceごとのlatest snapshot
    - nodeごとのlatest status
    - Node Agent / Hubの現在の接続状態
- ライフタイム:
    - 原則として上書きまたは再構築可能なcacheとして扱う。
    - raw historyの保持期間とは分けて考える。
- 方針:
    - current stateはAPI応答の安定性のために持ってよい。
    - source of truthはraw snapshotまたはingestされたsnapshotとする。
    - materialized latest tableを作る場合も、再構築可能であることを前提にする。

### Raw Snapshot History
`DiskSnapshot` をそのまま、または検索用カラムとJSONで保存する高解像度履歴。

- 例:
    - `disk_snapshot`
    - 60秒周期で収集されるSMART/NVMe snapshot
- 既定ライフタイム:
    - 30日
- 設定候補:
    - `retention.rawSnapshotDays`
    - default: `30`
    - allowed range: `1..365`
- 方針:
    - 初期実装ではこのraw snapshotを履歴APIの対象にする。
    - unbounded readは禁止する。
    - 収集周期が短いほどDB肥大化するため、保持期間は設定可能にする。
    - 保持期間を過ぎたデータはhard deleteしてよい。

### Aggregated History
長期グラフや傾向確認のための集約データ。

- 例:
    - hourly temperature max/avg
    - daily worst health
    - daily max warning count
    - daily wear / percentage used
- 初期実装:
    - 必須ではない。
- 将来の既定ライフタイム候補:
    - hourly: 180日
    - daily: 3年
- 方針:
    - 長期表示が必要になったら、raw snapshotの保持期間を伸ばすのではなく集約テーブルを追加する。
    - 集約値はpolicy versionや集約ロジックの変更で意味が変わるため、aggregation versionを持たせる。
    - 集約元のraw snapshotを削除しても、長期傾向が残るようにする。

### Health Evaluation
`GOOD`, `CAUTION`, `BAD`, `UNKNOWN` や `AttributeEvaluation` の結果。

- 現状:
    - `DiskSnapshot.health` はsnapshotに含まれる。
    - `evaluations` はcore側のruleから計算される。
- 方針:
    - 過去snapshotのhealthを後から黙って再解釈しない。
    - 評価結果を永続化する場合は、`policyName` と `policyVersion` を持たせる。
    - UIで「当時の判定」と「現在policyで再評価した判定」を分けたくなった場合に備える。
- ライフタイム:
    - snapshotに埋め込む評価結果はsnapshotと同じ。
    - 集約評価はaggregated historyと同じ。

### Device Inventory
ノードとデバイスの存在情報。

- 例:
    - node id / node name
    - device key / serial / model / path
    - first seen / last seen
- ライフタイム:
    - active device: 無期限
    - inactive device tombstone: 180日を初期候補
- 方針:
    - device pathは変わる可能性があるため、device identityと表示pathを分ける。
    - 一定期間見えなくなったデバイスは即削除せず、inactiveとして扱う。
    - tombstone保持により、履歴UIで「過去に存在したデバイス」を説明できるようにする。

### Node Agent / Hub Runtime State
Node Agentの登録、heartbeat、最後の送信状態。

- 例:
    - node id
    - endpoint
    - capabilities
    - last heartbeat
    - last error
- ライフタイム:
    - current registry: activeな間は保持
    - inactive registry: 30日を初期候補
    - heartbeat event history: 初期実装では保存しない、保存する場合は7日
- 方針:
    - heartbeatをすべて履歴として保存しない。
    - 必要なのは通常 `lastHeartbeatAt` と `lastError`。
    - 詳細な接続履歴が必要になった場合のみ短期event tableを追加する。

### Error / Event History
収集失敗、smartctl失敗、DB cleanup結果、ingest失敗などのイベント。

- ライフタイム:
    - default: 30日
    - troubleshooting重視なら90日まで設定可能
- 設定候補:
    - `retention.eventDays`
- 方針:
    - 失敗理由は最新状態だけではなく、短期履歴として残す価値がある。
    - ただし通常のINFOログをDBに長期保存しない。
    - DBには運用判断に必要な構造化eventだけを入れる。

### Raw smartctl JSON
smartctlの元JSONをそのまま保存するデータ。

- 現状:
    - 保存していない。
- ライフタイム:
    - 保存する場合もdefault 7日
- 方針:
    - 容量と情報露出のリスクがあるため、既定では保存しない。
    - デバッグ用途として明示設定された場合のみ保存する。
    - 長期保存したい場合は外部ログ/オブジェクトストレージなどを別途検討する。

## Default Retention Proposal
初期の既定値は次を候補にする。

```text
raw snapshots:        30 days
aggregated hourly:    not implemented initially
aggregated daily:     not implemented initially
event history:        30 days
heartbeat events:      7 days if implemented
inactive node agents: 30 days
inactive devices:    180 days if inventory table is implemented
raw smartctl JSON:     disabled, 7 days if enabled
```

この既定値は家庭内/NAS/小規模サーバー用途を想定する。大規模運用では設定ファイルで調整する。

## Cleanup Policy
cleanupはDB種別ごとの差分を隠すため、RepositoryまたはStorage Maintenance層に置く。

### Automatic Cleanup
- `standalone` と `hub` は、起動時と定期実行でcleanupする。
- 既定の定期実行間隔は24時間。
- `oneshot` は自動cleanupしない。
- `node-agent` はローカル永続bufferを持つ場合のみ、そのbufferのcleanupを行う。

### Manual Cleanup
将来的には次のようなサブコマンドに寄せる。

```text
cocoadiskinfo-agent db cleanup
cocoadiskinfo-agent db cleanup --raw-snapshot-days 14
cocoadiskinfo-agent db cleanup --dry-run
```

### Dry Run
cleanupにはdry-runを用意する。

- 削除対象件数
- 対象期間
- テーブルごとの削除予定
- vacuum実行予定

を表示し、実データは削除しない。

### Vacuum / Analyze
- SQLiteでは削除後に必要に応じて `VACUUM` を実行する。
- PostgreSQL/MySQLでは同じ意味にならないため、DB backendごとのmaintenanceとして扱う。
- cleanupとvacuumを常に同時にしない。vacuumは重い操作として明示または低頻度にする。

## API Implications
履歴APIはDBライフタイムの影響を受けるため、次を守る。

- `limit` の既定値と最大値を持つ。
- `from`, `to`, `order` を検証する。
- 保持期間外のデータが存在しないことは正常系として扱う。
- Clientは「古い履歴がない」ことをエラー扱いしない。
- 長期グラフを出す場合は、raw history APIではなくaggregated history APIを検討する。

## Configuration Implications
設定候補:

```toml
[retention]
rawSnapshotDays = 30
eventDays = 30
heartbeatEventDays = 7
inactiveNodeAgentDays = 30
inactiveDeviceDays = 180
rawSmartctlJsonDays = 7
enableRawSmartctlJson = false

[maintenance]
cleanupOnStartup = true
cleanupIntervalHours = 24
vacuumAfterCleanup = false
```

初期実装では、すべての項目を同時に実装する必要はない。まず `rawSnapshotDays`, `cleanupOnStartup`, `cleanupIntervalHours` から始める。

## Migration Implications
DBライフタイム方針を導入する際は、次を確認する。

- 既存 `disk_snapshot` の削除条件は `collect_time` に基づく。
- `node_id`, `device_key`, `collect_time` のindexを維持する。
- 将来inventory tableやevent tableを追加する場合は、各tableにcleanup対象となるtimestampを持たせる。
- migrationはデータ削除とスキーマ変更を混ぜすぎない。
- 破壊的cleanupを伴う場合は、PR notesに保持期間と削除条件を書く。

## Non-Goals
- 初期段階でraw snapshotを年単位保存しない。
- cleanupをOSのcronだけに依存させない。
- PrometheusをCocoaDiskInfoの履歴DBとして扱わない。
- すべてのログをDBへ保存しない。
- policy変更時に過去の判定を黙って上書きしない。

## First Implementation Scope
最初に実装するなら、範囲は次に絞る。

1. `disk_snapshot` のraw snapshot retentionを設定可能にする。
2. defaultを30日にする。
3. standalone起動時にcleanupを実行する。
4. 24時間ごとにcleanupを実行する。
5. `db cleanup --dry-run` 相当の手動確認を追加する。
6. cleanup結果をログに出す。

集約履歴、inventory tombstone、event tableは後続でよい。
