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
- Phase 4C以降、DB操作のtransaction開始はUseCase相当層に置く。Phase 4Dのcleanupもmaintenance repositoryではなくmaintenance use caseが`TransactionRunner`を使ってtransaction境界を持つ。
- SQLite `VACUUM`はtransaction中に実行できないため、Phase 4Dでは削除transactionとvacuum executionを分離する。
- Phase 4Eではpg_partmanを採用せず、plain PostgreSQL + row-level DELETE cleanupを基準実装にする。pg_partmanは運用規模とmaintenance ownerが具体化した後続taskへ延期する。
- standalone自動cleanupはhybrid案を採用する。startup cleanupはserver開始前、periodic cleanupはKtor lifecycleに紐づける。
- runtime Repository/maintenance接続はHikariCPを使うが、`db migrate`のDataSourceはFlywayが直接管理する。
- 利用者がいないpre-releaseのPhase 4 migrationは、SQLite/PostgreSQLとも単一V1へsquashする。

## Implementation Status
- Phase 4A〜4F implemented and verified.
- SQLiteを基準実装として、HikariCP接続、Repository interface、Flyway migration、transaction boundary、手動/自動cleanupを実装した。
- plain PostgreSQL backendを同じRepository/cleanup contractへ接続し、PostgreSQL 17実DBで検証した。
- pg_partmanはPhase 4の完了条件から外し、将来の独立migration/operations taskへ延期した。
