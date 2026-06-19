# Agent / Collector 実装整理計画

## Summary
`diskinfo-agent` は同一バイナリのまま、起動モードを `standalone / agent / collector / oneshot / migrate` のサブコマンドへ整理する。概念上は Collector と Agent を分離し、`standalone` では同一プロセス内で `Collector -> LocalSink -> Repository(SQLite)` として動かす。`collector` は当面インメモリ送信のみ、`agent/standalone` はSQLiteを永続化層にする。

## Key Changes
- CLIを Clikt サブコマンド構成へ移行する。
    - `standalone`: ローカル収集、SQLite保存、API公開。
    - `agent`: 外部collectorからingest、SQLite保存、API公開。
    - `collector`: ローカル収集、remote agentへHTTP送信。
    - `oneshot`: 現在の即時収集表示用途を維持。
    - `migrate`: 現在のDB作成用途を維持。
- 収集処理を `SapphireExecutor.Oneshot` から切り出す。
    - `Collector` interface: 指定デバイスまたはscanから `DiskSnapshot` を生成する。
    - 実装は既存 `SmartCtlCommand` と `toDiskSnapshot()` を使う `SmartctlCollector`。
    - CollectorはDB、Ktor、Colotok永続化providerを知らない。
- 保存経路を Colotok provider 依存からRepository中心へ寄せる。
    - `SnapshotRepository` を追加し、`DiskSnapshotTable` へのinsert/latest queryを集約する。
    - `DataStoreColotokProvider` は残す場合もRepositoryを呼ぶ薄い互換層にする。
    - `LocalSnapshotSink` はRepositoryへ直接保存する。
    - `HttpSnapshotSink` はremote agentのingest APIへPOSTする。
- Agent APIを実体化する。
    - Collector向け: `POST /api/v1/snapshots` で `DiskSnapshot` または受信用DTOをingest。
    - Client向け: `GET /api/v1/snapshots/latest?source=history|live&device=...` を既存戦略どおり実装。
    - `agent` modeでは `source=live` は原則無効または明示エラーにする。live取得はローカルCollectorを持つ `standalone` の責務。
- collector modeには最小の `SnapshotBuffer` を導入する。
    - 初期実装は `InMemorySnapshotBuffer`。
    - 送信失敗時はメモリ上でretry対象に残すが、プロセス終了で失われる前提。
    - 将来 `SqliteSnapshotBuffer` を追加できるinterfaceに留める。

## Implementation Order
1. `SmartctlCollector`, `SnapshotSink`, `SnapshotRepository` を追加し、既存oneshot/agent内の収集・保存処理を移す。
2. `SapphireAgentServer` を実装し、Ktor設定とroutingを `SapphireExecutor` から分離する。
3. CLIをサブコマンドへ整理し、各モードを新しいExecutorへ接続する。
4. `standalone` を `collector loop + local sink + API server` として組み立てる。
5. `agent` を `API server + repository` のみにし、ローカル収集を持たせない。
6. `collector` を `collector loop + in-memory buffer + http sink` として実装する。
7. 既存 `--agent/--oneshot/--migration` flags は今回の計画では残さず、README/strategy側もサブコマンド前提へ更新する。

## Test Plan
- `oneshot` が既存どおり単体device/scanで `DiskSnapshot` を出力できる。
- `standalone` が周期収集し、SQLiteへ保存し、latest APIでhistoryを返す。
- `agent` がHTTP ingestされたsnapshotをSQLiteへ保存し、latest APIで返す。
- `collector` がremote agentへsnapshotをPOSTし、成功時にbufferから削除する。
- agent停止時のcollector送信失敗で、プロセス稼働中はretry対象が残る。
- `source=history/live`、不正query、履歴なし、smartctl失敗のエラー応答を確認する。
- `./gradlew test` と `./gradlew :diskinfo-agent:build` を通す。smartctl依存部分は可能ならfake collector/fake commandで単体テスト化する。

## Assumptions
- 初期DBはSQLite固定。Postgres対応はRepository interfaceの後続実装で扱う。
- collector modeの永続retry queueは初期実装しない。
- standaloneのローカル投入はHTTP自己呼び出しではなくRepository直接保存にする。
- 認証、collector登録、heartbeat、multi-host集約メタデータは後続フェーズに分ける。
- 既存作業ツリーには未コミット変更があるため、実装時はユーザー変更を巻き戻さず差分を重ねる。
