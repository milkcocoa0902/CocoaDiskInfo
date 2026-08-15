# Phase 5G: Client Signed Requests and Hub-Less Compatibility

## Source Context
- Primary: `../../master.md`
- Phase plan: `../phase_0005_plan.md`
- Supporting: `../../strategy/0001_api_response_strategy.md`
- Prerequisite: `phase-5c-signed-request-auth.md`
- Prerequisite: `phase-5f-aggregate-api-freshness.md`

## Goal
Desktop Clientが`CLIENT` signing keyを使い、HubとHub-less Standaloneの両方へHTTP(S) + nonce-bound JWSで接続する。HTTPは明示opt-inとし、freshness/partial/error表示、aggregate pagination、read API authentication cutoverを同じtaskで完了する。

## Non-Goals
- client certificate、mTLS、PKCS#12 certificate importを実装しない。
- TLS certificateの生成、発行、CA lifecycleをClientへ実装しない。
- Client binary attestation、OAuth、browser loginを実装しない。
- OS全種類のenterprise key managementを初期必須にしない。
- Hub-less利用者へ別Hub processを要求しない。
- API payloadをClient都合で破壊的変更しない。

## Current State
- Desktop Clientはbase URLだけをJava Preferencesへ保存し、plain CIO `HttpClient`を使う。
- Client signing key、`kid`、nonce、JWS proof、HTTP insecure transport opt-inを扱わない。
- Hub-less StandaloneはClient read APIを提供するが、local Client Principal/key registrationを持たない。
- Client JSONはunknown fieldを許容するため、additive freshness metadataは旧Client互換にできる。

## Boundary Decision
- connection profileはHTTP(S) endpoint、`allowInsecureTransport`、operator-provisioned trust store reference、Client `kid`、private-key referenceをまとめて扱う。
- private key bytesやpasswordをJava Preferencesへ平文保存しない。
- private keyはowner-only local credential fileへ保存し、OS keychain/encrypted store integrationはPhase 7へ送る。
- Client HTTP boundaryがnonce取得、method/path/purpose署名、next nonce保持を担当する。
- Hub-less Standaloneはprocess内にlocal Hub roleを持ち、local Client Principal/public-key registryを提供する。
- read routeをsigned-request必須へ切り替えるcommitでは、Desktop ClientとHub-less pairing pathも同時に利用可能にする。

## Initial Client Provisioning Direction
Hub/Standaloneが短命・単回利用のClient pairing tokenを発行し、Desktop Clientがlocal key pairを生成して、Phase 5Cのjoin HMACと同じ考え方でpublic keyを登録する。

- Hub mode: Hubが`CLIENT` Principalを登録する。
- Hub-less mode: Standaloneのlocal Hub roleが`CLIENT` Principalを登録する。
- private keyはClientから外へ出さない。
- Hub/Standalone `publicEndpoint.baseUrl`、`hubId`、pairing tokenをout-of-bandでClientへ渡す。
- HTTP endpointの場合はClient profileでも`allowInsecureTransport=true`を明示する。
- pairing token plaintext、private key、passwordをPreferences/logへ保存しない。

## Signed Read Profile
- bodyなしGETではJWS payloadへ`kid`、nonce、method、normalized path、purpose=`CLIENT_READ`を含める。freshnessはserver-side nonce TTLで判定し、Client clockへ依存しない。
- query canonicalizationはPhase 5C profileを再利用し、`limit`、`from`、`to`、`order`のdecoded/validated effective valueをname順にencodeする。unknown/duplicate parameterを拒否し、raw query stringのparameter orderへ依存しない。
- Hub/Standaloneはactive `CLIENT` Principalとscopeを確認する。
- successful responseが次のnonceを返し、失った場合はnonce endpointから再取得する。

## Task Breakdown

### Task 1: Add Client Pairing and Key Registration
- Objective: Hub/Standalone共通のClient public-key registration contractを実装する。
- Affected modules/files: operator pairing-token CLI/use case、Principal repository、Desktop pairing flow。
- Expected behavior: Client local key generation + HMAC challengeで1つの`CLIENT` Principal/`kid`を登録する。
- Validation: valid、expired、same-key retry、different-key reuse、HTTP opt-in missing、disabled Principal。

### Task 2: Add Transport Connection Profiles and Signing Key Store
- Objective: Desktop Clientがprofileごとのendpoint、HTTP opt-in、operator-provisioned HTTPS trust、signing credentialを安全に扱う。
- Affected modules/files: Desktop settings model/UI、HTTP(S) client factory、credential store。
- Expected behavior:
  - `http://`は`allowInsecureTransport=true`なしで拒否し、設定時もsecurity downgradeをUIへ表示する。
  - HTTPSではCA/hostname verificationを無効化しない。
  - private key/passwordをPreferences/logへ出さない。
  - credential load failureをactionableに表示する。
- Validation: HTTP opt-in missing/present、valid/wrong HTTPS trust、missing key、file permission、profile switch、restart behavior。

### Task 3: Add Nonce-Bound Signed Read Client
- Objective: current/history requestへsmall JWS proofを付ける。
- Affected modules/files: Agent API client、nonce client/cache、JWS signer、query canonicalizer。
- Expected behavior: normal responseのnext nonceを再利用し、nonce失効/response loss時だけ再取得する。
- Validation: replay、wrong path/purpose/query、expired nonce、response loss recovery、concurrent reads。

### Task 4: Render Freshness and Partial State
- Objective: Phase 5F metadataを既存表示へ追加する。
- Affected modules/files: API DTO、view model、Desktop UI。
- Expected behavior:
  - fresh/stale/partial/errorを区別する。
  - metadataのない旧Agentを既定値で表示する。
  - cached stale dataを通信失敗と混同しない。
  - aggregate latestのpageを`nodeId + deviceKey`でmergeし、同じNodeが複数pageへ現れるcaseを扱う。
- Validation: old/new fixture、partial multi-node response、multi-page/same-node split、empty/error state。

### Task 5: Add Hub-Less Pairing Flow
- Objective: 別HubなしでStandaloneとClientをpairできるようにする。
- Affected modules/files: Standalone local Hub auth assembly、`publicEndpoint` config、operator CLI、example config/docs。
- Expected behavior: Node Agent join tokenやclient certificateを要求せず、local `CLIENT` keyでreadできる。
- Validation: valid local Client、missing/wrong/disabled Principal、existing latest/history behavior。

### Task 6: Perform Atomic Read Authentication Cutover
- Objective: HubとStandaloneのread routeをactive `CLIENT` signed request必須にする。
- Affected modules/files: route policy registration、Client/server integration tests、docs。
- Expected behavior: unsigned、`NODE_AGENT`、disabled Client、replayed nonceを拒否し、valid Clientだけを許可する。
- Validation: Hub/Standalone matrixをdirect HTTP opt-inとTLS-terminating proxyの両方で確認する。

## CLI/API Compatibility
- API response fieldはadditiveに保つ。
- existing URL-only profileはmigration対象とし、credential未設定をunsigned requestへfallbackしない。既存HTTP URLは明示opt-inが完了するまで接続しない。
- Hub-less topologyは維持するが、unauthenticated read compatibilityは維持しない。
- command名候補:

```text
cocoadiskinfo-agent hub client-pairing-token create --display-name desktop-client
cocoadiskinfo-agent standalone client-pairing-token create --display-name desktop-client
```

## Data and Persistence Impact
- Hub/StandaloneはClient Principal、public JWK、`kid`、statusをDBへ保存する。
- Desktop Clientはcredential reference/profile metadataだけをPreferencesに置く。
- private keyはpermissionを限定したlocal credential storeへ保存する。
- nonceはClient/Hub双方で短命memory stateとして扱い、履歴DBへ保存しない。

## Validation Plan
```text
./gradlew :diskinfo-client:compileKotlinDesktop
./gradlew :diskinfo-client:test
./gradlew :diskinfo-agent:test --tests '*Client*Auth*Test' --tests '*SignedRequest*Test'
```

Hub、Hub-less Standalone、HTTP opt-in、TLS-terminating proxy、wrong HTTPS trust、missing/disabled key、replayed nonce、旧metadata responseをreal socket integrationで確認する。

## Risks and Open Questions
- Client private keyはPhase 5ではowner-only local fileへ保存する。OS keychain/encrypted storeはPhase 7で検討し、Preferences平文保存は不可とする。
- key compromise/rotationはold Principal disable + new pairing tokenによるre-pairをPhase 5 flowとし、online rotationはfollow-upとする。
- URL-only既存profileには「credential registrationが必要」「HTTPは明示opt-inが必要」を区別したmigration messageを出す。

## Implementation Order
1. Client pairing tokenとlocal key registration flowを実装する。
2. Desktop connection profile、HTTP opt-in、HTTPS trust、private-key storeを実装する。
3. nonce-bound JWS read clientとquery canonicalizerを実装する。
4. freshness/partial/error DTO互換とUI表示を実装する。
5. Hub-less Standaloneのlocal Principal/pairing flowと`publicEndpoint`を実装する。
6. Client/server双方が揃った状態でread routeをsigned-request必須へcutoverする。
7. Hub、Standalone、旧metadata、invalid credential/replayのend-to-end matrixを通す。
