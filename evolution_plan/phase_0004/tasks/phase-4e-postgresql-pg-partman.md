# Phase 4E: PostgreSQL Backend and pg_partman Partitioning

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0004_plan.md`
- Supporting: `../../strategy/0003_db_cleanup_strategy.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`

## Goal
PostgreSQL backendを追加し、SQLiteと同じRepository contractでinsert/latest/history/cleanupを動かす。raw snapshot historyへのpg_partman採用可否を判断し、Phase 4ではplain PostgreSQLを基準実装として固定する。

## Non-Goals
- MySQL backendは追加しない。
- Hub/Node Agentのingest APIはPhase 5で扱う。
- pg_partman integrationは実装しない。必要な運用規模が見えた段階の独立taskで扱う。

## Current State
- PostgreSQL JDBC driver dependencyは未追加。
- storage backend判定、HikariCP connection、PostgreSQL用Flyway location selectionは実装済み。
- PostgreSQL用Flyway migration SQLは未追加。
- cleanup contractはPhase 4Dで先に固定する。
- Phase 4CではSQLite互換性を優先し、`TransactionRunner.readOnly`はJDBC `Connection.setReadOnly(true)`を使わない。
- SQLiteは同一DBファイルへ複数connectionを張れるが、Phase 4C時点ではHikariCP `maximumPoolSize = 1`、WAL mode未導入、`busy_timeout`未整理の保守運用に留めている。

## Boundary Decision
- Owner boundary: Repository / Storage Backend
- Why this belongs there: PostgreSQLのJSONB、timestamp、UUID、index、partitioning、extension availabilityはDB backend差分であり、API層へ漏らさない。
- Cross-boundary impact:
  - `storage.type = "postgresql"`または`jdbc:postgresql://...`でPostgreSQL backendを選択する。
  - Repository interfaceはSQLiteと同一。
  - Phase 4ではplain PostgreSQLを採用し、partitioning設定は追加しない。

## Task Breakdown
### Task 1: PostgreSQL driver and connectionを追加する
- Objective:
  - PostgreSQLへ接続できるようにする。
- Affected modules/files:
  - `diskinfo-agent/build.gradle.kts`
  - storage connection factory
  - config resolver tests
- Expected behavior:
  - `jdbc:postgresql://...`でPostgreSQL backendを選択する。
  - username/passwordを扱える。
  - HikariCPをPhase 4Aで採用した場合、PostgreSQLはpool経由で接続する。
- Validation:
  - URL/backend判定test。
  - local PostgreSQLがある場合のmanual `db migrate`。

### Task 2: Connection pool and transaction semanticsを見直す
- Objective:
  - PostgreSQL対応に合わせて、backend別のconnection pool、read-only transaction、SQLite並行性方針を整理する。
- Affected modules/files:
  - storage connection factory
  - transaction runner
  - config resolver / storage settings
  - integration tests
- Expected behavior:
  - PostgreSQLでは小さめのpoolを使い、必要なら`readOnly` transactionでJDBC `Connection.setReadOnly(true)`またはPostgreSQL向けtransaction optionを有効化できる。
  - SQLiteでは引き続きpool size 1を維持するか、WAL modeと`busy_timeout`を設定した上でpool拡張するかを明示的に決める。
  - `TransactionRunner.readOnly`/`readWrite`のinterfaceは維持し、JDBC read-only flagを有効化するかはbackend別実装詳細に閉じ込める。
  - SQLiteでread-only flagを再導入しない限り、`Cannot change read-only flag after establishing a connection`が再発しない。
- Validation:
  - SQLite regression test: `readOnly` queryがSQLiteで成功すること。
  - PostgreSQL integration test: `readOnly` queryと`readWrite` insertが同じRepository contractで動くこと。
  - PostgreSQLでread-only transactionを有効化する場合、write queryが拒否されることを確認する。
- Notes:
  - SQLiteは複数connectionを張れるが、同時writeは直列化される。WALなしでpoolだけ増やす判断は避ける。
  - PostgreSQL/RR構成を入れる場合も、Phase 4Eではまずsingle PostgreSQL backendのtransaction semanticsを固める。

### Task 3: PostgreSQL Flyway migrationを追加する
- Objective:
  - 現行`disk_snapshot` schemaをPostgreSQL SQLとして定義する。
- Affected modules/files:
  - `src/main/resources/db/migration/postgresql`
  - storage migrator
- Expected behavior:
  - JSONはPostgreSQL側では`jsonb`を使う。
  - timestamp/timezoneとUUID型をPostgreSQL前提で明示する。
  - `node_id`, `device_key`, `collect_time` indexを作る。
  - PostgreSQL migrationはSQLite migrationと分ける。
- Validation:
  - local PostgreSQLで`db migrate`が成功すること。
  - insert/latest/history repository integration check。

### Task 4: pg_partman採用判断を記録する
- Objective:
  - raw snapshot historyのpartition管理をPhase 4でpg_partmanに任せるか決める。
- Decision:
  - Phase 4ではpg_partmanを採用しない。
  - plain PostgreSQL + row-level DELETE cleanupを基準実装にする。
  - pg_partmanはデータ量、運用環境、extension version、maintenance ownerが具体化した時点の独立taskへ延期する。
- Expected behavior:
  - PostgreSQL backendはextensionなしで動作する。
  - partitioning configやschema分岐をPhase 4では追加しない。
  - Phase 4Dの厳密な`collect_time < cutoff` semanticsを維持する。
- Validation:
  - extensionなしのplain PostgreSQLでrepository/cleanup contractが動くこと。

### Deferred: pg_partman partition interval
- Objective:
  - 7日、14日、1ヶ月の候補から初期値を決める。
- Deferred decision candidate:
  - 将来採用する場合の初期候補は7日。
- Rationale:
  - default raw snapshot retentionが30日のため、7日partitionなら過剰保持が最大でもおおむね1partition分に収まる。
  - 14日はpartition数を減らせるが、30日retentionに対する過剰保持が大きくなる。
  - 1ヶ月は家庭内/NAS用途ではpartition数が少なく管理しやすいが、30日retentionとの境界が粗く、月長差もある。
  - 7日でも1年保持で約52partitionであり、Phase 4の想定規模では許容しやすい。
- Expected behavior:
  - `interval = "7 days"`をdefault候補にする。
  - intervalは将来設定可能にするが、Phase 4Eではまず`7 days`だけをsupportedにしてもよい。
  - pg_partmanのweekly boundaryは開始曜日の影響を受けるため、UTC基準かつ明示的なstart partitionを検討する。
- Validation:
  - partition intervalとretentionから、保持され得る最大期間をdoc/testで説明する。

### Deferred: pg_partman maintenanceとcleanup contract
- Objective:
  - 将来のpg_partman taskでPhase 4Dの`db cleanup` semanticsとの差を説明できるようにする。
- Expected behavior:
  - Phase 4ではplain tableのDELETE based cleanupだけを実装する。
  - partition drop/detach、partition dry-run result、BGW/cron ownershipは後続taskで扱う。
- Validation:
  - local pg_partman環境がある場合のmanual check。
  - extensionなし環境ではplain PostgreSQL testを優先する。

## CLI/API Compatibility
- `--db-url`互換を維持する。
- PostgreSQL credentialsはconfig/environmentを主経路にする。
- API payloadは変更しない。

## Data and Persistence Impact
- PostgreSQLではschema差分が出るが、Repository contractは同じにする。
- Phase 4の`disk_snapshot`はplain tableとする。
- 将来pg_partmanを導入する場合、plain tableからpartitioned tableへの変換は独立migration taskに分ける。

## Validation Plan
Default:

```text
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
```

Optional local PostgreSQL:

```text
./gradlew :diskinfo-agent:run --args='db migrate --db-url jdbc:postgresql://localhost:5432/cocoadiskinfo'
./gradlew :diskinfo-agent:run --args='db cleanup --db-url jdbc:postgresql://localhost:5432/cocoadiskinfo --dry-run'
```

## Risks and Open Questions
- pg_partmanはPhase 4で非採用とする。extension version、partition keyを含む主キー、migration分岐、maintenance owner、row cleanupとの差は後続taskの判断事項として残す。
- `TransactionRunner.readOnly`はPhase 4ではapplication intentのまま維持する。DB-level read-only transactionはreader/writer接続設計と合わせた後続taskで扱う。
- SQLiteのpool sizeを1より大きくする場合は、WAL mode、`busy_timeout`、write直列化、shutdown時のDataSource closeを同時に検証する。

## Implementation Order
1. PostgreSQL JDBC driverを追加する。
2. plain PostgreSQL用Flyway migrationを追加し、SQLiteとの差分をmigration location内に閉じる。
3. Phase 4Dのmaintenance contractをplain PostgreSQLのrow-level DELETEへ接続する。
4. PostgreSQL実DBでmigrateの冪等性とinsert/latest/history/cleanup contractを検証する。
5. pg_partman非採用判断と後続taskへ残す条件をphase planへ反映する。

## Implementation Status
- Implemented: PostgreSQL JDBC driverとplain PostgreSQL用Flyway migrationを追加した。
- Implemented: PostgreSQLではUUID、TIMESTAMPTZ、JSONBを使い、SQLiteと同じRepository/cleanup contractを維持した。
- Implemented: PostgreSQLのoptional maintenanceはrow-level DELETE後、transaction外で`VACUUM (ANALYZE)`を実行する。
- Implemented: PostgreSQLの単一V1 migrationにrow-level cleanup用`collect_time` indexを含めた。
- Decision recorded: pg_partmanはPhase 4では採用せず、partition移行とmaintenance ownershipは運用要件が具体化した後続taskへ延期する。
- Verified: PostgreSQL 17実DBでmigrationの二重実行、insert/latest/history、UUID/JSONB/TIMESTAMPTZ、count/delete、`VACUUM (ANALYZE)`を確認した。
