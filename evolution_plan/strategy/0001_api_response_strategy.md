# API Response Strategy

## Summary
APIの通常応答は、smartctlを同期実行するlive取得ではなく、Repositoryに保存された最新履歴またはcacheを返す方針にする。

これはStandaloneでも将来のHubでも同じである。ClientやPrometheusが読む通常経路は低遅延・bounded responseを優先し、live取得は明示的な診断操作として後続で扱う。

## Current State
現在のStandalone APIは次を提供する。

```text
GET /api/v1/snapshots/latest
GET /api/v1/devices/{deviceKey}/snapshots/latest
```

- `GET /api/v1/snapshots/latest` はRepositoryのlatest rowsから `LatestSnapshotsPayload` を返す。
- `GET /api/v1/devices/{deviceKey}/snapshots/latest` はRepositoryから対象deviceのlatest snapshotを返す。
- 現時点では `source=live` queryは実装しない。
- 履歴がないdeviceは `404 snapshot_not_found` とする。

## Direction
- current-state APIはhistory/cache-firstとする。
- Phase 5完了時のHub/Standalone read APIはtransport schemeに関係なくnonce-bound JWS `CLIENT` Principalを要求する。
- HTTPSの終端とcertificate lifecycleはdeployment responsibilityとする。ClientがHTTP endpointを使う場合は明示的なinsecure transport opt-inを要求する。
- unbounded responseは禁止する。
- live取得を追加する場合は、通常APIの既定挙動を変えず、明示的なqueryまたは別endpointで扱う。
- `oneshot` はCLI診断経路であり、APIの通常応答とは分ける。
- Hub導入後は、一部Node Agentが停止してもpartial/stale/error metadataで説明できる応答にする。

## Candidate Future Interfaces
現在状態:

```text
GET /api/v1/snapshots/latest
GET /api/v1/devices/{deviceKey}/snapshots/latest
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots/latest
```

node-scoped latestはPhase 5 Hub向けに追加する。device-only latestはStandalone compatibility routeとして維持し、複数Nodeを持つHub route setには含めない初期方針とする。

Hub aggregate latestはdevice row単位のkeyset paginationを持つ。`limit`はdefault 100、maximum 500とし、`nodeId ASC, deviceKey ASC`のstable order、opaque `nextCursor`、`hasMore`を返す。各pageは既存の`nodes[].devices[]`へ再groupするため、同じNodeが複数pageへ現れ得る。

履歴:

```text
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots
```

履歴APIは `nodeId` と `deviceKey` の組で対象を指定する。Standaloneではnodeが実質1つなので冗長に見えるが、Hub構成では複数Node Agentに同じdevice keyが存在し得るため、device key単体では履歴対象を一意にできない。

`deviceKey` はpathではなくopaque stable device identityとして扱う。Phase 3Cではserial-based UUIDv5 derived keyへ移行する。API利用者は `deviceKey` の中身を解釈せず、latest APIで受け取った値をhistory APIへ渡す。詳細は `0008_device_identity_strategy.md` に従う。

将来の明示live取得候補:

```text
GET /api/v1/snapshots/latest?source=live
GET /api/v1/devices/{deviceKey}/snapshots/latest?source=live
```

ただし、`source=live` はStandaloneにだけ自然に実装できる。Hubでlive同期問い合わせを許可するかはPhase 5以降で判断する。

## Error Policy
- blank `deviceKey`: `400`
- unknown `deviceKey`: `404`
- invalid query value: `400`
- smartctl失敗をlive取得で返す場合: `502` を第一候補にする
- history/cache-firstで部分失敗を返す場合: `200` + `partial=true` + `errors[]` をHub方針で検討する

## Test Plan
- latest APIがsmartctlを起動せずRepositoryから返すこと。
- 履歴なしdeviceは `404` を返すこと。
- invalid queryは `400` を返すこと。
- Hub導入時はpartial/stale/error metadataを含む部分成功を確認すること。
- 同じdevice keyを持つ複数Nodeでもnode-scoped latest/historyが混ざらないこと。
- signed readでmethod/path/query/purpose/nonceを検証し、unsigned/replayed/wrong-principal requestを拒否すること。
- aggregate latestがdevice row単位のkeyset paginationでboundedになり、全履歴や全deviceを1 responseへmaterializeしないこと。

## Follow-up Items
- Phase 5のStandalone/Hubはcache-firstに固定する。Standaloneのlive取得を追加する場合に`source=live` queryと別endpointのどちらを使うかは後続で決める。
- Hubのlive同期問い合わせはPhase 5 scope外とし、Node Agent timeout、fan-out負荷、partial response contractを設計できる段階まで延期する。
- device-only latest routeはStandaloneだけに登録し、Hubではnode-scoped latestを使う。
