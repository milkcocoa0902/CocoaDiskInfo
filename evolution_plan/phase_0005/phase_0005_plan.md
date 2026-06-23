# Phase 5: Hub and Node Agent

## Source
- Master: `../master.md`
- Supporting strategy: `../strategy/0002_future_architecture.md`

## Goal
複数Node Agentからsnapshotを受け取り、Hub/Masterがcache-firstの集約APIを提供する。

## Scope
- ingest APIを追加する。
- Node AgentからHubへsnapshotを送信する。
- HubとNode AgentはmTLSで相互認証する。
- 初期分散構成ではHubをCA/信頼anchorとして扱う。
- kubeadmのCP/Node参加モデルを参考に、Hub側でjoin tokenを発行し、Node Agent側で指定するbootstrap flowを設計する。
- Node Agentと将来ClientのPrincipalを分ける。Client principalは「公式Clientかどうか」ではなく「SMART情報を読ませてよい相手かどうか」を表す。
- Hub-lessなClient-to-Agent直接接続は単一ノード向け構成として残す。このときAgentはsmartctl実行者であると同時にlocal Hub roleを内包し、ClientはAgentへ直接接続する。
- Hub-less構成でもClient-to-Agent認証は必要であり、認証なしの例外扱いにはしない。Client read API認証はclient certificateによるmTLS Principal抽出を第一候補にする。
- Hubはcache-firstで集約APIを返す。
- stale、partial、error metadataをAPIに入れる。
- 中央側の呼称は `Hub` または `Master` に寄せ、`collector` と呼ばない。

## Non-Goals
- 初期実装で高可用Hubや分散DBを作らない。
- Node Agentの永続retry queueを初期必須にしない。
- `source=live` の同期問い合わせを初期必須にしない。
- CRL、OCSP、証明書失効運用をPhase 5の初期実装必須にしない。
- Client-to-Hub/Client-to-Agent認証を、Hub/Node Agent ingest認証と同じPhase 5内の別stepとして扱う。
- 単一ノード利用者にHubとNode Agentの両方を必須にしない。

## Validation
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
```

## Tasks
- [Hub/Agent Authentication and Principals](tasks/phase-5-auth-principals-mtls.md)
