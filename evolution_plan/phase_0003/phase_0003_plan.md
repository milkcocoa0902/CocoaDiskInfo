# Phase 3: History API and Client History

## Source
- Master: `../master.md`
- Supporting strategy: `../strategy/0001_api_response_strategy.md`
- Supporting strategy: `../strategy/0005_device_history_ui_api_strategy.md`

## Goal
現在状態APIを維持しながら、node/device単位のbounded history APIとClientのHistory表示を追加する。

## Scope
- `GET /api/v1/snapshots/latest` はcurrent-state向けに維持する。
- `GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots` を追加する。
- `nodeId` と `deviceKey` の組で履歴対象を一意にする。Hub化後は複数Node Agent間で `/dev/sda` や `nvme0n1` 相当のdevice keyが競合し得る。
- `limit`, `from`, `to`, `order` を持つbounded queryにする。
- Clientはnode grouped device listからdeviceを選ぶ。device選択により `selectedNodeId` と `selectedDeviceKey` が確定し、History tabでその組を使う。
- Client detail paneに `Current | History` の切替を追加する。
- 初期History UIはsummary tilesとtimelineを優先する。

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

## Implementation Status
- Phase 3A done: bounded history payload、Repository query、Server route/query validation、OpenAPI、Repository/route testsを追加した。
- Phase 3B pending: Client history tab、history API client、summary/timeline UIは未着手。
