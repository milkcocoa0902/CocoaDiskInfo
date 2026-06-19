# Execution Mode and Internal Boundary Strategy

## Summary
この文書は、Phase 1/2で整理した `diskinfo-agent` の実行モードと内部境界を記録し、後続のNode Agent / Hub実装時に同じ責務分離を保つための補助strategyである。

現在の実装は、旧flag中心ではなくCliktサブコマンド中心である。

```text
cocoadiskinfo-agent oneshot --scan
cocoadiskinfo-agent standalone --scan
cocoadiskinfo-agent db migrate
```

旧mode flagsである `--oneshot`, `--agent`, `--migration` はサポートしない。

## Current Execution Modes
### Oneshot
- 1回だけ収集してconsoleへ出力する。
- 既定ではDBへ接続しない。
- `--persist` または `runtime.persist=true` の場合だけRepository保存を追加する。
- HTTP APIは起動しない。

### Standalone
- 1プロセス内でローカル収集、SQLite保存、HTTP API公開を行う。
- 小規模運用と開発時の基準モードである。
- `Collector -> Sink -> Repository(SQLite)` と `Server` をruntime組み立て層で接続する。
- API listen host/portはconfig/env/CLIから解決し、既定hostは `127.0.0.1` とする。

### DB Commands
- `db migrate` はschema operationだけを担当する。
- 収集、HTTP API、snapshot output設定に巻き込まない。
- 将来の `db cleanup`、`db vacuum` も同じDB command familyに置く。

### Future Node Agent
- 各マシンでローカル収集し、Hubへsnapshotを送る。
- 初期実装では送信失敗時の永続retry queueを必須にしない。
- 将来 `SqliteSnapshotBuffer` を追加できるよう、buffer interfaceだけ意識する。
- Node AgentはHub APIを提供しない。

### Future Hub / Master
- Node Agentからsnapshotを受け取り、保存し、Client向けAPIを提供する。
- Hub自身はローカルsmartctl収集を持たない。
- 一部Node Agentが停止しても、前回キャッシュとstale/error情報で部分成功を返す。

## Boundary Decisions
- `Collector`: `smartctl` 実行から `DiskSnapshot` を生成する。DB、Ktor、Colotok、HTTP送信を知らない。
- `Sink`: snapshotの出力先を扱う。console、Repository、将来remote HTTPなどを差し替える。
- `Repository`: SQLiteなどの永続化差分を閉じ込める。
- `Server`: HTTP APIのroutingと起動設定を扱う。
- CLI/runtime assembly: 実行モード、config/env/CLI merge、DB接続、server起動、`Colotok.forceShutdown()` を扱う。

## Implemented State
- `DiskSnapshotCollector` / `SmartctlCollector` に収集責務を分離済み。
- `SnapshotSink` にconsole出力とRepository保存を分離済み。
- `DiskSnapshotRepository` がinsert/latest queryを担当済み。
- `SapphireAgentServer` はExecutorから分離済み。
- `SapphireCommandRuntime` にproduction side effectsを閉じ込め、CLI parse/assemblyをテスト可能にした。
- `AgentConfigResolver` がcommand scope別のeffective configを返す。

## Deferred Scope
- `node-agent` subcommand。
- `hub` subcommand。
- ingest API。
- remote HTTP sink。
- retry buffer。
- `db cleanup` とretention enforcement。
- authentication/authorization。

## Test Plan
- CLI parse/assembly:
    - root helpに `oneshot`, `standalone`, `db` が出る。
    - `oneshot --help`, `standalone --help`, `db migrate --help` が成功する。
    - 旧mode flagsは拒否される。
- Oneshot:
    - `oneshot --scan` がDB接続なしで実行設定を作る。
    - `oneshot --persist` だけがRepository保存を追加する。
- Standalone:
    - scan/device target、interval、host、port、dbUrl、output modeを解決する。
    - invalid interval/port/targetはCLI/config解決時に失敗する。
- DB commands:
    - `db migrate` はstorage設定だけを見る。
    - 未使用のsmartctl/runtime/http/output設定は `db migrate` を失敗させない。

## Notes
- `agent` という言葉は利用者向けの総称としてREADMEやUIに残ってよいが、実行モード名としては `standalone`, `node-agent`, `hub`, `db ...` を使う。
- `collector` は収集責務の概念として使う。中央集約側は `Hub` または `Master` と呼ぶ。
