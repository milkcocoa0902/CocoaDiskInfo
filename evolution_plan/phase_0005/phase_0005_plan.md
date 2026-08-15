# Phase 5: Hub and Node Agent

## Status

Phase 5A-Hの実装は完了（2026-08-15）。[Phase 5I](tasks/phase-5i-runtime-capability-alignment.md)でmode別capabilityは現実装を維持すると確定し、Standalone pairing token bug修正とREADME matrixへ反映した。

## Source Context
- Primary: `../master.md`
- Supporting: `../strategy/0001_api_response_strategy.md`
- Supporting: `../strategy/0002_future_architecture.md`
- Supporting: `../strategy/0004_future_architecture_mode.md`
- Supporting: `../strategy/0007_db_data_lifetime_policy.md`
- Supporting: `../strategy/0008_device_identity_strategy.md`
- Prerequisite: `../phase_0004/phase_0004_plan.md`
- Handoff baseline: `phase_0004_handoff_baseline.md`

## Goal
複数Node Agentからsnapshotを安全かつ冪等に受け取り、Hubがcache-firstの集約APIを提供する。

単一ノードの`standalone`はfirst-class topologyとして維持し、Phase 5完了時にはHub/Node Agent構成とHub-less Client-to-Agent構成の両方で、認証されたClientが現在状態と履歴を読めるようにする。

## Scope
- Node AgentからHubへのnode-scoped snapshot ingest contractを追加する。
- Node Agent/Client requestをbody digest付きJWS proofと短命・単回利用nonceで認証する。
- TLS終端とcertificate lifecycleをdeployment responsibilityとし、CocoaDiskInfoはX.509 certificateを生成・発行しない。
- Hub/Standaloneのinternal listen addressと外部公開先`publicEndpoint.baseUrl`を分離し、Node Agent/ClientのHTTP接続には明示的なinsecure transport opt-inを要求する。
- Hub側でjoin tokenを発行し、Node Agentがlocal key generation + HMAC challengeで公開鍵を登録するbootstrap flowを実装する。
- Node Agent principalとClient principalを分ける。
- HubはNode registry、received time、last-seen/error stateを保存する。
- Hubはcache-firstでlatest/history APIを返し、stale、partial、error metadataを付与する。
- Node Agentはローカル収集結果をremote HTTP sinkからHubへ送る。
- Hub-lessなClient-to-Agent直接接続を残し、Agent processがlocal Hub roleを内包する構成として認証する。
- HubとStandaloneのraw snapshot cleanupは、Phase 4のmaintenance contractを共有する。

## Non-Goals
- 初期実装で高可用Hubや分散DBを作らない。
- Node Agentの永続retry queueを初期必須にしない。
- Redis nonce backendと複数Hub instance間のreplay coordinationを初期必須にしない。
- `source=live` の同期問い合わせを初期必須にしない。
- client certificate、mTLS、CSR、CRL、OCSPを実装しない。
- TLS server、certificate generation、CA、renewalをCocoaDiskInfoへ実装しない。
- reverse proxyによるrequest proof再署名を初期必須にしない。
- reverse proxyによるAPI path rewriteを初期対応しない。
- 公式Client binaryのattestationやprovenanceを扱わない。
- 広範なRBAC、OAuth、browser login、multi-tenant authorizationを扱わない。
- 単一ノード利用者に別Hub processとNode Agent processの両方を要求しない。
- raw snapshotの長期集約、chart、Prometheus exportをPhase 5へ混ぜない。

## Phase 4 Impact Review

### 1. Repository insertはStandalone identityに固定されている
`DiskSnapshotRepository.insert(snapshot)`と`TransactionalSnapshotUseCase.saveSnapshot(snapshot)`はnode contextを受け取らず、`ExposedDiskSnapshotRepository`がglobal `NodeIdentity`を注入している。

Hub ingestではrequest bodyの`nodeId`を信頼せず、JWS `kid`から認証した`NODE_AGENT` Principalからnode identityを決める必要がある。したがって、ingest APIより先にnode-scoped application commandとRepository contractを導入する。

### 2. Remote deliveryのidempotency keyがない
`disk_snapshot.snapshot_id`は保存側で生成されるため、Node AgentがHTTP timeout後に同じsnapshotをretryすると重複行になり得る。

Node Agentが`ingestId`を一度生成し、retryでも同じ値を送る。`snapshot_id`はHub内部のrow identityとして維持し、別の`ingest_id`を追加して`UNIQUE(node_id, ingest_id)`で冪等性を保証する。Phase 4 rowは`ingest_id = snapshot_id`でbackfillする。

### 3. Freshness計算に必要なreceived timeとregistryがない
Phase 4 schemaは`collect_time`だけを持ち、Node Agentの存在、受信時刻、last seen、last errorを保存しない。

Hubでは`receivedAt`とNode registryを追加し、収集時刻、Hub受信時刻、node liveness、data freshnessを分ける。`lastSeenAt`はvalid heartbeat、`STORED`、`DUPLICATE`で更新し、`lastSnapshotReceivedAt`は`STORED`だけで更新する。data staleはHub clockのlatest snapshot `receivedAt`に対して期待収集間隔の2倍を超えた場合とし、heartbeatやduplicate retryでfreshへ戻さない。

### 4. Device-only latest routeはHubで曖昧になる
`GET /api/v1/devices/{deviceKey}/snapshots/latest`と`findLatestByDeviceKey`はnodeを指定しない。`deviceKey`はopaqueであってもHub全体でglobal uniqueとは限らない。

Hubでは`nodeId + deviceKey`を必須にするnode-scoped latest routeを追加する。device-only latest routeはStandalone互換として維持し、Hub route setには登録しない。

### 5. Maintenance lifecycleがStandalone名に固定されている
Phase 4の`StandaloneMaintenanceRunner` / `installStandaloneMaintenance`は実装内容としては長時間serverで再利用できるが、命名とassemblyがStandalone専用である。

Hubでも同じraw snapshot retentionを実行できるよう、business contractを変えずにmode-neutralなperiodic maintenance boundaryへ整理する。Node Agentは永続bufferを持たない初期実装ではDB cleanupを行わない。

### 6. Phase 4のV1は確定baselineになった
SQLite/PostgreSQLとも単一V1がPhase 4の完了成果としてcommit済みである。Phase 5ではV1を再編集せず、`received_at`、Node registry、Principal、join tokenなどをbackend別の追加migrationで導入する。

### 7. ServerとClientは平文HTTPかつrequest authenticationなしである
`SapphireAgentServer`はCIOのplain connectorを直接構築し、Desktop ClientはURLだけをPreferencesへ保存している。

Phase 5ではplain HTTP serverをTLS serverへ置き換えず、client identityをapplication-levelの公開鍵署名で認証する。TLSはALB/reverse proxyなどdeployment側で任意に終端できる。Node Agent/ClientがHTTP endpointへ接続する場合は明示opt-inを要求する。snapshot JSONはJWSへ内包せず、送信bodyのSHA-256をRFC 9530 `Content-Digest`形式でJWSへbindする。Hubはraw body bytesをdigest検証してからdeserializeする。

### 8. Nonce stateは既存storage lifecycleと性質が異なる
nonceは数十秒だけ必要なreplay-prevention stateであり、raw snapshot DBへ永続化する必要はない。Phase 5ではboundedなprocess-local `InMemoryNonceStore`を実装し、Hub再起動時はclientがnonceを再取得する。Redisは将来の複数Hub向けbackendとしてinterfaceだけ考慮する。

## Cross-Phase Decisions
- HTTP transport DTOは`SnapshotIngestRequest` / `SnapshotIngestResponse`とする。
- application boundaryはHTTP用語へ寄せず、`IngestSnapshotCommand` / `IngestSnapshotResult`とする。
- ingest bodyにはauthorityとしての`nodeId`を持たせない。node identityはauthenticated principalから渡す。
- 初期ingestは`ingestId + DiskSnapshot`の単件requestとする。batch ingestは実測後のfollow-upとする。
- `snapshot_id`はinternal row identity、`ingest_id`はNode Agent supplied idempotency keyとし、unique scopeは`node_id + ingest_id`にする。同じIDを異なるNodeが使うことは許可する。
- `DUPLICATE`は既存rowの`receivedAt`を返し、`lastSeenAt`だけを更新する。same Node/same ingest ID/different snapshotは`409 ingest_id_conflict`にする。
- Node Agentの永続queueは作らないが、bounded immediate retryとstructured failure logは実装する。
- Phase 5 schemaは追加migrationで前進し、Phase 4 V1を変更しない。
- client authenticationはtransport schemeに依存しないJWS proofで行う。CocoaDiskInfoはTLS certificateを生成・発行しない。
- Hub/Standaloneはinternal `host`/`port`と外部`publicEndpoint.baseUrl`を分離する。`baseUrl`はabsolute HTTP(S) URL、user info/query/fragment/base pathなしとし、security identityには使わない。
- Hubの不変identityはDBへ永続化した`hubId`とし、`publicEndpoint.baseUrl`変更で再join/re-pairを要求しない。
- Node Agent/Clientが`http://` endpointを使う場合は双方のconnection profileで`allowInsecureTransport=true`を明示し、server authentication、confidentiality、response integrityがないことを警告する。
- snapshot bodyはUTF-8/BOMなし、no `Content-Encoding`の送信bytesを正規形とし、意味的JSON normalizationや再serializeを行わない。
- signed requestはmethod、canonical API path、purpose、nonceをbindする。query付きGETはknown parameterのdecoded/validated effective valueをname順のcanonical formへ変換し、unknown/duplicate parameterを拒否する。Host、scheme、internal port、raw query orderにはbindしない。
- nonceは256-bit、subject/purpose-bound、短命・単回利用とし、signature/body/DTO validation後にatomic consumeする。
- Client read APIのsigned-request cutoverはDesktop Clientがsigning keyを扱える最後のtaskと同時に行い、途中commitで既存Standaloneを恒久的に利用不能にしない。
- `NODE_AGENT` principalでClient read routeを読ませず、`CLIENT` principalでingest routeへ送らせない。
- join/pairing secretから`joinKey = SHA-256(tokenSecret)`を導出し、challenge HMACとDB保存には`joinKey`を使う。Hubはtoken plaintextを保存しない。
- JWS proofは`Authorization: CocoaDiskInfo-JWS <compact JWS>`、次回nonceは`CocoaDiskInfo-Next-Nonce` response headerで渡す。
- aggregate latestのknown queryは`cursor`と`limit`とし、validated cursorをcanonical base64urlへ戻してname順で署名する。effective `limit`は省略時も含める。
- existing `NodeSnapshot.devices`は維持し、node status/last seenとdevice freshnessはadditiveな`deviceStates`へ置く。disabled Nodeはaggregateから除外するが、direct history参照は維持する。
- expected collection intervalはjoin時に初期登録し、heartbeatで更新できる。valid heartbeat/`STORED`/`DUPLICATE`はcurrent errorをclearし、`lastFailureAt`は最後のfailure時刻として保持する。
- recovery join tokenは既存`nodeId`へscopeできるようにし、key replacementでhistory identityを分断しない。registryの最新`nodeName`をcurrent表示名、snapshot rowの`nodeName`をhistorical recordとする。
- credential fileはversioned JSONとし、POSIX owner-only permissionまたはWindows owner-only ACLを保証できない場合はfail closedにする。HTTPS trustはplatform trustとoptional PEM CA fileだけを許可する。

## Tasks
- [Phase 5A: Node-Scoped Ingest Contract and Identity](tasks/phase-5a-ingest-contract-node-identity.md)
- [Phase 5B: Hub Storage, Registry, and Freshness Baseline](tasks/phase-5b-hub-storage-registry.md)
- [Phase 5C: Signed-Request Authentication and Principals](tasks/phase-5c-signed-request-auth.md)
- [Phase 5D: Hub Runtime and Authenticated Ingest API](tasks/phase-5d-hub-runtime-ingest.md)
- [Phase 5E: Node Agent Runtime and Remote Delivery](tasks/phase-5e-node-agent-delivery.md)
- [Phase 5F: Cache-First Aggregate API and Freshness](tasks/phase-5f-aggregate-api-freshness.md)
- [Phase 5G: Client Signed Requests and Hub-Less Compatibility](tasks/phase-5g-client-auth-hubless.md)
- [Phase 5H: End-to-End Acceptance and Phase 4 Regression](tasks/phase-5h-acceptance-phase4-regression.md)
- [Phase 5I: Runtime Capability Alignment](tasks/phase-5i-runtime-capability-alignment.md)

## Compatibility Impact
- `oneshot`, `standalone`, `db migrate`, `db cleanup`の既存CLI shapeを維持する。
- `GET /api/v1/snapshots/latest`とbounded history endpointはadditive metadataで拡張し、既存payload fieldを削除しない。
- 新しいClientはmetadataがない旧Agentを既定値で表示できるようにする。
- 旧ClientはJSONのunknown fieldを無視するが、Phase 5完了時のsigned-read cutoverにはClient updateが必要になる。
- device-only latest endpointはStandalone compatibility routeとして扱い、Hubではnode-scoped routeを使う。

## Data and Persistence Impact
- `disk_snapshot`へHub受信時刻を追加する。
- `disk_snapshot`へ`ingest_id`を追加し、Phase 4 rowを`snapshot_id`でbackfillして`UNIQUE(node_id, ingest_id)`を作る。
- Node registry、Principal public key、join token metadataのtableを追加する。
- heartbeatを無制限なevent historyとして保存せず、registryのcurrent stateを更新する。
- raw snapshot cleanupはPhase 4と同じstrict `collect_time < cutoff`を維持する。
- join tokenは短命・単回利用とし、expired/used tokenを無期限保存しない。
- nonceはDBへ保存せず、bounded in-memory storeでTTL管理する。
- migrationはSQLite/PostgreSQL両方に追加し、fresh V1→latestと既存V1→latestを検証する。

## Validation Plan
Compile/test baseline:

```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-client:compileKotlinDesktop
```

Representative CLI checks:

```text
./gradlew :diskinfo-agent:run --args='hub --help'
./gradlew :diskinfo-agent:run --args='node-agent --help'
./gradlew :diskinfo-agent:run --args='node-agent join --help'
./gradlew :diskinfo-agent:run --args='standalone --help'
```

Integration checks:
- direct HTTP + explicit insecure opt-inと、TLS-terminating reverse proxy経由HTTPSの両方で同じJWS contractが動くことを確認する。
- HTTP endpointをopt-inなしでNode Agent/Clientが拒否し、HTTPS endpointでcertificate verificationを無効化しないことを確認する。
- Ed25519 JWS、RFC 7638 `kid`、raw body `Content-Digest`のinteroperabilityを確認する。
- valid join、expired token、same-key idempotent retry、different-key reuse、wrong HMACを確認する。
- valid active Node Agent signed requestだけがingestできることを確認する。
- body byte変更、digest mismatch、nonce replay、wrong purpose/kid/method/pathを拒否する。
- same Node/same `ingestId`のretryがrowを増やさず、different payloadを拒否し、different Node/same IDを独立保存できることをSQLite/PostgreSQLで確認する。
- 2 Node AgentのsnapshotをHubへ保存し、node/device単位のlatest/historyを取得する。
- 1 Node停止時に`partial`, `stale`, `errors`を返し、前回cacheを維持する。
- heartbeatとduplicate retryがold snapshotのdata freshnessを延長しないことを確認する。
- aggregate latestがdefault 100/maximum 500のdevice-row keyset paginationでboundedになることを確認する。
- HubとStandaloneのstartup/periodic cleanupが同じretention contractで動く。
- Desktop ClientがHubとHub-less Standaloneの両方へsigned requestで接続し、HTTP opt-inとHTTPS reverse proxy topologyを扱える。
- 既存oneshot/standalone/db command regressionを実行する。

## Implementation Risks and Deferred Follow-ups
上記Cross-Phase Decisionsにより、Phase 5着手を妨げていたarchitecture/contract上のblockerは解消済みとする。以下は各task内で検証するimplementation risk、またはPhase 5完了後のfollow-upであり、workplan開始条件ではない。

- Ed25519/JWS/JWK Thumbprintを扱うJOSE libraryとKtor raw-body受信をTask 5Cのspikeで固定する。
- Hub storageは明示必須とし、暗黙のcwd SQLiteへ向けない。SQLiteはsmall/test topology、PostgreSQLは中央運用の推奨backendとする。
- Client signing keyはpairing時に生成またはowner-only local credential fileから読み込み、private key/passwordをJava Preferencesへ平文保存しない。
- Node Agent / Client key compromiseとrotationは、旧Principalをdisableして新しいjoin/pairing tokenで再join/re-pairすることをPhase 5の最小flowとする。online rotationはfollow-upとする。
- Node Agent/Client signing keyはpermissionを限定したlocal credential fileへ保存し、Preferencesや通常ログへ出さない。OS keychain/encrypted storeの最終運用はPhase 7で固定する。
- in-memory nonce storeは単一Hub instance限定であり、将来HA時にはRedis等のshared atomic backendが必要になる。

## Implementation Order
1. Phase 5Aでnode-scoped command、ingest idempotency、local identity providerを固定する。
2. Phase 5Bで追加migration、received time、Node registry、mode-neutral maintenanceを実装する。
3. Phase 5CでJOSE/raw-body/canonical request spikeを行い、Principal public key、join token HMAC、in-memory nonce、signed-request policyを実装する。
4. Phase 5Dで`hub` mode、authenticated ingest/heartbeat、storage lifecycleを接続する。
5. Phase 5Eで`node-agent join`、Node Agent mode、transport-aware signed remote sink、HTTP opt-in、bounded retryを接続する。
6. Phase 5Fでcache-first aggregate response、freshness metadata、node-scoped latest routeを実装する。
7. Phase 5GでDesktop Client signing credential、signed read、HTTP opt-in、freshness UI、Hub-less pairingを完成させる。
8. Phase 5HでSQLite/PostgreSQL、Hub/Node Agent、Hub-less Standaloneのintegration matrix、operator handoff、既存mode regressionを完了する。
9. Phase 5Iで現実装とcapability案を比較し、現行mode境界の維持、Standalone pairing token bug修正、README capability matrixを確定する。
