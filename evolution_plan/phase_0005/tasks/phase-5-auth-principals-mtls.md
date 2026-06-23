# Phase 5: Hub/Agent Authentication and Principals

## Source Strategy
- Primary: `../../master.md`
- Supporting: `../../strategy/0002_future_architecture.md`
- Related: `../../strategy/0001_api_response_strategy.md`
- Related: `../../strategy/0008_device_identity_strategy.md`

## Goal
HubとNode Agentは、分散ingest trafficを受け付ける前にmTLSで相互認証する。

初期分散構成では、HubをCAおよびtrust authorityとして扱う。Node Agentはkubeadm風のjoin flowでHubへ参加する。Hubは短命のjoin tokenを発行し、Node AgentはそのtokenとHub CA検証情報を指定して、Hub署名のclient certificateを取得する。

同じ認証モデルは、Client認証にも使う。ここでいうClient principalは「このcallerへSMART情報を応答してよい」という意味であり、「公式CocoaDiskInfo Clientである」ことの証明ではない。

Hub-lessなClient-to-Agent直接接続は、単一ノード用途として今後も残す。この構成ではClientがAgentへ直接接続し、Agent processがlocal Hub roleとsmartctl実行者roleを同時に担う。Phase 5は、単一ノードの状態を見るだけの利用者にHubとNode Agentの両方を動かすことを強制しないが、Hub-less構成でもClient-to-Agent認証は必要であり、認証なしの例外扱いにはしない。

## Non-Goals
- CRL、OCSP、完全な証明書失効運用はこのtaskでは実装しない。
- 外部CAを必須にしない。
- Hub-less Client認証のために、別Hub processの起動を必須にしない。
- 公式Clientのattestationやbinary provenanceは扱わない。
- 広範なRBAC、OAuth、browser login、multi-tenant authorizationは扱わない。
- 現在のHub-less Client-to-Agent topologyを削除しない。

## Current State
- Phase 5はHubとNode Agentの集約を計画しているが、active task documentはまだなかった。
- `../../strategy/0002_future_architecture.md` は、これまで認証/認可を後続課題としていた。
- 既存API strategyは、Hubのpartial/stale/error metadataとcache-first responseを前提にしている。
- `nodeId` と `deviceKey` は、履歴identityの組として既に方針化されている。

## Boundary Decision
- Owner boundary: `Server`, `Repository`, CLI/executor assembly, configuration/runtime settings。
- Why this belongs there:
  - TLS termination、mTLS peer extraction、route authorizationはServer責務である。
  - Principal、join token、certificate serial、status metadataはHub側で永続化する必要がある。
  - Hub、Node Agent、Standalone/Hub-lessはmode別の組み立てが必要であり、Hub-less operationではAgent processにlocal Hub roleを内包させる必要がある。
  - Collectorとhealth evaluationはcertificate、principal、join tokenを知らないままにする。
- Cross-boundary impact:
  - Node Agent send sinkはjoin後にmTLS credentialを使う。
  - Hub ingest routeは `NodeAgentPrincipal` を要求する。
  - Client read routeは、Hub modeでもHub-less modeでもAPI response modelを変えずに `ClientPrincipal` を要求できるようにする。

## Principal Model
Principalはdevice identityとは独立した小さなモデルにする。

初期principal type:
- `NODE_AGENT`: 1つの `nodeId` に対してsnapshot、heartbeat、node metadataを送信できる。
- `CLIENT`: HubまたはHub-less Agentから、現在状態や履歴をread permissionに応じて読める。

共通フィールド候補:
- `principalId`
- `principalType`
- `displayName`
- `status`: `ACTIVE`, `DISABLED`
- `createdAt`
- `expiresAt`
- `lastSeenAt`
- `certificateSubject`
- `certificateSerial`

Node Agent principal field候補:
- `nodeId`
- `nodeName`
- `capabilities`

Client principal field候補:
- read scope: all nodes、selected nodes、selected node/device pairs
- permission: `READ_CURRENT`, `READ_HISTORY`

Phase 5のauthorizationは粗く保つ。最初に強制するruleは次だけにする。
- `POST /api/v1/snapshots` はactiveな `NODE_AGENT` principalだけを受け付ける。

Client ruleはHub modeとHub-less modeの両方へ適用できるようにする。Clientの意味は「SMART dataのreadを許可されたcaller」であり、公式Client判定ではない。

Client認証の第一候補はclient certificateによるmTLS principal extractionとする。Hub modeではHubがClient principalのtrust sourceになり、Hub-less modeではAgent process内のlocal Hub roleがClient principalのtrust sourceになる。Bearer tokenなどの別方式は、Client UXや証明書配布運用を検討した後の代替案として扱う。

## Trust and Join Flow
初期実装ではHubをCAとして扱う。

推奨bootstrap flow:

1. HubがCA materialを初期化または読み込む。
2. Hub operatorがnode用の短命・単回利用join tokenを作る。
3. Hubがjoin materialを表示または返す。
   - Hub base URL
   - join token
   - Hub CA certificateまたは `sha256` CA fingerprint
   - 任意のexpected `nodeName`
4. Node Agentがそのmaterialでjoin commandを実行する。
5. Node Agentはprivate keyをローカル生成し、join token付きでCSRをHubへ送る。
6. Hubはtokenを検証し、`NODE_AGENT` principalを作成または有効化し、CSRへ署名して次を返す。
   - Node Agent client certificate
   - Hub CA certificate chain
   - `nodeId` を含むprincipal metadata
7. Node Agentはprivate key、certificate、Hub CA trust material、割り当てられた `nodeId` を保存する。
8. 以後のingestとheartbeatはmTLSを使う。

Private keyはNode Agent側で生成し、Node Agent側に保持する。HubはNode Agent private keyを生成せず、CSRに署名する。

Token behavior:
- 短いTTLを持つ。
- 既定では単回利用とする。
- 必要ならexpected `nodeName` にbindできる。
- token利用失敗時はprincipalを作らず、actionable errorを返す。

CA verification:
- Node Agentはbootstrap時にCA certificateまたはkubeadm風のCA fingerprintでHubを検証する。
- 分散modeではtrust-on-first-useを既定にしない。

## Runtime Modes
### Hub
- Phase 5ではCA materialを持つ。
- Principal repositoryとjoin token repositoryを持つ。
- join後のNode Agent ingestにはmTLSを要求する。
- Client principal enforcementを実装するまでは、Client read APIを未認証のまま残してよい。

### Node Agent
- ローカル収集を行い、snapshotをHubへ送信する。
- 分散modeではClient-facing APIを必須にしない。
- Hub-issued client certificateでingestとheartbeatを行う。

### Standalone / Hub-less Direct
- 単一ノード用のfirst-class modeとして残す。
- ClientはAgentへ直接接続する。
- Agent processはlocal Hub roleとsmartctl実行者roleを同時に担う。
- Hub CAやHub-issued Node Agent principalは要求しない。
- Client-to-Agent read APIは認証なしの例外にしない。
- Hub-less Client認証は、別Hub processを要求せず、Agent process内のlocal Hub roleが扱うlocal trust/principal sourceで実現する。

## Task Breakdown
### Task 1: Principal and Trust Strategy Update
- Objective: 認証を分散Hub/Node Agent設計の一部としてPhase 5に組み込む。
- Affected modules/files:
  - `evolution_plan/strategy/0002_future_architecture.md`
  - `evolution_plan/phase_0005/phase_0005_plan.md`
- Expected behavior: Phase 5はNode Agent ingest認証を未定義の後続課題として扱わない。
- Validation: Document review。
- Notes: CRLとrevocation operationsは明示的なopen questionとして残す。

### Task 2: Hub CA and Join Token Configuration
- Objective: Hub CA materialとjoin token policyの設定shapeを追加する。
- Affected modules/files:
  - Hub config model
  - TOML config loading
  - CLI/executor assembly
- Expected behavior: HubがCA materialを読み込みまたは初期化し、join token TTLを設定できる。
- Validation:
  - valid CA configで `hub` modeが起動する。
  - invalid CA pathやinvalid TTLはactionable errorで失敗する。
- Notes: Packaging-specific pathはPhase 7で確定できる。

### Task 3: Join Command and CSR Signing Flow
- Objective: Hub-issued tokenとlocal key generationに基づくNode Agent join flowを追加する。
- Affected modules/files:
  - CLI command definitions
  - Node Agent credential storage
  - Hub join API route
  - Principal repository
- Expected behavior: HubがNode Agent private keyを見ることなく、Node AgentがHub署名client credentialを取得できる。
- Validation:
  - success: valid token + CSRでcertificateと `nodeId` が返る。
  - failure: expired token、reused token、invalid CA fingerprint、malformed CSR。
- Notes: subcommand設計確定時は `node-agent join --hub ... --token ... --hub-ca-sha256 ...` のような形を候補にする。

### Task 4: mTLS Ingest Enforcement
- Objective: distributed ingestにactiveな `NODE_AGENT` principalを要求する。
- Affected modules/files:
  - Hub server TLS setup
  - auth middleware
  - ingest route
  - Node Agent HTTP send sink
- Expected behavior: valid active Node Agent certificateなしのsnapshot ingestをHubが拒否する。
- Validation:
  - valid Node Agent certでingestできる。
  - missing certは拒否される。
  - disabled principalは拒否される。
  - wrong principal typeは拒否される。
- Notes: TLS-level peer verificationとapplication-level principal status checkの両方が必要。

### Task 5: Client Principal and Read API Authentication
- Objective: Client認証をAPI redesignではなくpolicy decisionとして追加できるようにし、第一候補としてclient certificate mTLSでPrincipalを抽出する。
- Affected modules/files:
  - auth principal model
  - server route metadata or middleware
  - config model
- Expected behavior: Client read routeはHub modeとHub-less modeの両方で、response payloadを変えずにclient certificate由来の `CLIENT` principalを要求できる。
- Validation: Hub、Node Agent、Standalone modeごとのroute policy matrixを文書化する。
- Notes: Hub-less modeではAgent process内のlocal Hub roleがClient certificate/trustを扱う。

### Task 6: Hub-less Local Hub Behavior Checks
- Objective: 単一ノードのClient-to-Agent直接接続が、local Hub role内包構成として残ることを確認する。
- Affected modules/files:
  - Standalone executor assembly
  - server auth configuration
  - existing Client connection config if applicable
- Expected behavior: Standalone/direct modeは別Hub process、Hub-issued Node Agent certificate、Node Agent join tokenを要求しない。ただしClient read APIはlocal trust/principal sourceによって認証できる。
- Validation:
  - `./gradlew :diskinfo-agent:run --args='standalone --scan'`
  - valid Client certificateでexisting latest/history API readsがstandalone modeで成功する。
  - missing/invalid Client certificateはstandalone modeで拒否される。
- Notes: Hub-lessはauth bypassではなく、Agentがlocal Hub roleを内包する構成として扱う。

## CLI/API Compatibility
既存のstandalone/direct Client-to-Agent topologyは維持する。ただし、Hub-less構成でもClient read API認証は設計対象に含める。

新しいdistributed-mode command候補:

```text
cocoadiskinfo-agent hub join-token create --node-name sapphire-node-01
cocoadiskinfo-agent node-agent join --hub https://hub.example:8443 --token <token> --hub-ca-sha256 <sha256>
cocoadiskinfo-agent node-agent --config /etc/cocoadiskinfo/agent.toml
cocoadiskinfo-agent standalone client-principal create --display-name desktop-client
```

新規または refined Hub-side API候補:

```text
POST /api/v1/node-agents/join
POST /api/v1/node-agents/heartbeat
POST /api/v1/snapshots
```

`POST /api/v1/node-agents/join` はbootstrap tokenで認証し、caller側はHub CAをpin/verifyする。
`POST /api/v1/snapshots` はjoin後にmTLSで認証する。
Hub-lessのClient read APIは、Agent process内のlocal Hub roleが発行または登録したclient certificate由来の `CLIENT` principalで認証する。

## Data and Persistence Impact
Hub modeのHub、およびHub-less modeでlocal Hub roleを担うAgentは、次のlocal persistent metadataを必要とする。
- principals
- issued certificate serials
- join tokens and token use state
- optional node-agent status and last-seen timestamps
- Client principals and read permission metadata

初期revocation behaviorはapplication-levelにする。
- principalを `DISABLED` にする。
- disabled principalからのauthenticated requestを拒否する。

CRL、OCSP、rotation automation、emergency key compromise procedureは、production packaging前に別のoperations strategyで扱う。

## Validation Plan
Compile checks:

```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
```

実装後の代表runtime checks:

```text
./gradlew :diskinfo-agent:run --args='standalone --scan'
./gradlew :diskinfo-agent:run --args='hub --config <hub-test-config>'
./gradlew :diskinfo-agent:run --args='node-agent join --hub <hub-url> --token <token> --hub-ca-sha256 <sha256>'
./gradlew :diskinfo-agent:run --args='node-agent --config <agent-test-config>'
```

Security behavior checks:
- valid join succeeds。
- expired/reused join token fails。
- wrong Hub CA fingerprint fails。
- ingest without client certificate fails。
- ingest with disabled principal fails。
- standalone/direct mode remains usable without a separate Hub process。
- standalone/direct Client read API rejects missing/invalid Client certificates。

## Discussion Notes Before Coding
後日ディスカッションする懸念点。Phase 5の実装前に、少なくとも方針だけは決める。

### Hub as Role, Not Only Process
- `Hub` はプロセス名だけではなくroleとして扱う方が説明しやすい。
- Hub mode: `Hub role` only。
- Node Agent mode: `smartctl実行者 + remote Hub sender`。
- Hub-less/Standalone mode: `local Hub role + smartctl実行者`。
- この整理により、Client認証、Principal repository、read API authorizationをHub modeとHub-less modeで同じ概念として扱える。

### Local Hub Role Secret Protection
- Hub-lessではAgent processがClient certificateのtrust sourceも持つため、local CA/private key、Client principal DB、trust materialの保護が重要になる。
- Agentはsmartctl実行の都合でroot相当で動く可能性があるため、key fileの配置、permission、backup対象、ログ露出を早めに決める。
- Phase 7 packaging前には、`/etc/cocoadiskinfo/` と `/var/lib/cocoadiskinfo/` のどちらに何を置くかを明確にする。

### Principal-Derived Identity
- Hub ingestでは、request bodyの `nodeId` を信頼しない。
- 実際の `nodeId` はmTLSで認証された `NODE_AGENT` principalからHub側で決定する。
- body内の `nodeId` がprincipalと不一致なら拒否するか、Hub側で上書きするかを決める。
- `CLIENT` principalも同様に、証明書から抽出したprincipalをroute policyに渡し、Client自己申告のidentityを信頼しない。

### Client Certificate UX and Provisioning
- Client read APIの第一候補はmTLSだが、証明書配布UXが課題になる。
- Hub modeではHubがClient certificateを発行/登録できる。
- Hub-less modeではAgent内のlocal Hub roleがClient certificateを発行/登録する必要がある。
- CLIで発行してClientへimportする方式、pairing token方式、既存certificate登録方式のどれを初期候補にするかを決める。
- 公式Client判定ではなく「SMART情報を応答してよい相手」の認証であるため、Client binary attestationとは分けて考える。

### Revocation and Renewal Minimums
- CRL/OCSPを後回しにしても、最低限の失効・更新方針は必要。
- 決めるべき項目:
  - Node Agent certificate TTL
  - Client certificate TTL
  - renewal flow
  - `DISABLED` principalの即時拒否
  - 紛失/漏洩したClient certificateの無効化手順
  - Hub-less時のlocal trust material rotation
- 短命certificate + renewal + application-level principal disableを初期方針にするか、CRL/OCSPを早期に入れるかは未決定。

### TLS Termination Boundary
- 初期Phase 5では、mTLS terminationはCocoaDiskInfo process自身で行う前提にした方が安全。
- reverse proxy配下でclient certificate情報をheader転送する構成は、trust boundaryが複雑になるため初期必須にしない。
- 将来reverse proxy対応を入れる場合は、trusted proxy設定、header spoofing対策、audit logを別途設計する。

### Principal Type Separation
- `NODE_AGENT` certificateと `CLIENT` certificateは用途を分ける。
- Node Agent certificateでClient read routeを読めるようにするかは明示的に決める。初期はrejectが安全。
- Client certificateでingest routeへ送信できないことを検証する。
- route policy matrixを作り、Hub modeとHub-less modeの両方で同じprincipal type ruleを適用する。

### Repository and Migration Impact
- Principal、join token、certificate serial、status、lastSeenAtを永続化する場合、SQLite migrationに入る。
- Hub modeのHubとHub-less modeのlocal Hub roleで同じrepository abstractionを使えるかを確認する。
- join tokenは短命・単回利用なので、cleanup/retention方針も必要になる。
- certificate secret自体をDBに入れるか、file pathだけをDBに入れるかを決める。

## Risks and Open Questions
- CRL/OCSPとoperational revocationは意図的にdeferするが、packaging/distribution前には計画が必要。
- Certificate validity durationの初期defaultが必要。短命certificateはrevocation pressureを下げるがrenewal実装が必要になる。
- Renewal flowは未定義。既存Node Agent principalによるmTLS-authenticated renewalが候補になる。
- Principal repository schemaを既存SQLite migration flowへ入れる場合、backward-awareにする必要がある。
- Client principal enforcementはHub modeとHub-less modeの両方で必要。実装順はHub/Node Agent ingest認証と分けられるが、設計対象からは外さない。
- Hub-less Client-to-Agent authenticationは、Agent process内のlocal Hub roleがlocal trust sourceを持つ前提で具体化する必要がある。

## Implementation Order
1. Phase 5 strategy/planにHub CA、Node Agent mTLS、Principal用語、Hub-less compatibilityを反映する。
2. `Discussion Notes Before Coding` の論点を確認し、実装前に決定事項または明示的なdefer事項へ分ける。
3. Principal、join token、certificate metadataのstorage boundaryを定義する。
4. Hub CA configurationとvalidationを追加する。
5. Hub join token creationとNode Agent join commandを追加し、local key generation + CSR signing flowを実装する。
6. Hub ingestにmTLSとactive `NODE_AGENT` principal enforcementを追加する。
7. Client read routeにclient certificate由来の `CLIENT` principal enforcementを追加し、Hub modeとHub-less modeの両方で使えるlocal/server trust boundaryを定義する。
8. distributed auth failure、Hub-less Client auth failure、standalone topology compatibilityを検証する。
