# Phase 4B: Repository Interface and SQLite Baseline

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0004_plan.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`

## Goal
`DiskSnapshotRepository`のcontractを明示し、Server/Sinkが具体的なExposed実装に依存しないようにする。SQLite実装をPhase 4の基準実装として固定する。

## Non-Goals
- query結果やAPI payloadを変えない。
- migration方式はこのtaskでは変えない。Flyway移行はPhase 4Cで扱う。
- PostgreSQL backendはこのtaskでは追加しない。

## Current State
- `DiskSnapshotRepository`はclassで、Exposed transactionとquery実装を直接持っている。
- `RepositorySnapshotSink`と`SapphireAgentServer`は具体classに依存している。
- history APIは`findHistory(nodeId, deviceKey, query)`を使うbounded queryとして実装済み。

## Boundary Decision
- Owner boundary: `Repository`
- Why this belongs there: Server/Sinkは保存と検索のcontractだけを知ればよく、ExposedやDB backendを知る必要がない。
- Cross-boundary impact:
  - Server route testsはRepository fakeで書ける余地ができる。
  - PostgreSQL backendは同じinterfaceを実装するだけにする。

## Task Breakdown
### Task 1: Repository interfaceを追加する
- Objective:
  - insert/latest/historyのcontractを固定する。
- Affected modules/files:
  - `diskinfo-agent/src/main/kotlin/.../datastore/DiskSnapshotRepository.kt`
  - `diskinfo-agent/src/main/kotlin/.../sink/SnapshotSink.kt`
  - `diskinfo-agent/src/main/kotlin/.../server/SapphireAgentServer.kt`
- Expected behavior:
  - interfaceは少なくとも`insert`、`findLatestNodes`、`findLatestByDeviceKey`、`findHistory`を持つ。
  - 既存のExposed実装は`ExposedDiskSnapshotRepository`など具体名へ寄せる。
  - API payloadとCLI挙動は変えない。
- Validation:
  - existing repository/server testsが通ること。

### Task 2: SQLite baseline testsを明示する
- Objective:
  - SQLiteをPhase 4の基準実装として、insert/latest/history queryの期待値を固定する。
- Affected modules/files:
  - `diskinfo-agent/src/test/kotlin/.../datastore/DiskSnapshotRepositoryTest.kt`
  - `diskinfo-agent/src/test/kotlin/.../server/SapphireAgentServerTest.kt`
- Expected behavior:
  - latestはnode/device単位の最新snapshotを返す。
  - historyは`nodeId` + `deviceKey`で絞り、`limit/from/to/order`を守る。
  - empty resultは正常系として扱う。
- Validation:
  - `./gradlew :diskinfo-agent:test`

### Task 3: Server/Executorから具体Repository生成を取り除く
- Objective:
  - `SapphireAgentServer`と`SapphireExecutor.Standalone`が`ExposedDiskSnapshotRepository`を生成しないようにし、具体backend選択をCLI/runtime assemblyへ寄せる。
- Affected modules/files:
  - `diskinfo-agent/src/main/kotlin/.../Main.kt`
  - `diskinfo-agent/src/main/kotlin/.../SapphireCommandRuntime.kt`
  - `diskinfo-agent/src/main/kotlin/.../exec/SapphireExecutor.kt`
  - `diskinfo-agent/src/main/kotlin/.../server/SapphireAgentServer.kt`
  - `diskinfo-agent/src/test/kotlin/.../server/SapphireAgentServerTest.kt`
- Expected behavior:
  - `SapphireAgentServer`は`DiskSnapshotRepository`を必須依存として受け取り、defaultでExposed実装を生成しない。
  - `SapphireExecutor.Standalone`は`SapphireAgentServer`を必須依存として受け取り、default serverを生成しない。
  - `ProductionSapphireCommandRuntime`はrepository factoryだけを知り、Exposed実装名を直接参照しない。
  - `createSapphireAgentCommand()`のproduction defaultはSQLite基準実装を組み立て、CLI/API挙動は変えない。
- Validation:
  - server route testsはRepository fakeで検証する。
  - `./gradlew :diskinfo-agent:test`
  - `./gradlew :diskinfo-agent:compileKotlin`

## CLI/API Compatibility
- CLI変更なし。
- `GET /api/v1/snapshots/latest`と`GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots`は互換維持。

## Data and Persistence Impact
- schema変更なし。
- SQLite DB上の既存データに影響しない。

## Validation Plan
```text
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-core:compileKotlin
```

## Risks and Open Questions
- `findLatestNodes()`のin-memory `distinctBy`は動作維持を優先する。SQL最適化はPostgreSQL query plan確認後に別taskで扱う。
- interface名を`DiskSnapshotRepository`として維持し、具体実装をrenameするかどうか。
- DI framework導入はこのtaskでは行わない。backend選択は小さなrepository factoryで扱い、PostgreSQL backend追加時にfactoryの分岐を拡張する。
