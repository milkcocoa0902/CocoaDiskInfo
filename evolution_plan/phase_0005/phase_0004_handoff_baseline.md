# Phase 4 Handoff Baseline

## Purpose
Phase 5開始時点で維持すべきPhase 4の機能、互換性、既知の制約を固定する。Phase 5の設計・実装判断は、特記がない限りこのbaselineを壊さない。

対象source baselineはcommit `22d1d76`。確認日は2026-08-09。

## Verification Snapshot
- `:diskinfo-agent:test`: 86 tests discovered、85 passed、1 skipped、0 failed。
- skipは明示的なPostgreSQL接続設定を要求する`PostgreSqlStorageIntegrationTest`のみ。
- `:diskinfo-agent:compileKotlin`、`:diskinfo-core:compileKotlin`、`:diskinfo-client:compileKotlinDesktop`は成功。
- PostgreSQL integrationはproperty/env gatedであり、通常test suiteの成功と実DB検証済みを区別して記録する。

## Runtime Topologies

### Oneshot
- `oneshot --scan`または`oneshot --device <path>`で1回収集する。
- `--persist`を指定した場合だけstorageへ接続し、outputとrepositoryの両方へ書く。
- persistenceなしではstorage connectionを生成しない。

### Standalone
- 1 process内でperiodic collection、local persistence、HTTP read API、startup/periodic cleanupを提供する。
- storage connectionはouter runtimeが1回生成し、collection/repository/API/maintenanceで共有して終了時にcloseする。
- startup cleanupはserver start前、periodic cleanupはKtor lifecycle内で動く。
- maintenance failureは警告して継続し、coroutine cancellationは伝播する。

### Database Commands
- `db migrate`はschema operationだけを行い、collection/runtime poolを起動しない。
- Flywayはmigration用DataSourceを直接管理し、HikariCP runtime poolを使わない。
- runtimeは暗黙にmigrationしないため、operatorが`db migrate`を明示実行する。
- `db cleanup`はretention、dry-run、optional vacuumを単発実行する。

## CLI Baseline
- Root commands: `oneshot`、`standalone`、`db migrate`、`db cleanup`。
- legacy mode flags `--oneshot`、`--agent`、`--migration`は受け付けない。
- collection targetは`--scan`と`--device`の排他選択。
- `oneshot`: `--output`、`--persist/--no-persist`、`--db-url`、`--config`。
- `standalone`: `--interval-seconds`、`--output`、`--host`、`--port`、`--db-url`、`--config`。
- `db cleanup`: `--raw-snapshot-days`（1..365）、`--dry-run`、`--vacuum/--no-vacuum`、`--db-url`、`--config`。

Phase 5はこれらのcommand shapeを維持し、新modeをadditiveに追加する。

## Configuration Baseline
- precedenceはdefaults < TOML < environment < CLI。
- default config pathは`/etc/cocoadiskinfo/agent.toml`で、存在しないdefault fileは無視する。
- unknown section/key、型不一致、範囲外値を明示的に拒否する。
- sections: `smartctl`、`deviceIdentity`、`output`、`runtime`、`storage`、`http`、`retention`、`maintenance`。
- defaults: SQLite `jdbc:sqlite:./sapphire.db`、HTTP `127.0.0.1:14631`、collection 60秒、raw retention 30日、startup cleanup enabled、cleanup interval 24時間、vacuum disabled。
- storage passwordを`toString`や通常ログへ出さない。

## Collection, Domain, and Output
- smartctlのscan/explicit device収集とATA/NVMe snapshot変換を提供する。
- `DiskSnapshot`と既存health evaluationは`diskinfo-core`のdomain contractである。
- node identityはhostname由来、device keyはtrim済みserialとnamespace saltから決定的に生成し、blank serialを拒否する。
- output mode enumは`DEFAULT`、`JSON`、`TEXT`、`CBOR`。
- 現実装では`DEFAULT`/`JSON`はdetail structure formatter、`TEXT`/`CBOR`はconsole text formatterを使う。Phase 4時点の`CBOR`はbinary CBOR出力ではない。

## Storage and Migration
- runtime backendはJDBC URLからSQLite/PostgreSQLを選ぶ。
- HikariCP runtime poolはSQLite最大1 connection、PostgreSQL最大5 connections。
- Phase 4 schemaはbackend別の単一V1で確定しており、Phase 5ではV1を編集せずV2以降を追加する。
- `disk_snapshot`はsnapshot ID、node/device identity、collect time、health/metric fields、full snapshot JSONを保存する。
- SQLiteはBINARY UUID、UTC text timestamp、BLOB JSON。PostgreSQLはUUID、TIMESTAMPTZ、JSONB。
- `(node_id, device_key, collect_time)`と`(collect_time)`のindexを持つ。
- transaction runnerのread-only/read-writeはapplication intentであり、SQLite JDBC制約のため実transaction propertyとしては双方write-capableで実行する。

## Repository and Query Baseline
- insert、all-node latest、device latest、node/device historyを提供する。
- historyは`nodeId + deviceKey`でscopeし、default limit 100、maximum 1000、inclusive `from`/`to`、ascending/descending orderを持つ。
- collect timeはUTCで保存する。
- Phase 4のall-node latestは全行をloadしてJVMでsort/distinctするため、複数Node・大量履歴のHub queryには再利用しない。
- device-only latestはnode scopeを持たないため、Hubではnode-scoped endpoint/queryを追加する。
- repository insert時のnode identityとsnapshot IDはlocal Standalone側で決まるため、remote ingest用にPhase 5Aで明示commandを追加する。

## HTTP API Baseline
- Ktor CIOのplain HTTP serverで、default bindはloopback、request authenticationはない。
- `GET /api/v1/snapshots/latest`。
- `GET /api/v1/devices/{deviceKey}/snapshots/latest`。
- `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots`。
- history queryは`limit`、ISO offset datetimeの`from`/`to`、`asc`/`desc` orderを検証する。
- response envelopeはsuccess payloadまたはstable error code/message。
- OpenAPIはPhase 4の3 read routesを記述する。

Phase 5では既存fieldを削除・renameせずmetadataをadditiveに追加し、Hubのdevice lookupはnode-scopedにする。

## Desktop Client Baseline
- Ktor CIOでAgentのplain HTTP read APIへ接続する。
- Java PreferencesへAgent URLとrefresh intervalを保存し、defaultは`http://localhost:14631`、60秒。
- latest nodesとnode/device history（limit 100、descending）を取得する。
- JSON decoderはunknown fieldsを無視する。
- signing key、Hub principal、insecure transport opt-inはまだ持たない。

## Retention and Maintenance
- raw retentionは1..365日、default 30日。
- cutoffはUTC clockから決定し、削除条件はstrict `collect_time < cutoff`。
- dry-runはcountだけを行い、deleteしない。
- real cleanupはcount/delete transactionのcommit後にoptional vacuumを実行する。
- SQLiteは`VACUUM`、PostgreSQLはtransaction外で`VACUUM (ANALYZE) disk_snapshot`。
- resultはcutoff、table、matched/deleted、dry-run、vacuum requested/executed/skipped reasonを持つ。
- periodic maintenanceは重複開始をskipし、停止時のcancellationを握り潰さない。

## Known Constraints Carried into Phase 5
- Standalone collection loopは処理がintervalを超えた場合にjobが重なる可能性がある。Node Agent modeでは逐次実行を明示する。
- all-node latestはapplication memory上で全履歴をmaterializeする。HubではDB-side bounded queryへ置き換える。
- server/clientはplain HTTPかつunauthenticated。Phase 5でtransport schemeに依存しないsigned requestへ移行し、HTTP client接続には明示opt-inを追加する。TLS終端はdeployment responsibilityとする。
- Node registry、received time、remote ingest idempotency ID、Principal、nonce/join stateはない。
- Node Agentの永続delivery queueはPhase 5 initial scope外であり、失敗時にsnapshotを失い得る。
- PostgreSQL integrationはdefault CIではskipされるため、release evidenceに実DB versionとgated test結果を残す。

## Phase 5 Ownership Map
- 5A: node-scoped ingest command、identity、idempotency。
- 5B: received time、Node registry、additional migrations、mode-neutral maintenance。
- 5C: JWS/digest、canonical request、nonce/join、Principal authorization。
- 5D: Hub runtime、explicit storage/migration boundary、authenticated ingest/heartbeat。
- 5E: Node Agent runtime、remote delivery、bounded retry、slow-run non-overlap。
- 5F: DB-side bounded aggregate latest、freshness、node-scoped read API。
- 5G: Desktop signed read、credential/transport handling、Hub-less compatibility。
- 5H: end-to-end acceptance、OpenAPI/config/operator handoff、Phase 4 regression。

## Baseline Verification Commands
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-client:compileKotlinDesktop
```

PostgreSQL integrationは空の専用databaseを明示設定して別途実行し、通常suiteのskipを実DB成功として扱わない。
