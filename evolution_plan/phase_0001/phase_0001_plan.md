# Phase 1: Internal Boundaries

## Source
- Master: `../master.md`
- Supporting strategy: `../strategy/0004_future_architecture_mode.md`

## Goal
機能追加の前に、`収集`, `保存`, `公開`, `出力` の境界を分ける。

## Scope
- `SmartctlCollector` を追加または維持し、`DiskSnapshot` 生成に責務を寄せる。
- `SnapshotSink` を追加または維持し、stdout、local repository、remote HTTP の出力先を差し替え可能にする。
- `SnapshotRepository.insert(...)` とlatest/history queryの入口をRepository側へ寄せる。
- `SapphireAgentServer` をExecutorから切り離し、HTTP公開の責務をServer側へ閉じる。
- 既存Oneshot/Agent相当の挙動をできるだけ変えず、内部境界を整理する。

## Non-Goals
- CLI形状の大幅変更はPhase 2で扱う。
- Hub/Node Agent、DB backend追加、Prometheus、packagingは扱わない。

## Boundary Decision
- Collector: smartctl実行から `DiskSnapshot` を生成する。
- Sink: snapshotの出力先を扱う。
- Repository: SQLiteなどの永続化差分を閉じ込める。
- Server: HTTP APIのroutingと起動設定を扱う。

## Validation
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-agent:test
```

## Tasks
- No active task document in this phase.

## Implementation Status
- Done: `DiskSnapshotCollector` / `SmartctlCollector` で収集責務を分離した。
- Done: `SnapshotSink` でconsole出力とRepository保存の出力先を分離した。
- Done: `DiskSnapshotRepository.insert(...)` とlatest queryをRepository側へ寄せた。
- Done: `SapphireAgentServer` をExecutorから分離し、HTTP API公開の責務をServer側へ閉じた。
- Done: `Colotok.forceShutdown()` はproduction runtimeのprocess lifecycle側に閉じ込めた。
