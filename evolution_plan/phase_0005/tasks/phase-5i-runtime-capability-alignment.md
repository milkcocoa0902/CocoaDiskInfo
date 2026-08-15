# Phase 5I: Runtime Capability Alignment

## Status

方針確定。現行Phase 5のmode別capabilityを維持する。Standalone pairing tokenのresolver bug修正とREADME capability matrixだけを採用し、直前のcapability composition案は実装しない。

## Source Context

- Primary: `../../master.md`
- Phase plan: `../phase_0005_plan.md`
- Supporting: `../../strategy/0002_future_architecture.md`
- Supporting: `../../strategy/0004_future_architecture_mode.md`
- Supporting: `../../strategy/0007_db_data_lifetime_policy.md`
- Implemented baseline: `../phase_0005_handoff.md`
- Prerequisite: `phase-5h-acceptance-phase4-regression.md`

## Goal

`standalone`、`hub`、`node-agent`について、現実装、既存計画、会話中の仮定を混同せず比較し、変更要否を決められる状態にする。

直前の会話では、次を満たすcapability案を仮定した。ただし、この時点では採用決定ではない。

- Node Agentもlocal snapshotを保存し、認証済みClientへlatest/historyを提供できる。
- HubはNode Agent ingestに加え、同一processでlocal collectionを実行できる。
- 単一machineでHubとNode Agentを二重起動しなくても、後から別machineを追加できる。
- 既存の`standalone`、`hub`、`node-agent` CLIを互換presetとして維持する。
- 将来のupstream追加やrelayへ拡張できるが、未定義の多段転送を暗黙に有効化しない。

## Non-Goals

- capabilityを独立booleanとして無制限に自由合成できる設定を直ちに公開しない。
- Phase 5Iで複数upstream、永続delivery queue、store-and-forwardを実装しない。
- 子Hubから受信したsnapshotを別Hubへ転送する階層集約を実装しない。
- `standalone`、`hub`、`node-agent` commandをrename/removeしない。
- Principal type、JWS、nonce、pairing/join wire contractを不用意に統合しない。
- local persistenceをremote deliveryの暗黙outboxとして扱わない。

## Current State

現行Phase 5実装は、modeごとに次の固定bundleを組み立てる。

| Mode | Local collect | Local store | Upstream push | Remote ingest | Client API |
| --- | ---: | ---: | ---: | ---: | ---: |
| `standalone` | on | on | off | off | on |
| `hub` | off | on | off | on | on |
| `node-agent` | on | off | on | off | off |

この形はPhase 5A-Hのtask文書、`master.md`、strategy 0002/0004と整合する。会話中に別のcapability構成も検討したが、再確認後はこの現行境界を維持すると決定した。

### 現実装の詳細

| Capability | `standalone` | `node-agent` | `hub` |
| --- | --- | --- | --- |
| periodic local collection | on | on | off |
| console output | on | on | server logのみ |
| snapshot保存 | local originをSQLite/PostgreSQLへ保存 | 保存しない | authenticated Node originをSQLite/PostgreSQLへ保存 |
| storage default | default SQLiteあり | DBへ接続しない | JDBC URL明示必須 |
| Client pairing token作成 | 対応 | 非対応 | 対応 |
| signed Client latest/history | 対応 | 非対応 | 対応 |
| Node Agent join token作成 | 非対応 | tokenを利用してjoinする側 | 対応 |
| snapshot ingest/heartbeat受信 | 非対応 | 送信する側 | 対応 |
| upstream push | off | local collection結果をHubへ即時送信 | off |
| push失敗時の永続queue | なし | なし。bounded immediate retryのみ | なし |
| retention maintenance | on | off | on |
| 現在Clientが接続できるか | yes | no | yes |

### 直前の会話で仮定した案（未決定）

| Capability | `standalone` | `node-agent` | `hub` |
| --- | --- | --- | --- |
| local collection | default on、offも内部的には可能 | default on、offも内部的には可能 | target明示時on、その他off |
| local snapshot保存 | on | onと仮定した | on |
| signed Client latest/history | on | onと仮定した | on |
| remote Node ingest | off | off | on |
| upstream push | off | on | default off、local originだけなら将来on可 |
| aggregated snapshot forwarding | off | off | off。多段Hubとして別設計 |
| modeの位置付け | local all-in-one preset | Standalone + upstream preset | remote ingestを追加したserver preset |

### 比較時の主な差分と結論

| 論点 | 現実装 | 直前の仮定 | 結論 |
| --- | --- | --- | --- |
| Node Agentのlocal保存 | しない | する | 現実装を維持する |
| Node AgentへのClient接続 | できない | できる | 現実装を維持する |
| Hubのlocal collection | しない | 明示時に行える | 現実装を維持する |
| Hubのupstream push | しない | local origin限定なら可能 | 現実装を維持する |
| mode設計 | 固定bundle | named preset + typed capability | 固定bundleを維持する |

## Confirmed Independent Bug

`standalone client-pairing-token create`がHub用storage resolverを経由し、`[storage].jdbcUrl is required for hub mode.`で失敗していた問題は、上記architecture判断とは独立したバグである。

現在のworking treeでは、Standalone token作成はStandalone runtimeと同じdefault SQLiteを解決し、Hub token作成だけが明示JDBC URLを要求するよう修正済みである。CLIとconfig resolverのfocused testでこの境界を固定する。schemaが未migrationの場合は、Hub設定エラーではなく通常のschema migration案内で失敗する。

## Final Decision

- `standalone`: local collection、local persistence、Client pairing/read API、maintenanceを持つ。
- `node-agent`: local collectionとHubへのsigned push/heartbeatに専念し、local persistenceとClient APIを持たない。
- `hub`: Node Agent ingest、central persistence、Client pairing/read API、maintenanceを持ち、local collectionとupstream pushを持たない。
- Desktop Clientの接続先はStandaloneまたはHubとする。
- modeを任意capabilityの組み合わせへ変更せず、現行CLI/runtime assemblyを維持する。
- capability matrixをREADMEへ掲載し、利用者がmode差を事前に判断できるようにする。

## Deferred Alternative

以下は比較時に検討した案として残すが、Phase 5では採用しない。将来要件が変わった場合は新しいtaskとして再検討する。

### 1. Modeはpreset、実装はtyped capability planとする案

利用者が通常操作するCLI名は維持する。一方、runtime入口ではmodeを次の型付きcapabilityへ展開し、依存関係を副作用開始前に一括検証する。

- `CollectLocal`: local smartctl collectionを生成する。
- `PersistSnapshots`: local/remote originを明示してsnapshotを保存する。
- `ServeClient`: local authorityでClient pairingとsigned latest/history APIを提供する。
- `AcceptNodeIngest`: Node Agent join、ingest、heartbeat、registryを提供する。
- `PushLocalSnapshots`: local collection originだけをupstreamへ送る。
- `MaintainStorage`: retentionとcleanupを長時間runtimeで実行する。

capabilityは単純なbooleanではない。`ServeClient`はauthorityとquery source、`AcceptNodeIngest`はauthority、registry、persistent storage、`PushLocalSnapshots`はupstream membership credentialを必要とする。server routeも`on/off`ではなく、Client serviceとNode ingest serviceを別々に組み立てる。

### 2. 仮定したpreset

| Preset | Collect | Persist | Client service | Node ingest | Push local | 意味 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `standalone` | on | on | on | off | off | local all-in-oneの簡易preset |
| `node-agent` | on | on | on | off | on | Standaloneにupstream deliveryを加えたpreset |
| `hub` | explicit | on | on | on | off by default | 複数Nodeを受け入れられるserver。local collectionも選択可能 |

これにより、Clientから見た接続先はすべて`CocoaDiskInfo Server`となる。Client profileへtopology種別を保存せず、返された`nodes`を同じUIで表示する。

`node-agent`のlocal persistenceはhistory提供のsourceであり、Phase 5時点ではdelivery queueではない。local保存成功後のpush失敗はlocal履歴をrollbackせず、既存のbounded retryとstructured errorで扱う。永続再送は別taskとする。

### 3. Collectionのoffはtopologyではなく運用状態として扱う

`standalone`または`node-agent`でcollectionをoffにすると、保存済みcacheをClientへ提供するserverになる。これは保守、smartctl一時停止、過去履歴参照には意味があるが、主要なpresetではない。

初期defaultは次とする案を推奨する。

- `standalone`: collect on。
- `node-agent`: collect on。upstream未設定なら`standalone`を案内する。
- `hub`: collection targetが明示された場合だけon。EC2など収集対象を持たないHubで暗黙scanしない。

すべてのproducer、server、maintenanceがoffになるlong-running planは拒否する。

### 4. Hubのpushはsource scopeを分ける

`PushLocalSnapshots`はそのprocessがlocalで収集したsnapshotだけを送る。Hubでこれを有効にしても、子Node Agentから受信したsnapshotは転送しないため、多段Hubにはならない。

受信済みsnapshotを上位Hubへ送る`PushAggregatedSnapshots`は、origin chain、loop prevention、node identity mapping、idempotency、freshness、partial failureを別途定義する必要がある。Phase 5Iでは非対応とし、設定された場合はfail fastにする。

### 5. process roleとsecurity principalを分ける

1つのprocessがClient APIとupstream pushを同時に持っても、credentialと権限は混ぜない。

- local API authority: Client principal、pairing token、read authorizationを所有する。
- upstream membership: `NODE_AGENT` credentialで別authorityへingest/heartbeatする。
- remote Node受入: join tokenとNode Agent principal registryを所有する。

`CLIENT` principalでingestさせず、`NODE_AGENT` principalでClient readさせない現行policyを維持する。bootstrap token発行可否はmode名ではなく、`ServeClient`または`AcceptNodeIngest` capabilityへ結び直す。

### 6. identityを3種類に分ける

- `AuthorityId`: local API security realmのidentity。wire互換上は現行`hubId`を維持する。
- `AgentInstanceId`: installation/machineの安定したlocal identity。
- `MembershipNodeId`: authority内でsnapshotを識別するnode identity。

Hubがlocal collectionする場合は自己join HTTPを行わず、local Agent instanceをそのHub authorityのregistryへ直接登録し、remote ingestと同じaggregate queryへ載せる。

StandaloneからNode Agentまたはcollecting Hubへ移行してもlocal historyを分断しないため、安定した`AgentInstanceId`を永続化する。upstreamの`MembershipNodeId`との対応方法はjoin wire変更の要否を含むため、実装前の確認事項とする。

## Deferred Candidate Task Breakdown

以下は将来capability案を再検討する場合の候補であり、現在は開始しない。

### Task 1: Correct Architecture Documents

- Objective: 当初構想と衝突するmode定義をsource change前に修正する。
- Affected modules/files: `evolution_plan/master.md`、strategy 0002/0004、Phase 5 plan/handoff。
- Expected behavior:
  - modeをpreset、runtimeをcapability compositionとして定義する。
  - Node AgentのClient APIとlocal persistence、Hubのoptional local collectionを明記する。
  - local-only pushとaggregated forwardingを区別する。
- Validation: 文書間で用語、preset matrix、deferred scopeが一致する。

### Task 2: Introduce RuntimePlan without Behavior Change

- Objective: 現行3 modeを内部のtyped planへ変換し、mode依存判断を一か所へ集約する。
- Affected modules/files: config resolver、command request、runtime assembly、executor tests。
- Expected behavior:
  - existing CLI/config/defaultを同じplanへ変換する。
  - capability prerequisiteを副作用開始前に検証する。
  - disabled capabilityの未使用設定は`db migrate`と同様に解決しない。
- Validation: Phase 5A-H regressionと代表CLI success/failure。

### Task 3: Split Server and Authority Capability Boundaries

- Objective: mode固定route bundleをClient service、Node ingest service、共通auth/healthへ分割する。
- Affected modules/files: Ktor installers、auth application services、bootstrap token runtime。
- Expected behavior:
  - nonce/auth authorityを重複installしない。
  - Client pairing/readとNode join/ingest/heartbeatをcapability別に有効化する。
  - token作成commandがmode名ではなくhost capabilityを検証する。
- Validation: Standalone/Node Agent/Hub別route authorization matrix。

### Task 4: Unify Local Persistence and Client Service

- Objective: StandaloneとNode Agentが同じlocal write/read pathを使う。
- Affected modules/files: snapshot sink composition、repository/query service、maintenance lifecycle。
- Expected behavior:
  - collector snapshotをlocal originとして先に保存する。
  - local保存とremote pushのfailureを独立して扱う。
  - Node Agentがpairing、latest、historyを提供する。
- Validation: DB-enabled local history、push成功/失敗、Client signed read、cleanup。

### Task 5: Add Optional Local Collection to Hub

- Objective: Hubがremote Node受信とlocal machine収集を同一processで行えるようにする。
- Affected modules/files: Hub runtime assembly、local registry enrollment、aggregate query、freshness。
- Expected behavior:
  - local snapshotをHub authority内のlocal nodeとして保存する。
  - local/remote nodeを同じaggregate APIで返す。
  - local collection failureがremote ingest serverを停止しない。
- Validation: Hub local node + remote Nodeのlatest/history、stale/error、restart identity。

### Task 6: Align Operator and Client UX

- Objective: topology名ではなく接続可能なserverとして一貫して扱う。
- Affected modules/files: CLI help、example TOML、README、Desktop settings/pairing labels、OpenAPI。
- Expected behavior:
  - `Server URL` / `Server ID`を基本表示にする。
  - ClientはStandalone、Node Agent、Hubへ同じprofile contractで接続する。
  - collection、upstream、node ingestの有効状態をstartup summaryへ表示する。
- Validation: 3 presetのpairing/read smoke、config examples、secret redaction。

## CLI/API Compatibility

- `standalone`、`hub`、`node-agent`は互換presetとして維持する。
- Client read/pairing contractは3 presetで共通にする。
- wire上の`hubId`は即時renameせず、UI/内部概念では`Server ID` / `AuthorityId`として扱う。
- Node AgentへのClient routes追加はadditiveとする。
- Hubの既存remote ingest/read routesを維持する。
- capability設定を公開する場合も、preset defaultとCLI precedenceを明記する。

## Data and Persistence Impact

- Node Agentへlocal snapshot persistence、Client principal、authority identity、maintenanceが追加される。
- Hub local collectionではlocal nodeをregistryへ登録し、remote ingest rowと区別できるoriginを維持する。
- existing Phase 5 schemaで表現できる範囲を先に確認し、必要なschema変更は追加migrationとする。
- local repositoryをdelivery queueとして再利用せず、将来outboxが必要なら状態とretentionを別schemaで定義する。
- mode切替でlocal identity/historyを失わないmigration testを追加する。

## Validation Plan

```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-client:desktopTest
./gradlew :diskinfo-client:compileKotlinDesktop
```

加えて次のtopologyをreal socketまたはprocess-level testで確認する。

- Standalone + Client。
- Node Agent + local Client + upstream Hub。
- Hub local collection + remote Node Agent + Client。
- Hub without collection on a machine without smartctl target。
- push failure中もlocal read/historyを維持するNode Agent。
- unsupported aggregated forwardingをstartup時に拒否する構成。

## Risks and Open Questions

- Hub collection defaultは「target明示時だけon」を推奨する。常時onにするとEC2 Hubが意図せずlocal scanする。
- `standalone collect=off`はcache-only serverとして意味を持つが、Phase 5Iでoperator-facing optionを公開するかは未決定。
- Node Agentのlocal saveとpushの順序はlocal saveを先とする案を推奨する。永続再送保証は含めない。
- 安定した`AgentInstanceId`とauthority-scoped `MembershipNodeId`をjoin時にどう対応付けるかは、identity migrationとwire互換を調査して確定する。
- 1 processから複数upstreamへ送る場合のcredential/profile modelは将来taskとする。
- aggregated forwardingを実装する場合はloop防止とorigin preservationが設計blockerになる。

## Implementation Order

1. 現実装のmode別capabilityをsourceとtestから確認する。
2. Standalone pairing token作成がHub用storage resolverを使うbugを修正する。
3. Standaloneはdefault SQLite、Hubは明示storage必須であることをfocused testで固定する。
4. READMEへ現行modeのcapability matrixとClient接続先を記載する。
5. Phase 5 plan/handoffを「現実装維持」の決定へ同期する。

### Deferred capability案の実装順

以下は今回実行しない。

1. `master.md`とstrategy 0002/0004を今回のcapability/preset方針へ修正する。
2. 現行挙動を変えず、3 commandを`RuntimePlan`へ変換してvalidationを中央化する。
3. auth authority、Client service、Node ingest service、healthのserver boundaryを分割する。
4. Standaloneのlocal nodeをregistry/query modelへ統合し、共通local server baselineを作る。
5. Node Agentへlocal persistence、maintenance、Client pairing/readを追加し、pushと複合する。
6. Hubへoptional local collectionとlocal registry enrollmentを追加する。
7. CLI/configで必要最小限のcapability optionを公開し、preset defaultsを維持する。
8. Client/CLI/OpenAPI/operator docsを`CocoaDiskInfo Server`表現へ同期する。
9. 3 presetのcross-topology acceptanceとPhase 5 regressionを完了する。
