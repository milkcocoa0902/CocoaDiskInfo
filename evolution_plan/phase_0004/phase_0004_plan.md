# Phase 4: Storage Backends

## Source
- Master: `../master.md`
- Supporting strategy: `../strategy/0003_db_cleanup_strategy.md`
- Supporting strategy: `../strategy/0007_db_data_lifetime_policy.md`

## Goal
SQLiteを基準実装としてRepository interfaceを固め、後続のPostgreSQL/MySQL backend追加に備える。

## Scope
- SQLite backendを明示的な基準実装として整理する。
- Repository interfaceとinsert/history/latest queryを固定する。
- DBごとの差分をstorage層に閉じ込める。
- DBデータライフタイムとcleanup方針を実装判断に反映する。
- PostgreSQL backendを追加する。
- MySQL backendはPostgreSQL対応後に必要性を確認する。

## Non-Goals
- Repository境界を越えてDB依存をdomain/API/UIへ広げない。
- retention、cleanup、history、cache、event storageを同じ保持期間として扱わない。

## Validation
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:run --args='db migrate'
```

## Tasks
- [Phase 4: Storage Backends Task Index](tasks/phase-4-storage-backends.md)
- [Phase 4A: Storage Settings and HikariCP Decision](tasks/phase-4a-storage-settings-hikari.md)
- [Phase 4B: Repository Interface and SQLite Baseline](tasks/phase-4b-repository-interface-sqlite.md)
- [Phase 4C: Flyway Migration and Transaction Boundary](tasks/phase-4c-flyway-migration-transaction.md)
- [Phase 4D: Raw Snapshot Cleanup and Manual Maintenance](tasks/phase-4d-raw-snapshot-cleanup.md)
- [Phase 4E: PostgreSQL Backend and pg_partman Partitioning](tasks/phase-4e-postgresql-pg-partman.md)
- [Phase 4F: Standalone Automatic Cleanup](tasks/phase-4f-standalone-automatic-cleanup.md)

## Implementation Status
- Planning updated: Phase 4 task documentsを独立ファイルへ分割し、HikariCPは4A、Flyway移行は4C、pg_partman/partition interval判断は4Eへ配置した。
- Phase 4A implemented: storage設定拡張、backend推定、HikariCP-backed connection factory、runtime接続境界を追加した。PostgreSQL driver dependencyはPhase 4Eへ残す。
- Phase 4B implemented: `DiskSnapshotRepository`をinterface化し、Exposed-backed SQLite実装を`ExposedDiskSnapshotRepository`へ分離した。Server/Executorは具体Repositoryを生成せず、production assemblyがSQLite基準実装を注入する。SQLite baselineとしてinsert/latest/history queryのテストを固定した。
- Phase 4C implemented: `db migrate`をFlyway migratorへ移し、backend別migration locationとFlyway-created test schemaを導入した。`SnapshotUseCase`と`TransactionRunner`を追加し、Server/SinkからのDBアクセスはUseCase経由になった。
- Phase 4D implemented: backend-agnostic cleanup contract、retention/maintenance設定、`db cleanup`、SQLite row cleanupとtransaction外`VACUUM`を追加した。利用者のいないpre-release schemaはUTC `Z`表現とcleanup用indexを含む単一V1へsquashした。
- Phase 4E implemented: cleanup用indexを含む単一V1のplain PostgreSQL migration/backend、row-level cleanup、transaction外`VACUUM (ANALYZE)`を追加し、PostgreSQL 17実DBで検証した。pg_partmanはPhase 4では採用せず、後続taskへ延期する。
- Phase 4F implemented: startup cleanupはserver開始前、periodic cleanupはKtor lifecycleで動かすhybrid方式を採用した。cleanup失敗はwarning継続、cancellationは伝播する。
- Phase 4 complete: Phase 4A〜4Fの実装とSQLite/PostgreSQL検証を完了した。
