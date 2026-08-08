# Phase 4D: Raw Snapshot Cleanup and Manual Maintenance

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0004_plan.md`
- Supporting: `../../strategy/0003_db_cleanup_strategy.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`

## Goal
`disk_snapshot`のraw snapshot retentionを実装し、手動`db cleanup`で削除対象確認と削除を実行できるようにする。

このtaskではbackend-agnosticなcleanup contractを固定する。PostgreSQLでpg_partmanを使う場合も、このcontractのPostgreSQL実装として扱う。

## Non-Goals
- PostgreSQL partitioningやpg_partman extensionの導入はこのtaskでは行わない。
- standalone自動cleanupはPhase 4Fで扱う。
- aggregated history、device inventory、event tableは追加しない。

## Current State
- retention/maintenance設定は未実装。
- `db cleanup`サブコマンドは未実装。
- raw snapshot historyのdefault retentionはstrategy上30日。
- Phase 4Aにより、storage設定は`StorageSettings`へ解決され、DB接続は`StorageConnectionFactory`で組み立てる。
- Phase 4Cにより、schema source of truthはFlyway SQLへ移り、`db migrate`は`StorageMigrator`経由になっている。
- Phase 4Cにより、通常のsnapshot保存/参照は`SnapshotUseCase` + `TransactionRunner`をtransaction境界にしている。Repository実装はtransactionを開始しない。
- SQLiteの`VACUUM`はtransaction中に実行できないため、cleanupの削除transactionとvacuum実行は分ける必要がある。

## Boundary Decision
- Owner boundary: Storage Maintenance Repository / Maintenance UseCase / DB command assembly
- Why this belongs there: cleanupはDB種別ごとにDELETE/VACUUM/partition dropなどの差分が出るため、storage層に閉じ込める。transaction開始はPhase 4Cの方針に合わせてUseCase相当層に置く。
- Cross-boundary impact:
  - CLIはcleanup requestを組み立てるだけにする。
  - Runtime assemblyはstorage connectionとmaintenance use caseを組み立てる。
  - Storage maintenance repositoryはcount/deleteなどのDB操作だけを持ち、transactionを開始しない。
  - Maintenance use caseはdry-run/count/deleteを`TransactionRunner`経由で実行する。
  - SQLite `VACUUM`は`TransactionRunner`の外側で実行する。
  - Standalone自動cleanupはこのcontractを後続で呼ぶ。

## Task Breakdown
### Task 1: retention/maintenance設定を追加する
- Objective:
  - raw snapshot retentionとmaintenance設定をconfigで解決する。
- Affected modules/files:
  - `AgentConfig.kt`
  - `AgentConfigLoader.kt`
  - `AgentConfigResolver.kt`
  - `agent.example.toml`
- Expected behavior:
  - `[retention].rawSnapshotDays` defaultは30。
  - allowed rangeは`1..365`。
  - `[maintenance].vacuumAfterCleanup` defaultはfalse。
  - `COCOADISKINFO_AGENT_RETENTION_RAW_SNAPSHOT_DAYS`と`COCOADISKINFO_AGENT_MAINTENANCE_VACUUM_AFTER_CLEANUP`で上書きできる。
  - `db cleanup --raw-snapshot-days N`と`db cleanup --vacuum`はconfig/environmentより優先する。
  - `cleanupOnStartup`と`cleanupIntervalHours`はPhase 4Fで扱い、このtaskでは未実装のunknown keyとして扱う。
  - unknown key拒否方針を維持する。
- Validation:
  - config parser/resolver tests。

### Task 2: cleanup request/result modelを追加する
- Objective:
  - dry-run、実削除、vacuum予定/実績を同じ形式で表示・テストできるようにする。
- Affected modules/files:
  - 新規 `SnapshotCleanupRequest`
  - 新規 `SnapshotCleanupResult`
  - 新規 `SnapshotCleanupTableResult` などのresult model
- Expected behavior:
  - requestは`rawSnapshotDays`、`dryRun`、`vacuumAfterCleanup`、現在時刻算出用の`Clock`またはcutoff算出境界を持つ。
  - resultに対象table、cutoff、削除対象件数、実削除件数、dry-runかどうか、vacuum requested/executed/skipped reasonを含める。
  - cutoffは`now - rawSnapshotDays`で算出し、削除条件は`collect_time < cutoff`に固定する。
- Validation:
  - cutoff算出がdeterministicにテストできること。
  - dry-run resultと実行resultの表示に必要な情報が欠けないこと。

### Task 3: Storage maintenance repository/use caseを追加する
- Objective:
  - cleanup対象確認と削除実行をPhase 4Cのtransaction境界に合わせて呼べるようにする。
- Affected modules/files:
  - 新規 `SnapshotMaintenanceRepository`または`DiskSnapshotMaintenanceRepository`
  - 新規 `StorageMaintenanceUseCase`または`SnapshotMaintenanceUseCase`
  - SQLite implementation
- Expected behavior:
  - dry-runでは`readOnly` transactionで削除対象件数だけを数える。
  - 実行時は`collect_time < cutoff`のraw snapshotを削除する。
  - 削除は`readWrite` transactionで実行する。
  - SQLite `VACUUM`は削除transaction完了後、transaction外のbackend-specific maintenance operationとして実行する。
  - `node_id`, `device_key`, `collect_time` indexは維持する。
- Validation:
  - 複数時点のsnapshotを入れ、retention境界より古い行だけ対象になること。
  - dry-runで行が削除されないこと。
  - `--vacuum`相当の経路でVACUUMがtransaction内から呼ばれないこと。

### Task 4: `db cleanup` CLIを追加する
- Objective:
  - schema operationではなくdata maintenanceとして手動cleanupを提供する。
- Affected modules/files:
  - `Main.kt`
  - `SapphireCommandRuntime.kt`
  - `SapphireExecutor.kt`またはDB command handler
- Expected behavior:
  - `db cleanup`
  - `db cleanup --dry-run`
  - `db cleanup --raw-snapshot-days N`
  - `db cleanup --vacuum`
  - smartctl target、HTTP、output設定を要求しない。
  - cleanup結果を人間が読める出力にする。
  - `SapphireCommandRequest.DbCleanup`を追加し、`ProductionSapphireCommandRuntime`がstorage connectionとmaintenance use caseを組み立てる。
  - `SapphireExecutor.Cleanup`はcollection behaviorを持たず、maintenance use caseを呼んで結果を出力する。
- Validation:
  - CLI testsでsuccess/failure invocationを確認する。
  - `db cleanup`が`--scan`/`--device`なしで成功すること。
  - `db cleanup --raw-snapshot-days 0`と`366`がusage errorになること。

### Task 5: backend-specific maintenance semanticsを固定する
- Objective:
  - PostgreSQL/pg_partmanへ進む前に、cleanup contract上の意味を決める。
- Expected behavior:
  - SQLite: `DELETE FROM disk_snapshot WHERE collect_time < cutoff`、必要時だけ`VACUUM`。
  - Phase 4Dの実装対象はSQLiteのみ。PostgreSQL driver/backend implementationはPhase 4Eまで追加しない。
  - PostgreSQL plain table: Phase 4Eで`DELETE` + optional analyze相当を検討する。
  - PostgreSQL pg_partman: Phase 4では採用せず、partition drop/detachは運用要件が具体化した後続taskで扱う。
  - Phase 4D時点でSQLite以外のbackendにcleanup requestが到達した場合は、backend unsupportedをactionableに返す。
- Validation:
  - SQLite実装でcontractを満たすこと。
- Notes:
  - pg_partmanのpartition intervalは後続taskで決める。
  - Phase 4Dでは「cleanup結果の表現」をpartition方式でも使える形にしておく。

## CLI/API Compatibility
- `db cleanup`は追加コマンド。
- 既存`db migrate`、`oneshot`、`standalone`は互換維持。
- API payloadは変更しない。

## Data and Persistence Impact
- raw snapshot retention defaultは30日。
- cleanupはhard deleteを許可する。
- migrationとcleanupを同じ操作にしない。

## Validation Plan
```text
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:run --args='db cleanup --dry-run'
./gradlew :diskinfo-agent:run --args='db cleanup --raw-snapshot-days 14 --dry-run'
# expected usage error
./gradlew :diskinfo-agent:run --args='db cleanup --raw-snapshot-days 0 --dry-run'
```

## Risks and Open Questions
- `rawSnapshotDays = 0`をテスト専用に許可するか。初期方針は許可せず、テストは時刻データで調整する。
- `--vacuum`はPhase 4DではSQLiteのみ実行対象にする。PostgreSQLではPhase 4Eまでbackend unsupportedとして扱う。
- pg_partman利用時のretention差異は、将来のpartition導入taskで明文化する。
- cleanup実行中にstandalone/APIが同じSQLite DBへアクセスする場合、Phase 4A/4CのSQLite pool size 1方針により実行は直列化される。自動cleanup時のスケジューリングはPhase 4Fで扱う。

## Implementation Order
1. `retention`/`maintenance` config model、minimal TOML allowed keys、resolver、example TOMLを追加する。
2. `EffectiveDbCleanupConfig`、CLI/environment overrides、validation rangeを追加する。
3. cleanup request/result modelを追加し、cutoff算出をdeterministicにテストできるようにする。
4. SQLite raw snapshot maintenance repositoryを追加し、count/deleteをtransaction開始なしで実装する。
5. maintenance use caseを追加し、dry-runは`readOnly`、削除は`readWrite`、SQLite `VACUUM`はtransaction外で実行する。
6. `db cleanup` CLI、`SapphireCommandRequest.DbCleanup`、runtime assembly、`SapphireExecutor.Cleanup`を追加する。
7. CLI success/failure、config parser/resolver、SQLite cleanup repository/use case testsを追加する。
8. compileと代表CLI invocationで、DB-enabled cleanup pathとvalidation failure pathを確認する。

## Implementation Status
- Implemented: retention/maintenance config、environment override、`db cleanup` CLIと`--dry-run`/retention/vacuum overrideを追加した。
- Implemented: backend-agnosticなcleanup request/result、maintenance repository/use case、厳密な`collect_time < cutoff`を追加した。
- Implemented: dry-runはread-only transaction、削除はread-write transaction、SQLite `VACUUM`はtransaction外の別connectionで実行する。
- Implemented: 利用者のいないpre-release schemaを単一V1へsquashし、SQLiteの新規保存とquery parameterを同じUTC `Z`表現に固定した。cleanup用`collect_time` indexもV1に含める。
- Verified: cutoff境界、dry-run非破壊性、実削除、VACUUM、CLI success/failureをunit/integration testと代表CLI invocationで確認した。
