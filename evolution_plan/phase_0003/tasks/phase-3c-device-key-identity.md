# Phase 3C: Device Key Identity

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0003_plan.md`
- Supporting: `../../strategy/0001_api_response_strategy.md`
- Supporting: `../../strategy/0005_device_history_ui_api_strategy.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`
- Supporting: `../../strategy/0008_device_identity_strategy.md`

## Goal
Phase 3A/3Bで追加したhistory APIとClient history flowを維持しつつ、`deviceKey` の意味と導出方式を固定する。

`deviceKey` はpathではなく、API/DB/Client selectionで使うopaque stable device identityとして扱う。`/dev/sda` や `/dev/nvme0n1` のようなdevice pathは差し替え、起動順、ポート変更で変わる可能性があるため、履歴identityには使わない。

## Non-Goals
- History API path shapeを変えない。
- Clientにdevice identityの解釈ロジックを持たせない。
- Phase 3CではHub/Node Agent ingestのidentity交渉を実装しない。
- Phase 3CではHMAC派生を実装しない。
- Phase 3CではHMAC secretやscheme selectionのTOML設定を追加しない。
- 既存SQLite dataのmigration/backfillは行わない。現時点の利用者は開発者本人のみであり、開発途中のDB互換を維持しない。
- blank serial、重複serial、不正コントローラ、ダミーデバイスなどの例外的identity sourceは今後の課題とし、Phase 3Cでは扱わない。
- serial number表示制御のUIを実装しない。
- disk graph UIを追加しない。

## Current State
- `DiskSnapshot` は `deviceKey`, `path`, `serial` を別フィールドとして持つ。
- ATA/NVMe converterは現在 `deviceKey = serialNumber` を設定している。
- SQLite `disk_snapshot` は `device_key`, `device_serial_name`, `device_path` を別カラムとして保存している。
- History APIは `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots` を提供し、`nodeId + deviceKey` で履歴対象を検索する。
- Clientはlatest APIで受け取った `deviceKey` を解釈せず、そのままHistory APIへ渡している。
- TOML設定基盤は存在するが、device identity設定はまだない。
- Supporting strategyは `deviceKey` をopaque stable device identityとして扱う方針に更新済みである。

## Boundary Decision
- Owner boundary: `diskinfo-agent` のcollection/conversion/configuration/runtime settings。
- Why this belongs there:
    - raw `serialNumber` や将来のdevice UUID/WWN/EUI/NGUIDはsmartctl入力に近い。
    - `diskinfo-core` の `DiskSnapshot` は導出済みのopaque `deviceKey` を保持するだけにし、secretやTOML設定を知らない方がよい。
    - `diskinfo-client` は `deviceKey` をopaque stringとして扱うだけにする。
- Cross-boundary impact:
    - `diskinfo-agent` converter/collectorは `deviceKey` 導出器を通す必要がある。
    - OpenAPI/strategy/phase planは `deviceKey` をopaque stable keyとして説明する必要がある。

## Resolved Decisions
- Phase 3CではUUIDv5のみを実装する。
- 将来HMAC派生を追加できるよう、`DeviceKeyDeriver` のような導出抽象は用意する。
- Phase 3Cではruntime scheme selectionを実装しない。標準も唯一の実装もUUIDv5である。
- UUIDv5の入力は正規化した `serialNumber` に固定する。
- `path`, `protocol`, `model`, ATA WWN, NVMe EUI/NGUIDなどはPhase 3Cのidentity sourceに含めない。
- TOMLでは任意のnamespace UUIDではなく、UUIDv5 namespaceを導出するためのsaltを設定する。
- `namespaceSalt` の既定値は `"default"` とする。
- 既存SQLite rowsのmigration/backfillは実施しない。
- ダミーデバイス、不正コントローラ、blank/重複serialに対する専用check commandは今後の課題とする。

## UUIDv5 Design
Phase 3Cの `deviceKey` は次の段階で導出する。

1. CocoaDiskInfo固有のbase namespaceを定義する。
2. TOMLで指定された `namespaceSalt` をbase namespaceへ与え、effective namespaceをUUIDv5で導出する。
3. 正規化した `serialNumber` をeffective namespaceへ与え、device key UUIDをUUIDv5で導出する。

実装候補:

```text
COCOADISKINFO_DEVICE_NAMESPACE = UUIDv5(NAMESPACE_DNS, "com.milkcocoa.info.sapphire.device")
effectiveNamespace = UUIDv5(COCOADISKINFO_DEVICE_NAMESPACE, namespaceSalt)
deviceKey = UUIDv5(effectiveNamespace, "serial:v1:${normalizedSerialNumber}")
```

`namespaceSalt` はsecretではない。saltを変えると同じdiskでも `deviceKey` が変わるため、履歴identity変更として扱う。

## Config Shape
TOML設定候補:

```toml
[deviceIdentity]
# Optional. Changing this changes derived device keys.
namespaceSalt = "default"
```

環境変数候補:

```text
COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT
```

CLI flagは増やさない。

## Task Breakdown

### Task 1: Fix Device Key Documentation
- Objective: `deviceKey` の意味をopaque stable device identityとして文書化し、path由来と読める説明を修正する。
- Affected modules/files:
    - `evolution_plan/master.md`
    - `evolution_plan/strategy/0001_api_response_strategy.md`
    - `evolution_plan/strategy/0005_device_history_ui_api_strategy.md`
    - `evolution_plan/strategy/0007_db_data_lifetime_policy.md`
    - `evolution_plan/phase_0003/phase_0003_plan.md`
    - `evolution_plan/phase_0003/tasks/phase-3a-bounded-history-api.md`
    - `evolution_plan/phase_0003/tasks/phase-3b-client-history-view.md`
- Expected behavior:
    - `deviceKey` はpathではないと明記する。
    - `path` は表示・診断用、`serial` は表示・照合用、`deviceKey` はAPI/DB selection用と整理する。
    - `nodeId + deviceKey` のAPI shapeは維持する。
- Validation:
    - supporting strategyやphase planに、`deviceKey` がpath由来であるかのような説明が残っていないことを確認する。
- Notes:
    - 既存API pathは変えない。

### Task 2: Add Device Identity Config
- Objective: UUIDv5 namespace saltをTOML/environmentから解決できるようにする。
- Affected modules/files:
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/config/AgentConfig.kt`
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/config/AgentConfigLoader.kt`
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/config/AgentConfigResolver.kt`
    - `diskinfo-agent/src/main/resources/agent.example.toml`
    - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/config/AgentConfigLoaderTest.kt`
    - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/config/AgentConfigResolverTest.kt`
- Expected behavior:
    - `[deviceIdentity].namespaceSalt` を解決する。
    - environment variable `COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT` で上書きできる。
    - 未設定時は既定saltを使う。
    - blank saltはvalidation errorにする。
    - runtime scheme selection、HMAC-related secret settings、raw namespace UUID設定はPhase 3Cでは受け付けない。
    - CLI flagは増やさない。設定優先順位は既存方針どおり `default < config file < environment variables < CLI arguments` とし、CLIに該当flagがない項目はenvironmentが最上位になる。
- Validation:
    - config loader/resolver unit tests。
    - unknown section/key/type errorが既存MinimalTomlParserの方針と一致すること。
- Notes:
    - `namespaceSalt` はsecretではないため、example TOMLにコメント付きで載せてよい。

### Task 3: Implement Device Key Derivation
- Objective: raw `serialNumber` からopaque `deviceKey` を生成する。
- Affected modules/files:
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/...`
    - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/...`
- Expected behavior:
    - `DeviceKeyDeriver` などの抽象を用意し、Phase 3Cでは `UuidV5DeviceKeyDeriver` だけを実装する。
    - 将来HMAC派生を追加する場合は、この抽象に実装を追加できるようにする。
    - `serialNumber` をtrimしてcanonical inputにする。
    - blank serialは専用fallbackせずvalidation errorにする。ダミーデバイス対応は将来課題とする。
    - UUIDv5はCocoaDiskInfo固有base namespace、`namespaceSalt`、normalized serialから決定的UUIDを生成する。
    - same input + same configは同じ `deviceKey` を返す。
    - different namespace saltは違う `deviceKey` を返す。
- Validation:
    - UUIDv5 deterministic test。
    - namespace salt差分のtest。
    - blank serial/blank salt test。
- Notes:
    - `deviceKey` はURL path segmentで安全に扱える文字列にする。
    - 出力はUUID文字列だが、API/OpenAPI上は将来HMAC派生を追加できるようopaque stringとして扱う。
    - Phase 3Cではserialベースに固定する。将来smartctlを使わない収集経路やidentity source選択式に移行する可能性があるため、source選択は急がない。

### Task 4: Thread Derivation Through Collection
- Objective: smartctl converterがraw serialを `deviceKey` として直接使わないようにする。
- Affected modules/files:
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/collector/SmartctlCollector.kt`
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/smartctl/converter/AtaSnapshotConverter.kt`
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/smartctl/converter/NvmeSnapshotConverter.kt`
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/SapphireCommandRuntime.kt`
    - related tests
- Expected behavior:
    - `DiskSnapshot.deviceKey` は導出済みopaque keyになる。
    - `DiskSnapshot.serial` は引き続きraw serialを保持する。
    - `DiskSnapshot.path` は引き続きdevice pathを保持する。
    - oneshot output、standalone persistence、latest API、history APIが同じkeyを使う。
- Validation:
    - converter/collector tests。
    - `./gradlew :diskinfo-agent:test`
    - `./gradlew :diskinfo-agent:compileKotlin`
- Notes:
    - `diskinfo-core` にsecret/config依存を入れない。

### Task 5: Update API/OpenAPI and Tests
- Objective: `deviceKey` をopaque keyとしてAPI契約に反映する。
- Affected modules/files:
    - `diskinfo-agent/src/main/resources/openapi/documentation.yaml`
    - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/server/SapphireAgentServerTest.kt`
    - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/datastore/DiskSnapshotRepositoryTest.kt`
- Expected behavior:
    - OpenAPIは `deviceKey` をraw serialやpathと説明しない。
    - server/repository testsのfixtureは `serial-a` ではなくopaque key風の値を使う。
    - `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots` のpath shapeは変えない。
- Validation:
    - `./gradlew :diskinfo-agent:test`
    - tests、OpenAPI、evolution planに、raw serialやpathを `deviceKey` として説明する記述が残っていないことを確認する。

## CLI/API Compatibility
- API path shapeは維持する。
- Clientはlatest APIで得た `deviceKey` をそのままhistory APIへ渡すため、Client API flowは維持できる。
- `deviceKey` の値はPhase 3Cで変わるため、raw serialを直接指定していた手動API利用は互換ではない。
- CLI flagは増やさないことを初期方針にする。
- TOML/environment設定は `namespaceSalt` のみ追加する。未設定時は既定saltでUUIDv5 derivationを行う。

## Data and Persistence Impact
- 新規snapshotの `device_key` はraw serialではなく導出済みopaque keyになる。
- `device_serial_name` と `snapshot_json.serial` にはraw serialが残るため、serial露出制御は別途必要。
- 既存DBの `device_key` migration/backfillはPhase 3Cでは行わない。
- `namespaceSalt` を変更すると同じdiskの `deviceKey` が変わる。設定変更は履歴identity変更として扱う。
- Phase 3C時点では開発途中のDB互換を維持しない。

## Validation Plan
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:run --args='oneshot --scan'
./gradlew :diskinfo-agent:run --args='standalone --scan'
./gradlew :diskinfo-agent:run --args='db migrate'
```

追加で確認すること:
- default configでraw serialが `deviceKey` に出ない。
- same disk + same configで `deviceKey` が安定する。
- custom namespace saltで `deviceKey` が変わる。
- History tabはlatest APIから受け取ったopaque keyで履歴を取得できる。
- config validation errorが実行前に分かる。

## Risks and Follow-up Notes
- UUIDv5 helperのpackage配置は実装時に既存の `diskinfo-agent` 境界に合わせて決める。
- 将来HMAC派生を追加する場合、secret管理とscheme selectionを別phaseで扱う。
- blank serialや明らかにダミーのserialは将来課題である。Phase 3Cでは専用check commandやfallbackを実装しない。
- 将来smartctlを使わない収集経路、またはidentity source選択式を導入する可能性がある。Phase 3Cではserialベースに固定する。

## Implementation Order
1. 文書上の `deviceKey` 定義をopaque stable identityへ修正する。
2. `[deviceIdentity].namespaceSalt` configを追加する。
3. `DeviceKeyDeriver` 抽象とUUIDv5実装を追加する。
4. converter/collector/runtimeへ導出器を通す。
5. OpenAPI/testsをopaque key前提へ更新する。
6. compile/test/runtime checkを実行する。
