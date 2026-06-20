# Phase 4E: PostgreSQL Backend and pg_partman Partitioning

## Source Strategy
- Primary: `../../master.md`
- Phase plan: `../phase_0004_plan.md`
- Supporting: `../../strategy/0003_db_cleanup_strategy.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`

## Goal
PostgreSQL backendを追加し、SQLiteと同じRepository contractでinsert/latest/history/cleanupを動かす。あわせて、raw snapshot historyにpg_partmanを使うか判断し、使う場合のpartition intervalとmigration方針を固定する。

## Non-Goals
- MySQL backendは追加しない。
- Hub/Node Agentのingest APIはPhase 5で扱う。
- pg_partmanを必須依存にしない。PostgreSQL backendはpg_partmanなしでも動く必要がある。

## Current State
- PostgreSQL driver dependencyは未追加。
- storage connectionはSQLite固定。
- Flyway移行はPhase 4Cで先に行う想定。
- cleanup contractはPhase 4Dで先に固定する想定。

## Boundary Decision
- Owner boundary: Repository / Storage Backend
- Why this belongs there: PostgreSQLのJSONB、timestamp、UUID、index、partitioning、extension availabilityはDB backend差分であり、API層へ漏らさない。
- Cross-boundary impact:
  - `storage.type = "postgresql"`または`jdbc:postgresql://...`でPostgreSQL backendを選択する。
  - Repository interfaceはSQLiteと同一。
  - pg_partman有無はstorage設定とmigration/maintenanceで閉じる。

## Task Breakdown
### Task 1: PostgreSQL driver and connectionを追加する
- Objective:
  - PostgreSQLへ接続できるようにする。
- Affected modules/files:
  - `diskinfo-agent/build.gradle.kts`
  - storage connection factory
  - config resolver tests
- Expected behavior:
  - `jdbc:postgresql://...`でPostgreSQL backendを選択する。
  - username/passwordを扱える。
  - HikariCPをPhase 4Aで採用した場合、PostgreSQLはpool経由で接続する。
- Validation:
  - URL/backend判定test。
  - local PostgreSQLがある場合のmanual `db migrate`。

### Task 2: PostgreSQL Flyway migrationを追加する
- Objective:
  - 現行`disk_snapshot` schemaをPostgreSQL SQLとして定義する。
- Affected modules/files:
  - `src/main/resources/db/migration/postgresql`
  - storage migrator
- Expected behavior:
  - JSONはPostgreSQL側では`jsonb`を使う。
  - timestamp/timezoneとUUID型をPostgreSQL前提で明示する。
  - `node_id`, `device_key`, `collect_time` indexを作る。
  - PostgreSQL migrationはSQLite migrationと分ける。
- Validation:
  - local PostgreSQLで`db migrate`が成功すること。
  - insert/latest/history repository integration check。

### Task 3: pg_partman採用判断を行う
- Objective:
  - raw snapshot historyのpartition管理をpg_partmanに任せるか決める。
- Expected behavior:
  - pg_partmanなしのplain PostgreSQL backendを必ず動作可能にする。
  - pg_partman利用は明示設定にする。候補:

```toml
[storage.postgresql.partitioning]
enabled = false
manager = "pg_partman"
interval = "7 days"
premake = 4
retention = "30 days"
```

  - extensionがない環境ではactionableなerrorにするか、plain tableへfallbackするかを決める。
  - fallbackする場合は、fallbackした事実をログ/出力に残す。
- Validation:
  - pg_partman disabledでPostgreSQL backendが動くこと。
  - pg_partman enabledでextension不在時のerrorが明確であること。

### Task 4: partition intervalを決める
- Objective:
  - 7日、14日、1ヶ月の候補から初期値を決める。
- Decision:
  - 初期候補は7日。
- Rationale:
  - default raw snapshot retentionが30日のため、7日partitionなら過剰保持が最大でもおおむね1partition分に収まる。
  - 14日はpartition数を減らせるが、30日retentionに対する過剰保持が大きくなる。
  - 1ヶ月は家庭内/NAS用途ではpartition数が少なく管理しやすいが、30日retentionとの境界が粗く、月長差もある。
  - 7日でも1年保持で約52partitionであり、Phase 4の想定規模では許容しやすい。
- Expected behavior:
  - `interval = "7 days"`をdefault候補にする。
  - intervalは将来設定可能にするが、Phase 4Eではまず`7 days`だけをsupportedにしてもよい。
  - pg_partmanのweekly boundaryは開始曜日の影響を受けるため、UTC基準かつ明示的なstart partitionを検討する。
- Validation:
  - partition intervalとretentionから、保持され得る最大期間をdoc/testで説明する。

### Task 5: pg_partman maintenanceとcleanup contractを接続する
- Objective:
  - Phase 4Dの`db cleanup` semanticsをPostgreSQL partitioningでも説明できるようにする。
- Expected behavior:
  - plain tableではDELETE based cleanup。
  - pg_partman enabledではpg_partman retention/maintenanceを使う。
  - dry-runでは、削除予定partitionまたはretention対象を表示する。
  - `--vacuum`はpg_partman partition dropとは別のoperationとして扱う。
- Validation:
  - local pg_partman環境がある場合のmanual check。
  - extensionなし環境ではplain PostgreSQL testを優先する。

## CLI/API Compatibility
- `--db-url`互換を維持する。
- PostgreSQL credentialsはconfig/environmentを主経路にする。
- API payloadは変更しない。

## Data and Persistence Impact
- PostgreSQLではschema差分が出るが、Repository contractは同じにする。
- pg_partmanを使う場合、`disk_snapshot`はpartitioned parent tableになる。
- pg_partman有効化は後からplain tableをpartitioned tableへ変換するより初期migration時に決めるほうが安全。既存PostgreSQL DBからの移行は別migration taskに分ける。

## Validation Plan
Default:

```text
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
```

Optional local PostgreSQL:

```text
./gradlew :diskinfo-agent:run --args='db migrate --db-url jdbc:postgresql://localhost:5432/cocoadiskinfo'
./gradlew :diskinfo-agent:run --args='db cleanup --db-url jdbc:postgresql://localhost:5432/cocoadiskinfo --dry-run'
```

Optional pg_partman:

```text
./gradlew :diskinfo-agent:run --args='db migrate --config ./local-postgres-partman.toml'
```

## Risks and Open Questions
- pg_partman requires extension availability and operational setup. Some environments will not allow it.
- pg_partman background worker requires PostgreSQL server configuration. CocoaDiskInfo should not require BGW for basic operation.
- PostgreSQL partitioned tables have uniqueness/primary key constraints that must include the partition key; `snapshot_id` design must be checked before finalizing migration.
- pg_partman retention drops/detaches whole partitions. It is not identical to row-level `collect_time < cutoff` deletion.
- If pg_partman is enabled after data already exists in a plain table, migration becomes more complex. Phase 4E should prefer clear initial choice over automatic conversion.
