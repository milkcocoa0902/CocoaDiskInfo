---
name: phase-workplan
description: Create project-specific implementation work plans for CocoaDiskInfo before coding. Use when advancing a phase from evolution_plan/master.md, breaking a phase into concrete tasks, preparing task documents under evolution_plan/phase_000N/tasks/, or planning architecture, execution mode, storage, API, client history, packaging, or Prometheus work in this repository.
---

# Phase Workplan

Use this skill to turn CocoaDiskInfo strategy phases into concrete task documents before implementation.

## Core Rule

Create or update a task document under the relevant `evolution_plan/phase_000N/tasks/` directory first. Do not implement source changes in the same step unless the user explicitly asks to proceed after reviewing the workplan.

## Required Context

Always read these files before drafting the task document:

1. `AGENTS.md`
2. `evolution_plan/master.md`

Then read supporting strategy documents based on the target scope:

- Phase 1 or Phase 2 execution-mode work: `evolution_plan/strategy/0004_future_architecture_mode.md`
- Phase 3 history API or client history work: `evolution_plan/strategy/0005_device_history_ui_api_strategy.md`
- API response behavior: `evolution_plan/strategy/0001_api_response_strategy.md`
- Hub, Master, Node Agent, or distributed architecture: `evolution_plan/strategy/0002_future_architecture.md`
- DB cleanup or maintenance: `evolution_plan/strategy/0003_db_cleanup_strategy.md`
- Retention, cleanup, history, cache, event storage, or data lifetime: `evolution_plan/strategy/0007_db_data_lifetime_policy.md`

If the relevant strategy is missing, stale, or conflicts with `evolution_plan/master.md`, note that in the task document instead of silently inventing direction.

## Workflow

1. Identify the target phase, subphase, or feature area from the user request.
2. Read the required strategy context.
3. Inspect the current code only enough to understand the existing shape and affected boundaries.
4. Classify the work using the `evolution_plan/master.md` decision rule:
   - `Collector`
   - `Sink`
   - `Repository`
   - `Server`
   - `Client`
   - `Health Policy`
   - CLI/executor assembly
   - configuration/runtime settings
   - packaging/operations
5. Split the phase into small tasks that can be implemented and validated independently.
6. Create or update a Markdown file under the relevant phase task directory.
7. Report the created task document path and summarize the proposed implementation order.

## Task Document Location

Use the relevant `evolution_plan/phase_000N/tasks/` directory. Create it if it does not exist.

Prefer filenames that make the phase and scope obvious:

```text
evolution_plan/phase_0001/tasks/phase-1-internal-boundaries.md
evolution_plan/phase_0002/tasks/phase-2a-subcommand-migration.md
evolution_plan/phase_0002/tasks/phase-2b-config-runtime-settings.md
evolution_plan/phase_0003/tasks/phase-3-history-api-client-history.md
evolution_plan/phase_0004/tasks/phase-4-storage-backends.md
evolution_plan/phase_0005/tasks/phase-5-hub-node-agent.md
evolution_plan/phase_0006/tasks/phase-6-health-policy.md
evolution_plan/phase_0007/tasks/phase-7-packaging-operations.md
evolution_plan/phase_0008/tasks/phase-8-prometheus-export.md
```

For smaller follow-up plans, append a concise topic:

```text
evolution_plan/phase_0002/tasks/phase-2b-config-validation.md
evolution_plan/phase_0003/tasks/phase-3-bounded-history-api.md
```

## Task Document Format

Use the user's language for prose. Preserve code identifiers, CLI names, file paths, and API paths exactly.

```markdown
# Phase X: Title

## Source Strategy
- Primary: `../../master.md`
- Supporting: ...

## Goal
...

## Non-Goals
...

## Current State
...

## Boundary Decision
- Owner boundary:
- Why this belongs there:
- Cross-boundary impact:

## Task Breakdown
### Task 1: ...
- Objective:
- Affected modules/files:
- Expected behavior:
- Validation:
- Notes:

### Task 2: ...
- Objective:
- Affected modules/files:
- Expected behavior:
- Validation:
- Notes:

## CLI/API Compatibility
...

## Data and Persistence Impact
...

## Validation Plan
...

## Risks and Open Questions
...

## Implementation Order
1. ...
2. ...
3. ...
```

Omit sections only when they clearly do not apply. Keep the task document actionable enough that another coding turn can implement it without re-deciding the phase direction.

## Planning Rules

- Keep tasks small, safe, and verifiable.
- Preserve existing `diskinfo-agent` and `diskinfo-core` ownership unless the phase explicitly requires a boundary change.
- Prefer mode-dependent decisions in CLI/executor assembly.
- Treat `Colotok.forceShutdown()` as process-lifecycle logic, not business logic.
- Do not change CLI compatibility unless the target phase requires it.
- For CLI behavior changes, include representative success and failure invocations.
- For persistence changes, include DB-enabled and DB-disabled validation paths.
- For schema, retention, cleanup, history, cache, or event storage changes, include migration and backward-awareness notes.
- For packaging or systemd work, include expected command/config/data/log locations.

## Validation Guidance

At minimum, include compile checks for touched modules:

```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
```

Add focused runtime checks when relevant:

```text
./gradlew :diskinfo-agent:run --args='oneshot --scan'
./gradlew :diskinfo-agent:run --args='standalone --scan'
./gradlew :diskinfo-agent:run --args='db migrate'
```

Use newer subcommand examples when the task itself introduces or changes them. Keep legacy examples only when validating compatibility.

## Final Response

After creating the task document, respond with:

- the task document path
- the phase or scope covered
- the proposed implementation order in brief
- any open questions that should be resolved before coding
