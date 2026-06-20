# Phase 3B: Client History View

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0003_plan.md`
- Supporting: `../../strategy/0005_device_history_ui_api_strategy.md`
- API prerequisite: `phase-3a-bounded-history-api.md`

## Goal
Clientの現在状態ビューを維持したまま、選択中deviceのdetail paneに `Current | History` の切替を追加し、Phase 3Aのbounded history APIを使って履歴summaryとtimelineを表示する。

Phase 3Bでは独立したNode選択画面を新設しない。既存のnode grouped device listでdeviceを選ぶことで `selectedNodeId` と `selectedDeviceKey` を同時に確定し、History tabはその `(nodeId, deviceKey)` で履歴APIを呼ぶ。

## Non-Goals
- 複雑なグラフは作らない。
- main device listに履歴を出さない。
- long-term aggregated historyやdownsamplingは扱わない。
- Hubのpartial/stale/error metadata UIはPhase 5まで扱わない。
- Client側でunbounded history取得を行わない。

## Baseline Before Implementation
- `AgentApiClient` は `fetchLatestNodes(baseUrl)` だけを持つ。
- `Dashboard` がlatest nodesを周期refreshし、選択node/deviceを管理している。
- Client stateにはすでに `selectedNodeId` と `selectedDeviceKey` があり、左ペインのdevice選択で両方を更新している。
- `DeviceContent` は `DeviceDetailPane` に選択nodeとlatest snapshotを渡す。
- `DeviceDetailPane` はCurrent専用で、header、focus tiles、detail tableを表示している。
- Phase 3Aのhistory APIは実装済みで、`GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots` が `NodeDeviceHistoryPayload` を返す。
- Phase 3Aのquery validationでは、`from` / `to` は `1970-01-01T00:00:00Z` のようなISO-8601 instantを受ける。初期UIではまだ `from` / `to` を使わない。
- history用のclient model、loading/error/empty state、tab UIはまだない。

## Implementation Status
- Done: `AgentApiClient.fetchDeviceHistory(...)` を追加した。
- Done: `Dashboard` / `DeviceContent` / `DeviceDetailPane` にhistory loaderを通した。
- Done: `DeviceDetailPane` に `Current | History` tabを追加し、Current既存表示を維持した。
- Done: History tab内にloading/error/empty stateを追加した。
- Done: `DeviceHistoryPane` でsummary tilesとtimeline rowsを表示する。
- Done: 初期UIでは `limit=100`, `order=desc` のbounded readだけを行い、`from` / `to` は送らない。

## Boundary Decision
- Owner boundary: `Client`
- Why this belongs there: Phase 3AでAPI contractを固定し、Phase 3BではClientがそのcontractを読むだけにする。履歴の抽出・filtering・並び替えはServer/Repository側に置き、Clientはbounded resultを表示する。
- Cross-boundary impact:
    - `diskinfo-core`: Phase 3Aの `NodeDeviceHistoryPayload` をClientで利用する。
    - `diskinfo-agent`: Phase 3Bでは変更しない。

## Task Breakdown
### Task 1: Add Client API Method
- Objective: `AgentApiClient` にdevice history取得を追加する。
- Affected modules/files:
    - `diskinfo-client/src/desktopMain/kotlin/com/milkcocoa/info/sapphire/client/AgentApiClient.kt`
- Expected behavior:
    - `fetchDeviceHistory(baseUrl, nodeId, deviceKey, limit = 100)` を追加する。
    - URLは `/api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots` を組み立てる。
    - `limit`, `order` は初期UIでは `limit=100`, `order=desc` に固定してよい。
    - `from` / `to` は送らない。
    - `200` + empty `snapshots` は成功として扱い、empty stateへ渡す。
    - non-200 responseは既存latest取得と同じ方針で `AgentApiException` にする。
- Validation:
    - `./gradlew :diskinfo-client:compileKotlinDesktop`
- Notes:
    - `from` / `to` は初期UIでは使わない。将来の期間filter用にAPI contractだけ残す。

### Task 2: Thread History Loader to Detail Pane
- Objective: 選択deviceのdetail paneがhistory APIを呼べるようにする。
- Affected modules/files:
    - `diskinfo-client/src/desktopMain/kotlin/com/milkcocoa/info/sapphire/client/Dashboard.kt`
    - `diskinfo-client/src/desktopMain/kotlin/com/milkcocoa/info/sapphire/client/DeviceContent.kt`
    - `diskinfo-client/src/desktopMain/kotlin/com/milkcocoa/info/sapphire/client/DeviceDetailPane.kt`
- Expected behavior:
    - `Dashboard` は `agentUrl` と `AgentApiClient` をdetail paneまで渡す。
    - 既存のnode grouped device listを初期選択UIとして維持し、追加のNode selectorは作らない。
    - deviceクリックで確定した `selectedNodeId` / `selectedDeviceKey` をhistory loaderに渡す。
    - History tab選択時だけ `fetchDeviceHistory(...)` を呼ぶ。
    - History tabを選択中にnode/deviceが変わった場合、新しいnode/deviceのhistoryを読み直す。
    - Current tabは既存latest refreshに従い、historyのloading/errorに影響されない。
- Validation:
    - compile check。
    - 手動でdevice選択とtab切替を確認する。
- Notes:
    - `DeviceDetailPane` にAPI clientを直接渡すか、`suspend (nodeId, deviceKey) -> NodeDeviceHistoryPayload` のloader callbackを渡す。実装時は差分が小さく、Current表示が読みやすい方を選ぶ。

### Task 3: Add Current / History Tabs
- Objective: detail pane内にCurrentとHistoryを切り替えるUIを追加する。
- Affected modules/files:
    - `diskinfo-client/src/desktopMain/kotlin/com/milkcocoa/info/sapphire/client/DeviceDetailPane.kt`
- Expected behavior:
    - `Current` は既存の `FocusMetrics` と `DetailInfoList` を表示する。
    - `History` はhistory summaryとtimelineを表示する。
    - tab切替でmain listやheaderのlayoutを崩さない。
- Validation:
    - `Current` 既存表示が維持されること。
    - History tab empty/loading/error stateがdetail pane内だけに収まること。
- Notes:
    - `TabRow` / `Tab` などMaterial3の標準部品を使う。
    - グラフ用スペースは先取りしない。

### Task 4: Add History Summary and Timeline
- Objective: 初期History viewとしてsummary tilesとtimeline rowsを実装する。
- Affected modules/files:
    - `diskinfo-client/src/desktopMain/kotlin/com/milkcocoa/info/sapphire/client/DeviceDetailPane.kt`
    - 必要なら `DeviceHistoryPane.kt` を新規追加する。
    - `SnapshotUiFormat.kt`
- Expected behavior:
    - Summary:
        - snapshot count
        - first / latest timestamp
        - max temperature
        - worst observed health
    - Timeline:
        - timestamp
        - health
        - temperature
        - lifetime remaining or percentage used
        - critical warning count
    - Empty state:
        - history APIが空の `snapshots` を返した場合、Current表示は維持しつつHistory tab内にempty messageを出す。
    - Error state:
        - history APIのnon-200 responseや通信失敗はHistory tab内に表示し、latest polling全体を失敗扱いにしない。
- Validation:
    - compile check。
    - 手動でhistoryあり/なし/agent停止時を確認する。
- Notes:
    - TimelineではCurrent tabから自明なprotocol列を持たず、時間変化する指標を優先して表示する。
    - 既存の `healthColor`, `temperatureColor`, `wearColor`, `warningColor` を再利用する。
    - 文字列整形は `SnapshotUiFormat.kt` に寄せ、UI内に重複ロジックを増やしすぎない。

## CLI/API Compatibility
- CLIは変更しない。
- ClientはPhase 3Aのhistory APIが存在するAgentを前提にする。
- 古いAgentに接続した場合、History tabだけがerror stateになり、Current tabはlatest APIで使い続けられるようにする。

## Data and Persistence Impact
- Client側はDBに直接触らない。
- `limit=100` のbounded readだけを行う。
- `from` / `to` filterは初期UIでは未使用。
- APIから返る空 `snapshots` は正常なempty stateであり、Client側でerror扱いしない。

## Validation Plan
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-client:compileKotlinDesktop
```

Manual checks:

```text
./gradlew :diskinfo-agent:run --args='standalone --scan'
./gradlew :diskinfo-client:run
```

Check:

- Current tab shows existing focus/detail content.
- Device selection in the node grouped list determines both `nodeId` and `deviceKey`.
- History tab loads selected device history only.
- Switching selected devices refreshes History.
- History error/empty states do not break Current view.

## Risks and Open Questions
- Client module currently has no dedicated test source set. Phase 3B can start with compile + manual UI checks, then add UI/state tests only if the implementation grows.
- Passing `AgentApiClient` deep into UI is simple but couples UI to API. A loader callback is cleaner if the diff stays small.
- Time range filters are intentionally deferred; adding them now would grow API/UI scope without proving the base history view.

## Implementation Order
1. Phase 3Aを実装し、history API contractを固定する。
2. `AgentApiClient.fetchDeviceHistory(...)` を追加する。
3. `Dashboard` / `DeviceContent` / `DeviceDetailPane` にhistory loaderを渡す。
4. `Current | History` tabを追加し、Current既存表示を維持する。
5. History tabのloading/error/empty stateを追加する。
6. summary tilesとtimeline rowsを実装する。
7. client compile checkと手動UI確認を実行する。
