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
- `limit`, `from`, `to`, `order` を持つbounded queryにする。
- Client detail paneに `Current | History` の切替を追加する。
- 初期History UIはsummary tilesとtimelineを優先する。

## Non-Goals
- 初期実装で複雑なグラフを作り込まない。
- unbounded history responseを許可しない。
- Hub集約のstale/partial metadataはPhase 5で扱う。

## Validation
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
```

## Tasks
- No active task document in this phase.
