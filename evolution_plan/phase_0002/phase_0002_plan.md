# Phase 2: Subcommands and Config

## Source
- Master: `../master.md`
- Supporting strategy: `../strategy/0004_future_architecture_mode.md`

## Goal
前半でCLI形状をサブコマンド中心に安定させ、後半で設定ファイルとruntime設定の扱いを固定する。

## Scope
- Cliktサブコマンドへ移行する。
- 最低限、`oneshot`, `standalone`, `db migrate` を提供する。
- 旧mode flagsは、未リリースであれば互換維持しなくてよい。
- 設定ファイル形式をTOMLとして固定する。
- 設定優先順位を `default < config file < environment variables < CLI arguments` として明確にする。
- `smartctl`, `runtime`, `storage`, `http`, `output` の初期設定範囲を固定する。
- 設定値のvalidationをCLI/config解決層へ集める。

## Non-Goals
- DB backend追加、retention実装、Hub/Node Agent設定は必須にしない。
- packaging生成はPhase 7で扱う。

## Task Documents
- `tasks/phase-2a-subcommand-migration.md`
- `tasks/phase-2b-config-runtime-settings.md`

## Validation
```text
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:run --args='--help'
./gradlew :diskinfo-agent:run --args='oneshot --help'
./gradlew :diskinfo-agent:run --args='standalone --help'
./gradlew :diskinfo-agent:run --args='db migrate --help'
```

## Implementation Order
1. `tasks/phase-2a-subcommand-migration.md` でCLI形状とテスト境界を固定する。
2. `tasks/phase-2b-config-runtime-settings.md` でconfig contract、merge、validation、documentationを固定する。
