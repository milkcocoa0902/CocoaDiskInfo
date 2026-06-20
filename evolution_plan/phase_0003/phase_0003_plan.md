# Phase 3: History API and Client History

## Source
- Master: `../master.md`
- Supporting strategy: `../strategy/0001_api_response_strategy.md`
- Supporting strategy: `../strategy/0005_device_history_ui_api_strategy.md`
- Supporting strategy: `../strategy/0008_device_identity_strategy.md`

## Goal
現在状態APIを維持しながら、node/device単位のbounded history APIとClientのHistory表示を追加する。

## Scope
- `GET /api/v1/snapshots/latest` はcurrent-state向けに維持する。
- `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots` を追加する。
- `deviceKey` はpathではなくopaque stable device identityとして扱う。`nodeId` と `deviceKey` の組で履歴対象を一意にする。
- `limit`, `from`, `to`, `order` を持つbounded queryにする。
- Clientはnode grouped device listからdeviceを選ぶ。device選択により `selectedNodeId` と `selectedDeviceKey` が確定し、History tabでその組を使う。
- Client detail paneに `Current | History` の切替を追加する。
- 初期History UIはsummary tilesとtimelineを優先する。
- Phase 4へ進む前に、Phase 3Cで `deviceKey` をUUIDv5 derived opaque keyへ移行する。UUIDv5 namespace saltはTOMLで設定可能にし、HMAC派生は後続拡張として抽象境界だけ残す。

## Non-Goals
- 初期実装で複雑なグラフを作り込まない。
- unbounded history responseを許可しない。
- Hub集約のstale/partial metadataはPhase 5で扱う。

## Validation
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-client:compileKotlinDesktop
```

## Tasks
- [Phase 3A: Bounded History API](tasks/phase-3a-bounded-history-api.md)
- [Phase 3B: Client History View](tasks/phase-3b-client-history-view.md)
- [Phase 3C: Device Key Identity](tasks/phase-3c-device-key-identity.md)

## Implementation Status
- Phase 3A done: bounded history payload、Repository query、Server route/query validation、OpenAPI、Repository/route testsを追加した。
- Phase 3B done: Client history API method、`Current | History` tab、history loading/error/empty state、summary/timeline UIを追加した。
- Phase 3C planned: `deviceKey` をserial-based UUIDv5 derived opaque keyとして再定義し、TOMLのnamespace salt設定と将来HMAC派生に備えた導出抽象を追加する。既存開発DBのmigration/backfillは扱わない。
