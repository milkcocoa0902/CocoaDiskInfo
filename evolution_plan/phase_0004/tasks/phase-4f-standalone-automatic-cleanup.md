# Phase 4F: Standalone Automatic Cleanup

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0004_plan.md`
- Supporting: `../../strategy/0003_db_cleanup_strategy.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`

## Goal
Phase 4Dで作ったcleanup contractをstandalone modeの起動時/定期maintenanceとして実行する。

## Non-Goals
- `oneshot`に自動cleanupを追加しない。
- Hub modeのcleanupはPhase 5以降で扱う。
- pg_partman固有の設定追加は、運用要件が具体化した後続taskで扱う。

## Current State
- `standalone`は定期collectionとHTTP serverを動かす。
- cleanupは未実装。
- 現在のcollection loopは`SapphireExecutor.Standalone`から`SapphireAgentServer.start { launch { ... } }`へ渡され、Ktor `Application`側のcoroutine scopeで起動している。
- Ktor 3.5.0には`ApplicationStarted`、`ApplicationStopPreparing`、`ApplicationStopping`、`ApplicationStopped`などのlifecycle eventがあり、`Application.monitor.subscribe(...)`で扱える。

## Boundary Decision
- Owner boundary: CLI/executor assembly and Repository maintenance
- Why this belongs there: automatic cleanupは実行モード依存の判断であり、実際の削除処理はstorage maintenance層にある。
- Cross-boundary impact:
  - `SapphireCommandRuntime`または`SapphireExecutor.Standalone`がmaintenance dependencyを受け取る。
  - cleanup結果は運用ログに出す。

## Runtime Integration Decision
Phase 4Fの実装前に、次の3案を比較して決める。

### Option A: executor-owned coroutine
- Shape:
  - `SapphireExecutor.Standalone.execute()`内でcollection loopとcleanup loopをそれぞれ`launch`する。
- Pros:
  - 実行モード依存の判断がexecutor assemblyに集まり、master planの「mode判断はCLI/Executor組み立て層へ寄せる」に合う。
  - Ktorを知らないtest doubleで検証しやすい。
- Cons:
  - `server.start(wait = true)`がlifecycleの中心になっている現状では、server start/stopとの同期が読みにくくなりやすい。
  - HTTP server停止時にcleanup jobを確実にcancelする責務をexecutor側で持つ必要がある。

### Option B: Ktor lifecycle module
- Shape:
  - `installStandaloneMaintenance(...)`のようなKtor moduleを作り、`ApplicationStarted`でperiodic cleanup jobを起動し、`ApplicationStopping`または`ApplicationStopped`でcancel/cleanupする。
- Pros:
  - HTTP server lifecycleとcleanup job lifecycleが一致する。
  - Ktorの起動/終了hookを使えるため、server停止時のjob cancelやresource解放を表現しやすい。
  - Phase 5以降のHubでもKtor server lifecycleに同じmaintenance moduleを載せやすい。
- Cons:
  - cleanupがKtorに寄りすぎると、storage maintenanceがHTTP serverの機能に見えてしまう。
  - 起動時cleanupを「HTTP API公開前に必ず完了」させたい場合、`ApplicationStarted`では遅い可能性がある。

### Option C: hybrid
- Shape:
  - `cleanupOnStartup`を「API公開前に完了させたい処理」としてexecutor assemblyで1回実行する。
  - periodic cleanupはKtor lifecycle moduleで`ApplicationStarted`時にjobを起動し、`ApplicationStopping`/`ApplicationStopped`時にcancelする。
- Pros:
  - 起動前cleanupの順序保証と、定期jobのlifecycle管理を両立できる。
  - maintenance business logicはstorage serviceに残し、Ktor moduleは起動/停止だけを担当できる。
- Cons:
  - cleanup entrypointが2つに見えるため、共通runnerを作らないとログ/エラー処理が重複しやすい。

### Initial Recommendation
Option Cを採用する。

- `cleanupOnStartup = true`の場合、standalone assemblyでHTTP server開始前に一度だけcleanupを実行する。
- periodic cleanupはKtor lifecycle moduleにする。
- Ktor moduleはcleanupの業務判断を持たず、`StandaloneMaintenanceRunner`のようなserviceを起動/停止するだけにする。
- collection loopも可能なら同じ方針でKtor lifecycle上のlong-running jobとして整理する。ただしPhase 4Fではcleanupに必要な最小変更に留める。
- cleanup失敗はwarningとして記録し、standalone processは継続する。`CancellationException`は伝播する。
- standalone専用のcleanup CLI flagは追加せず、config/environmentで制御する。

Reference:
- Ktor application monitoring: `https://ktor.io/docs/server-events.html`

## Task Breakdown
### Task 1: maintenance runtime settingsを解決する
- Objective:
  - standaloneで自動cleanupを制御できるようにする。
- Affected modules/files:
  - `AgentConfig.kt`
  - `AgentConfigLoader.kt`
  - `AgentConfigResolver.kt`
  - `agent.example.toml`
- Expected behavior:
  - `[maintenance].cleanupOnStartup` defaultはtrue。
  - `[maintenance].cleanupIntervalHours` defaultは24。
  - `[maintenance].vacuumAfterCleanup` defaultはfalse。
  - intervalは正の値だけ許可する。
  - standalone専用CLI flagは追加しない。
- Validation:
  - config/environment precedence tests。
  - standalone CLIにcleanup専用flagが増えていないことを既存CLI testで確認する。

### Task 2: standalone起動時cleanupを追加する
- Objective:
  - standalone開始時にraw snapshot cleanupを1回実行する。
- Affected modules/files:
  - `SapphireCommandRuntime.kt`
  - `SapphireExecutor.kt`
- Expected behavior:
  - `cleanupOnStartup = true`ならHTTP server/collection開始前にcleanupを実行する。
  - cleanup失敗時はwarningを出してprocessを継続する。
  - 結果をログに出す。
- Validation:
  - executor testで起動時cleanupが呼ばれること。

### Task 3: 定期cleanupを追加する
- Objective:
  - standalone long-running processで24時間ごとにcleanupする。
- Affected modules/files:
  - `SapphireExecutor.kt`
  - `SapphireAgentServer.kt`
  - 新規 `StandaloneMaintenanceModule.kt`または同等のKtor module
- Expected behavior:
  - collection loopとは別coroutineで動かす。
  - 初期候補では、periodic cleanup jobはKtor `ApplicationStarted` eventで起動する。
  - `ApplicationStopping`または`ApplicationStopped`でperiodic cleanup jobをcancelし、必要なresource解放を行う。
  - cleanupの多重実行を避ける。
  - process shutdown時に自然に止まる。
- Validation:
  - fake maintenanceとtest dispatcherで定期呼び出しを確認する。
  - Ktor test hostまたはmodule-level testで、lifecycle eventによりjob start/cancelが行われることを確認する。

### Task 4: maintenance runnerを共通化する
- Objective:
  - 起動時cleanupとperiodic cleanupでログ/エラー処理を重複させない。
- Affected modules/files:
  - 新規 `StandaloneMaintenanceRunner`
  - `SapphireCommandRuntime.kt`
  - `StandaloneMaintenanceModule.kt`
- Expected behavior:
  - cleanup失敗時のfatal/warn判断を一箇所に置く。
  - cleanup実行開始、完了、削除件数、dry-runではないこと、vacuum有無をログに出す。
  - periodic cleanupの実行中に次回tickが来た場合はskipまたは待機する。初期候補はskipしてwarningログ。
- Validation:
  - runner unit test。
  - duplicate execution guard test。

## CLI/API Compatibility
- standaloneの既存CLIは維持する。
- API payloadは変更しない。

## Data and Persistence Impact
- raw snapshot retentionに基づき古い履歴が自動削除される。
- 保持期間外の履歴がないことは正常系。

## Validation Plan
```text
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:run --args='standalone --scan'
```

## Risks and Open Questions
- cleanup失敗はwarning継続とする。ただしcoroutine cancellationは握りつぶさない。
- Phase 4Eはplain PostgreSQLを採用するため、SQLite/PostgreSQLともapplication側maintenance use caseを呼ぶ。
- periodic cleanupをKtor lifecycleに載せる場合、HTTP serverが起動しない環境ではcleanup loopも起動しない。standaloneではHTTP APIがmode要件なので許容する。
- 起動時cleanupは順序保証を優先してserver start前にexecutorで行う。

## Implementation Order
1. `cleanupOnStartup`と`cleanupIntervalHours`をconfig/environmentから解決する。
2. 起動時/定期実行で共有する`StandaloneMaintenanceRunner`を追加し、warning継続と多重実行guardを実装する。
3. standalone executorでHTTP server開始前のstartup cleanupを実行する。
4. Ktor lifecycleへperiodic cleanup jobのstart/cancelを接続する。
5. config precedence、起動順序、定期実行、重複防止、cancellation、既存CLI互換をテストする。

## Implementation Status
- Implemented: `cleanupOnStartup`と`cleanupIntervalHours`をconfig/environmentから解決し、既存standalone CLIは変更しなかった。
- Implemented: startup cleanupをserver開始前に同期実行し、periodic cleanupをKtorのstart/stop lifecycleへ接続した。
- Implemented: 共通runnerで多重実行をskipし、通常の失敗はwarning継続、`CancellationException`は伝播する。
- Verified: config resolution、startup順序、cleanup無効化、複数回の定期実行と停止、多重実行guard、失敗/cancellationをtestで確認した。
