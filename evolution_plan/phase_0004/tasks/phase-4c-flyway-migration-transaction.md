# Phase 4C: Flyway Migration and Transaction Boundary

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0004_plan.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`

## Goal
`db migrate`をExposed `MigrationUtils`からFlywayのversioned SQL migrationへ移行し、schema source of truthをFlyway SQLへ寄せる。同時に、UseCase相当層をtransaction境界にして、Repository実装からtransaction開始責務を外す。

## Non-Goals
- cleanupやretention削除は実装しない。Phase 4Dで扱う。
- pg_partman partitioningは実装しない。Phase 4Eで扱う。
- `TransactionResult`、advisory lock、reader/writer DB分離は導入しない。
- pre-release段階のため、Flyway管理外の既存DBを自動baselineする後方互換処理は導入しない。

## Current State
- `SapphireExecutor.Migrate`はSQLite driver固定で接続し、`DROP TABLE IF EXISTS disk_snapshot`を実行してからExposed migration SQLを流している。
- migration sourceがExposed table definitionに残っており、Flyway SQLとの差分を検出しにくい。
- test helperもExposed `MigrationUtils`でschemaを作ると、Flyway SQLが壊れてもrepository testsで検出できない。
- Repository queryは各method内でExposed `transaction { ... }`を直接呼んでいる。
- `TransactionRunner`は追加途中だが、Phase 4Cでは`TransactionResult`を導入せず、UseCase相当層が成功時の戻り値と失敗時の例外伝播を扱う。

## Boundary Decision
- Owner boundary: UseCase, Repository, and DB command assembly
- Why this belongs there: schema migrationはstorage層の実装詳細であり、transaction開始は複数Repository操作をまとめ得るUseCase相当層の責務として扱う。Collector/Server/ClientにはExposed transactionを漏らさない。
- Cross-boundary impact:
  - `db migrate`はFlyway migratorを呼ぶだけにする。
  - Server/SinkはRepositoryを直接呼ばず、UseCase相当層を呼ぶ。
  - Repository実装はtransaction開始を行わず、既存transaction内でSQL/Exposed DSLだけを実行する。

## Task Breakdown
### Task 1: Flyway migratorを追加する
- Objective:
  - Exposed schema diffではなく、versioned SQL migrationを実行する。
- Affected modules/files:
  - `diskinfo-agent/build.gradle.kts`
  - 新規 `diskinfo-agent/src/main/kotlin/.../datastore/StorageMigrator.kt`
  - 新規 `diskinfo-agent/src/main/resources/db/migration/...`
  - `SapphireExecutor.kt`
- Expected behavior:
  - `db migrate`はFlyway `migrate`を実行する。
  - migration時のconnection/DataSource lifecycleはFlywayが直接管理し、Hikari runtime poolを経由しない。
  - migration scriptはversion controlに置く。
  - empty DBでは`V1__create_disk_snapshot...`が適用される。
  - `flyway_schema_history`で適用済みmigrationを管理する。
  - `db migrate`を複数回実行しても安全に成功する。
- Validation:
  - 一時SQLite DBで`db migrate`を2回実行して成功すること。
  - migration適用後に`disk_snapshot`が存在すること。
- Notes:
  - ShadowJarでFlywayをfat jar化する場合、Flywayのdatabase plugin discoveryは`META-INF/services/org.flywaydb.core.extensibility.Plugin`に依存する。
  - `gradlew run`では各dependency jar上のServiceLoader metadataが個別に読まれるため成功しても、shadowJarでは同名service fileがmergeされないと`No Flyway database plugin found to handle jdbc:sqlite:...`で失敗する。
  - `mergeServiceFiles()`だけで不十分な場合は、Shadow taskで重複service fileをtransformerへ渡すために`duplicatesStrategy = DuplicatesStrategy.INCLUDE`を明示する。
  - fat jar検証では次を確認する。

```text
unzip -p diskinfo-agent/build/libs/diskinfo-agent-1.0-SNAPSHOT-all.jar META-INF/services/org.flywaydb.core.extensibility.Plugin
```

  - SQLiteでは少なくとも`org.flywaydb.core.internal.database.sqlite.SQLiteDatabaseType`が含まれることを確認する。`flyway-database-nc-sqlite`を使う場合は`org.flywaydb.nc.sqlite.NativeConnectorsSqlite`も含まれ得る。

### Task 2: test helperのschema作成をFlywayへ統一する
- Objective:
  - production/testの両方で、schema作成をFlyway SQLへ一本化する。
- Affected modules/files:
  - test helper
  - migration tests
- Expected behavior:
  - `connectDiskSnapshotTestDatabase()`はExposed `MigrationUtils`を使わない。
  - repository testsもFlyway `V1__create_disk_snapshot_table.sql`で作成したschemaに対して実行される。
  - `diskinfo-agent/src/test`からExposed migration API依存を撤去する。
- Validation:
  - repository testsがFlyway-created schemaで成功すること。
  - `rg MigrationUtils diskinfo-agent/src/test`でヒットしないこと。
- Notes:
  - Phase 4C時点ではpre-release扱いのため、Flyway管理外DBの移行互換性は扱わない。

### Task 3: DB別migration locationを決める
- Objective:
  - SQLite/PostgreSQLでSQL差分が出てもstorage層に閉じ込める。
- Affected modules/files:
  - `src/main/resources/db/migration/sqlite`
  - `src/main/resources/db/migration/postgresql`
  - storage migrator
- Expected behavior:
  - backendごとにFlyway locationを選択できる。
  - 共通SQLに無理に寄せず、JSON/UUID/timestamp/index差分をDB別SQLで表現できる。
- Validation:
  - SQLite migration locationが選択されるunit test。
  - PostgreSQL location selectionはPhase 4Eで追加検証する。

### Task 4: UseCase相当層とTransactionRunnerを追加する
- Objective:
  - Repository内に散っているExposed transaction入口をUseCase相当層へ集約する。
- Affected modules/files:
  - 新規 `diskinfo-agent/src/main/kotlin/.../datastore/TransactionRunner.kt`
  - 新規 `diskinfo-agent/src/main/kotlin/.../usecase/SnapshotUseCase.kt`
  - Repository implementation
  - `SnapshotSink.kt`
  - `SapphireAgentServer.kt`
  - `SapphireCommandRuntime.kt`
- Expected behavior:
  - 初期interfaceは軽量にする。

```kotlin
interface TransactionRunner {
    suspend fun <T> readOnly(block: suspend () -> T): T
    suspend fun <T> readWrite(block: suspend () -> T): T
}
```

  - UseCase相当層は`saveSnapshot`、`findLatestNodes`、`findLatestByDeviceKey`、`findHistory`を持ち、それぞれ適切なtransactionを選ぶ。
  - Repository implementationは`transaction { ... }`を直接開始しない。
  - `readOnly`/`readWrite`はPhase 4Cではアプリケーション上の意図として扱い、JDBC `Connection.setReadOnly(true)`へは落とさない。SQLite JDBCは接続確立後のread-only flag変更を拒否するため、実DB read-only化はRRやDB別接続設計が入る将来タスクに回す。
  - SQLiteは複数connection自体は可能だが、Phase 4CではSQLite `maximumPoolSize = 1`を前提にする。WAL mode、`busy_timeout`、SQLite read/write並行性、pool size拡張はPostgreSQL対応時に改めて比較・判断する。
  - `CancellationException`を握りつぶさない。
  - Repository呼び出し元の既存エラー処理を維持する。
- Validation:
  - repository tests。
  - server route tests。

## CLI/API Compatibility
- `db migrate`サブコマンド名は維持する。
- `db migrate`はschema operationのみで、collection behaviorを持たない。
- API payloadは変更しない。

## Data and Persistence Impact
- Flyway history tableが追加される。
- 初期migrationは現行`disk_snapshot` schemaを再現する。
- Flyway管理外の既存DBに対する自動baselineは行わない。
- 利用者がいないpre-release期間は、未公開migrationをSQLite/PostgreSQLそれぞれ単一V1へsquashしてよい。既存の開発DBは再作成する。

## Validation Plan
```text
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:run --args='db migrate --db-url jdbc:sqlite:/tmp/cocoadiskinfo-phase4c.db'
./gradlew :diskinfo-agent:run --args='db migrate --db-url jdbc:sqlite:/tmp/cocoadiskinfo-phase4c.db'
./gradlew :diskinfo-agent:shadowJar
java -jar diskinfo-agent/build/libs/diskinfo-agent-1.0-SNAPSHOT-all.jar db migrate --db-url jdbc:sqlite:/tmp/cocoadiskinfo-phase4c-shadow.db
```

## Risks and Open Questions
- Flyway dependencyはバージョンによってDB support artifactが分かれる可能性があるため、実装時にSQLite/PostgreSQLの必要artifactを確認する。
- ShadowJarではServiceLoader metadataのmergeが必要。`META-INF/services/org.flywaydb.core.extensibility.Plugin`にSQLite/PostgreSQLのplugin entriesが残っていることをjar内容で確認する。
- Exposed table definitionとFlyway SQLの二重管理になる。今後はFlyway SQLをschema source of truthとして扱う。
- Flyway管理外の既存DBを指定した場合はmigration failureになり得る。pre-release段階では許容する。
- JDBC read-only flagはSQLiteでruntime errorになり得るため、Phase 4Cでは有効化しない。PostgreSQL/RR導入時にbackend別transaction optionとして再検討する。
- SQLite multi-connectionはWAL modeや`busy_timeout`なしに広げるとlock待ちやwrite直列化の挙動が読みにくい。Phase 4Cではpool size 1に留め、PostgreSQL backend追加時にSQLiteとPostgreSQLのconnection/pool/read-only semanticsをまとめて見直す。

## Implementation Order
1. Flyway dependencyとDB support artifactを追加し、ShadowJarのServiceLoader metadata mergeを確認する。
2. SQLite用のversioned SQL migrationを追加し、`db migrate`をFlyway migratorへ差し替える。
3. empty SQLite DBで`db migrate`を2回実行し、冪等に成功することを確認する。
4. test helperのschema作成をFlyway migrator経由へ変更し、Exposed `MigrationUtils`依存を撤去する。
5. backend別migration location selectionをstorage層に閉じ込める。
6. `TransactionRunner`と`SnapshotUseCase`を追加し、Server/SinkからのDBアクセスをUseCase経由にする。
7. Repository実装内のExposed `transaction { ... }`を撤去し、UseCaseをtransaction境界にする。
8. `shadowJar`成果物を`java -jar ... db migrate`で実行し、Flyway plugin discoveryがfat jarでも壊れていないことを確認する。
