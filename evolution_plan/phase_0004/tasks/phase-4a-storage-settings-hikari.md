# Phase 4A: Storage Settings and HikariCP Decision

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0004_plan.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`

## Goal
SQLite固定のDB接続処理を、storage設定からDB接続を作る境界へ移す。同時に、HikariCPをPhase 4で導入するか判断し、導入する場合のpool lifecycleと設定範囲を固定する。

## Non-Goals
- Repository queryやschemaを変更しない。
- PostgreSQL固有schemaやpg_partman設定はこのtaskでは実装しない。
- Koin annotation plugin、reader/writer分離、JDBC/R2DBC切替抽象は導入しない。

## Current State
- `SapphireCommandRuntime.connectDatabase()`は`Database.connect(jdbcUrl, "org.sqlite.JDBC")`でSQLite driver固定。
- `SapphireExecutor.Migrate`もSQLite driver固定で接続している。
- `AgentConfig.StorageConfig`は`jdbcUrl`のみを持つ。
- `oneshot`は`--persist`がない場合はDB接続しない。この挙動は維持する。

## Boundary Decision
- Owner boundary: configuration/runtime settings and Repository assembly
- Why this belongs there: driver selection、DataSource/pool、credential解決はDB backend差分であり、Collector/Server/Clientへ漏らさない。
- Cross-boundary impact:
  - `SapphireCommandRuntime`はstorage factoryを呼ぶだけにする。
  - Repositoryは接続済みDatabase/DataSourceを受け取る。
  - `db migrate`/`db cleanup`も同じstorage設定解決を使う。

## Task Breakdown
### Task 1: Storage settingsを拡張する
- Objective:
  - `jdbcUrl`互換を維持しながら、PostgreSQLに必要な設定を足せる形にする。
- Affected modules/files:
  - `diskinfo-agent/src/main/kotlin/.../config/AgentConfig.kt`
  - `diskinfo-agent/src/main/kotlin/.../config/AgentConfigLoader.kt`
  - `diskinfo-agent/src/main/kotlin/.../config/AgentConfigResolver.kt`
  - `diskinfo-agent/src/main/resources/agent.example.toml`
- Expected behavior:
  - 既定は`jdbc:sqlite:./sapphire.db`。
  - `--db-url`、`[storage].jdbcUrl`、`COCOADISKINFO_AGENT_STORAGE_JDBC_URL`は互換維持。
  - `storage.type`は任意。未指定時は`jdbcUrl` prefixから`sqlite`/`postgresql`を推定する。
  - 明示された`storage.type`と`jdbcUrl` prefixが矛盾する場合はvalidation errorにする。
  - `username`/`password`はPostgreSQL用に解決できるようにする。passwordは環境変数を主経路として扱う。
- Validation:
  - config precedence tests。
  - unknown key拒否方針が維持されること。

### Task 2: Driver/DataSource factoryを作る
- Objective:
  - SQLite/PostgreSQL driver選択を一箇所に集める。
- Affected modules/files:
  - 新規 `diskinfo-agent/src/main/kotlin/.../datastore/StorageSettings.kt`
  - 新規 `diskinfo-agent/src/main/kotlin/.../datastore/StorageBackend.kt`
  - 新規 `diskinfo-agent/src/main/kotlin/.../datastore/StorageConnectionFactory.kt`
- Expected behavior:
  - SQLiteは`org.sqlite.JDBC`を選択する。
  - PostgreSQLはPostgreSQL JDBC driverを選択する。
  - unsupported JDBC URLはactionableなvalidation errorにする。
- Validation:
  - SQLite URL、PostgreSQL URL、不明URLのunit test。

### Task 3: HikariCP導入判断を実装可能な形にする
- Objective:
  - HikariCPを使う場合のpool設定とclose lifecycleを明確にする。
- Affected modules/files:
  - `diskinfo-agent/build.gradle.kts`
  - storage connection factory
  - `SapphireCommandRuntime.kt`
- Expected behavior:
  - HikariCPを採用する場合、ExposedはDataSource経由で接続する。
  - SQLite defaultは`maximumPoolSize = 1`を初期候補にする。
  - PostgreSQL defaultは小さめのpool、例: `maximumPoolSize = 5`を初期候補にする。
  - `db migrate`/`db cleanup`のような短命コマンドでもDataSourceを確実にcloseする。
  - `standalone`ではprocess lifecycleに合わせてpoolをcloseする。
- Validation:
  - DB command後にDataSource closeが呼ばれることをtest doubleで確認する。
  - `oneshot --scan`は`--persist`なしでstorage factoryを呼ばないこと。
- Notes:
  - HikariCPはJDBC poolとして採用価値が高いが、SQLiteではpoolが大きいと逆効果になり得るため制限する。
  - SQLiteは同一DBファイルへ複数connectionを張れるが、同時writeは直列化される。Phase 4A/4Cでは安全側に倒してSQLite `maximumPoolSize = 1`を維持し、WAL mode、`busy_timeout`、SQLite pool拡張はPostgreSQL backend追加時の比較材料として再検討する。
  - PostgreSQLではTCP keepaliveやconnection lifetimeの設定を将来のoperations taskで詰める。

## CLI/API Compatibility
- 既存CLIの`--db-url`は維持する。
- このtaskでは新しいCLI flagを必須化しない。
- APIレスポンスは変更しない。

## Data and Persistence Impact
- schema変更なし。
- storage設定の拡張のみ。

## Validation Plan
```text
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:run --args='oneshot --scan'
./gradlew :diskinfo-agent:run --args='oneshot --scan --persist'
./gradlew :diskinfo-agent:run --args='db migrate'
```

## Risks and Open Questions
- HikariCPを全DB commandに使うか、long-running modeだけに使うか。
- passwordをTOMLで許可する場合、Phase 7 packagingでconfig file permissionを明文化する必要がある。
- PostgreSQL driver dependencyをこのtaskで入れるか、Phase 4Eまで遅らせるか。

## Implementation Status
- Implemented: storage settings now resolve `type`, `jdbcUrl`, `username`, and `password`.
- Implemented: `storage.type` is optional and inferred from JDBC URL when omitted.
- Implemented: HikariCP-backed storage connection factory is used by persisted `oneshot`, `standalone`, and `db migrate`.
- Deferred: PostgreSQL JDBC driver dependency remains Phase 4E scope.
- Deferred: SQLite multi-connection tuning remains out of Phase 4A/4C scope. Revisit WAL, `busy_timeout`, and pool size when Phase 4E compares SQLite and PostgreSQL connection behavior.
