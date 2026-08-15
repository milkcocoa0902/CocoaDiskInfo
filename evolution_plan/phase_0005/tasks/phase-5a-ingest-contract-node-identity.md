# Phase 5A: Node-Scoped Ingest Contract and Identity

## Source Context
- Primary: `../../master.md`
- Phase plan: `../phase_0005_plan.md`
- Supporting: `../../strategy/0002_future_architecture.md`
- Supporting: `../../strategy/0008_device_identity_strategy.md`
- Prerequisite: `../../phase_0004/phase_0004_plan.md`

## Goal
Hubが複数Node Agentのsnapshotを保存できるよう、node identity、ingest idempotency、application command、Repository insert contractをHTTP実装より先に固定する。

## Non-Goals
- HTTP route、JWS authentication、join tokenはこのtaskでは実装しない。
- Node Agent runtimeやremote retry loopは実装しない。
- freshness responseやClient UIは実装しない。
- `DiskSnapshot`へtransport-specificなnode/JWS fieldを追加しない。
- batch ingestを初期contractにしない。

## Current State
- `DiskSnapshotRepository.insert(snapshot)`はnode contextを受け取らない。
- `ExposedDiskSnapshotRepository`と`DiskSnapshotTable`のdefaultがglobal `NodeIdentity`を使う。
- `SnapshotUseCase.saveSnapshot(snapshot)`はStandalone local saveと将来Hub ingestを区別できない。
- `disk_snapshot.snapshot_id`は保存側で生成され、remote retryのidempotency keyとして使えない。
- `DiskSnapshot`はdevice snapshotであり、node identityを持たない。この分離自体は維持する。

## Boundary Decision
- Owner boundary: application/use case、Repository contract、runtime assembly。
- Why this belongs there:
  - node identityはHTTP bodyではなく、Standaloneのlocal identity providerまたはHubのauthenticated principalから渡されるapplication contextである。
  - `DiskSnapshot`はCollector outputとしてnode transportを知らないままにする。
  - Repositoryはglobal hostnameを参照せず、呼び出し側から明示された保存recordを永続化する。
- Cross-boundary impact:
  - Standalone assemblyはlocal node identityを明示注入する。
  - Hub routeは後続taskでauthenticated principalをcommandへ変換する。
  - Node Agent remote sinkは後続taskで`ingestId`を生成する。

## Contract Shape
application modelの初期候補:

```text
SnapshotOrigin
  nodeId: Uuid
  nodeName: String

IngestSnapshotCommand
  ingestId: Uuid
  origin: SnapshotOrigin
  snapshot: DiskSnapshot
  receivedAt: Instant

IngestSnapshotResult
  status: STORED | DUPLICATE
  snapshotId: Uuid
  ingestId: Uuid
  receivedAt: Instant
```

`snapshotId`はRepository内部のrow identity、`ingestId`はcallerが生成するidempotency keyとして分離する。`receivedAt`はHub/application clockで初回insert時だけ決め、HTTP callerがauthorityとして指定しない。`DUPLICATE`は既存rowの`snapshotId`と元の`receivedAt`を返す。Standalone local saveでも同じrecord contractを使い、`receivedAt`はlocal application clockから設定する。

HTTP transportは後続taskで次のように分離する。

```text
SnapshotIngestRequest
  ingestId
  snapshot

SnapshotIngestResponse
  ingestId
  status
  receivedAt
```

request bodyにauthorityとしての`nodeId` / `nodeName`を持たせない。Hubはverified JWS `kid`から解決した`NODE_AGENT` Principalから`SnapshotOrigin`を作る。

## Task Breakdown

### Task 1: Introduce Explicit Node Origin
- Objective: global `NodeIdentity`依存をRepositoryから外す。
- Affected modules/files:
  - `diskinfo-agent/.../datastore/NodeIdentity.kt`
  - `diskinfo-agent/.../datastore/DiskSnapshotRepository.kt`
  - `diskinfo-agent/.../datastore/ExposedDiskSnapshotRepository.kt`
  - `diskinfo-agent/.../datastore/DiskSnapshotTable.kt`
- Expected behavior:
  - Repository insertはnode ID/nameを明示的に受け取る保存recordを使う。
  - table columnの`clientDefault`でhostname identityを注入しない。
  - query側の`nodeId + deviceKey` semanticsは維持する。
- Validation:
  - 同じRepositoryへ2つのnode identityで保存し、latest/historyが混ざらない。
  - global hostnameを変更せずfake identityでtestできる。
- Notes: `LocalNodeIdentityProvider`のような境界をruntime assemblyへ置き、Standalone behaviorを維持する。

### Task 2: Separate Local Save and Ingest Command
- Objective: local persistenceとremote ingestの共通application contractを作る。
- Affected modules/files:
  - `diskinfo-agent/.../usecase/SnapshotUseCase.kt`
  - `diskinfo-agent/.../sink/SnapshotSink.kt`
  - `diskinfo-agent/.../SapphireCommandRuntime.kt`
- Expected behavior:
  - Standalone/oneshot persistenceはlocal identityとapplication-generated ingest IDを付けて保存する。
  - Hubは後続taskでprincipal-derived originを同じuse caseへ渡せる。
  - HTTP request/result型をuse case interfaceへ持ち込まない。
- Validation:
  - persisted oneshotとStandaloneの既存保存behaviorが維持される。
  - cancellationは引き続きSinkから伝播する。

### Task 3: Define Idempotent Insert Result
- Objective: 同じ`ingestId`を複数回受けてもrowを増やさないRepository contractを固定する。
- Affected modules/files:
  - `diskinfo-agent/.../datastore/DiskSnapshotRepository.kt`
  - `diskinfo-agent/.../datastore/ExposedDiskSnapshotRepository.kt`
  - repository/use case tests
- Expected behavior:
  - 初回insertは`STORED`を返す。
  - 同じ`ingestId`のretryは`DUPLICATE`を返し、既存rowを上書きしない。
  - idempotency scopeは`nodeId + ingestId`とし、異なるNodeが同じIDを使うことを許可する。
  - 同じNode/IDで異なるsnapshotが来た場合はdata conflictとして失敗させ、黙って成功にしない。
  - `nodeName`は変更可能な表示metadataなのでduplicate equalityへ含めず、`nodeId + DiskSnapshot`で判定する。
  - unique constraint競合時は既存rowを再取得して`DUPLICATE`またはconflictを決め、check-then-insert raceを残さない。
- Validation:
  - SQLiteでsame ID/same payloadがidempotent。
  - same Node/same ID/different payloadがactionable conflict。
  - different Node/same IDは両方保存できる。
  - concurrent retryは1 rowへ収束する。
  - duplicate resultは既存rowの元の`receivedAt`を返す。
  - PostgreSQL integrationでも同じsemantics。
- Notes: remote persistent queueはNon-Goalだが、network timeout後のimmediate retryにはidempotencyが必要である。

### Task 4: Add Node-Scoped Latest Repository Query
- Objective: Hubで曖昧にならないlatest query contractを追加する。
- Affected modules/files:
  - `diskinfo-agent/.../datastore/DiskSnapshotRepository.kt`
  - `diskinfo-agent/.../usecase/SnapshotUseCase.kt`
- Expected behavior:
  - `findLatest(nodeId, deviceKey)`を追加する。
  - 既存`findLatestByDeviceKey`はStandalone compatibilityのため当面維持するか、Standalone adapterへ閉じる。
- Validation:
  - 同じdevice keyを持つ2 Nodeのlatestが正しく分離される。

## Compatibility Impact
- `DiskSnapshot` JSON shapeは変えない。
- current/history API shapeはこのtaskでは変えない。
- existing standalone/oneshot command shapeは変えない。
- internal Repository/UseCase signatureは変更するため、fake/test doubleを同時に更新する。

## Data and Persistence Impact
- schema追加はPhase 5Bで行う。
- `snapshot_id`はinternal row identityとして維持し、Phase 5Bで別の`ingest_id`と`UNIQUE(node_id, ingest_id)`を追加する。
- Phase 4 V1は編集しない。

## Validation Plan
```text
./gradlew :diskinfo-agent:test --tests '*Snapshot*RepositoryTest' --tests '*SnapshotUseCaseTest'
./gradlew :diskinfo-agent:test --tests '*SapphireCommandRuntimeTest'
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-core:compileKotlin
```

## Risks and Open Questions
- duplicate/conflict判定は既存rowを読み、`nodeId + DiskSnapshot`のdomain equalityを使う。transport raw JSON equalityや変更可能な`nodeName`へ依存しない。
- local saveのingest IDをrandom UUIDにするかdeterministic UUIDにするか。retry元を持たないlocal saveはrandom UUIDでよい。
- `SnapshotUseCase`へwrite/readを持たせ続けるか、`SnapshotIngestUseCase`を分離するか。Hub route ownershipを明確にするため、write commandは別interfaceへ分ける案を推奨する。

## Implementation Order
1. node originとingest record/resultのapplication modelを追加する。
2. Repository insertからglobal `NodeIdentity`とtable defaultを外す。
3. idempotent insert contractとconflict behaviorを実装する。
4. local identity providerをoneshot/Standalone assemblyへ接続する。
5. node-scoped latest queryを追加する。
6. SQLite/PostgreSQL repositoryと既存mode regressionを検証する。
