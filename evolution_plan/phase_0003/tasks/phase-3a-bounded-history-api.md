# Phase 3A: Bounded History API

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0003_plan.md`
- Supporting: `../../strategy/0001_api_response_strategy.md`
- Supporting: `../../strategy/0005_device_history_ui_api_strategy.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`

## Goal
現在状態APIを維持したまま、StandaloneのSQLite historyからnode/device単位のbounded history APIを追加する。

追加するAPI:

```text
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots
```

`nodeId` をURIに含める目的は、Hub化後も履歴対象のdeviceを一意に指定するためである。Standaloneではnodeが1つなので冗長に見えるが、複数Node Agent構成では `/dev/sda` や `nvme0n1` 相当のdevice keyが各マシンで競合し得るため、履歴APIは `(nodeId, deviceKey)` をdevice history identityとして扱う。

Query:

- `limit`: default `100`, max `1000`
- `from`: optional ISO-8601 timestamp
- `to`: optional ISO-8601 timestamp
- `order`: `desc` default, `asc` optional

Response:

```kotlin
NodeDeviceHistoryPayload(
    nodeId: String,
    nodeName: String,
    deviceKey: String,
    snapshots: List<DiskSnapshot>,
)
```

## Non-Goals
- `source=live` を実装しない。
- Hubのpartial/stale/error metadataは扱わない。
- aggregated history tableやdownsamplingは追加しない。
- retention/cleanup実装はPhase 4以降のstorage maintenanceとして扱う。
- current-state API `GET /api/v1/snapshots/latest` の意味を変えない。

## Baseline Before Implementation
- `DiskSnapshotTable` には `node_id`, `device_key`, `collect_time` のindexがある。
- `DiskSnapshotRepository` は `insert(...)`, `findLatestNodes()`, `findLatestByDeviceKey(...)` を持つ。
- `SapphireAgentServer` は次のAPIを公開している。
    - `GET /api/v1/snapshots/latest`
    - `GET /api/v1/devices/{deviceKey}/snapshots/latest`
- `diskinfo-core` のAPI payloadは `LatestSnapshotsPayload` と `NodeSnapshot` のみで、history payloadはまだない。
- OpenAPI定義はlatest系endpointだけを含む。

## Implementation Status
- Done: `NodeDeviceHistoryPayload` を `diskinfo-core` に追加した。
- Done: `HistoryQuery` / `HistoryOrder` と `DiskSnapshotRepository.findHistory(...)` を追加した。
- Done: `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots` を追加し、`limit`, `from`, `to`, `order` をvalidationする。
- Done: no historyは `200` + empty `snapshots` を返す。
- Done: OpenAPIにhistory endpoint、query、payload schemaを追加した。
- Done: Repository testとKtor route testを追加した。

## Boundary Decision
- Owner boundary: `Repository` and `Server`
- Why this belongs there: 履歴取得は永続化済みsnapshotの検索であり、smartctl再実行やClient側集約ではなくRepository queryとHTTP routingで扱う。
- Cross-boundary impact:
    - `diskinfo-core`: ClientとAgentで共有するhistory response payloadを追加する。
    - `diskinfo-agent`: Repository query、query validation、route、OpenAPI定義を追加する。
    - `diskinfo-client`: Phase 3BでこのAPIを読む。Phase 3AだけではClient UIは変えない。

## Task Breakdown
### Task 1: Add Shared History Payload
- Objective: `diskinfo-core` に `NodeDeviceHistoryPayload` を追加する。
- Affected modules/files:
    - `diskinfo-core/src/main/kotlin/com/milkcocoa/info/sapphire/core/api/ApiResponse.kt`
- Expected behavior:
    - `nodeId`, `nodeName`, `deviceKey`, `snapshots` を持つserializable payloadを提供する。
    - `snapshots` はbounded queryの結果だけを含む。
- Validation:
    - `./gradlew :diskinfo-core:compileKotlin`
- Notes:
    - `LatestSnapshotsPayload` と `NodeSnapshot` は変更しない。

### Task 2: Add Repository History Query
- Objective: `DiskSnapshotRepository` にnode/device単位のbounded history queryを追加する。
- Affected modules/files:
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/datastore/DiskSnapshotRepository.kt`
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/datastore/DiskSnapshotTable.kt`
- Expected behavior:
    - `nodeId`, `deviceKey`, `limit`, `from`, `to`, `order` で `disk_snapshot` を検索する。
    - `deviceKey` 単体では検索しない。
    - `collect_time` でsortする。
    - `limit` を必ず適用し、unbounded readを作らない。
    - 未知のnode/deviceや保持期間外は空の `snapshots` を返す。
- Validation:
    - 一時SQLite DBに複数node/device/時刻のsnapshotを入れ、対象node/deviceだけ返ること。
    - `asc` / `desc` の順序が正しいこと。
    - `from` / `to` の境界が正しいこと。
- Notes:
    - 既存index `(node_id, device_key, collect_time)` を前提にする。
    - node idはDBではUUID型なので、invalid UUID文字列の扱いをroute側で `400` にする。

### Task 3: Add History Route and Query Validation
- Objective: `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots` を追加する。
- Affected modules/files:
    - `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/server/SapphireAgentServer.kt`
    - `diskinfo-agent/src/test/kotlin/com/milkcocoa/info/sapphire/agent/server/...`
- Expected behavior:
    - valid queryは `ApiResponse.Success(NodeDeviceHistoryPayload(...))` を返す。
    - no historyは `200` + empty `snapshots` を返す。
    - blank `nodeId` / `deviceKey`、invalid UUID、invalid `limit`、invalid timestamp、invalid `order` は `400` を返す。
    - `limit` defaultは `100`、maxは `1000`。
    - `from > to` は `400`。
- Validation:
    - Ktor route testでsuccess/empty/invalid queryを確認する。
    - route testを入れる場合は `ktor-server-test-host` のtest dependencyを追加する。
- Notes:
    - latest APIのrouteとresponseは変更しない。
    - `source=live` はここでは追加しない。

### Task 4: Update OpenAPI Documentation
- Objective: history endpointとpayload schemaをOpenAPIに反映する。
- Affected modules/files:
    - `diskinfo-agent/src/main/resources/openapi/documentation.yaml`
- Expected behavior:
    - path/query/response/error schemaがPhase 3A APIと一致する。
    - latest APIのschemaは維持する。
- Validation:
    - `rg` でendpoint pathとquery parameterがOpenAPIに存在することを確認する。

## CLI/API Compatibility
- CLI互換は変更しない。
- `GET /api/v1/snapshots/latest` はcurrent-state optimized APIとして維持する。
- `GET /api/v1/devices/{deviceKey}/snapshots/latest` は既存互換のlatest device endpointとして維持する。
- 新APIはbounded history専用とし、`source=live` queryは追加しない。
- history APIでは `(nodeId, deviceKey)` を必須にし、Hub構成でdevice keyが競合してもAPI shapeを変えずに扱えるようにする。

## Data and Persistence Impact
- 既存 `disk_snapshot` を読むだけでschema変更は不要。
- history APIはraw snapshot retentionの影響を受ける。保持期間外の空結果は正常系とする。
- unbounded readは禁止し、`limit` default/maxをroute validationで固定する。

## Validation Plan
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
```

Focused checks:

```text
./gradlew :diskinfo-agent:run --args='db migrate'
```

Manual API check after starting Standalone with persisted data:

```text
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots?limit=10&order=desc
```

## Risks and Open Questions
- No decision needed for live retrieval in Phase 3A. `source=live` remains an open question in `0001_api_response_strategy.md`.
- Ktor resource routingでquery parameter validationをどこまでResource classに寄せるかは実装時に決める。方針は「route boundaryで明示的に400を返す」こと。
- no historyを `404` ではなく `200` emptyにする点は、Clientのempty stateを単純にするためstrategy `0005` に従う。

## Implementation Order
1. `NodeDeviceHistoryPayload` を `diskinfo-core` に追加する。
2. `HistoryQuery` 相当の内部query modelを `diskinfo-agent` に追加する。
3. `DiskSnapshotRepository.findHistory(...)` を実装し、Repository testを追加する。
4. `SapphireAgentServer` にhistory routeとquery validationを追加する。
5. route testでsuccess/empty/invalid queryを固定する。
6. OpenAPI定義を更新する。
7. compile/testと `db migrate` 回帰確認を実行する。
