# Phase 2B: Config and Runtime Settings

## Source Strategy
- Primary: `../../master.md`
- Supporting: `../../strategy/0004_future_architecture_mode.md`
- Related task: `phase-2a-subcommand-migration.md`

## Goal
Phase 2Aで安定化する `oneshot`, `standalone`, `db migrate` のサブコマンドを前提に、設定ファイルとruntime設定の扱いを固定する。

中心方針は次の4点とする。

- 設定ファイル形式をTOMLとして確定する。
- 設定の優先順位を `default < config file < environment variables < CLI arguments` として実装・テストで固定する。
- `oneshot`, `standalone`, `db migrate` で使う設定値のvalidationとエラー表示を明示する。
- systemd/package想定の `/etc/cocoadiskinfo/agent.toml` とrepository内のexample TOMLを同期する。

## Non-Goals
- Phase 2Aのサブコマンド移行そのものは扱わない。
- 旧mode flagsである `--oneshot`, `--agent`, `--migration` の互換維持は扱わない。
- DB backend追加、`storage.type`, `storage.username`, `storage.password` の実運用対応は扱わない。
- retention、cleanup、history cache、event storageの実装は扱わない。
- Hub、Node Agent、ingest API、Prometheus、package生成は扱わない。
- health policy設定の実装は扱わない。

## Current State
- Phase 2Aが並行実装中で、`Main.kt` にはCliktベースの `oneshot`, `standalone`, `db migrate` が入っている。
- `phase-2a-subcommand-migration.md` では、既存の最小TOML読み込みをPhase 2Bの先行要素として扱っている。
- 現時点の `AgentConfigLoader` は独自の最小TOML parserで、`smartctl.scan`, `smartctl.device`, `runtime.persist`, `runtime.intervalSeconds`, `storage.jdbcUrl`, `http.port`, `output.mode` を読める。
- 現時点ではenvironment variables overlayとdefault config pathは未実装である。
- `standalone` は `--port` を持つが、`http.host` の設定経路はまだない。
- `diskinfo-agent/src/main/resources/agent.example.toml` とREADMEにはTOML例が入り始めている。

Phase 2B着手時は、Phase 2Aの差分を先に確認し、CLI形状・テスト境界・`SapphireCommandRequest` の最新形に合わせてから実装する。Phase 2Aの未確定な内部構造へ強く依存しない。

## Boundary Decision
- Owner boundary: configuration/runtime settings
- Secondary boundary: CLI/executor assembly
- Why this belongs there: `master.md` はmode-dependent behaviorをCLI/Executor組み立て層へ集める方針であり、設定優先順位とruntime validationは各Collector/Repository/Serverへ散らさず入口で解決するべきため。
- Cross-boundary impact: `Server` には `http.host` と `http.port` の最終値だけを渡す。`Collector`, `Sink`, `Repository` は設定ファイルやenvironment variableを直接読まない。

## Task Breakdown

### Task 1: Phase 2A完了状態をbaselineとして再確認する
- Objective: Phase 2AとPhase 2Bの責務が混ざらないように、着手時点のCLI shapeとテスト境界を固定する。
- Affected modules/files:
  - `evolution_plan/phase_0002/tasks/phase-2a-subcommand-migration.md`
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/Main.kt`
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/SapphireCommandRuntime.kt`
  - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/CliCommandTest.kt`
- Expected behavior:
  - `oneshot`, `standalone`, `db migrate` のサブコマンド名と主要optionをPhase 2B側で再定義しない。
  - Phase 2Aのrecording runtime/test helperがある場合はそのまま使う。
  - Phase 2A中に入った暫定config処理は、Phase 2Bで正式なresolverへ整理する対象として扱う。
- Validation:
  - `./gradlew :diskinfo-agent:test`
  - `./gradlew :diskinfo-agent:compileKotlin`
- Notes:
  - 既存差分を巻き戻さず、Phase 2Bの作業は追加・整理に留める。

### Task 2: Config contractを明文化する
- Objective: Phase 2Bでサポートするsection/key、型、command適用範囲を固定する。
- Affected modules/files:
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/config/AgentConfig.kt`
  - `diskinfo-agent/src/main/resources/agent.example.toml`
  - `README.md`
  - `README.en.md`
- Expected behavior:
  - Phase 2Bで扱うTOML keyは次に限定する。
    - `[smartctl] scan: Boolean`
    - `[smartctl] device: String`
    - `[runtime] intervalSeconds: Long`
    - `[runtime] persist: Boolean`
    - `[storage] jdbcUrl: String`
    - `[http] host: String`
    - `[http] port: Int`
    - `[output] mode: String`
  - `smartctlPath`, `storage.type`, `storage.username`, `storage.password`, `retention`, `maintenance`, `health` は将来keyとして扱い、Phase 2Bでは実装しない。
  - `[output].mode` を `oneshot` 専用にするか、`standalone` のsnapshot console outputにも適用するかを実装前に決め、READMEとtestに反映する。
- Validation:
  - `AgentConfigLoaderTest` で全keyのparseと型不一致を確認する。
  - READMEのTOML例と `agent.example.toml` の差分を目視確認する。
- Notes:
  - unknown section/keyを拒否する方針を維持する場合は、将来keyをexampleへ先に置かない。

### Task 3: Config loadingとmergeを専用resolverへ分離する
- Objective: `default < config file < environment variables < CLI arguments` を1か所で説明できる構造にする。
- Affected modules/files:
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/config/`
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/Main.kt`
- Expected behavior:
  - `AgentConfigLoader` はTOMLを `AgentConfig` へ読む責務に寄せる。
  - 新しいresolver候補として `AgentConfigResolver` または `EffectiveAgentConfig` を追加する。
  - CLI層はraw CLI optionをresolverへ渡し、resolverが最終値とvalidation errorを返す。
  - `Main.kt` 内の `resolveTarget`, `resolveDbUrl`, `resolveIntervalSeconds`, `resolvePort`, `resolveOutput` は重複しない形へ整理する。
- Validation:
  - defaultのみ、configのみ、envのみ、CLI overrideのunit testを追加する。
  - `oneshot`, `standalone`, `db migrate` のrecording runtime testで最終値を確認する。
- Notes:
  - `Collector`, `Sink`, `Repository`, `Server` がresolverを直接参照しない構造にする。

### Task 4: Environment variable overlayを追加する
- Objective: config fileより強く、CLI argumentsより弱い設定sourceを実装する。
- Affected modules/files:
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/config/`
  - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/config/`
  - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/CliCommandTest.kt`
- Expected behavior:
  - environment variable名は `COCOADISKINFO_AGENT_` prefixで統一する。
  - Phase 2B候補:
    - `COCOADISKINFO_AGENT_SMARTCTL_SCAN`
    - `COCOADISKINFO_AGENT_SMARTCTL_DEVICE`
    - `COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS`
    - `COCOADISKINFO_AGENT_RUNTIME_PERSIST`
    - `COCOADISKINFO_AGENT_STORAGE_JDBC_URL`
    - `COCOADISKINFO_AGENT_HTTP_HOST`
    - `COCOADISKINFO_AGENT_HTTP_PORT`
    - `COCOADISKINFO_AGENT_OUTPUT_MODE`
  - boolean、integer、enum、blank stringのparse errorはactionableなmessageにする。
  - CLI argumentが同じ値を指定した場合はenvironment variableより優先する。
- Validation:
  - config file値をenvironment variableが上書きするtestを追加する。
  - environment variable値をCLI argumentが上書きするtestを追加する。
  - 不正なboolean/integer/enum/blankのfailure testを追加する。
- Notes:
  - testではprocess全体の環境変数変更を避け、resolverへ `Map<String, String>` を注入できる形を優先する。

### Task 5: Config path policyを決めて実装する
- Objective: 開発時とsystemd/package時の設定ファイル探索を明確にする。
- Affected modules/files:
  - `Main.kt`
  - `AgentConfigDefaults`
  - `deploy/systemd/cocoadiskinfo-agent.service`
  - `README.md`
  - `README.en.md`
- Expected behavior:
  - 明示された `--config <path>` は存在しない場合に失敗する。
  - systemd/package想定pathは `/etc/cocoadiskinfo/agent.toml` として定義する。
  - default pathを自動読み込みする場合は、存在するときだけ読む。存在しない場合はempty configとして続行する。
  - default pathを自動読み込みしない場合は、systemd templateで `--config /etc/cocoadiskinfo/agent.toml` を明示する。
- Validation:
  - explicit config path missing failure。
  - default pathあり/なしのresolver test。
  - systemd templateの `ExecStart` が決定したpolicyと一致すること。
- Notes:
  - 自動読み込みは便利だが、インストール済み `/etc` が開発実行へ影響する可能性がある。着手前に方針を決める。

### Task 6: Runtime validationを明示する
- Objective: 不正設定を遅いruntime failureではなくCLI/config解決時に止める。
- Affected modules/files:
  - `AgentConfigResolver` または同等のconfig解決層
  - `Main.kt`
  - `CliCommandTest.kt`
- Expected behavior:
  - `scan=true` と `device` の同時指定は、config/env/CLIをmergeした後の最終値で拒否する。
  - `intervalSeconds` は正の値のみ許可する。
  - `port` は `1..65535` の範囲のみ許可する。
  - `host` はblankを拒否する。
  - `output.mode` は `OutputMode` の列挙値のみ許可する。
  - `storage.jdbcUrl` はblankを拒否する。
  - `oneshot` は既定ではDB接続しない。`--persist` または有効な `runtime.persist=true` のときだけ保存する。
- Validation:
  - 各validationごとにunit testを追加する。
  - failure messageにsection/keyまたはoption名を含める。
- Notes:
  - `db migrate` はschema operationのみで、target device設定を要求しない。

### Task 7: `http.host` をStandalone起動へ接続する
- Objective: 設定対象に含める `http.host` をserver起動へ反映する。
- Affected modules/files:
  - `SapphireCommandRequest.Standalone`
  - `SapphireCommandRuntime.kt`
  - `SapphireAgentServer.kt`
  - `Main.kt`
  - `CliCommandTest.kt`
- Expected behavior:
  - `standalone --host <host>` を追加する場合、config/envより優先する。
  - `SapphireAgentServer` はhostとportの最終値を受け取る。
  - default hostは運用上の安全を優先して `127.0.0.1` または現行Ktor default相当のどちらかに固定し、READMEに明記する。
- Validation:
  - default host、config host、env host、CLI hostのassembly test。
  - `./gradlew :diskinfo-agent:run --args='standalone --help'` で `--host` が表示されること。
- Notes:
  - default hostを変えると公開範囲が変わるため、既存挙動との差分をPR notesに明記する。

### Task 8: Example config、README、systemd templateを同期する
- Objective: 実装された設定仕様と運用例を一致させる。
- Affected modules/files:
  - `diskinfo-agent/src/main/resources/agent.example.toml`
  - `README.md`
  - `README.en.md`
  - `deploy/systemd/cocoadiskinfo-agent.service`
- Expected behavior:
  - example TOMLはPhase 2Bで実装済みのkeyだけを含む。
  - READMEに優先順位 `default < config file < environment variables < CLI arguments` を記載する。
  - environment variable一覧をREADMEに記載する。
  - systemd templateは `/etc/cocoadiskinfo/agent.toml` を使う運用例に揃える。
  - DB pathは `/var/lib/cocoadiskinfo/sapphire.db` へ寄せるか、現行のworking directory相対pathを維持するかを明記する。
- Validation:
  - `rg -- '--oneshot|--agent|--migration' README.md README.en.md AGENTS.md deploy`
  - READMEの代表コマンドをparserで確認する。
- Notes:
  - package生成自体はPhase 7で扱う。

### Task 9: 実装後レビュー指摘を反映してresolverをcommand scope別に整理する
- Objective: `oneshot`, `standalone`, `db migrate` が使う設定だけを解決・validationするようにし、未使用設定による副作用を避ける。
- Affected modules/files:
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/config/AgentConfigResolver.kt`
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/Main.kt`
  - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/config/AgentConfigResolverTest.kt`
  - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/CliCommandTest.kt`
- Expected behavior:
  - `resolveOneshot`, `resolveStandalone`, `resolveDbMigrate` のように、commandごとのeffective configを分ける。
  - `oneshot` はtarget、output、persist、storageだけを必要範囲として扱う。
  - `standalone` はtarget、runtime、storage、http、outputを必要範囲として扱う。
  - `db migrate` はstorageだけを必要範囲として扱い、`smartctl`, `runtime`, `http`, `output` の値が不正でも未使用なら失敗しない。
  - 共通merge処理は残してよいが、validationはcommand scope別に行う。
- Validation:
  - `db migrate --config <file>` が、`smartctl.scan` と `smartctl.device` の競合、または未使用の `output.mode` 不正値に影響されず、`storage.jdbcUrl` だけで実行できること。
  - `oneshot` と `standalone` では引き続きtarget競合、interval/port、blank DB URL、output mode不正を検出できること。
- Notes:
  - `master.md` と `AGENTS.md` の方針どおり、DB commandはschema operationのみを担当し、収集・HTTP公開・出力形式のvalidationに巻き込まない。

### Task 10: `[output].mode` の適用範囲を実装とドキュメントで揃える
- Objective: 設定ファイルに書ける `[output].mode` がどのcommandに効くのかを明確にし、実装とREADME/exampleの認識差をなくす。
- Affected modules/files:
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/SapphireCommandRuntime.kt`
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/Main.kt`
  - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/config/AgentConfigResolver.kt`
  - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/CliCommandTest.kt`
  - `README.md`
  - `README.en.md`
  - `diskinfo-agent/src/main/resources/agent.example.toml`
- Expected behavior:
  - 推奨方針は、`[output].mode` を `oneshot` と `standalone` のsnapshot console outputへ適用する。
  - `SapphireCommandRequest.Standalone` に `outputMode` を持たせ、`runStandalone()` で `setupConsoleOutput(request.outputMode)` を使う。
  - `standalone --output <mode>` を追加する場合は、config/envよりCLIを優先する。
  - `db migrate` は通常のsnapshot outputを行わないため、`[output].mode` のvalidation対象にしない。
- Validation:
  - `standalone --config <file>` で `[output].mode` がrequestに反映されることをrecording runtime testで確認する。
  - `COCOADISKINFO_AGENT_OUTPUT_MODE` と `standalone --output` の優先順位を確認する。
  - README/exampleが実際の適用範囲と一致していることを確認する。
- Notes:
  - 代替方針として `output.mode` を `oneshot` 専用にする場合は、README/exampleで明記し、`standalone` では設定値を無視するだけでなく未使用設定として扱う。

## CLI/API Compatibility
Phase 2Bは既存サブコマンドを壊さない。

追加候補のCLI option:

```text
cocoadiskinfo-agent oneshot --config <path>
cocoadiskinfo-agent standalone --config <path>
cocoadiskinfo-agent standalone --output <mode>
cocoadiskinfo-agent standalone --host <host>
cocoadiskinfo-agent standalone --port <port>
cocoadiskinfo-agent db migrate --config <path>
```

既存の `--scan`, `--device`, `--output`, `--persist`, `--no-persist`, `--interval-seconds`, `--db-url` はPhase 2Aの決定に従う。

API response shapeは変更しない。`http.host` はlisten addressの設定であり、API pathやpayloadを変えない。

## Data and Persistence Impact
- schema変更は行わない。
- `storage.jdbcUrl` の解決順序が明確になるため、`oneshot --persist`, `standalone`, `db migrate` の接続先が変わり得る。
- `oneshot` は既定ではDB接続しない方針を維持する。
- `standalone` はDB接続必須のままとする。
- `db migrate` はschema operationのみで、収集処理を持たせない。
- retention、cleanup、history、cache、event storageの保持期間は変更しない。

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

代表success確認:

```text
./gradlew :diskinfo-agent:run --args='--help'
./gradlew :diskinfo-agent:run --args='oneshot --help'
./gradlew :diskinfo-agent:run --args='standalone --help'
./gradlew :diskinfo-agent:run --args='db migrate --help'
./gradlew :diskinfo-agent:run --args='db migrate --config src/main/resources/agent.example.toml'
```

実機またはfixture deviceがある環境での代表確認:

```text
./gradlew :diskinfo-agent:run --args='oneshot --config src/main/resources/agent.example.toml'
./gradlew :diskinfo-agent:run --args='standalone --config src/main/resources/agent.example.toml'
```

代表failure確認:

```text
./gradlew :diskinfo-agent:run --args='oneshot --config /path/does/not/exist.toml'
./gradlew :diskinfo-agent:run --args='standalone --scan --interval-seconds 0'
./gradlew :diskinfo-agent:run --args='standalone --scan --port 70000'
```

## Risks and Open Questions
- TOML parserは当面、現状の独自最小実装を維持する。専用libraryへの切り替えはPhase 2B修正範囲には含めない。
- `/etc/cocoadiskinfo/agent.toml` を暗黙に自動読み込みするか、systemd templateで明示 `--config` にするか。
- `[output].mode` は `standalone` にも適用する方針を推奨する。実装時に `standalone --output` を追加するか、設定ファイル/envだけにするかを確定する。
- `http.host` のdefaultを現行Ktor default相当にするか、`127.0.0.1` に固定して公開範囲を絞るか。
- `storage.jdbcUrl` のdefaultを現行 `jdbc:sqlite:./sapphire.db` のままにするか、systemd/package想定の `/var/lib/cocoadiskinfo/sapphire.db` へ寄せるか。Phase 7前に変える場合はREADMEとsystemd templateも同時に更新する。
- Phase 2Aの実装がまだ動いているため、Phase 2B着手時に `SapphireCommandRequest` やtest helperの形が変わっている可能性がある。
- 実装後レビューで、`db migrate` が未使用のruntime/http/output設定までvalidationしている点が見つかっている。次の修正ではcommand scope別validationを優先する。

## Implementation Order
1. Phase 2Aの最新差分とtask文書を読み、CLI/test baselineを固定する。
2. Phase 2Bのconfig contractと未実装keyの扱いを決める。
3. `AgentConfigLoader` と `AgentConfigResolver` の責務を分け、merge順序をunit testで固定する。
4. environment variable overlayを追加する。
5. config path policyと `/etc/cocoadiskinfo/agent.toml` の扱いを実装する。
6. runtime validationをresolverへ集約する。
7. `http.host` を `standalone` の起動設定へ接続する。
8. example TOML、README、systemd templateを同期する。
9. 実装後レビュー指摘として、resolverをcommand scope別に分け、`db migrate` をstorage-only validationへ修正する。
10. `[output].mode` を `standalone` にも適用する方針で、`SapphireCommandRequest.Standalone`、`runStandalone()`、必要なら `standalone --output` を更新する。
11. `AgentConfigResolverTest` と `CliCommandTest` に、command scope別validation、`standalone` output mode、`db migrate` の未使用設定無視を追加する。
12. README、README.en、example TOMLを最終仕様に同期する。
13. `:diskinfo-agent:test`、compile check、代表success/failure commandを実行する。
