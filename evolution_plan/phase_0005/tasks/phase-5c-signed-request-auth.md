# Phase 5C: Signed-Request Authentication and Principals

## Source Context
- Primary: `../../master.md`
- Phase plan: `../phase_0005_plan.md`
- Supporting: `../../strategy/0002_future_architecture.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`
- Prerequisite: `phase-5a-ingest-contract-node-identity.md`
- Prerequisite: `phase-5b-hub-storage-registry.md`
- Standards: RFC 7515 (JWS), RFC 7638 (JWK Thumbprint), RFC 9530 (`Content-Digest`)

## Goal
transport schemeに依存せず、Node Agent/Clientが保持する非対称鍵、body digest付きJWS proof、短命・単回利用nonceによりrequestを認証する。

Hubは短命・単回利用join tokenとHMAC challengeでNode Agentの公開鍵を登録し、`kid`からPrincipal、公開鍵、route permissionを解決する。Phase 5ではboundedなin-memory nonce storeだけを実装し、Redisは将来backendとして境界を残す。

## Non-Goals
- client certificate、mTLS、Hub CAによるclient certificate発行、CSR signingを実装しない。
- Redis nonce backendと複数Hub instanceのreplay coordinationをPhase 5へ含めない。
- Hub runtimeとsnapshot ingest routeの組み立てはPhase 5Dで扱う。
- Node Agent collection/delivery loopはPhase 5Eで扱う。
- Desktop Clientのcredential UXとread API cutoverはPhase 5Gで扱う。
- OAuth、browser login、公式Client binary attestation、広範なRBACを扱わない。
- TLS server、X.509 certificate生成/発行、CA、renewalをCocoaDiskInfoへ実装しない。
- reverse proxyによるAPI path rewriteやrequest proof再署名を実装しない。

## Current State
- `SapphireAgentServer`はplain HTTPのCIO connectorを直接構築している。
- Principal、join token、public key、nonce、request proofの境界がない。
- Desktop ClientはURLだけを保存し、signing keyやHTTP insecure transport opt-inを管理しない。
- Phase 5Aでprincipal-derived node identity、Phase 5Bで追加migrationの基盤を用意する計画である。

## Boundary Decision
- TLS terminationとserver certificate lifecycleはdeployment responsibilityにし、CocoaDiskInfo processの責務にしない。
- client identityはTLS certificateではなく、application-level JWS proofの`kid`から解決する。
- JWS verification、body digest validation、nonce consume、Principal type/status checkは共通auth application boundaryに置く。
- Principal、public key、join token use stateはRepositoryへ保存する。
- Node Agent/Client private keyはDBへ保存せず、permissionを限定したlocal credential storeで管理する。
- nonceは短命なreplay-prevention stateであり、snapshot DBへ永続化しない。
- Collector、`DiskSnapshot`、health evaluationはJWS、nonce、Principalを知らないままにする。

## Transport Security Contract
- Hub/Standalone serverはHTTP application listenerを提供し、ALB/reverse proxy/service meshが任意にTLSを終端できる。
- Node Agent/ClientはHTTP(S) endpointへ接続できる。`http://`では`allowInsecureTransport=true`を明示必須にし、server authentication、confidentiality、response integrityがないことを警告する。
- HTTPSではplatform trust storeまたはoperator-provisioned trust storeを使い、`trust-all`やhostname verification無効化へfallbackしない。
- CocoaDiskInfoはX.509 certificate、CA、TLS private keyを生成・保存しない。
- Hubは`X-Forwarded-Proto`、`Host`、source IPをPrincipal authorizationへ使わない。
- `publicEndpoint.baseUrl`はjoin/pairing material用のdeployment addressであり、security identityではない。不変identityにはDBへ永続化した`hubId`を使う。

## Signed Request Profile

### Initial Algorithm Policy
- asymmetric keyはEd25519、JWS `alg`は`EdDSA`だけを初期候補とする。
- `alg=none`、HMAC系JWS algorithm、request指定によるalgorithm切替を拒否する。
- `kid`はRFC 7638 JWK Thumbprintから決定し、join response喪失時にも同じ公開鍵から再計算できるようにする。
- 実装前のfocused spikeで、選定JOSE libraryとJDK間のkey generation、serialize、sign、verifyを確認する。Ed25519 interoperabilityに問題がある場合はES256へ変更する前に相談する。

### JWS Protected Header

```text
typ: cocoadiskinfo-proof+jws
alg: EdDSA
kid: RFC 7638 public-key thumbprint
```

### JWS Payload

```text
version
nonce
method
path
query
purpose
bodySha256  # bodyを持つrequestでは必須、32-byte digestのbase64url without padding
```

- `purpose`は`SNAPSHOT_INGEST`、`HEARTBEAT`、`CLIENT_READ`などの固定enumにする。
- Phase 5のprotected POST endpointはqueryを持たせず、methodとnormalized pathを厳密一致させる。
- bodyなしread requestは`bodySha256`を持たないが、method/path/canonical query/purpose/nonceを署名する。
- pathはroute templateとvalidated path parameterからcanonicalに再構築し、Host、scheme、external/internal port、raw percent-encodingへbindしない。Phase 5ではreverse proxyのAPI path rewriteを許可しない。
- queryはknown parameterのdecoded/validated effective valueからname順に構築する。historyではeffective `limit`と`order`を常に含め、`from`/`to`は存在時だけUTC instantのcanonical formで含める。unknown/duplicate parameterを拒否する。
- raw query parameter orderやequivalent percent-encodingはsignature semanticsへ影響させない。
- request freshnessはclient clockではなく、Hubが発行・TTL管理するnonceで判定する。Phase 5 JWSに`iat`/`exp`を要求しない。
- JWS compact tokenは専用`Authorization` schemeまたは単一headerで渡し、複数proof headerを拒否する。

## Snapshot Body Digest Profile
snapshot本体をJWS payloadへ入れず、送信bodyのSHA-256をJWSへbindする。

正規化条件を次に固定する。

1. Node Agentは`SnapshotIngestRequest`をUTF-8、BOMなしのJSON bytesへ1回だけserializeする。
2. Phase 5ではrequest `Content-Encoding`を許可しない。
3. whitespace、object key order、number representationの意味的正規化は行わない。
4. SHA-256は、実際にHTTP bodyとして送信するそのbyte列へ計算する。
5. `Content-Digest`はRFC 9530の`sha-256=:base64:`形式で送り、Phase 5 profileではSHA-256を1つだけ許可する。
6. JWS payloadは同じ32-byte digestを`bodySha256`のbase64url without paddingとして署名対象にする。
7. Hubはbody size上限を適用してraw bytesを受け、同じbytesからSHA-256を再計算する。
8. HubはJWS署名とdigestを検証してから、同じbytesをJSON deserializeする。
9. Hubはactual SHA-256、decoded `Content-Digest`、decoded JWS `bodySha256`の3つが一致することを確認する。
10. digest比較はdecoded digest bytes同士をconstant-timeで行う。

JSONをparseして再serializeした値はhash対象にしない。中継者がwhitespaceやencodingを変更した場合もdigest mismatchとして拒否する。

## Nonce Contract

### Shape and Binding
- nonceはCSPRNGで生成した256-bit valueをbase64url without paddingで表す。
- default TTLは60秒、設定可能範囲は10..300秒を初期候補とする。
- nonceは`subjectRef + purpose`へbindする。
  - join前: `joinTokenId + JOIN`
  - join後: `kid + SNAPSHOT_INGEST/HEARTBEAT/CLIENT_READ`
- nonce responseは`Cache-Control: no-store`を返す。
- clientへ返すerrorはunknown、expired、usedの詳細を区別しすぎず、再取得可能であることを示す。

### Store Boundary

```text
NonceStore
  issue(binding, ttl) -> IssuedNonce
  consume(nonce, expectedBinding) -> consumed | unavailable
```

Phase 5 implementation:
- process-local、bounded `InMemoryNonceStore`。
- atomic compare-and-removeで1回だけconsumeする。
- TTL超過entryをaccess時とperiodic maintenanceで除去する。
- global/per-subject発行上限とnonce endpoint rate limitを持つ。
- Hub再起動で未使用nonceが消えることを正常とし、clientは再取得する。

future backend:
- Redis `SET NX EX` + atomic consumeを同じcontractへ実装できるようにする。
- Redis configured時にmemoryへsilent fallbackしない。
- Redis dependency、config、integration testはPhase 5 Non-Goalとする。

### Verification and Consume Order
1. request/body size、proof syntax、path/query syntaxを検証する。
2. `kid`からactive Principal/public keyを解決する。
3. requestからcanonical method/path/query/purposeを構築する。
4. algorithm、JWS signature、canonical request bindingを検証する。
5. bodyがある場合はactual bytesのdigestを検証する。
6. DTOをparseし、route-level validationを完了する。
7. expected bindingでnonceをatomic consumeする。
8. application use caseを実行する。

nonceをsignature/body validation前にconsumeしない。DB処理失敗やresponse loss後は新しいnonceを取得し、同じ`ingestId`でretryする。

成功responseには次回用nonceを含め、通常時の追加round tripを減らす。responseを失った場合だけnonce endpointから再取得する。

## Principal and Route Policy
初期Principal type:
- `NODE_AGENT`: bindされた1つの`nodeId`としてsnapshot/heartbeatを送信できる。
- `CLIENT`: 許可されたcurrent/history APIを読める。

Principal metadata:
- `principalId`
- `principalType`
- `displayName`
- `status`: `ACTIVE` / `DISABLED`
- `kid`
- `publicKeyJwk`
- `keyAlgorithm`
- `createdAt`, `lastSeenAt`
- Node Agentの場合は`nodeId`

初期route policy:

| Route class | NODE_AGENT | CLIENT | proofなし |
| --- | --- | --- | --- |
| join bootstrap | join token HMACのみ | 拒否 | token proofなしは拒否 |
| snapshot ingest / heartbeat | active signed requestのみ | 拒否 | 拒否 |
| current/history read | 拒否 | active signed requestのみ | 拒否 |

read route cutoverはPhase 5GでClient対応と同時に行う。

## Join Flow
1. Hub operatorが高entropy、短命・単回利用のjoin tokenを発行する。
2. join materialとしてHub `publicEndpoint.baseUrl`、`hubId`、join token ID/secret、任意のexpected node nameを渡す。HTTP endpointの場合はNode Agent側でもinsecure transportを明示承認する。
3. Node AgentがEd25519 key pairをlocal生成し、public JWKとRFC 7638 `kid`を作る。
4. Node Agentが`joinTokenId + JOIN`にbindされたnonceを取得する。
5. Node Agentがdomain-separated canonical inputへ`HMAC-SHA256(joinTokenSecret, input)`を計算する。
6. Hubがtoken digest/TTL、HMAC、nonce、expected node nameを検証する。
7. Hubがnonce/tokenをatomicにconsumeし、`NODE_AGENT` PrincipalとNode registryを作成する。
8. Hubが`kid`、割り当てた`nodeId`、Principal metadataを返す。
9. Node Agentがprivate key、public key、`kid`、`hubId`、endpoint、node identityをlocal credential storeへ保存する。

HMAC inputは文字列の単純連結にしない。次のfieldをdomain tag付きlength-prefixed binary formatでencodeする。

```text
cocoadiskinfo.join.v1
hubId
joinTokenId
nonce raw bytes
kid raw thumbprint bytes
nodeName
```

token利用は最初の成功時に`kid`へbindする。同じtoken secret + 同じ`kid`のretryはtoken TTL内で同じjoin resultを返し、response lossから回復できるようにする。異なる`kid`への再利用は拒否する。

Phase 5のkey rotation/compromise recoveryは、operatorがold Principalを`DISABLED`にし、新しいjoin/pairing tokenで再join/re-pairする最小flowとする。active old keyによるonline rotationはfollow-upに送る。

## Task Breakdown

### Task 1: Prove JOSE, Canonical Request, and Raw-Body Boundaries
- Objective: Ed25519 JWS、proxy-stable canonical path/query、raw body digest validationをreal socketで実証する。
- Affected modules/files: JOSE dependency spike、canonical request profile、Ktor raw body fixture、simple forwarding proxy fixture。
- Expected behavior: valid signed requestだけが成功し、wrong signature、body modification、algorithm confusion、path/query/purpose swappingを拒否する。TLS terminationの有無でJWS semanticsを変えない。
- Validation: real HTTP socketとTLS-terminating test proxyの両方を使い、`testApplication`だけでraw transport boundaryを代用しない。

### Task 2: Add Principal and Join Token Storage
- Objective: Principal/public keyとjoin token stateをSQLite/PostgreSQLへ保存する。
- Affected modules/files: backend別Phase 5 migration、Principal/join token Repository。
- Expected behavior: `kid`からactive Principalを解決し、tokenを1つの`kid`へatomic/idempotentにbindできる。
- Validation: SQLite concurrency/expiry testsとPostgreSQL実DBintegration。

### Task 3: Implement Bounded In-Memory Nonce Store
- Objective: purpose/subject-bound nonceの発行、TTL、atomic consumeを実装する。
- Affected modules/files: auth application boundary、in-memory store、config/metrics/logging。
- Expected behavior: replay、wrong purpose/kid、expiry、concurrent consumeを拒否し、restart後は再取得で回復する。
- Validation: clock-injected unit tests、concurrency barrier、capacity/rate-limit tests。

### Task 4: Implement Join Challenge and Key Registration
- Objective: join token HMACでNode Agent public keyを登録する。
- Affected modules/files: join-token CLI/use case、nonce/join API、Node Agent join client、credential storage。
- Expected behavior: Hubがprivate keyを受け取らず、valid tokenだけが1つの`kid`/node identityを取得する。
- Validation: valid、expired、reused-same-key、reused-different-key、response loss、HTTP opt-in missing、wrong HMAC、wrong nonce binding。

### Task 5: Add Shared Signed-Request Verification
- Objective: JWS、digest、nonce、Principal route policyを1つのreusable boundaryで強制する。
- Affected modules/files: server auth plugin/middleware、raw body receiver、Principal resolver。
- Expected behavior: routeが個別に検証手順を実装せず、`AuthenticatedPrincipal`とverified bodyだけをapplicationへ渡す。
- Validation: policy matrix、proof replay、body substitution、method/path/purpose swapping。

## CLI/API Compatibility
追加command/API候補:

```text
cocoadiskinfo-agent hub join-token create --node-name sapphire-node-01
cocoadiskinfo-agent node-agent join --hub https://hub.example --token <token>
cocoadiskinfo-agent node-agent join --hub http://192.168.1.10:14631 --allow-insecure-transport --token <token>

POST /api/v1/auth/nonces
POST /api/v1/node-agents/join
```

既存`oneshot`、`standalone`、`db` command shapeは変更しない。

## Data and Persistence Impact
- Principal public key/JWK、`kid`、algorithm、statusとjoin token digest/use stateを追加migrationで保存する。
- Phase 5BのV2へ追記せず、Principal、join token、persistent `hubId`はbackend別V3 migrationで追加する。
- join token plaintextとNode/Client private keyをDBへ保存しない。
- nonceをDBへ保存しない。
- used/expired join tokenはbounded期間後にcleanupする。
- Principalはraw snapshot cleanupと連動削除しない。
- certificate metadata/CRL/OCSP tableは不要になる。

## Validation Plan
```text
./gradlew :diskinfo-agent:test --tests '*SignedRequest*Test' --tests '*CanonicalRequest*Test' --tests '*Nonce*Test' --tests '*Join*Test'
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-core:compileKotlin
```

SQLiteと実PostgreSQLでmigration、join token atomic binding、Principal resolutionを検証する。

## Risks and Open Questions
- JOSE libraryのEd25519/JWK Thumbprint interoperabilityはTask 1で固定する。
- reverse proxyがbody bytesまたはAPI pathを変更するとproof validationが失敗する。Phase 5ではTLS terminationだけをsupported topologyとし、URL rewriteとbody transformationを禁止する。
- in-memory nonce storeは単一Hub instance専用である。HA要件が出た場合はRedis等のshared backendを追加する。
- Node/Client private keyはowner-only local credential fileへ置き、Preferencesや通常ログへ出さない。encrypted store/OS keychainはPhase 7 operationsで具体化する。

## Implementation Order
1. JOSE、canonical request、raw body SHA-256 binding、forwarding proxyのfocused spikeを実行する。
2. Principal/public key、join tokenの追加migrationとRepositoryを実装する。
3. bounded `InMemoryNonceStore`とnonce APIを実装する。
4. join-token CLI、HMAC join、Node Agent key registrationを実装する。
5. shared signed-request verifierとroute policyを実装する。
6. replay、body substitution、wrong binding、join retry、SQLite/PostgreSQL integrationを通す。
