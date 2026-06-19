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
- No active task document in this phase.
