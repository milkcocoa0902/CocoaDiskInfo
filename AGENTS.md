# AGENTS.md

This document defines working rules for human contributors and coding agents in this repository.

## 1. Purpose
- Keep changes safe, small, and verifiable.
- Preserve existing architecture (`diskinfo-agent`, `diskinfo-core`) unless a change explicitly requires refactor.
- Prefer operational clarity over clever abstractions.

## 2. Repository Structure
- `diskinfo-agent/`: CLI and runtime behavior (oneshot / agent / migration).
- `diskinfo-core/`: domain models and health evaluation logic.
- `sample/`: sample inputs/outputs for validation.
- `evolution_plan/`: master plan, supporting strategy documents, phase plans, and phase tasks.

## 3. Evolution Plan Documents
- Treat `evolution_plan/master.md` as the top-level direction document for agent evolution.
- Before making architecture, mode, storage, API, client history, packaging, or Prometheus-related changes, read `evolution_plan/master.md` first and follow its decision rules.
- Use `evolution_plan/strategy/` as supporting, topic-specific strategy documents.
- Use `evolution_plan/phase_000N/phase_000N_plan.md` for phase-level execution plans.
- Use `evolution_plan/phase_000N/tasks/` for concrete implementation task documents.
- If a proposed change does not fit the direction in `evolution_plan/master.md`, write or update a strategy or phase document before implementation.

## 4. Local Development
- Build:
  - `./gradlew :diskinfo-core:compileKotlin`
  - `./gradlew :diskinfo-agent:compileKotlin`
- Run agent module:
  - `./gradlew :diskinfo-agent:run --args='oneshot --scan'`
  - `./gradlew :diskinfo-agent:run --args='standalone --scan'`
  - `./gradlew :diskinfo-agent:run --args='db migrate'`

## 5. Coding Rules
- Use Kotlin idioms already present in the codebase.
- Keep edits focused; avoid unrelated cleanup.
- Do not change CLI compatibility unless required by task.
- Prefer explicit validation and clear error messages.
- For mode-dependent behavior, centralize decisions in a single place.

## 6. Runtime / Mode Policy
- `Standalone`: periodic collection, persistence allowed, HTTP API, long-running process.
- `Oneshot`: single execution, optional persistence via flag.
- `DB migrate`: schema operation only, no collection behavior.
- `Colotok.forceShutdown()` must be treated as process-lifecycle logic, not business logic.

## 7. Database Policy
- SQLite is the source of local history in agent-side operation.
- Connect only when needed by execution mode/flags.
- Keep schema changes backward-aware; document migration intent in PR notes.
- Follow `evolution_plan/strategy/0007_db_data_lifetime_policy.md` when changing retention, cleanup, history, cache, or event storage behavior.

## 8. Logging / Output
- Keep machine-readable and human-readable output paths explicit.
- Do not silently change default output format semantics.
- Include enough context in logs for device-level troubleshooting.

## 9. Testing Expectations
- At minimum, run compile checks for touched modules.
- If CLI argument behavior changes, verify representative success/failure invocations.
- If persistence behavior changes, verify both DB-enabled and DB-disabled paths.

## 10. PR / Change Checklist
- Scope is limited to requested behavior.
- Build passes for affected modules.
- New/changed flags are documented.
- Error messages are actionable.
- No accidental destructive command usage.

## 11. Safety Notes for Agents
- Respect sandbox/approval policies of the execution environment.
- If patch tooling is blocked, use approved elevated commands and keep diffs minimal.
- Never revert unrelated local changes.


---

## 日本語補足 (Japanese Notes)

- このファイルは、開発者とコーディングエージェントの共通ルールです。
- 変更は「小さく・安全に・検証可能」に保ってください。
- `diskinfo-agent` は実行モード制御、`diskinfo-core` はドメインロジックを担当します。
- 方針判断では `evolution_plan/master.md` を最上位文書として扱い、関連する個別strategyやphase planを補助文書として参照してください。
- DBの保持期間・cleanup・履歴・cache・event storageに関する変更では `evolution_plan/strategy/0007_db_data_lifetime_policy.md` を確認してください。
- 実行モードの原則:
  - `Standalone`: 定期収集・長時間稼働・永続化可・HTTP API
  - `Oneshot`: 単発実行・`--persist` 時のみ永続化
  - `DB migrate`: スキーマ操作のみ
- `Colotok.forceShutdown()` は業務ロジックではなく、プロセス終了時の責務として扱ってください。
- CLIの仕様変更時は、成功ケース/失敗ケースの代表コマンドを必ず確認してください。
- サンドボックス制約で `apply_patch` が失敗する場合は、承認付きの昇格コマンドで最小差分編集を行ってください。
