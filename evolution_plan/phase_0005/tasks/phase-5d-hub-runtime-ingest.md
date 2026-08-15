# Phase 5D: Hub Runtime and Authenticated Ingest API

## Source Context
- Primary: `../../master.md`
- Phase plan: `../phase_0005_plan.md`
- Supporting: `../../strategy/0002_future_architecture.md`
- Prerequisite: `phase-5a-ingest-contract-node-identity.md`
- Prerequisite: `phase-5b-hub-storage-registry.md`
- Prerequisite: `phase-5c-signed-request-auth.md`

## Goal
収集を行わない`hub` runtimeを追加し、activeな`NODE_AGENT` Principalからだけsnapshotとheartbeatを受け取り、Hub storage/registryへ保存する。

## Non-Goals
- Node Agent側の収集・送信loopはPhase 5Eで扱う。
- aggregate read responseはPhase 5Fで扱う。
- Desktop Clientのread認証cutoverはPhase 5Gで扱う。
- Hubでsmartctlを実行しない。

## Current State
- runtime requestは`Oneshot`、`Standalone`、DB commandを扱うが、Hub modeを持たない。
- serverはStandalone collection lifecycleと同じassemblyにあり、plain connector前提である。
- Phase 4のstorage connectionはmodeごとに必要時だけ生成され、outer runtimeがlifecycleを所有する。
- Phase 5A-Cでnode-scoped ingest、registry、JWS Principal boundaryを用意する計画である。

## Boundary Decision
- `hub`は独立したCLI/runtime requestとし、Collector/smartctl dependencyを組み立てない。
- Hub server routeはshared verifierから受け取ったPrincipalとdigest検証済みraw bodyをDTOへ変換し、application clockを使って`IngestSnapshotCommand`を作る。
- node identityはrequest bodyから受け取らず、`NODE_AGENT` Principalからだけ導出する。
- storage connection、HTTP server、maintenance、auth repository/nonce storeのlifecycleはHub executorの外側assemblyが所有する。TLS terminationはdeployment側に置く。
- Flyway migrationはPhase 4どおり直接DataSourceを管理し、Hub runtime用Hikari poolとは共有しない。
- Hub起動時にmigrationを暗黙実行しない。operatorが事前に`db migrate`を明示実行し、schema不足時はactionable errorでfail fastする。

## Task Breakdown

### Task 1: Add Hub Command and Effective Configuration
- Objective: `hub` modeのCLI/config/env resolutionを追加する。
- Affected modules/files: CLI request、config model/loader/resolver、example config、help tests。
- Expected behavior:
  - internal listen address、`publicEndpoint.baseUrl`、HTTP insecure public endpoint opt-in、auth material、storage、retention/maintenanceを解決する。
  - Hub専用設定は他modeを失敗させない。
  - Hub storageを明示必須とし、暗黙のcwd SQLiteへ向けない。
  - `publicEndpoint.baseUrl`はabsolute HTTP(S)、user info/query/fragment/base pathなしとし、HTTPでは`allowInsecureTransport=true`を要求する。
  - persistent `hubId`をRepositoryから解決し、URL変更とは独立させる。
- Validation: precedence、unknown/invalid key、HTTP opt-in、public URL変更後も同じ`hubId`、representative `hub --help`。

### Task 2: Assemble Hub Runtime Lifecycle
- Objective: Collectorなしでstorage/auth/server/maintenanceを組み立てる。
- Affected modules/files: runtime factory、executor、server transport boundary。
- Expected behavior:
  - required dependenciesを1回だけ作り、終了時に確実にcloseする。
  - startup cleanupはserver start前、periodic cleanupはserver lifecycle内で実行する。
  - schema不足・pending migrationを検出した場合は、`db migrate`の実行方法を示してserver listen前に失敗する。
  - `Colotok.forceShutdown()`はprocess lifecycleにだけ残す。
- Validation: fake factoryでno-collector、ordering、close-on-success/failure/cancellationを確認する。

### Task 3: Add Authenticated Snapshot Ingest
- Objective: single snapshot ingest APIを実装する。
- Affected modules/files: route、DTO、Principal authorization、ingest use case。
- Expected behavior:
  - valid JWS、body digest、nonceを持つactive `NODE_AGENT`だけを許可する。
  - valid requestへ`STORED`または`DUPLICATE`を返す。
  - same ID/different payloadは`409`のactionable conflictにする。
  - `STORED`はregistry `lastSeenAt`と`lastSnapshotReceivedAt`を更新する。
  - `DUPLICATE`は`lastSeenAt`だけを更新し、既存rowの元の`receivedAt`を返す。
  - different Node/same `ingestId`は独立保存し、same Node/same ID/different snapshotは`409 ingest_id_conflict`にする。
- Validation: missing/wrong/disabled Principal、invalid JWS/digest/nonce、malformed payload、retry idempotency、concurrent retry、2 Node/same ID分離。

### Task 4: Add Authenticated Heartbeat
- Objective: snapshotがない期間もNode Agent current stateを更新できるようにする。
- Affected modules/files: heartbeat DTO/route/use case、registry repository。
- Expected behavior: active Principal自身のregistryだけを更新し、event rowを増やさない。
- Validation: monotonic last seen、wrong Principal type、out-of-order heartbeat。

### Task 5: Add Minimal Health Endpoint
- Objective: ALB/reverse proxyがapplication readinessをrequest authenticationなしで確認できるようにする。
- Affected modules/files: Hub route set、runtime readiness state、operator docs。
- Expected behavior: `GET /healthz`はSMART data、Principal、DB credentialを返さず、schema/storage/server readinessだけをbounded responseで示す。
- Validation: unauthenticated health check、not-ready status、no sensitive payload。

## CLI/API Compatibility
追加command/API:

```text
cocoadiskinfo-agent hub --config <path>
POST /api/v1/snapshots
POST /api/v1/node-agents/heartbeat
```

既存Standalone read routeとcommand shapeは維持する。ingest requestは`ingestId + snapshot`で、authorityとしての`nodeId`を含めない。Hub application serverはHTTPでlistenでき、外部HTTPSはdeployment側で終端する。

## Data and Persistence Impact
- Hubはstorage必須とする初期案を推奨する。
- ingestとregistry updateは、片方だけ成功して説明不能にならないtransaction boundaryを定義する。
- raw cleanupは`collect_time`基準を維持する。
- heartbeatはcurrent registry updateであり、無制限なevent historyを作らない。

## Validation Plan
```text
./gradlew :diskinfo-agent:test --tests '*Hub*Test' --tests '*Ingest*Test'
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:run --args='hub --help'
```

real HTTP socketとTLS-terminating proxyの両方からsigned ingest/heartbeatを通し、PostgreSQLでもsame-ID retryとtransaction behaviorを検証する。

## Risks and Open Questions
- Hub storageは明示必須とする。SQLiteは小規模/test対応、中央運用はPostgreSQLを推奨する。
- ingest成功とregistry更新のtransaction scopeを分ける場合、snapshotは保存済みだがresponseが失敗したcaseを`DUPLICATE` retryで回復できる設計が必要になる。
- snapshot ingest bodyは明示的なsize limitとrequest timeoutを持ち、超過をstable error codeで拒否する。

## Implementation Order
1. Hub command/config requestとvalidationを追加する。
2. Collectorを持たないHub runtime assemblyとresource lifecycleを実装する。
3. authenticated snapshot ingest routeをapplication commandへ接続する。
4. heartbeat routeとregistry monotonic updateを接続する。
5. startup/periodic maintenanceをHub lifecycleへ接続する。
6. minimal `/healthz`を追加する。
7. direct HTTP、TLS-terminating proxy、SQLite、PostgreSQL、resource closeのintegrationを通す。
