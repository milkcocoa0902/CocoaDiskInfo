# Phase 2A: Subcommand Migration Test Workplan

## Source Strategy
- Primary: `strategy/0006_agent_evolution_direction.md`
- Supporting: `strategy/0004_future_architecture_mode.md`

## Goal
Phase 2Aで導入済みのサブコマンドCLIを、自動テストと代表コマンド検証で固定する。

対象は `oneshot`, `standalone`, `db migrate` のCLI形状、mode選択、target解決、基本validation、Executor組み立てである。実装済みの最小TOML読み込みはPhase 2Bの先行要素だが、現時点のCLI挙動に影響する範囲だけPhase 2Aテストに含める。

## Non-Goals
- Phase 2Bの設定優先順位全体を完成させない。
- environment variables、default config path、`/etc/cocoadiskinfo/agent.toml` の実装は扱わない。
- PostgreSQL/MySQLなどのDB backend追加は扱わない。
- Hub、Node Agent、Prometheus、package生成は扱わない。
- unit testで実機 `smartctl` 実行、長時間稼働Ktor server、実デバイスアクセスに依存しない。

## Current State
- `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/Main.kt` にCliktベースの `oneshot`, `standalone`, `db migrate` が実装されている。
- 旧mode flagsである `--oneshot`, `--agent`, `--migration` の互換維持は不要というPhase 2A方針に寄っている。
- `AgentConfigLoader` に最小TOML parserがあり、`--config` から `smartctl`, `runtime`, `storage`, `http`, `output` の一部を読める。
- `SapphireExecutor.Oneshot`, `SapphireExecutor.Standalone`, `SapphireExecutor.Migrate` へ実行が接続されている。
- 現時点ではCLI挙動を固定する自動テストがまだない。

## Boundary Decision
- Owner boundary: CLI/executor assembly と configuration/runtime settings
- Why this belongs there: Phase 2Aの本体は実行モードをフラグからサブコマンドへ移すことであり、mode-dependent behaviorは `0006` の方針どおりCLI/Executor組み立て層に集めるべきため。
- Cross-boundary impact: `Collector`, `Sink`, `Repository`, `Server` の責務は変更しない。テスト容易性のためにExecutor生成やruntime起動の境界を薄く切る場合も、収集・保存・API公開の内部責務へCLI知識を漏らさない。

## Task Breakdown

### Task 1: CLIを副作用なしにテストできる境界を作る
- Objective: Clikt parseとExecutor組み立てを、`smartctl` 実行、DB接続、Ktor起動、`Colotok.forceShutdown()` なしで検証できるようにする。
- Affected modules/files:
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/Main.kt`
  - 必要なら `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/cli/`
  - `diskinfo-agent/src/test/kotlin/...`
- Expected behavior:
  - productionの `main(args)` は従来どおり実Executorを起動する。
  - testではrecording runtimeまたはfactoryを差し込み、どのsubcommandがどの設定で実行されるか確認できる。
  - `Colotok.forceShutdown()` はproduction runtime側に閉じ込め、テスト対象のparse/assemblyから分離する。
- Validation:
  - `./gradlew :diskinfo-agent:test`
  - `./gradlew :diskinfo-agent:compileKotlin`
- Notes:
  - 大きな抽象化は避け、Phase 2Aのテストに必要な最小境界に留める。

### Task 2: root/subcommand/helpと旧flag拒否をテストする
- Objective: CLIの入口形状を固定する。
- Affected modules/files:
  - `Main.kt`
  - `diskinfo-agent/src/test/kotlin/.../CliCommandTest.kt`
- Expected behavior:
  - root helpに `oneshot`, `standalone`, `db` が表示される。
  - `oneshot --help`, `standalone --help`, `db migrate --help` が成功する。
  - `--oneshot`, `--agent`, `--migration` は未リリース方針どおり拒否される。
- Validation:
  - `./gradlew :diskinfo-agent:test`
  - 代表手動確認:
    - `./gradlew :diskinfo-agent:run --args='--help'`
    - `./gradlew :diskinfo-agent:run --args='oneshot --help'`
    - `./gradlew :diskinfo-agent:run --args='standalone --help'`
    - `./gradlew :diskinfo-agent:run --args='db migrate --help'`
- Notes:
  - help文面の全文一致は避け、重要なsubcommand/optionsだけを確認する。

### Task 3: `oneshot` のtarget/output/persist組み立てをテストする
- Objective: 単発収集モードのCLI semanticsを固定する。
- Affected modules/files:
  - `Main.kt`
  - `RuntimeModels.kt`
  - `AgentConfigLoader.kt`
  - `diskinfo-agent/src/test/kotlin/...`
- Expected behavior:
  - `oneshot --scan` は `TargetDevice.Scan` として実行される。
  - `oneshot --device <path>` は絶対pathの `TargetDevice.Explicit` として実行される。
  - `--output` は `OutputMode` に変換される。
  - `--persist` がない場合はDB repositoryを要求しない。
  - `--persist --db-url <url>` は永続化ありの構成になる。
  - target未指定、`--scan` と `--device` の同時指定は失敗する。
- Validation:
  - `./gradlew :diskinfo-agent:test`
  - 失敗系の代表手動確認:
    - `./gradlew :diskinfo-agent:run --args='oneshot'`
    - `./gradlew :diskinfo-agent:run --args='oneshot --scan --device /dev/sda'`
- Notes:
  - `<path>` はテスト用一時ファイルまたは存在するfixture pathを使い、実デバイスに依存しない。

### Task 4: `standalone` のruntime/http/storage validationをテストする
- Objective: 長時間稼働モードの起動設定を固定する。
- Affected modules/files:
  - `Main.kt`
  - `RuntimeModels.kt`
  - `diskinfo-agent/src/test/kotlin/...`
- Expected behavior:
  - `standalone --scan` はscan targetで実行される。
  - `--interval-seconds` は正の値のみ受け付ける。
  - `--port` は `1..65535` の範囲のみ受け付ける。
  - `--db-url` はstorage設定としてExecutor組み立てに反映される。
  - target未指定、target競合、interval不正、port不正は失敗する。
- Validation:
  - `./gradlew :diskinfo-agent:test`
  - 失敗系の代表手動確認:
    - `./gradlew :diskinfo-agent:run --args='standalone'`
    - `./gradlew :diskinfo-agent:run --args='standalone --scan --interval-seconds 0'`
    - `./gradlew :diskinfo-agent:run --args='standalone --scan --port 70000'`
- Notes:
  - unit testではKtor serverを実起動しない。server起動を含む検証は別途integration test候補に分ける。

### Task 5: `db migrate` のparseと最小integrationをテストする
- Objective: DB commandが収集処理を持たず、schema operationだけを実行することを固定する。
- Affected modules/files:
  - `Main.kt`
  - `SapphireExecutor.kt`
  - `diskinfo-agent/src/test/kotlin/...`
- Expected behavior:
  - `db migrate --db-url jdbc:sqlite:<temp-file>` が成功する。
  - `db migrate --config <file>` がconfig内の `storage.jdbcUrl` を使える。
  - `db migrate --db-url <url>` はconfigの `storage.jdbcUrl` より優先される。
  - `db` 単体の挙動を明示的に確認する。
- Validation:
  - `./gradlew :diskinfo-agent:test`
  - 代表手動確認:
    - `./gradlew :diskinfo-agent:run --args='db migrate --db-url jdbc:sqlite:/tmp/cocoadiskinfo-phase2a-test.db'`
- Notes:
  - `SapphireExecutor.Migrate` は現状 `DROP TABLE IF EXISTS disk_snapshot` を実行するため、テストでは必ず一時DBを使う。
  - `db` 単体をhelp表示に寄せるか、no-op許容にするかは実装前に判断する。

### Task 6: Phase 2Aに影響する最小config読み込みをテストする
- Objective: 既に入っている `--config` 挙動のうち、サブコマンド実行に直接影響する部分だけを固定する。
- Affected modules/files:
  - `AgentConfigLoader.kt`
  - `Main.kt`
  - `diskinfo-agent/src/main/resources/agent.example.toml`
  - `diskinfo-agent/src/test/kotlin/...`
- Expected behavior:
  - `[smartctl] scan = true` で `oneshot --config <file>` がtarget指定なしでも動く。
  - `[smartctl] device = "<path>"` で明示device targetになる。
  - config内の `scan` と `device` の競合は失敗する。
  - CLI targetはconfig targetより優先される。
  - malformed TOML、unknown section/key、型不一致はactionableなerrorになる。
  - `[output].mode`, `[runtime].intervalSeconds`, `[runtime].persist`, `[storage].jdbcUrl`, `[http].port` の基本parseを確認する。
- Validation:
  - `./gradlew :diskinfo-agent:test`
- Notes:
  - environment variable precedence、default config path、設定項目の拡張はPhase 2Bで扱う。

### Task 7: ドキュメント例と代表コマンドを同期する
- Objective: README、AGENTS、systemdテンプレートのコマンド例がPhase 2AのCLIと一致していることを確認する。
- Affected modules/files:
  - `README.md`
  - `README.en.md`
  - `AGENTS.md`
  - `deploy/`
- Expected behavior:
  - 旧 `--oneshot`, `--agent`, `--migration` の実行例が残っていない。
  - `oneshot`, `standalone`, `db migrate` の代表例が現在のparserで通る。
- Validation:
  - `rg -- '--oneshot|--agent|--migration' README.md README.en.md AGENTS.md deploy`
  - README記載の代表コマンド実行
- Notes:
  - 互換性維持をしない方針なので、旧flagはドキュメントからも消す。

## CLI/API Compatibility
Phase 2Aでは、未リリースであることを前提に旧mode flags互換を維持しない。

固定するCLIは次の形にする。

```text
cocoadiskinfo-agent oneshot --scan
cocoadiskinfo-agent oneshot --device <path>
cocoadiskinfo-agent standalone --scan
cocoadiskinfo-agent standalone --device <path>
cocoadiskinfo-agent db migrate
```

API shapeは変更しない。`standalone` 起動後のHTTP APIの詳細検証はPhase 3以降または別integration testで扱う。

## Data and Persistence Impact
- Phase 2Aテスト計画自体ではschema変更を行わない。
- `db migrate` のintegration testを追加する場合は、必ず一時SQLite DBを使う。
- `oneshot --persist` と `standalone` はDB接続が必要なモードとして扱い、DBなしの `oneshot` と区別して検証する。
- `SapphireExecutor.Migrate` のdrop/create挙動は既存実装に従う。migration policyそのものの見直しは別タスクに分ける。

## Validation Plan
自動テスト:

```text
./gradlew :diskinfo-agent:test
```

compile check:

```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
```

代表runtime確認:

```text
./gradlew :diskinfo-agent:run --args='--help'
./gradlew :diskinfo-agent:run --args='oneshot --help'
./gradlew :diskinfo-agent:run --args='standalone --help'
./gradlew :diskinfo-agent:run --args='db migrate --help'
./gradlew :diskinfo-agent:run --args='db migrate --db-url jdbc:sqlite:/tmp/cocoadiskinfo-phase2a-test.db'
```

代表failure確認:

```text
./gradlew :diskinfo-agent:run --args='--oneshot --scan'
./gradlew :diskinfo-agent:run --args='oneshot'
./gradlew :diskinfo-agent:run --args='oneshot --scan --device /dev/sda'
./gradlew :diskinfo-agent:run --args='standalone --scan --interval-seconds 0'
./gradlew :diskinfo-agent:run --args='standalone --scan --port 70000'
```

## Risks and Open Questions
- Cliktのtest helperを使うか、command factoryとrecording runtimeで検証するかを実装前に決める。production副作用を避けられるなら後者を優先する。
- `db` 単体をhelp表示にするか、現状どおりno-opにするかを決める必要がある。
- 既に入っている最小TOML loaderをPhase 2Aテストにどこまで含めるかは境界が曖昧。推奨は「CLI挙動に影響する項目だけPhase 2Aで固定し、優先順位全体はPhase 2Bで扱う」。
- `--device` がCliktの `mustExist = true` を要求するため、ドキュメント例の `/dev/sda` は環境によって失敗する。テストではfixtureまたは一時ファイルを使う。
- `SapphireExecutor.Migrate` がdrop/create方式のため、実運用migrationの後方互換性は別途Phase 4またはDB maintenance計画で見直す。
- `standalone` のserver起動まで自動integration testに含めると不安定化しやすい。Phase 2Aではparse/assemblyを中心にし、server APIは別範囲にする。

### Resolution Notes
- Resolved: Clikt helper and recording runtime are both used. Help/error output uses Clikt's `test` helper, while success paths assert recorded `SapphireCommandRequest`.
- Resolved: `db` alone is treated as help output without runtime execution, and is covered by `CliCommandTest`.
- Resolved: Phase 2A tests cover only config values that affect current subcommand behavior. Full precedence, environment variables, and default config path remain Phase 2B scope.
- Accepted: `--device` requires an existing path. Tests use temporary files instead of `/dev/*`.
- Deferred: migration backward-compatibility and server integration tests remain outside Phase 2A.

## Implementation Order
1. `Main.kt` のCLI組み立てを、production runtimeとtest runtimeに分けられる最小構造へ整える。
2. root/subcommand/helpと旧flag拒否のテストを追加する。
3. `oneshot` のtarget/output/persist/DB URL解決テストを追加する。
4. `standalone` のinterval/port/storage/target validationテストを追加する。
5. `db migrate` のparseテストと一時SQLiteを使う最小integration testを追加する。
6. Phase 2Aに影響する最小config loaderテストを追加する。
7. README、AGENTS、systemdテンプレートの例をparserと同期確認する。
8. `:diskinfo-agent:test`、compile check、代表runtime/failure確認を実行する。

## Implementation Status
- Done: CLI parsing and production side effects are split by `SapphireCommandRuntime`.
- Done: `oneshot`, `standalone`, and `db migrate` requests can be tested without running `smartctl`, Ktor, or production DB setup.
- Done: CLI help, legacy flag rejection, target validation, runtime/http/storage validation, `db migrate` assembly, and minimal config behavior are covered by tests.
- Done: `AgentConfigLoader` has direct tests for supported values and actionable parse errors.
- Done: `SapphireExecutor.Migrate` has a temporary SQLite integration test that verifies `disk_snapshot` table creation.
- Done: `kotlin("test")` is scoped as `testImplementation`.
- Verified:
  - `./gradlew :diskinfo-agent:test`
  - `./gradlew :diskinfo-core:compileKotlin`
  - `./gradlew :diskinfo-agent:compileKotlin`
  - `./gradlew :diskinfo-agent:run --args='--help'`
  - `./gradlew :diskinfo-agent:run --args='oneshot --help'`
  - `./gradlew :diskinfo-agent:run --args='standalone --help'`
  - `./gradlew :diskinfo-agent:run --args='db migrate --help'`
  - `./gradlew :diskinfo-agent:run --args='db migrate --db-url jdbc:sqlite:/tmp/cocoadiskinfo-phase2a-test.db'`
  - failure checks for legacy flags, missing target, target conflict, invalid interval, and invalid port.
