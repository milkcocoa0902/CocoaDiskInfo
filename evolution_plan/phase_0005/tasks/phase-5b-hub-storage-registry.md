# Phase 5B: Hub Storage, Registry, and Freshness Baseline

## Source Context
- Primary: `../../master.md`
- Phase plan: `../phase_0005_plan.md`
- Supporting: `../../strategy/0002_future_architecture.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`
- Prerequisite: `phase-5a-ingest-contract-node-identity.md`
- Prerequisite: `../../phase_0004/phase_0004_plan.md`

## Goal
Hubが複数Node Agentのcurrent stateとraw historyを説明できるよう、received time、Node registry、last-seen/error state、backend別追加migrationを実装する。

## Non-Goals
- Principal public key、join token、nonce/auth boundaryはPhase 5Cで扱う。
- HTTP ingest/read routeは実装しない。
- heartbeat eventを時系列tableへ無期限保存しない。
- materialized latest tableは初期必須にしない。
- long-term aggregated historyは扱わない。

## Current State
- SQLite/PostgreSQLともPhase 4の単一V1が確定baselineである。
- `disk_snapshot`は`collect_time`を持つが、Hub受信時刻を持たない。
- Node Agent registry、last seen、last error、expected collection intervalがない。
- latestはraw `disk_snapshot`からquery時に求めている。
- Phase 4 cleanupは`disk_snapshot.collect_time < cutoff`だけを対象にする。
- automatic maintenanceの実装名がStandaloneに固定されている。

## Boundary Decision
- Owner boundary: storage migration、Repository、maintenance application boundary。
- Why this belongs there:
  - received timeとregistryはHub current-state/freshnessの永続化契約である。
  - SQLite/PostgreSQL差分はstorage層へ閉じ込める。
  - stale判定に必要なraw dataを保存し、API representationはPhase 5Fで組み立てる。
- Cross-boundary impact:
  - Phase 5DのHub runtimeがregistry repositoryを組み立てる。
  - Phase 5Eのheartbeat/ingestがlast seenを更新する。
  - Phase 5Fのquery use caseがregistryとsnapshotを結合する。

## Initial Data Model

### disk_snapshot addition
- `ingest_id`: Node Agent/applicationが生成するidempotency key。
- `received_at`: Hub/applicationが受け付けた時刻。
- unique constraint: `(node_id, ingest_id)`。
- Phase 4 rowの`ingest_id` backfillは`snapshot_id`を使う。
- Phase 4 rowのbackfillは`collect_time`を使う。
- 新規writeでは必ずapplication clockから設定する。

### node_agent_registry
- `node_id`
- `node_name`
- `status`: `ACTIVE`, `DISABLED`
- `expected_collection_interval_seconds`
- `capabilities_json`または初期の最小field
- `joined_at`
- `last_seen_at`
- `last_snapshot_received_at`
- `last_error_code`
- `last_error_message`
- `last_failure_at`

heartbeatを受けるたびにcurrent rowを更新し、heartbeat event tableは作らない。

update rule:

- valid heartbeat、`STORED`、`DUPLICATE`は`last_seen_at`をmonotonicに更新する。
- `last_snapshot_received_at`は新しいrowを`STORED`した場合だけ更新する。
- `DUPLICATE`は既存snapshotの`received_at`と`last_snapshot_received_at`を変更しない。
- data freshnessはlatest snapshotの`received_at`から計算し、heartbeat/livenessとは分離する。
- expected collection intervalはjoin時に初期登録し、heartbeatでpositive valueへ更新できる。
- valid heartbeat、`STORED`、`DUPLICATE`はcurrent errorをclearする。`last_failure_at`は最後にfailureが発生した時刻として保持し、clear時にnullへ戻さない。
- registryの最新`node_name`をcurrent APIの表示authorityとし、既存snapshot rowの`node_name`は書き換えない。

## Task Breakdown

### Task 1: Add Backend-Specific Phase 5 Migration
- Objective: Phase 4 V1を変更せず、Hub freshness schemaを追加する。
- Affected modules/files:
  - `diskinfo-agent/src/main/resources/db/migration/sqlite/`
  - `diskinfo-agent/src/main/resources/db/migration/postgresql/`
  - migration tests
- Expected behavior:
  - fresh DBはV1→latestを適用できる。
  - Phase 4 V1だけが適用済みのDBもlatestへ進める。
  - existing snapshotの`ingest_id`は`snapshot_id`、`received_at`は`collect_time`でbackfillする。
  - `(node_id, ingest_id)` unique constraintを作る。
  - Node registry tableと必要indexを作る。
- Validation:
  - SQLite migration二重実行。
  - PostgreSQL実DBmigration二重実行。
  - phase 4 rowが失われずtimestamp instantが変わらない。
- Notes: SQLite table rebuildが必要な場合も、raw SQL migrationに閉じ込める。

### Task 2: Add Node Registry Repository
- Objective: Node Agent current stateを保存・参照するRepositoryを追加する。
- Affected modules/files:
  - `diskinfo-agent/.../datastore/NodeAgentRegistryRepository.kt`
  - Exposed table/repository implementation
  - transaction/use case tests
- Expected behavior:
  - joinでregistryをcreate/activateできる。
  - heartbeatで`lastSeenAt`を更新できる。
  - `STORED` ingestだけが`lastSnapshotReceivedAt`を更新する。
  - `DUPLICATE` ingestは`lastSeenAt`だけを更新する。
  - failure stateをlast errorとして記録・clearできる。
  - disabled nodeはstatusを保持したままhistory参照対象として説明できる。
- Validation:
  - 2 Nodeを独立して更新・取得できる。
  - out-of-order heartbeatで`lastSeenAt`を後退させない。
  - duplicate retryで`lastSnapshotReceivedAt`を進めない。

### Task 3: Persist and Query Received Time
- Objective: collect timeとHub received timeを区別する。
- Affected modules/files:
  - `DiskSnapshotTable`
  - `ExposedDiskSnapshotRepository`
  - repository query models
- Expected behavior:
  - new writeはreceived timeを保存する。
  - latest queryはsnapshotとreceived timeを返せる。
  - historyの並び順は引き続き`collect_time`を使う。
- Validation:
  - collect timeが過去でもreceived timeが現在であるcaseを保持する。
  - timezone/UTC instantをSQLite/PostgreSQLで一致させる。

### Task 4: Generalize Periodic Maintenance Ownership
- Objective: Phase 4 cleanupをHub long-running lifecycleでも再利用できる名前とassemblyへ整理する。
- Affected modules/files:
  - `maintenance/StandaloneMaintenanceRunner.kt`
  - `server/StandaloneMaintenanceModule.kt`
  - `SapphireCommandRuntime.kt`
- Expected behavior:
  - cleanup business ruleとresultは変えない。
  - StandaloneとHubが同じmode-neutral runner/moduleを使える。
  - startup cleanup、periodic interval、duplicate skip、cancellation propagationを維持する。
- Validation:
  - existing Standalone maintenance testsを維持する。
  - fake Hub lifecycleでもstartup-before-serverとperiodic cancellationを確認する。
- Notes: 名前変更だけの大規模cleanupにせず、Hub assemblyに必要な最小境界を作る。

## Compatibility Impact
- existing Phase 4 API/CLI payloadは変えない。
- Phase 4 raw retention semanticsを変えない。
- schemaは追加migrationで前進する。

## Data and Persistence Impact
- Phase 4 V1を変更しない。
- Phase 5 storage baseline migrationはV2とし、5CのPrincipal/join schemaは後続V3へ分ける。
- raw snapshot cleanupは`collect_time`基準を維持し、`received_at`基準へ黙って変更しない。
- Node registryはcurrent stateであり、raw snapshot retentionと同時削除しない。
- inactive registryのcleanupは30日を候補とするが、Phase 5で自動削除する前にhistory/tombstone要件を確定する。

## Validation Plan
```text
./gradlew :diskinfo-agent:test --tests '*StorageMigrationTest' --tests '*Registry*Test'
./gradlew :diskinfo-agent:test --tests '*Snapshot*RepositoryTest'
./gradlew :diskinfo-agent:test --tests '*Maintenance*Test'
./gradlew :diskinfo-agent:compileKotlin
```

PostgreSQLは明示的なtest DBでV1→latest、repository、maintenanceを実行する。

## Risks and Open Questions
- `capabilities`をPhase 5初期schemaへ入れるか。利用箇所がなければ空のJSON fieldを先取りせず後続migrationへ送る。
- registryのdisabled/inactive rowをいつhard deleteするか。historyにnodeNameを保持しているため再構築可能だが、Principalとの参照関係をPhase 5Cで確認する。
- `last_error_message`へ外部入力やsecretを保存しないsanitize ruleが必要。
- HubのSQLite対応は小規模integration baselineとして維持できるが、中央運用の推奨backendをPostgreSQLへ固定するかはPhase 5Dで決める。

## Implementation Order
1. SQLite/PostgreSQL追加migrationとmigration regressionを作る。
2. received timeをsnapshot repositoryへ接続する。
3. Node registry table/repositoryとmonotonic updateを実装する。
4. mode-neutral periodic maintenance boundaryへ最小整理する。
5. SQLite focused testsとPostgreSQL実DBintegrationを通す。
