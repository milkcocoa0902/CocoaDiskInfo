# Phase 4: Storage Backends Task Index

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0004_plan.md`
- Supporting: `../../strategy/0003_db_cleanup_strategy.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`

## Goal
Phase 4の実装作業を、独立して実装・検証できるtask documentへ分割する。

Phase 4全体の判断は、SQLite基準実装を崩さず、Repository/storage層にDB差分を閉じ込めることを優先する。Flyway、HikariCP、PostgreSQL、pg_partmanはこの順序の中で段階的に判断する。

## Task Documents
- [Phase 4A: Storage Settings and HikariCP Decision](phase-4a-storage-settings-hikari.md)
- [Phase 4B: Repository Interface and SQLite Baseline](phase-4b-repository-interface-sqlite.md)
- [Phase 4C: Flyway Migration and Transaction Boundary](phase-4c-flyway-migration-transaction.md)
- [Phase 4D: Raw Snapshot Cleanup and Manual Maintenance](phase-4d-raw-snapshot-cleanup.md)
- [Phase 4E: PostgreSQL Backend and pg_partman Partitioning](phase-4e-postgresql-pg-partman.md)
- [Phase 4F: Standalone Automatic Cleanup](phase-4f-standalone-automatic-cleanup.md)

## Boundary Decision
- Owner boundary: `Repository`
- Why this belongs there: 保存、検索、migration、cleanup、DB driver/pool、partitioningはstorage層の責務であり、Collector/Server/Client/Health Policyから隠す。
- Cross-boundary impact:
  - CLI/executor assemblyは実行モードに応じてstorageを組み立てる。
  - Server/SinkはRepository interfaceに依存する。
  - Configはstorage接続・retention・maintenance設定を解決するが、queryやschemaの詳細を持たない。

## Implementation Order
1. Phase 4A: storage設定、driver判定、HikariCP採用判断。
2. Phase 4B: Repository interface固定とSQLite baseline。
3. Phase 4C: Exposed `MigrationUtils`からFlywayへ移行し、transaction境界を整理する。
4. Phase 4D: raw snapshot cleanupと`db cleanup`。
5. Phase 4E: PostgreSQL backendとpg_partman partitioning判断。
6. Phase 4F: standalone起動時/定期cleanup。

## Key Decisions
- HikariCPはPhase 4Aで導入可否を決める。導入する場合もSQLiteは小さいpool、PostgreSQLは設定可能なpoolにする。
- Flyway移行はPhase 4Cで行う。`db migrate`はExposed schema diffではなくversioned SQL migrationを実行する。
- pg_partmanはPostgreSQL固有機能なのでPhase 4Eで扱う。ただし、retention semanticsはPhase 4Dのcleanup contractと整合させる。
- pg_partmanを使う場合の初期partition interval候補は7日を第一候補にする。30日retentionに対する過剰保持を抑えつつ、partition数も過大になりにくいため。
- standalone自動cleanupはPhase 4FでKtor lifecycle module案とexecutor-owned coroutine案を比較する。初期候補は、periodic cleanupをKtor lifecycleに紐づけ、起動前に完了させたいcleanupだけexecutor assemblyで扱うhybrid案。
