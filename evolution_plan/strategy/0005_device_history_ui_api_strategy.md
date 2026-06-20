## Device History UI / API Strategy

### Summary
Client should expose history because disk health is trend-oriented. The default view should remain current-state first, while history is available from the selected device detail. The first implementation should prefer a simple `Current | History` tab and a history API that returns bounded snapshots for one node/device pair.

### UX Direction
- Keep the main screen focused on current status:
    - Left pane: node grouped device list.
    - Right pane: selected device detail.
    - Current view: focus tiles + SMART/NVMe information.
- Add a tab switch inside the right detail pane:
    - `Current`: existing focus tiles and information table.
    - `History`: selected device trend and snapshot timeline.
- Initial node selection is implicit in the left pane:
    - The Client renders a node grouped device list from `LatestSnapshotsPayload.nodes`.
    - Selecting a device also selects its `nodeId`.
    - The History tab calls the history API with the selected `(nodeId, deviceKey)` pair.
- Do not show full history by default in the main list. It makes current health harder to scan.
- In the first History view, prefer data clarity over chart complexity:
    - Summary tiles:
        - snapshot count
        - first / last collected time
        - max temperature
        - worst health / warning count
    - Timeline list:
        - collected time
        - health
        - temperature
        - wear / percentage used
        - critical warnings or evaluation warning count
    - Charts can be added later after the API shape is stable.

### API Direction
- Keep latest endpoint for current-state UI:
    - `GET /api/v1/snapshots/latest`
    - Returns `LatestSnapshotsPayload(nodes=[...devices...])`.
- Add a device history endpoint:
    - `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots`
    - `nodeId` is part of the URI because device keys such as `/dev/sda` or `nvme0n1` can collide across Node Agents after Hub aggregation.
    - Query:
        - `limit`: default `100`, max `1000`
        - `from`: optional ISO-8601 timestamp
        - `to`: optional ISO-8601 timestamp
        - `order`: `desc` default, `asc` optional
- Response payload:
    - `NodeDeviceHistoryPayload`
        - `nodeId`
        - `nodeName`
        - `deviceKey`
        - `snapshots: List<DiskSnapshot>`
- Error behavior:
    - blank `nodeId` or `deviceKey`: `400`
    - invalid `limit`, `from`, `to`, or `order`: `400`
    - no node/device history: return `200` with an empty `snapshots` list for UI simplicity

### Repository / DB Direction
- Query by both `node_id` and `device_key`.
- Sort by `collect_time`.
- Use bounded queries; never return unbounded history by default.
- Keep the existing latest query separate from history query.
- Suggested repository methods:
    - `findLatestNodes(): List<NodeSnapshot>`
    - `findHistory(nodeId: String, deviceKey: String, query: HistoryQuery): NodeDeviceHistoryPayload`
- Existing index `(node_id, device_key, collect_time)` is appropriate for this endpoint.

### Client Implementation Plan
1. Keep the existing node grouped device list as the initial selection mechanism.
2. Treat a device click as selecting `(nodeId, deviceKey)`.
3. Add `AgentApiClient.fetchDeviceHistory(nodeId, deviceKey, limit)` using the selected agent URL.
4. Add detail tabs:
    - `Current`
    - `History`
5. Load history only when:
    - the History tab is selected, or
    - the selected device changes while already on History.
6. Show loading/error/empty states inside the History tab only.
7. Start with a timeline list and summary tiles. Add charts later.

### History View Fields
- Summary tiles:
    - `Snapshots`: count
    - `Range`: first -> latest timestamp
    - `Max Temp`: maximum temperature
    - `Worst`: worst observed health
- Timeline rows:
    - timestamp
    - health
    - temperature
    - percentage used
    - critical warnings
    - protocol

### Chart Follow-up
After the history API and tab flow are stable, add charts for:
- temperature over time
- percentage used / wear over time
- warning count or non-GOOD evaluation count over time

Charts should stay inside the History tab, not the current-state tab.

### Test Plan
- API:
    1. known node/device returns ordered bounded snapshots
    2. unknown node/device returns empty snapshots
    3. invalid query values return `400`
    4. `limit` max is enforced
- Client:
    1. selecting a device keeps Current view usable
    2. switching to History loads only that device
    3. switching devices refreshes History for the new selection
    4. history loading/error/empty states do not break the Current view
- Regression:
    - `GET /api/v1/snapshots/latest` remains current-state optimized
    - agent periodic collection and migration behavior remain unchanged

### Assumptions
- History is SQLite-backed history in Standalone, and later Hub-backed history for aggregated deployments.
- Hub / Node Agent aggregation can reuse the same UI concept later, but freshness/stale metadata from strategy `0002` should be added before remote aggregation is exposed.
- The first History implementation does not need downsampling. If history grows large, add server-side aggregation or retention/downsampling after the basic endpoint is proven.
