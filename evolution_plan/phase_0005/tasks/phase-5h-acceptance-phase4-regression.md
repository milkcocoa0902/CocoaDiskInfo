# Phase 5H: End-to-End Acceptance and Phase 4 Regression

## Source Context
- Primary: `../../master.md`
- Phase plan: `../phase_0005_plan.md`
- Baseline: `../phase_0004_handoff_baseline.md`
- Prerequisite: `phase-5a-ingest-contract-node-identity.md`
- Prerequisite: `phase-5b-hub-storage-registry.md`
- Prerequisite: `phase-5c-signed-request-auth.md`
- Prerequisite: `phase-5d-hub-runtime-ingest.md`
- Prerequisite: `phase-5e-node-agent-delivery.md`
- Prerequisite: `phase-5f-aggregate-api-freshness.md`
- Prerequisite: `phase-5g-client-auth-hubless.md`

## Goal
Phase 5の分散topologyとHub-less topologyを実transport・実storageで検証し、Phase 4互換性、operator手順、公開contractを揃えて完了判定できる状態にする。

## Non-Goals
- 5A-Gで未実装のfeatureをこのtaskへ持ち込まない。
- HA Hub、Redis nonce backend、永続delivery queue、mTLSを追加しない。
- Phase 7のOS package/systemd配置を先行実装しない。

## Current State
- 各taskにfocused validationはあるが、topology横断のacceptance、Phase 4 regression、operator documentationのownerがない。
- Phase 4 baselineはSQLite中心のdefault suiteが通り、PostgreSQL testは明示接続時だけ実行される。
- Phase 4 OpenAPI/config examplesはPhase 5のroutes、`publicEndpoint`、HTTP opt-in、credentialsをまだ表現しない。

## Boundary Decision
- implementation taskのunit/focused testを置き換えず、実process/real socket/backend matrixを最後に通す。
- Hubはstorageを明示設定し、起動前にoperatorが`db migrate`を実行する。runtimeは自動migrationしない。
- production codeにtest-only bypassを追加せず、TLS-terminating proxy用temporary certificate、signing key、databaseをtest harnessで生成する。
- validation evidenceはdefault suiteとexternal PostgreSQL-gated suiteを区別して記録する。

## Acceptance Matrix

| Topology | Transport/Auth | Storage | Required outcome |
| --- | --- | --- | --- |
| Hub + 2 Node Agents + Desktop | direct HTTP opt-in + signed requests | SQLite | join、ingest、duplicate retry、aggregate pagination、partial/stale、signed read |
| ALB-like TLS proxy + Hub + Node Agent | external HTTPS + internal HTTP + signed requests | PostgreSQL | public endpoint、migrate、ingest idempotency、bounded latest/history、cleanup |
| Hub-less Standalone + Desktop | direct HTTP opt-in + signed requests | SQLite | local collection/cache、latest/history、signed read、startup/periodic cleanup |
| Oneshot | local process | disabled/enabled SQLite | DB-disabled no-connect、`--persist` write、existing output behavior |

## Task Breakdown

### Task 1: Complete Contract and Operator Documentation
- Objective: Phase 5のCLI/config/env/API/security/operator sequenceを一か所から追えるようにする。
- Affected modules/files: OpenAPI、example config、phase status/handoff、CLI help assertions。
- Expected behavior:
  - `/nonce`、`/join`、ingest、heartbeat、read routesとPrincipal別authorizationを記述する。
  - internal listenと`publicEndpoint.baseUrl`の分離、HTTP insecure opt-in、external TLS termination、key file permission、secret redaction、join token lifetimeを記述する。
  - CocoaDiskInfoがX.509 certificate/CA lifecycleを所有しないこと、HTTPではserver authentication/confidentiality/response integrityがないことを記述する。
  - `db migrate`→Hub start→join→Node Agent start→Client pairingの順序を記述する。
- Validation: OpenAPI parse、example config load、representative `--help`、secret非表示。

### Task 2: Run Security Negative Matrix over Real Transport
- Objective: unit verifierだけでなくreal socketからrequest proof boundaryを確認する。
- Affected modules/files: integration test harness、forwarding TLS proxy fixture、auth route tests。
- Expected behavior:
  - HTTP endpointをopt-inなしで拒否し、opt-inありではJWS policyを同じように強制する。
  - HTTPSのuntrusted certificate、expired/replayed nonce、wrong purpose/kid/method/path/query、body/digest改変を拒否する。
  - TLS-terminating proxyを通してもcanonical requestとbody digestを検証し、URL rewrite/body transformationはunsupportedとして失敗させる。
  - wrong Principal type、disabled Principal、expired/used join tokenを拒否する。
  - retryは同じ`ingestId`/body bytesと新しいnonce/JWSで`DUPLICATE`へ収束する。
- Validation: negative caseごとにstable HTTP status/error codeをassertする。

### Task 3: Verify Distributed Data and Lifecycle Matrix
- Objective: 2 Node、slow/failing Node、Hub restart、both backendでcache-first behaviorを確認する。
- Affected modules/files: process-level/integration tests、database fixtures。
- Expected behavior:
  - same device keyを持つ2 Nodeをnode scopeで分離する。
  - different Node/same `ingestId`を独立保存し、same Node/same ID/different payloadをconflictにする。
  - slow collection/deliveryでもNode Agent jobが重ならない。
  - 1 Node停止中もlast cacheとbounded partial/stale metadataを返す。
  - heartbeat/duplicate retryでold snapshotのdata freshnessを延長しない。
  - aggregate latestをdefault 100/maximum 500のdevice-row keyset paginationで取得する。
  - Hub restart後はnonce再取得で回復し、persisted Principal/registry/snapshotを維持する。
  - cleanupはstrict cutoffを保ち、latest queryは全履歴をJVMへloadしない。
- Validation: SQLiteと明示的なPostgreSQL integration、resource close、query-plan evidence。

### Task 4: Run Phase 4 Compatibility Regression
- Objective: Phase 5追加後もbaselineのlocal topologyとcommand behaviorを維持する。
- Affected modules/files: CLI/runtime/API/client/storage regression tests。
- Expected behavior:
  - existing `oneshot`、`standalone`、`db migrate`、`db cleanup` shapeを維持する。
  - Oneshot DB-disabledはconnectionを作らず、`--persist`は保存する。
  - Phase 4 read payload field、history bounds、retention/VACUUM、cancellation semanticsを維持する。
  - Desktopがnew additive metadataのない旧responseも読める。
- Validation: `phase_0004_handoff_baseline.md`の各compatibility項目をtest/evidenceへ対応付ける。

## CLI/API Compatibility
- Phase 5の新command/routesはadditiveとし、Phase 4 commandをrename/removeしない。
- security cutover後もHub-less Standaloneをfirst-class topologyとして提供する。
- response field変更はadditiveとし、旧Clientのunknown-field toleranceと新Clientのmissing-metadata toleranceを確認する。
- HTTP利用は明示opt-inを必要とするため、旧URL-only Client profileはmigration messageを経て更新する。

## Data and Persistence Impact
- acceptance fixture以外のproduction schema変更は5B/5Cのmigrationに所属させる。
- SQLite/PostgreSQLのfresh V1→latestとexisting V1→latestを両方検証する。
- real PostgreSQL testは空の専用databaseだけを使用し、実行versionとcommandをevidenceへ残す。
- proxy test certificate、signing private key、join token、database credentialをrepositoryやtest reportへ残さない。

## Validation Plan
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-client:compileKotlinDesktop
```

加えて、direct HTTP opt-in topology、TLS-terminating proxy topology、SQLite process test、明示設定したPostgreSQL integration、fat JARの代表commandを実行し、skipと成功を区別して記録する。

## Risks and Open Questions
- external PostgreSQL/TLS proxy fixtureをdefault CIへ常設できない場合、release gate commandと保存すべきevidenceを明文化する。
- hardware smartctlを必要とするfull process testはfake collector acceptanceと実機smokeを分ける。
- Phase 5のowner-only signing key fileとre-pair recoveryをacceptance条件にし、OS keychain/encrypted storeはPhase 7 follow-upとして区別する。

## Implementation Order
1. OpenAPI、example config、operator sequence、CLI helpを5A-Gの最終contractへ同期する。
2. direct HTTP opt-inとTLS-terminating proxyのsecurity negative matrixを通す。
3. SQLiteの2 Node distributed topologyとHub-less topologyを通す。
4. PostgreSQL integrationでmigration、ingest/query/cleanupを通す。
5. Phase 4 compatibility checklistと全module compile/testを完了する。
6. command、database version、skip、known limitationを含むhandoff evidenceを記録する。
