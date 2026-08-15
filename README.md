# CocoaDiskInfo

[English](README.en.md)

CocoaDiskInfoは、ローカルPC、NAS、ホームサーバー、検証機などのS.M.A.R.T.情報を、同じ見方で継続的に確認するためのディスクヘルスビューアです。

CrystalDiskInfoのような一覧性を大切にしながら、`smartctl`が返すATA/SATAとNVMeの差を共通のsnapshotとhealthへ正規化します。単発確認だけでなく、履歴を保存し、画面のないマシンもDesktop Clientから確認できる小さなストレージObservability基盤を目指しています。

![CocoaDiskInfo client overview](docs/image/0001_client_overview.png)

## コンセプトと体験

ストレージの異常は、壊れた瞬間よりも「以前と比べて何が変わったか」が重要です。CocoaDiskInfoでは、温度、通電時間、書き込み量、代替セクタ、NVMe media errorなどを、デバイスごとの現在状態と時系列の履歴として扱います。

- **一覧から異常へすぐ辿り着ける**: ノード/デバイスをまとめて表示し、healthと警告を起点に詳細を確認する。
- **ATAとNVMeを同じ体験で見る**: protocol固有値を保持しつつ、共通の`DiskSnapshot`とhealthへ正規化する。
- **現在値だけで終わらない**: snapshotをSQLiteまたはPostgreSQLへ保存し、選択デバイスの履歴を確認する。
- **小さく始められる**: 1 processのStandalone構成で収集・保存・署名付きAPI公開を完結できる。
- **分散できる**: Node Agentを複数マシンへ置き、中央のHubからcache-firstで確認できる。

## 現在の実装状況

Phase 5まで実装済みです。

- `smartctl --json`によるdevice scanと明示deviceの収集
- ATA/SATA、NVMe snapshot変換
- `GOOD` / `CAUTION` / `BAD` / `UNKNOWN`のhealth判定
- Oneshotによる単発収集とoptional persistence
- Standaloneによる定期収集、永続化、HTTP API、定期cleanup
- SQLiteとPostgreSQLのstorage backend、Flyway migration
- node/device単位のlatestおよびbounded history API
- retention dry-run、削除、SQLite/PostgreSQL VACUUM
- Compose Desktop Clientの一覧、詳細、`Current | History`表示、自動refresh
- Hub / Node Agentの分散runtime、冪等なsnapshot ingest、heartbeat
- Ed25519 JWS、body digest、purpose-bound single-use nonceによる署名付きrequest
- Node Agent joinとDesktop Client pairing、owner-only credential file
- node/device横断のbounded aggregate、pagination、freshness/partial metadata

API requestは署名されますが、TLSの終端とcertificate lifecycleはdeployment側の責務です。HTTP接続はNode Agent/Clientごとの明示opt-inが必要で、server authentication、confidentiality、response integrityを提供しません。ALBやreverse proxyでHTTPSを終端する構成を選べます。mTLSとRedis nonce backendはPhase 5の対象外です。

## 実行モード

```text
oneshot      1回収集して出力する。--persist指定時だけDBへ接続する
standalone   定期収集、local DB、read API、maintenanceを1 processで実行する
hub          Node Agentから受信し、中央DB、read API、maintenanceを実行する
node-agent   定期収集し、署名付きrequestでHubへ送信する
db migrate   Flyway migrationだけを明示実行する
db cleanup   retention対象の確認・削除・optional vacuumを単発実行する
```

長時間動作する3モードのcapabilityは次の通りです。`node-agent`は収集と送信に専念し、local DBへの保存やClient向けAPI公開を行いません。`hub`はNode Agentからの受信に専念し、Hub自身のlocal deviceは収集しません。

| Capability | `standalone` | `node-agent` | `hub` |
| --- | :---: | :---: | :---: |
| local deviceの定期収集 | ✓ | ✓ | — |
| snapshotのlocal/central DB保存 | ✓ | — | ✓ |
| consoleへのsnapshot出力 | ✓ | ✓ | — |
| Desktop Client pairing token発行 | ✓ | — | ✓ |
| Desktop Client向けsigned latest/history API | ✓ | — | ✓ |
| Node Agent join token発行 | — | — | ✓ |
| Node Agentからのingest/heartbeat受信 | — | — | ✓ |
| Hubへのsigned snapshot push/heartbeat | — | ✓ | — |
| 起動時・定期retention maintenance | ✓ | — | ✓ |

したがって、Desktop Clientの接続先は`standalone`または`hub`です。単一machineでは`standalone`を使用し、複数machineを集約する場合は各収集machineで`node-agent`、中央側で`hub`を個別に実行します。現時点ではHubの多段構成と、1 process内でのHub + local collectionは対応していません。

## リポジトリ構成

```text
CocoaDiskInfo
├── diskinfo-core      # domain model、health判定、共有API payload
├── diskinfo-agent     # smartctl、CLI/runtime、storage、HTTP API
├── diskinfo-client    # Compose Desktop UI
├── evolution_plan     # strategy、phase plan、task、handoff baseline
├── sample             # smartctl JSON sample
└── docs/image         # README等で使用する画像
```

`diskinfo-core`は収集方法やDBを知らず、`diskinfo-agent`がCollector、Sink、Repository、runtimeを組み立てます。`diskinfo-client`はAPIから現在値と履歴を読みます。

## 必要なもの

- JDK 21
- `smartmontools`
- `smartctl`を実行できる権限

LinuxやmacOSでは、接続方式やdeviceによって`smartctl`に管理者権限が必要です。

## クイックスタート: SQLite Standalone

schema migrationはruntime起動時に自動実行しません。最初に明示的に実行します。

```bash
./gradlew :diskinfo-agent:run --args='db migrate'
./gradlew :diskinfo-agent:run --args='standalone --scan'
```

特定deviceだけを監視する場合:

```bash
./gradlew :diskinfo-agent:run --args='standalone --device /dev/sda'
```

既定では`./sapphire.db`へ保存し、`http://127.0.0.1:14631`でread APIを公開します。

### Desktop Client

```bash
./gradlew :diskinfo-client:run
```

Standaloneのread APIも署名付きです。先にpairing tokenを作成し、ClientのSettingsでendpoint、token情報、credential file、HTTP利用時の明示opt-inを入力してpairingします。

```bash
./gradlew :diskinfo-agent:run --args='standalone client-pairing-token create --public-endpoint http://127.0.0.1:14631 --allow-insecure-transport'
```

token作成commandが出力するsecretは一度だけ表示されます。shell history、設定ファイル、通常ログへ保存しないでください。device詳細の`Current | History`から現在値と直近履歴を切り替えられます。

## Hub / Node Agent

Hubはschemaを自動migrationしません。以下の順序で準備します。

```bash
./gradlew :diskinfo-agent:run --args='db migrate --config ./hub.toml'
./gradlew :diskinfo-agent:run --args='hub --config ./hub.toml'
```

別terminalで短命なjoin tokenを発行し、出力された`hubId`、`tokenId`、`tokenSecret`をNode側のjoinに渡します。

```bash
./gradlew :diskinfo-agent:run --args='hub join-token create --config ./hub.toml --node-name nas-01'
./gradlew :diskinfo-agent:run --args='node-agent join --config ./node.toml --hub-id <hubId> --token-id <tokenId> --token-secret <tokenSecret> --node-name nas-01'
./gradlew :diskinfo-agent:run --args='node-agent --config ./node.toml --scan'
```

Desktop Client用には`hub client-pairing-token create`を使います。credential recoveryでは`hub join-token create --recovery-node-id <existing-node-id>`により既存のnode identity/historyを維持できます。不要になった旧keyは`hub principal disable --kid <old-kid>`で無効化します。

## Oneshot

既定ではDBへ接続せず、HTTP serverも起動せずに、1回だけ収集して出力します。

```bash
./gradlew :diskinfo-agent:run --args='oneshot --scan'
./gradlew :diskinfo-agent:run --args='oneshot --device /dev/sda --output text'
./gradlew :diskinfo-agent:run --args='oneshot --scan --output json'
```

DBにも保存する場合だけ`--persist`を指定します。schemaは事前にmigrationしてください。

```bash
./gradlew :diskinfo-agent:run --args='db migrate'
./gradlew :diskinfo-agent:run --args='oneshot --scan --persist'
```

output modeには`default`、`json`、`text`、`cbor`があります。ただし現時点の`cbor`はbinary CBORではなくtext formatterを使用します。

## 設定

設定の優先順位は次の通りです。

```text
defaults < TOML config < environment variables < CLI arguments
```

`--config`を省略した場合、`/etc/cocoadiskinfo/agent.toml`が存在すれば読み込みます。unknown section/key、型不一致、範囲外値はエラーになります。完全な例は[agent.example.toml](diskinfo-agent/src/main/resources/agent.example.toml)を参照してください。

```toml
[smartctl]
scan = true
# device = "/dev/sda"

[deviceIdentity]
namespaceSalt = "default"

[runtime]
intervalSeconds = 60
persist = false

[storage]
type = "sqlite"
jdbcUrl = "jdbc:sqlite:./sapphire.db"

[http]
host = "127.0.0.1"
port = 14631

[publicEndpoint]
baseUrl = "https://hub.example"
allowInsecureTransport = false

[hub]
endpoint = "https://hub.example"
allowInsecureTransport = false
credentialFile = "/etc/cocoadiskinfo/node-credential.json"
# pemCaFile = "/etc/cocoadiskinfo/private-ca.pem"
heartbeatIntervalSeconds = 60
requestTimeoutSeconds = 30
maxRetries = 2

[auth]
nonceTtlSeconds = 60
maxRequestBodyBytes = 2097152

[retention]
rawSnapshotDays = 30

[maintenance]
cleanupOnStartup = true
cleanupIntervalHours = 24
vacuumAfterCleanup = false

[output]
mode = "text"
```

```bash
./gradlew :diskinfo-agent:run --args='db migrate --config ./agent.toml'
./gradlew :diskinfo-agent:run --args='standalone --config ./agent.toml'
```

利用できる環境変数:

```text
COCOADISKINFO_AGENT_SMARTCTL_SCAN
COCOADISKINFO_AGENT_SMARTCTL_DEVICE
COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT
COCOADISKINFO_AGENT_OUTPUT_MODE
COCOADISKINFO_AGENT_RUNTIME_PERSIST
COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS
COCOADISKINFO_AGENT_STORAGE_TYPE
COCOADISKINFO_AGENT_STORAGE_JDBC_URL
COCOADISKINFO_AGENT_STORAGE_USERNAME
COCOADISKINFO_AGENT_STORAGE_PASSWORD
COCOADISKINFO_AGENT_HTTP_HOST
COCOADISKINFO_AGENT_HTTP_PORT
COCOADISKINFO_AGENT_PUBLIC_ENDPOINT_BASE_URL
COCOADISKINFO_AGENT_PUBLIC_ENDPOINT_ALLOW_INSECURE_TRANSPORT
COCOADISKINFO_AGENT_HUB_ENDPOINT
COCOADISKINFO_AGENT_HUB_ALLOW_INSECURE_TRANSPORT
COCOADISKINFO_AGENT_HUB_CREDENTIAL_FILE
COCOADISKINFO_AGENT_HUB_PEM_CA_FILE
COCOADISKINFO_AGENT_HUB_HEARTBEAT_INTERVAL_SECONDS
COCOADISKINFO_AGENT_HUB_REQUEST_TIMEOUT_SECONDS
COCOADISKINFO_AGENT_HUB_MAX_RETRIES
COCOADISKINFO_AGENT_AUTH_NONCE_TTL_SECONDS
COCOADISKINFO_AGENT_AUTH_MAX_REQUEST_BODY_BYTES
COCOADISKINFO_AGENT_RETENTION_RAW_SNAPSHOT_DAYS
COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_ON_STARTUP
COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_INTERVAL_HOURS
COCOADISKINFO_AGENT_MAINTENANCE_VACUUM_AFTER_CLEANUP
```

## PostgreSQL

PostgreSQLを使う場合はbackendとcredentialを設定し、同じ`db migrate`を明示実行します。

```toml
[storage]
type = "postgresql"
jdbcUrl = "jdbc:postgresql://localhost:5432/cocoadiskinfo"
username = "cocoadiskinfo"
password = "change-me"
```

```bash
./gradlew :diskinfo-agent:run --args='db migrate --config ./agent-postgresql.toml'
./gradlew :diskinfo-agent:run --args='standalone --config ./agent-postgresql.toml'
```

passwordは通常ログへ出さない実装ですが、productionでは設定ファイルのpermissionとsecretの受け渡しを適切に管理してください。

## RetentionとDB maintenance

Standaloneは既定で起動時と24時間ごとに、30日より古いraw snapshotをcleanupします。境界上のsnapshotは保持し、`collect_time < cutoff`だけを削除します。

単発のdry-runとcleanup:

```bash
./gradlew :diskinfo-agent:run --args='db cleanup --raw-snapshot-days 30 --dry-run'
./gradlew :diskinfo-agent:run --args='db cleanup --raw-snapshot-days 30'
./gradlew :diskinfo-agent:run --args='db cleanup --raw-snapshot-days 30 --vacuum'
```

SQLiteでは`VACUUM`、PostgreSQLではtransaction外で`VACUUM (ANALYZE) disk_snapshot`を実行します。削除前には必ずdry-run結果と対象DBを確認してください。

## HTTP API

HubとStandaloneは次の署名付きcache/history-first read APIを提供します。Standaloneだけがdevice-only compatibility routeも提供します。

```text
GET /api/v1/snapshots/latest
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots/latest
GET /api/v1/devices/{deviceKey}/snapshots/latest
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots
    ?limit=100&from=<ISO-8601>&to=<ISO-8601>&order=desc
```

aggregateの`limit`は1..500、既定100で、opaqueな`cursor`によるkeyset paginationです。historyの`limit`は1..1000、既定100です。`from`と`to`はinclusiveで、`order`は`asc`または`desc`です。`deviceKey`はOS pathやraw serialではなく、opaqueなstable identityとして扱ってください。認証bootstrap/ingest/heartbeatを含むwire contractはOpenAPIを参照してください。

OpenAPIは[documentation.yaml](diskinfo-agent/src/main/resources/openapi/documentation.yaml)にあります。

## systemdテンプレート

Linux向けに[deploy/systemd/cocoadiskinfo-agent.service](deploy/systemd/cocoadiskinfo-agent.service)を用意しています。現時点では正式なOS packageではなく、開発・検証用テンプレートです。

```bash
./gradlew :diskinfo-agent:shadowJar
sudo install -d /opt/cocoadiskinfo /var/lib/cocoadiskinfo /etc/cocoadiskinfo
sudo install -m 0644 diskinfo-agent/build/libs/diskinfo-agent-1.0-SNAPSHOT-all.jar /opt/cocoadiskinfo/
sudo install -m 0640 diskinfo-agent/src/main/resources/agent.example.toml /etc/cocoadiskinfo/agent.toml
(cd /var/lib/cocoadiskinfo && sudo /usr/bin/java -jar /opt/cocoadiskinfo/diskinfo-agent-1.0-SNAPSHOT-all.jar db migrate --config /etc/cocoadiskinfo/agent.toml)
sudo install -m 0644 deploy/systemd/cocoadiskinfo-agent.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cocoadiskinfo-agent
```

## 開発と検証

```bash
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-client:compileKotlinDesktop
```

PostgreSQL integration testは明示的な接続設定と空の専用databaseがある場合だけ実行されます。通常suiteでskipされた結果を実DB成功として扱わないでください。

`sample/`には`smartctl --json`相当の入力例があります。converterやhealth判定を変更するときはATA/SATAとNVMeの両方を確認してください。

## ロードマップ

- **Phase 5（実装済み）**: Hub / Node Agent、JWS + body digest、nonce/join、transport opt-in、aggregate freshness
- **Phase 6**: health policy versioningと説明性
- **Phase 7**: package、systemd、permission、log運用の確定
- **Phase 8以降**: Prometheus、chart、長期aggregate

詳細は[evolution plan](evolution_plan/master.md)、[Phase 5 plan](evolution_plan/phase_0005/phase_0005_plan.md)、[Phase 5 handoff](evolution_plan/phase_0005/phase_0005_handoff.md)を参照してください。
