# Phase 8: Prometheus Export

## Source
- Master: `../master.md`

## Goal
Prometheus向けに現在値をexportする追加インターフェースを提供する。

## Scope
- `GET /metrics` を追加する。
- StandaloneまたはHubから現在値をexportする。
- READMEにPrometheus scrape例を追加する。
- metric nameとlabel方針を安定させる。

## Non-Goals
- PrometheusをCocoaDiskInfoの主履歴DBとして扱わない。
- serial numberなどの露出を無制御にしない。
- history queryをPrometheus依存にしない。

## Validation
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
```

## Tasks
- No active task document in this phase.
