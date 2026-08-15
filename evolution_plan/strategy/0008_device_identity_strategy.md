# Device Identity Strategy

## Summary
`deviceKey` は、API、DB、Client selectionで使うopaque stable device identityである。

`deviceKey` はdevice pathではない。`/dev/sda` や `/dev/nvme0n1` は差し替え、起動順、SATAポート変更、OS側の列挙順で変わり得るため、履歴identityには使わない。

Phase 3Cでは、正規化した `serialNumber` からUUIDv5で `deviceKey` を導出する。raw serialをURL pathや通常ログに直接出さないための措置であり、強い秘匿を目的にしたHMAC対応は後続で検討する。

## Terms
- `deviceKey`: API/DB/Client selection用のopaque stable identity。
- `path`: OS上の現在のdevice path。表示・診断用でありidentityではない。
- `serial`: smartctlなどのcollector sourceから得られるraw serial。表示・照合用であり、露出制御対象。
- `namespaceSalt`: UUIDv5 effective namespaceを導出するためのTOML設定値。secretではない。
- `DeviceKeyDeriver`: `serialNumber` などのcollector inputから `deviceKey` を導出する抽象境界。

## Current State
- `DiskSnapshot` は `deviceKey`, `path`, `serial` を別フィールドとして持つ。
- 現行converterはinjectされた`DeviceKeyDeriver`を使い、正規化したserialからUUIDv5 `deviceKey`を生成する。
- SQLite `disk_snapshot` は `device_key`, `device_serial_name`, `device_path` を別カラムとして保存している。
- History APIは `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots` を提供している。
- Clientはlatest APIで受け取った `deviceKey` を解釈せず、そのままHistory APIへ渡す。

## Phase 3C Decision
- Phase 3CではUUIDv5のみを実装する。
- Phase 3CではHMAC派生を実装しない。
- Phase 3Cではruntime scheme selectionを実装しない。
- 将来HMAC派生を追加できるよう、導出処理は `DeviceKeyDeriver` のような抽象に閉じ込める。
- UUIDv5の入力は正規化した `serialNumber` に固定する。
- `path`, `protocol`, `model`, ATA WWN, NVMe EUI/NGUIDはPhase 3Cのidentity sourceに含めない。
- 既存SQLite rowのmigration/backfillは行わない。
- blank serial、重複serial、不正コントローラ、ダミーデバイスは後続課題とする。

## UUIDv5 Derivation
Phase 3Cの導出は次の形にする。

```text
COCOADISKINFO_DEVICE_NAMESPACE = UUIDv5(NAMESPACE_DNS, "com.milkcocoa.info.sapphire.device")
effectiveNamespace = UUIDv5(COCOADISKINFO_DEVICE_NAMESPACE, namespaceSalt)
deviceKey = UUIDv5(effectiveNamespace, "serial:v1:${normalizedSerialNumber}")
```

`normalizedSerialNumber` はまずtrimした値を使う。大小文字や空白以外の正規化は、sample確認と互換影響を見て必要になった時点で追加する。

`namespaceSalt` の既定値は `"default"` とする。saltを変えると同じdiskでも `deviceKey` が変わるため、履歴identity変更として扱う。

## Configuration
TOML設定候補:

```toml
[deviceIdentity]
# Optional. Changing this changes derived device keys.
namespaceSalt = "default"
```

環境変数候補:

```text
COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT
```

Phase 3Cでは次を設定しない。

- runtime scheme selection
- HMAC-related secret settings
- raw namespace UUID

CLI flagは追加しない。

## API Implications
- API path shapeは維持する。
- `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots` は `(nodeId, deviceKey)` をhistory identityとして扱う。
- Phase 5 Hubのlatest queryも`(nodeId, deviceKey)`をidentityとして扱う。`deviceKey`をHub全体でglobal uniqueとは仮定しない。
- `deviceKey` はopaque stringとして扱う。Phase 3Cの実装値はUUID文字列だが、OpenAPI上でUUID formatに固定しない。
- Clientは `deviceKey` の中身を解釈しない。
- raw serialを直接指定していた手動API利用はPhase 3C後に互換ではない。

## DB Implications
- 新規snapshotの `device_key` はraw serialではなく導出済みopaque keyになる。
- `device_serial_name` と `snapshot_json.serial` にはraw serialが残る。
- Phase 3Cでは既存開発DBのmigration/backfillを行わない。
- `node_id`, `device_key`, `collect_time` のindexは維持する。

## Privacy Notes
UUIDv5はraw serialの偶発的露出を避けるための措置であり、強い秘匿ではない。

namespace saltとserial候補を知っている相手は再計算できる。より強いprivacyが必要な場合は、後続phaseでHMAC派生、secret管理、scheme selectionを検討する。

## Future Work
- HMAC-SHA256 derived `deviceKey`。
- secret管理とrotation方針。
- identity source selection。
- smartctl以外のcollector source。
- ATA WWN / NVMe EUI / NGUIDの採用判断。
- blank serial、重複serial、不正コントローラ、ダミーデバイスのcheck command。
- serial表示制御。

## Validation Direction
- default configでraw serialが `deviceKey` に出ない。
- same serial + same namespace saltで同じ `deviceKey` を返す。
- same serial + different namespace saltで違う `deviceKey` を返す。
- blank serialとblank namespace saltはvalidation errorにする。
- latest APIで返ったopaque `deviceKey` でHistory APIを取得できる。
