---
sessionId: session-260524-115333-ri43
---

# Requirements

### 概要と目的
`CocoaDiskInfo` の `sapphire.db` の肥大化を管理するため、データベースクリーンアップ戦略を実装します。エージェントモードで実行すると、ディスクスナップショットが1分ごとに収集されるため、時間の経過とともにデータベースが大幅に増大する可能性があります。

### スコープ
- **スコープ内**:
    - 設定された保持期間より古いディスクスナップショットレコードの自動削除。
    - エージェント実行中の定期的なクリーンアップの実行。
    - CLIを介した手動クリーンアップコマンド。
    - 戦略のドキュメント化。
- **スコープ外**:
    - データのダウンサンプリング（古いデータの解像度を下げるなど）。
    - 古いレコードの圧縮。
    - 外部ストレージへのデータ移動。

### 機能要件
- エージェントはデフォルトで30日より古いレコードを自動的に削除する必要があります。
- ユーザーはCLIオプション（例: `--retention-days 7`）を介して保持期間を指定できます。
- クリーンアップはエージェントの起動時と、その後24時間ごとに実行される必要があります。
- 手動クリーンアップコマンドが利用可能である必要があります。

# Technical Design

### 現状の実装
- `DiskSnapshotTable` は `collect_time`（タイムゾーン付きタイムスタンプ）とともにスナップショットを保存します。
- `SapphireExecutor.Agent` は60秒ごとに収集ループを実行します。
- データはローカルのSQLiteデータベース（`sapphire.db`）に保存されます。

### 主要な決定事項
- **保持期間**: ユーザーのフィードバックに基づき、デフォルトを30日に設定します。
- **クリーンアップのトリガー**: 手動メンテを避けるため、エージェントモードのバックグラウンドタスクとして定期実行します。
- **メカニズム**: TTLベースのクリーンアップにはSQLの `DELETE` を使用し、ディスクスペースを回収するために `VACUUM` を実行します。

### 変更内容
- **新規戦略ドキュメント**: `strategy/0003_db_cleanup_strategy.md` を作成し、日本語で内容を記述します。
- **新規クラス**: `DatabaseCleaner` (`com.milkcocoa.info.sapphire.agent.datastore` パッケージ)。
    - `cleanup(retentionDays: Int)`: `DiskSnapshotTable` からレコードを削除します。
    - `vacuum()`: SQLiteの `VACUUM` コマンドを実行します。
- **CLIの変更**:
    - `SapphireAgent` に `--retention-days` を追加。
    - `executionMode` に `ExecutionMode.Cleanup` を追加。
- **Executorの変更**:
    - `SapphireExecutor.Agent` はクリーンアップコルーチンを開始します。
    - `SapphireExecutor.Cleanup` は手動クリーンアップを実装します。

### ファイル構成
- `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/datastore/DatabaseCleaner.kt` (新規)
- `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/Main.kt` (修正)
- `diskinfo-agent/src/main/kotlin/com/milkcocoa/info/sapphire/agent/exec/SapphireExecutor.kt` (修正)
- `strategy/0003_db_cleanup_strategy.md` (新規)

# Testing

### 検証アプローチ
- **手動テスト**: 非常に短い保持期間（例: 0日）でエージェントを実行し、既存のレコードが削除されることを確認します。
- **手動テスト**: 手動クリーンアップコマンドを実行し、データベースのサイズ/レコード数を確認します。

### 主要シナリオ
- **エージェント起動**: エージェントの開始直後にクリーンアップが実行されること。
- **長時間稼働**: 24時間の稼働後に再びクリーンアップが実行されること。
- **手動実行**: `java -jar diskinfo-agent.jar --cleanup --retention-days 1` で1日より古いレコードが削除されること。

# Delivery Steps

###   Step 1: DBクリーンアップ戦略ドキュメントの作成
`strategy/0003_db_cleanup_strategy.md` を作成し、保持ポリシーとクリーンアップメカニズムを日本語で定義します。
- デフォルト保持期間（30日）を定義。
- クリーンアップのトリガー（起動時および24時間ごと）を指定。
- 設定用のCLIオプションをドキュメント化。

###   Step 2: DatabaseCleaner ロジックの実装
新しい `DatabaseCleaner` クラスにコアとなるクリーンアップロジックを実装します。
- `DatabaseCleaner.kt` に `cleanup(retentionDays: Int)` 関数を追加。
- `DiskSnapshotTable.deleteWhere` を使用して、指定されたしきい値より古いレコードを削除。
- スペースを再利用するための基本的な `vacuum()` 関数を実装。

###   Step 3: CLIとAgent Executorへのクリーンアップの統合
`SapphireAgent` CLIと `SapphireExecutor` を更新し、クリーンアップをサポートします。
- `SapphireAgent` コマンドに `--retention-days` オプションを追加。
- 手動クリーンアップタスク用に `ExecutionMode.Cleanup` と `SapphireExecutor.Cleanup` を追加。
- `SapphireExecutor.Agent` を変更して、`DatabaseCleaner.cleanup()` を定期的に実行するバックグラウンドコルーチンを起動。