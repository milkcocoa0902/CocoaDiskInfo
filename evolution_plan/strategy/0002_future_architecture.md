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
- Phase 5ではtransport schemeに関係なく、Node Agent/Client requestを公開鍵署名で認証する。
- Hub側が短命・単回利用を基本とするjoin tokenを発行し、Node Agent側がHub endpointとjoin tokenを指定して参加する。
- Node Agentはlocal key pairを生成し、HMAC challengeでpublic keyをHub Principalへ登録する。
- requestはsubject/purpose-bound single-use nonce、method/path、body `Content-Digest`を含むJWS proofで保護する。
- Node Agent principalとClient principalを分ける。Client principalは「SMART情報を応答してよい相手」を表し、公式Client判定ではない。
- Hub-lessなClient-to-Agent直接接続は単一ノード構成として残す。このときAgent processはsmartctl実行者であると同時にlocal Hub roleを内包し、ClientはAgentへ直接接続する。
- Hub-less構成でもClient-to-Agent認証は必要であり、認証なしの例外扱いにはしない。
- 一部Node Agent障害時も、Hubは前回cache、stale metadata、errorsで部分成功を返す。
- freshness metadataをAPI応答に含められる形にする。
- 初期Hubではlive同期問い合わせを必須にしない。
- TLS終端、server certificate、CA lifecycleはdeployment responsibilityとし、CocoaDiskInfoはX.509 certificateを生成・発行しない。
- Hub/Standaloneのinternal listen addressと外部公開先を分け、外部公開先は`publicEndpoint.baseUrl`で明示する。

## Authentication Direction
Phase 5の分散構成では、Node Agent/Client identityをapplication-level signed requestで確認する。HTTPSを利用する場合のTLS終端とcertificate lifecycleはALB、reverse proxy、service meshなどdeployment側が所有する。

transport rule:

- 信頼できないnetworkではHTTPSを強く推奨する。
- 隔離された信頼済みnetworkでは、operatorがserver authentication、confidentiality、response integrityがないriskを受容した場合だけHTTPを利用できる。
- Node Agent/Clientが`http://` endpointへ接続する場合は`allowInsecureTransport=true`を明示必須にする。
- HTTPS endpointでは通常のhostname/certificate verificationを行い、`trust-all`へfallbackしない。private CAを使う場合のtrust store provisioningはoperator責任とする。
- Hub processがHTTPでlistenしていても、`publicEndpoint.baseUrl`がHTTPSならALB/reverse proxy終端として正常な構成である。
- CocoaDiskInfoはforwarded headerからtransport securityを推測せず、Principal authorizationにも使用しない。

bootstrapは次のモデルにする。

1. Hub operatorが高entropy、短命・単回利用のjoin tokenを作成する。
2. Hub `publicEndpoint.baseUrl`、join token ID/secretをNode Agentへ渡す。HTTP endpointの場合はNode Agent側でもinsecure transportを明示承認する。
3. Node AgentはEd25519 private keyをローカル生成し、public JWKとRFC 7638 `kid`を作る。
4. Node AgentとHubは`joinKey = SHA-256(tokenSecret)`を導出し、Node Agentはjoin用nonceとcanonical inputへ`HMAC-SHA256(joinKey, input)`を計算する。Hubはtoken plaintextではなくjoin keyだけを保存する。
5. Hubはtoken、HMAC、nonceを検証し、Node Agent Principalへpublic keyを登録する。
6. Node Agentは以後、nonce、method/path/purpose、body digestをEd25519 JWSで署名してingest/heartbeatを行う。

HubはNode Agentのprivate keyを生成・受領しない。

JWS proofは`Authorization: CocoaDiskInfo-JWS <compact JWS>`で送り、successful protected responseの次回nonceは`CocoaDiskInfo-Next-Nonce` headerで返す。nonce issue requestは`subjectType`、`subjectId`、`purpose`を持つ。

初期に強制するrule:

- Hub modeの `POST /api/v1/snapshots` はactiveな `NODE_AGENT` principalだけを受け付ける。

Client認証も同じPrincipal/public-key/JWS vocabularyを再利用する。ただしClientの意味は変えない。Client Principalは、callerがSMART dataを読んでよいことを表し、公式CocoaDiskInfo Client executableであることは証明しない。

失効はPrincipal `DISABLED`で即時に拒否する。certificate/CRL/OCSPは不要になるが、key rotationとprivate-key compromise recoveryはoperations topicとして残る。

Hub-lessなClient-to-Agent直接接続はsupported topologyとして残す。このmodeの正体は、Agent processがHub roleとsmartctl実行者roleを同時に持つ構成である。Agent process内のlocal Hub roleがClient pairing tokenとpublic-key Principalを扱う。TLSを使う場合の終端とcertificateはdeployment側が扱う。

nonceはDBの長期データではない。Phase 5は単一Hub instanceを前提にbounded in-memory storeでatomic consumeし、Redis/shared nonce storeはHA要件が出た後に追加する。

## Minimal Hub Model
候補モデル:

- `NodeAgentRegistry`: `nodeId`, `nodeName`, `endpoint`, `capabilities`, `status`, `lastHeartbeatAt`
- `NodeSnapshotCache`: `nodeId`, `deviceKey`, latest snapshot, `receivedAt`
- `NodeAgentErrorState`: `nodeId`, `lastError`, `lastFailureAt`

用語として `collectorId` は避け、既存の `nodeId` / `nodeName` と整合させる。

Hub自身の不変identityはDBへ永続化した`hubId`とする。`publicEndpoint.baseUrl`はjoin/pairing materialの生成とoperator表示に使う変更可能なdeployment addressであり、Principal identityやJWS `kid`には使わない。ALB移行やDNS変更で既存Principalの再join/re-pairを要求しない。

## Phase 4 Handoff Decisions
Phase 4で永続化が実装された結果、Phase 5では次を先に変更してからHub routeを接続する。

- 既存Repositoryはglobal hostname由来のNode identityをinsert時に注入するため、Hub向けにはverified JWS `kid`から解決した`NODE_AGENT` Principalからnode contextを明示して保存する。
- ingest bodyの`nodeId`はauthorityにしない。HubがPrincipalへbindされた`nodeId`をapplication commandへ渡す。
- Node Agentはsnapshotごとに`ingestId`を一度生成し、HTTP retryでも同じ値を使う。Hubは`disk_snapshot.snapshot_id`をinternal row identityとして維持し、別の`ingest_id`へ保存して`UNIQUE(node_id, ingest_id)`で冪等性を保証する。
- Hub受信時刻`receivedAt`とNode registryを追加し、収集時刻、受信時刻、`lastSeenAt`、`lastSnapshotReceivedAt`、last errorを分ける。
- `lastSeenAt`はvalid heartbeat、`STORED`、`DUPLICATE`で更新し、`lastSnapshotReceivedAt`は新しいsnapshotを`STORED`した場合だけ更新する。duplicate retryはdata freshnessを延長しない。
- expected collection intervalはjoin時に登録し、設定変更を反映できるようheartbeatで更新可能にする。成功heartbeat/ingestはcurrent errorをclearするが、`lastFailureAt`は最後のfailure時刻として保持する。
- recovery join tokenは既存`nodeId`へscopeできるようにし、key replacementでhistory identityを維持する。current表示名はregistry、snapshot rowの名前はhistorical metadataとして扱う。
- `deviceKey`単体のlatest queryはHub全体では曖昧なため、Hubでは`nodeId + deviceKey`を使う。
- Phase 4のSQLite/PostgreSQL V1は確定baselineとし、Phase 5 schemaはV2以降の追加migrationで導入する。
- raw snapshot cleanup contractはHubでも再利用するが、Principal/join token lifecycleは別のsecurity metadata policyとして扱う。

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
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots/latest
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots
```

aggregate latestは`limit`（default 100、maximum 500）とopaque cursorを持ち、device row単位で`nodeId ASC, deviceKey ASC`のkeyset paginationを行う。

Node Agent向け ingest:

```text
POST /api/v1/snapshots
```

ingest requestは単件の`ingestId + snapshot`を初期contractとし、node identityはJWS `kid`から解決したPrincipalから導出する。snapshot JSONはJWSへ内包せず、送信body bytesのSHA-256をJWSへbindする。batchは実測後に検討する。

Node Agent join/heartbeat path:

```text
POST /api/v1/node-agents/join
POST /api/v1/node-agents/heartbeat
```

Node Agent向けjoin/heartbeat APIは、実行体としてのroleを明示するため`/api/v1/node-agents/...`を使う。Client向けsnapshot/history APIはresource identityに合わせて`/api/v1/nodes/...`を使う。

## Follow-up Items

- Node Agent/Client private keyはPhase 5ではowner-only local credential fileへ保存する。OS keychain/encrypted storeはPhase 7で検討する。
- key compromise/rotationは旧Principal disable + 再join/re-pairをPhase 5 flowとし、online rotationは後続で検討する。
- Client public-key pairingのoperator UXを実装時に具体化する。
- HA Hubが必要になった場合のRedis/shared nonce store。

## Test Plan
- 複数Node AgentのsnapshotをHubへingestできること。
- 集約APIがnode/device単位でlatest snapshotを返すこと。
- aggregate latestがkeyset paginationでboundedになり、全deviceを1 responseへ返さないこと。
- 1台停止時、APIは `partial=true` と `errors[]` を返すこと。
- 停止Node Agentに前回cacheがある場合、`stale=true` として返せること。
- cacheがない停止Node Agentはdata欠落 + errorsで説明されること。

## Assumptions
- 初期はcache-first固定で運用する。
- data stale判定はHub clockのsnapshot `receivedAt`に対して収集周期の2倍を閾値とし、heartbeat/livenessとは分ける。
- Hub/Node Agentの分散ingest認証はPhase 5で扱う。
- Client-to-Hub/Client-to-Agent signed-request認証とkey rotationはPhase 5 taskで分割して具体化する。
