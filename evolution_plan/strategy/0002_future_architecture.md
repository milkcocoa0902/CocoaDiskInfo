# Hub / Node Agent Aggregation Strategy

## Summary
将来構成では、各マシンで動く `Node Agent` がローカルSMART snapshotを収集し、中央側の `Hub` / `Master` が受信、保存、集約、Client API提供を担う。

古い文書で使っていた `Collector Agent` / `Master Agent` という呼称は、現在のmaster planでは次のように読み替える。

- `Collector Agent` -> `Node Agent`
- `Master Agent` -> `Hub` / `Master`
- `Collector` -> 収集責務またはcollector interface。中央側の呼称には使わない。

## Core Direction
- Hubの通常応答はcache-firstにする。
- Node AgentはHubへ自己登録または初回snapshot送信で識別される。
- 一部Node Agent障害時も、Hubは前回cache、stale metadata、errorsで部分成功を返す。
- freshness metadataをAPI応答に含められる形にする。
- 初期Hubではlive同期問い合わせを必須にしない。

## Minimal Hub Model
候補モデル:

- `NodeAgentRegistry`: `nodeId`, `nodeName`, `endpoint`, `capabilities`, `status`, `lastHeartbeatAt`
- `NodeSnapshotCache`: `nodeId`, `deviceKey`, latest snapshot, `receivedAt`
- `NodeAgentErrorState`: `nodeId`, `lastError`, `lastFailureAt`

用語として `collectorId` は避け、既存の `nodeId` / `nodeName` と整合させる。

## Response Metadata
候補:

- `collectedAt`
- `receivedAt`
- `ageMs`
- `stale`
- `nodeId`
- `errors[]`
- `partial`

## Candidate Public Interfaces
Client向け:

```text
GET /api/v1/snapshots/latest
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots
```

Node Agent向け ingest:

```text
POST /api/v1/snapshots
```

Node Agent登録/heartbeat候補:

```text
POST /api/v1/node-agents/register
POST /api/v1/node-agents/heartbeat
```

## Open Questions
Node Agent向け管理APIのpathは未確定である。候補は次のどちらか。

- `/api/v1/node-agents/...`: 実行体としてのNode Agentを強調する。
- `/api/v1/nodes/...`: Client向けhistory APIの `nodeId` と揃える。

この命名はPhase 5 task作成時に決める。現時点では `collector` pathへ戻さないことだけを固定する。

## Test Plan
- 複数Node AgentのsnapshotをHubへingestできること。
- 集約APIがnode/device単位でlatest snapshotを返すこと。
- 1台停止時、APIは `partial=true` と `errors[]` を返すこと。
- 停止Node Agentに前回cacheがある場合、`stale=true` として返せること。
- cacheがない停止Node Agentはdata欠落 + errorsで説明されること。

## Assumptions
- 初期はcache-first固定で運用する。
- stale判定閾値は収集周期の2倍を初期候補にする。
- 認証/認可は後続フェーズで導入する。
