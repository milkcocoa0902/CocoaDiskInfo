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

## Boundary Decision
- Owner boundary: Repository / Storage Maintenance
- Why this belongs there: cleanupはDB種別ごとにDELETE/VACUUM/partition dropなどの差分が出るため、storage層に閉じ込める。
- Cross-boundary impact:
  - CLIはcleanup requestを組み立てるだけにする。
  - Repository maintenanceはdry-run resultを構造化して返す。
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
  - unknown key拒否方針を維持する。
- Validation:
  - config parser/resolver tests。

### Task 2: Storage maintenance interfaceを追加する
- Objective:
  - cleanup対象確認と削除実行をRepository contractから呼べるようにする。
- Affected modules/files:
  - 新規 `SnapshotMaintenanceRepository`または`DiskSnapshotMaintenance`
  - SQLite implementation
- Expected behavior:
  - dry-run resultに対象table、cutoff、削除対象件数、vacuum予定を含める。
  - 実行時は`collect_time < cutoff`のraw snapshotを削除する。
  - `node_id`, `device_key`, `collect_time` indexは維持する。
- Validation:
  - 複数時点のsnapshotを入れ、retention境界より古い行だけ対象になること。
  - dry-runで行が削除されないこと。

### Task 3: `db cleanup` CLIを追加する
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
- Validation:
  - CLI testsでsuccess/failure invocationを確認する。

### Task 4: backend-specific maintenance semanticsを固定する
- Objective:
  - PostgreSQL/pg_partmanへ進む前に、cleanup contract上の意味を決める。
- Expected behavior:
  - SQLite: `DELETE FROM disk_snapshot WHERE collect_time < cutoff`、必要時だけ`VACUUM`。
  - PostgreSQL plain table: `DELETE` + optional analyze相当を検討。
  - PostgreSQL pg_partman: retentionはpartition drop/detachで実現可能。ただし厳密なcutoff削除ではなくpartition interval分の過剰保持があり得る。
- Validation:
  - SQLite実装でcontractを満たすこと。
- Notes:
  - pg_partmanのpartition intervalはPhase 4Eで決める。
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
```

## Risks and Open Questions
- `rawSnapshotDays = 0`をテスト専用に許可するか。初期方針は許可せず、テストは時刻データで調整する。
- `--vacuum`をSQLite専用にするか、PostgreSQLでは明示的にunsupportedにするか。
- pg_partman利用時は「30日retention」が実削除時点では30日+partition interval未満の保持になり得る。これはPhase 4Eで明文化する。
