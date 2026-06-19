# Phase 5: Hub and Node Agent

## Source
- Master: `../master.md`
- Supporting strategy: `../strategy/0002_future_architecture.md`

## Goal
複数Node Agentからsnapshotを受け取り、Hub/Masterがcache-firstの集約APIを提供する。

## Scope
- ingest APIを追加する。
- Node AgentからHubへsnapshotを送信する。
- Hubはcache-firstで集約APIを返す。
- stale、partial、error metadataをAPIに入れる。
- 中央側の呼称は `Hub` または `Master` に寄せ、`collector` と呼ばない。

## Non-Goals
- 初期実装で高可用Hubや分散DBを作らない。
- Node Agentの永続retry queueを初期必須にしない。
- `source=live` の同期問い合わせを初期必須にしない。

## Validation
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
```

## Tasks
- No active task document in this phase.
