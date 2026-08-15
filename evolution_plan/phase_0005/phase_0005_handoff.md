# Phase 5 Handoff

## Status

Phase 5の実装は2026-08-15時点で完了した。Phase 4のlocal topologyを維持しながら、Hub / Node Agent、signed request、Desktop Client pairing、Hub-less Standaloneを追加した。

Phase 5Iの再確認ではmode別capabilityを変更せず、Standalone pairing tokenのstorage resolver bugだけを修正した。利用者向けの確定matrixはrepository root READMEに記載する。

外部fixtureを必要とするPostgreSQL実DBとTLS-terminating proxyのacceptanceは、default suiteの成功と区別して後述のrelease gateとして残す。これは実装上のblockerではないが、production release evidenceとして省略しない。

## Delivered Topologies

### Hub / Node Agent

- `hub`はcollectorを起動せず、明示storage、authenticated ingest/heartbeat、signed read API、maintenanceを組み立てる。
- `node-agent`はlocal DB/APIを持たず、collectionとheartbeatを逐次実行してHubへ送る。
- snapshot deliveryは同じraw bodyと`ingestId`を保ったbounded retryを行う。
- Hubは`node_id + ingest_id`で冪等化し、same bodyは`DUPLICATE`、different bodyは`409 ingest_id_conflict`とする。
- aggregate latestはACTIVE nodeを対象にDB-side keyset paginationを行い、freshness、partial、bounded errorsを返す。

### Hub-less Standalone

- periodic collection、local persistence、signed read API、Client pairing、maintenanceを1 processで提供する。
- productionの`SapphireAgentServer`はauth dependencyを必須とし、unsigned fallbackを持たない。
- Phase 4 payload regression用のunsigned route helperはproduction server assemblyから分離して残した。

### Desktop Client

- connection profileはendpoint、HTTP opt-in、credential path、optional PEM CA referenceを保持する。
- Ed25519 private keyはPreferencesではなくowner-only versioned JSONへ保存する。
- nonce取得、JWS署名、next nonce、`401 nonce_unavailable`だけの1回再取得をtransport層へ分離した。
- aggregate paginationを全page取得してnode/device identityでmergeし、partial/error/fresh/stale/unknownを表示する。
- HubとStandaloneのpairingをSettingsから行える。

## Security Contract

- Authorization: `CocoaDiskInfo-JWS <compact JWS>`。
- response next nonce: `CocoaDiskInfo-Next-Nonce` header。
- signing key: Ed25519、`kid`はRFC 7638 JWK thumbprint。
- signed body: 送信したraw UTF-8 JSON bytesのSHA-256をJWSとRFC 9530 `Content-Digest`の両方へbindする。
- request bodyの`Content-Encoding`は拒否し、実際に受信したbyte数へ上限を適用する。
- nonceはsubject/purpose-bound、短命、single-use、process-local bounded storeである。
- join/pairingは`joinKey = SHA-256(tokenSecret)`を使い、6 fieldをunsigned 32-bit big-endian length prefix付きでHMAC-SHA256へ入力する。DBはtoken plaintextを保存しない。
- Principal typeをrouteごとに分離し、disabled keyはnonce取得とsigned requestの両方で拒否する。
- credential fileはPOSIX 0400/0600またはWindows owner-only ACLを検証できない場合にfail closedとする。
- CocoaDiskInfoはCAやX.509 certificateを発行しない。TLSはALB/reverse proxyを含むdeployment側の責務で、HTTPは接続profileごとの明示opt-inを必要とする。

## Storage and Migration

- Phase 4 V1は変更せず、SQLite/PostgreSQLへV2とV3を追加した。
- V2は`ingest_id`、`received_at`、idempotency constraint、latest lookup index、Node registryを追加する。V1 rowは`ingest_id=snapshot_id`、`received_at=collect_time`でbackfillする。
- V3はpersistent Hub identity、Security Principal、bootstrap tokenを追加する。
- Hub/Standalone runtimeは自動migrationせず、listener/runtime pool作成前にcurrent schemaを検証する。
- raw retentionのstrict `collect_time < cutoff`、transaction後vacuum、mode-neutral periodic maintenanceを維持した。

## Operator Sequence

1. `db migrate`で対象storageをV3まで進める。
2. Hubではinternal listenと`publicEndpoint.baseUrl`を別々に設定し、`hub`を起動する。
3. `hub join-token create`で短命tokenを発行する。
4. `node-agent join`でowner-only credentialを作成し、`node-agent`を起動する。
5. `hub client-pairing-token create`、またはHub-lessでは`standalone client-pairing-token create`を実行し、Desktop Settingsからpairingする。
6. key recovery時は`--recovery-node-id`で既存history identityを維持し、旧keyを`hub principal disable --kid ...`または`standalone principal disable --kid ...`で無効化する。

bootstrap token secretは一度だけ表示される。通常ログ、TOML、Preferences、repositoryへ保存しない。完全なCLI/config例はrepository root READMEと`agent.example.toml`を参照する。

## Validation Evidence

成功:

```text
./gradlew :diskinfo-core:test \
  :diskinfo-agent:test \
  :diskinfo-client:desktopTest \
  :diskinfo-core:compileKotlin \
  :diskinfo-agent:compileKotlin \
  :diskinfo-client:compileKotlinDesktop

BUILD SUCCESSFUL
```

`--rerun-tasks`付きの最終実行では185 tests、0 failures、0 errors、2 skippedだった。skipは専用接続設定を要求する2件のPostgreSQL integration testだけである。

主要な追加evidence:

- RFC 8037/7638 vector、JWS substitution、canonical path/query、raw body digest、join HMAC。
- nonce TTL/capacity/concurrent consume、replay、wrong purpose/principal type、disabled principal。
- SQLite V1→V3 migration、idempotency/conflict/concurrency、registry/freshness、retention後missing snapshot。
- real CIO HTTP socketでNode join、owner-only credential、signed ingest、`STORED`→`DUPLICATE`。
- server boundaryでContent-Encoding、actual body size、unexpected exception非漏洩、cancellation伝播。
- Node Agentの同一body retry、HTTP opt-in、timeout、heartbeat、job non-overlap。
- Desktopのpairing、signed read、nonce recovery、pagination merge、legacy JSON/profile、permission/TLS policy。
- OpenAPI YAML parse、10 paths、local `$ref`解決。

## External Release Gates

このworkspaceでは専用fixtureが設定されていないため、次は未実行でありdefault suite成功へ含めない。

- `COCOADISKINFO_TEST_POSTGRESQL_JDBC_URL`等を使う実PostgreSQL migration/ingest/query/cleanup test。
- 実certificateを使うTLS-terminating reverse proxy/ALB-like topologyと、対象OS上のWindows ACL smoke test。

release前には空の専用PostgreSQL databaseと一時certificateを用意し、[Phase 5H task](tasks/phase-5h-acceptance-phase4-regression.md)のmatrixを実行してdatabase/version/proxy設定と結果を保存する。certificate verificationやhostname verificationを無効化するtest bypassは作らない。

## Deferred Follow-ups

- HA Hub時のshared atomic nonce backend（Redis等）。
- persistent Node delivery queueとoffline replay。
- mTLS、online key rotation、OS keychain/encrypted credential storeの運用確定。
- Desktop Clientのtyped DataStore候補、profile catalog、active profile選択、OS別default credential directory、pairing時のpath自動生成は[Phase 7A](../phase_0007/tasks/phase-7a-client-profile-credential-management.md)まで検討保留とする。Phase 5/6の完了条件にはしない。DataStore serializer、TinkとOS key protection、Desktop per-user配置、system-wideの`/var/lib/cocoadiskinfo/credentials/`をPhase 7開始時点のlibrary/platform状況で再評価する。
- package/systemd hardeningとproduction secret deliveryはPhase 7で扱う。
