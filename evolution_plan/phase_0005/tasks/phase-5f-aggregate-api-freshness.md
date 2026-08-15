# Phase 5F: Cache-First Aggregate API and Freshness

## Source Context
- Primary: `../../master.md`
- Phase plan: `../phase_0005_plan.md`
- Supporting: `../../strategy/0001_api_response_strategy.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`
- Prerequisite: `phase-5b-hub-storage-registry.md`
- Prerequisite: `phase-5d-hub-runtime-ingest.md`

## Goal
Hubが保存済みsnapshotとNode registryだけから、複数Nodeのlatest/historyをboundedに返し、stale、partial、errorを説明できるようにする。

## Non-Goals
- Client requestを契機にNode Agentへlive collectionを要求しない。
- long-term aggregate tableやchart downsamplingを実装しない。
- Desktop Client UIはPhase 5Gで扱う。
- device keyをHub全体でglobal uniqueと仮定しない。

## Current State
- latest APIはRepository cache-firstであるが、Standalone単一node前提である。
- device-only latest queryはHubで曖昧になる。
- history APIは`nodeId + deviceKey`とbounded queryを既に使う。
- API responseにはHubのreceived time、Node status、stale/partial/error metadataがない。

## Boundary Decision
- freshness判定はregistry/snapshotを読むquery use caseで行い、routeやClientへ重複実装しない。
- `collectTime`と`receivedAt`を分けて返す。
- data staleはHub clockのlatest snapshot `receivedAt`に対してexpected collection intervalの2倍を超えた場合とし、clockを注入してdeterministicに計算する。exact thresholdではfreshとする。
- node livenessは`lastSeenAt`、data freshnessはsnapshot `receivedAt`で別々に表現し、heartbeatや`DUPLICATE`でold snapshotをfreshへ戻さない。
- 一部Nodeが失敗しても保存済みcacheを返せる場合はHTTP 200 + `partial=true`と説明metadataを返す。
- `partial=true`はACTIVE Nodeにsnapshotがない、data stale、またはcurrent errorがある場合とする。administratively `DISABLED`なNodeはpartial判定から除外する。
- `nodeId + deviceKey`をHubのlatest/history identityにする。
- aggregate latestはDB側でnode/deviceごとの最新行へ絞り込み、Phase 4の全履歴load + JVM `distinctBy`をHubへ持ち込まない。
- aggregate latestはdevice row単位で`nodeId ASC, deviceKey ASC`のkeyset paginationを行い、default limit 100、maximum 500とする。

## Response Direction
既存payloadへadditiveに次を加える。
- response生成時刻
- `partial`
- node/deviceごとの`stale`
- `lastReceivedAt`
- bounded `errors[]`（stable code、node context、sanitized message）
- pagination metadata（`limit`, opaque `nextCursor`, `hasMore`）
- `errorsTruncated`

古いAgent responseにmetadataがない場合をPhase 5G Clientが扱えるよう、既存fieldを削除・renameしない。

## Task Breakdown

### Task 1: Add Freshness Query Model and Policy
- Objective: registryとlatest snapshotからfresh/stale/error stateを決定する。
- Affected modules/files: query use case、clock/policy、response mapper。
- Expected behavior: node livenessとdata freshnessを分離し、boundary時刻、never-seen、disabled、last-errorを一貫して分類する。`ageMs`はHub clockの`receivedAt`を基準にする。
- Validation: exact 2x threshold、heartbeat-only、duplicate retry、no snapshot、disabled、partial multi-node cases。

### Task 2: Add Hub Aggregate Latest Route
- Objective: multiple Nodeのcached latestをbounded responseとして返す。
- Affected modules/files: Hub read route、API DTO、query repository。
- Expected behavior:
  - 1 Node停止でも他Nodeと停止Nodeのlast cacheを返し、partial/staleを説明する。
  - `limit`はdevice row数へ適用し、page内のrowを既存`nodes[].devices[]`へ再groupする。同じNodeが複数pageへ現れ得る。
  - cursorはversioned opaque valueとして最後の`nodeId + deviceKey`を表し、不正値を`400 invalid_cursor`で拒否する。
  - `errors[]`も上限を持ち、切り捨て時は`errorsTruncated=true`を返す。
- Validation: 2 Node正常、1 Node停止、空Hub、registry-only node、default/max/invalid limit、cursor継続、same Node page split、大量履歴でも1 responseが最大device row数にboundedであること。

### Task 3: Add Node-Scoped Latest Route
- Objective: Hubでdeviceを一意に指定できるlatest routeを追加する。
- Affected modules/files: route/resource、SnapshotUseCase、Client API contract tests。
- Expected behavior:
  - `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots/latest`を提供する。
  - device-only latestはStandalone compatibility routeとしてだけ提供し、Hub route setには登録しない。
- Validation: same device key across 2 Nodes、unknown node/device、Hub route registration。

### Task 4: Extend Bounded History Metadata
- Objective: existing history semanticsを壊さず、source/freshness contextをadditiveに返す。
- Affected modules/files: history DTO/mapper/route tests。
- Expected behavior: limit/from/to/orderとretention-empty behaviorを維持する。
- Validation:旧payload field compatibility、unknown metadata tolerance、retention境界。

## CLI/API Compatibility
- existing latest/history fieldを削除しない。
- 新しいnode-scoped latest routeを追加する。
- device-only latest routeはStandaloneでは維持し、Hub route setから外す。
- aggregate latestのpagination metadataはadditiveに追加する。新Desktop Clientはpageをmergeできるよう更新する。
- Client read認証の強制はPhase 5G cutoverで行う。

## Data and Persistence Impact
- read pathは新規snapshotを生成しない。
- freshnessはraw snapshot retentionと独立し、registry current stateを利用する。
- errors配列はboundedにし、無制限なDB event historyを要求しない。

## Validation Plan
```text
./gradlew :diskinfo-agent:test --tests '*ServerTest' --tests '*Freshness*Test' --tests '*History*Test'
./gradlew :diskinfo-agent:compileKotlin
```

SQLite/PostgreSQL双方で2 Node/same device key、partial/stale、bounded history、aggregate keyset paginationを確認する。
aggregate latestについて両backendのquery planを確認し、全履歴のapplication memory materializationがないことを回帰テストで固定する。

## Risks and Open Questions
- aggregate queryはSQLite/PostgreSQLでSQL shapeが異なり得るため、両backendでkeyset boundaryとquery planを固定する。
- partial aggregateはdataを返せる限りHTTP 200とし、request/query/auth failureだけを4xx、Hub storage/query全体 failureを5xxにする。stable error code表はOpenAPIへ記録する。

## Implementation Order
1. deterministic freshness policyとquery modelを実装する。
2. aggregate latest responseをkeyset paginationとadditive metadata付きで実装する。
3. node-scoped latest routeを追加し、Hub/Standalone route setを分ける。
4. bounded history responseへ必要なmetadataを追加する。
5. multi-node partial/stale、same device key、compatibility regressionを検証する。
