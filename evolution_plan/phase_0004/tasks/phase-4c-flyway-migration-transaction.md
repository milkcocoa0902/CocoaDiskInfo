# Phase 4C: Flyway Migration and Transaction Boundary

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0004_plan.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`

## Goal
`db migrate`をExposed `MigrationUtils`からFlywayのversioned SQL migrationへ移行し、migrationをbackward-awareにする。同時に、Repository用のtransaction入口を軽量に整理する。

## Non-Goals
- cleanupやretention削除は実装しない。Phase 4Dで扱う。
- pg_partman partitioningは実装しない。Phase 4Eで扱う。
- `TransactionResult`、advisory lock、reader/writer DB分離は導入しない。

## Current State
- `SapphireExecutor.Migrate`はSQLite driver固定で接続し、`DROP TABLE IF EXISTS disk_snapshot`を実行してからExposed migration SQLを流している。
- この挙動は既存履歴を破壊するため、Phase 4で修正が必要。
- Repository queryは各method内でExposed `transaction { ... }`を直接呼んでいる。

## Boundary Decision
- Owner boundary: Repository and DB command assembly
- Why this belongs there: schema migrationとtransaction実行はstorage層の実装詳細であり、Collector/Server/Clientに漏らさない。
- Cross-boundary impact:
  - `db migrate`はFlyway migratorを呼ぶだけにする。
  - Repository実装は`TransactionRunner`を通してread-only/read-writeを表現する。

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
  - migration scriptはversion controlに置く。
  - empty DBでは`V1__create_disk_snapshot...`が適用される。
  - `flyway_schema_history`で適用済みmigrationを管理する。
  - `db migrate`を複数回実行しても安全に成功する。
- Validation:
  - 一時SQLite DBで`db migrate`を2回実行して成功すること。
  - migration適用後に`disk_snapshot`が存在すること。

### Task 2: 既存SQLite DBのbaseline方針を入れる
- Objective:
  - Phase 3までに作られたSQLite DBを無条件dropしない。
- Affected modules/files:
  - storage migrator
  - migration tests
- Expected behavior:
  - `disk_snapshot`が既に存在し、Flyway historyがないDBでは、既存schemaをbaselineとして扱える。
  - baseline処理はログ/出力で分かる。
  - 既存snapshot行はmigration後も残る。
- Validation:
  - 既存schema相当のSQLite DBへsnapshotを入れ、`db migrate`後も行が残ること。
- Notes:
  - `baselineOnMigrate`を常時trueにするか、既存table検出時だけ使うかは実装時に決める。
  - 破壊的migrationが必要になった場合は、このtaskではなく別PR notesで明示する。

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

### Task 4: TransactionRunnerを追加する
- Objective:
  - Repository内に散っているExposed transaction入口を集約する。
- Affected modules/files:
  - 新規 `diskinfo-agent/src/main/kotlin/.../datastore/TransactionRunner.kt`
  - 新規 `diskinfo-agent/src/main/kotlin/.../datastore/ExposedTransactionRunner.kt`
  - Repository implementation
- Expected behavior:
  - 初期interfaceは軽量にする。

```kotlin
interface TransactionRunner {
    fun <T> readOnly(block: () -> T): T
    fun <T> readWrite(block: () -> T): T
}
```

  - `CancellationException`を握りつぶさない。
  - Repository呼び出し元の既存エラー処理を維持する。
- Validation:
  - repository tests。

## CLI/API Compatibility
- `db migrate`サブコマンド名は維持する。
- `db migrate`はschema operationのみで、collection behaviorを持たない。
- API payloadは変更しない。

## Data and Persistence Impact
- 既存DBをdropしない方針へ変更する。
- Flyway history tableが追加される。
- 初期migrationは現行`disk_snapshot` schemaを再現する。

## Validation Plan
```text
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:run --args='db migrate --db-url jdbc:sqlite:/tmp/cocoadiskinfo-phase4c.db'
./gradlew :diskinfo-agent:run --args='db migrate --db-url jdbc:sqlite:/tmp/cocoadiskinfo-phase4c.db'
```

## Risks and Open Questions
- Flyway dependencyはバージョンによってDB support artifactが分かれる可能性があるため、実装時にSQLite/PostgreSQLの必要artifactを確認する。
- Exposed table definitionとFlyway SQLの二重管理になる。今後はFlyway SQLをschema source of truthとして扱う。
- Existing DB baselineを自動化しすぎるとschema driftを見逃すため、ログとvalidationを強める。
