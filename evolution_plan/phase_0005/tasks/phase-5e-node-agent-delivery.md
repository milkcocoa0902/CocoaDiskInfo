# Phase 5E: Node Agent Runtime and Remote Delivery

## Source Context
- Primary: `../../master.md`
- Phase plan: `../phase_0005_plan.md`
- Supporting: `../../strategy/0004_future_architecture_mode.md`
- Prerequisite: `phase-5c-signed-request-auth.md`
- Prerequisite: `phase-5d-hub-runtime-ingest.md`

## Goal
`node-agent` runtimeを追加し、ローカルsmartctl収集結果をHubへbody digest付きJWSで冪等送信する。HTTP(S) transportはdeployment profileに従い、Hub停止時もprocess lifecycleを壊さず、bounded retry後の失敗を運用可能な形で報告する。

## Non-Goals
- 永続retry queueやstore-and-forward DBを初期実装しない。
- Node AgentにHubのraw history repositoryを持たせない。
- Node AgentをClient-facing read APIとして公開することを必須にしない。
- Hubからの同期live collection RPCを実装しない。

## Current State
- `Standalone`は同一process内でperiodic collection、Repository sink、HTTP serverを組み立てる。
- `SnapshotSink`はlocal repositoryまたはstdoutへwriteし、通常のExceptionは警告として継続し、`CancellationException`を伝播する。
- remote delivery用JWS sink、HTTP insecure opt-in、signing credential store、bounded retry policyはない。

## Boundary Decision
- collection loopは既存Collectorと`DiskSnapshot`を再利用する。
- remote sinkが`ingestId`をcollection結果ごとに一度生成し、そのdelivery試行中は再利用する。
- HTTP DTO/client、raw body digest、JWS proof、nonce、retry/backoffはremote transport adapterへ閉じ込める。
- Node Agent modeは通常storage connectionを作らない。将来永続queueを追加する場合だけ別taskでstorage policyを導入する。
- cancellationはretry delay、HTTP call、sinkから必ず伝播する。
- collectionとdeliveryは1本の逐次lifecycleとして実行し、処理時間がintervalを超えても次回jobを無制限に重ねない。

## Task Breakdown

### Task 1: Add Node Agent Runtime Configuration
- Objective: Hub endpoint、HTTP insecure opt-in、signing credential path、collection interval/target、delivery timeout/retryを解決する。
- Affected modules/files: CLI/config/env model、resolver、credential loader。
- Expected behavior: invalid/missing credentialsをcollection開始前にactionable errorで報告し、`http://` endpointは`allowInsecureTransport=true`なしで拒否する。HTTPSはplatform/operator trust storeで通常検証する。
- Validation: precedence、Hub-only/Standalone-only設定の分離、HTTP opt-in、HTTPS verification、secret非表示。

### Task 2: Implement Transport-Aware Signed Remote Snapshot Sink
- Objective: Phase 5D ingest APIへraw JSON body + signed digestでsnapshotを送るtransport adapterを作る。
- Affected modules/files: remote sink、Ktor client factory、DTO mapper。
- Expected behavior:
  - HTTP(S) endpointへ接続し、HTTPは明示opt-in、HTTPSはhostname/certificate verification必須とする。
  - JSONをUTF-8/BOMなしで1回serializeし、送信する同じbytesからSHA-256 `Content-Digest`を作る。
  - `kid`、nonce、method/path/purpose、`bodySha256`をEd25519 JWSで署名する。
  - `STORED`と`DUPLICATE`を成功として扱う。
  - private key、token、nonce、snapshot payloadを通常ログへ出さない。
- Validation: HTTP opt-in missing/present、valid/untrusted HTTPS、TLS-terminating proxy、disabled Principal、body modification、nonce replay、success/duplicate/conflict response。

### Task 3: Add Bounded Immediate Retry
- Objective: transient timeout/5xxに限定した即時retryを実装する。
- Affected modules/files: delivery policy、remote sink tests。
- Expected behavior:
  - 同じsnapshot deliveryでは同じ`ingestId`を使う。
  - retryごとに新しいnonceとJWSを使い、body bytesと`ingestId`は維持する。
  - retry回数とbackoffに上限を持つ。
  - validation/auth/conflictの4xxを盲目的にretryしない。
  - exhausted failureをstructured warningとして記録し、次のcollectionへ進む。
- Validation: timeout後のduplicate recovery、retry classification、cancellation during backoff。

### Task 4: Assemble Node Agent Loop and Heartbeat
- Objective: existing periodic collectionをremote sinkとheartbeatへ接続する。
- Affected modules/files: command request/runtime/executor、collection lifecycle。
- Expected behavior:
  - Node Agentはlocal DB/APIを不要とする。
  - heartbeatまたはdelivery success/failureに必要なcurrent statusをHubへ送る。
  - slow collection/delivery時は同一Node内の次回実行を重ねず、完了後に次の周期を待つ。
  - shutdownでin-flight HTTP/retryをcancelしclientをcloseする。
- Validation: fake slow collector/Hubでnon-overlap、interval、failure continuation、shutdown ordering、no storage connection。

## CLI/API Compatibility
追加command:

```text
cocoadiskinfo-agent node-agent join ...
cocoadiskinfo-agent node-agent --config <path>
```

既存`standalone`はlocal storage/API topologyとして残す。`node-agent`はそのaliasではなく、remote Hub senderとして別requestにする。

## Data and Persistence Impact
- 初期Node Agentはsnapshot DBや永続queueを持たない。
- join credentialはprivate key、public JWK、`kid`、`hubId`、endpoint、node identityとして、permissionを限定したlocal credential fileへ保存する。
- delivery failureでsnapshotはmemory上から失われ得ることを明示し、永続queueは運用要件が確認された後のfollow-upとする。

## Validation Plan
```text
./gradlew :diskinfo-agent:test --tests '*RemoteSnapshotSinkTest' --tests '*NodeAgent*Test'
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:run --args='node-agent --help'
```

real Hubへのdirect HTTP opt-inとTLS-terminating proxy経由deliveryでtimeout-after-store→new nonce/JWS retry→`DUPLICATE`を確認する。

## Risks and Open Questions
- retry default回数、timeout、backoff上限は実測前に小さく固定し、設定を過剰に増やさない。
- snapshot喪失を許容できない利用者には永続queueが必要になるが、Phase 5初期scopeを広げない。
- heartbeat周期をcollection周期と共用するか独立させるか。初期は余分なschedulerを避け、collection lifecycleから送る案を推奨する。

## Implementation Order
1. Node Agent request/configとcredential validationを追加する。
2. HTTP(S) Ktor client、insecure opt-in、nonce client、JWS/digest remote snapshot sinkを実装する。
3. idempotency IDを保持するbounded retry policyを実装する。
4. Node Agent periodic collectionとheartbeatを接続する。
5. no-storage、slow-run non-overlap、failure continuation、cancellation、resource closeを検証する。
6. real Hubとのtimeout/duplicate recovery integrationを通す。
