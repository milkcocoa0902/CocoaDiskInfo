## Agent API返却戦略（History既定 + Live選択）

### Summary
API返却は **履歴の最新値をデフォルト** とし、必要時のみ **query指定でリアルタイム取得** に切り替える。  
これにより、Observability用途で重要な安定応答（低遅延・低負荷）を維持しつつ、診断時の即時再取得も提供する。

### Key Changes
- Agent APIの返却ポリシーを明文化:
    - 既定: `source=history`（省略時）
    - 明示: `source=live` で smartctl を即時実行して返却
- 単一エンドポイント方式を採用（queryで切替）。
    - 例: `GET /api/v1/snapshots/latest?source=history|live`
    - `device` 指定あり/なし（単体 or scan）は既存収集ロジックのパターンを流用
- `history` 実装:
    - SQLiteの `disk_snapshot` から対象デバイスの最新レコードを返す
    - 履歴未収集時は `404`（または規約化した空レスポンス）を返す
- `live` 実装:
    - smartctlで再取得して `DiskSnapshot` を返す
    - `Agent` モードでは `DataStoreColotokProvider` が有効なため、live結果は保存される（追加設定不要）
- エラー規約（最低限）:
    - 不正query値: `400`
    - smartctl失敗: `502` or `500`（統一方針を固定）
    - 履歴なし: `404`

### Public Interfaces
- 追加API（例）:
    - `GET /api/v1/snapshots/latest`
    - Query:
        - `source`: `history` (default) | `live`
        - `device`: 任意（未指定時はscan相当、指定時は単体取得）
- 応答フォーマットは既存 `DiskSnapshot` ベースを維持（JSON）。

### Test Plan
- `source` 切替:
    1. `source` 省略で history が返る
    2. `source=history` で history が返る
    3. `source=live` で再取得結果が返る
    4. `source=foo` は `400`
- データ有無:
    1. 履歴ありで最新値返却
    2. 履歴なしで `404`
- 負荷/挙動:
    1. history は smartctl を起動しない
    2. live は smartctl を起動する
- 回帰:
    - 既存の Agent定期収集、Oneshot、Migration のCLI制約は不変

### Assumptions
- Agentは常駐で履歴収集しており、通常参照は history が主用途。
- live取得は高頻度利用を想定せず、診断/緊急確認用途。
- API実装時は認証/認可が未導入ならローカル利用前提で開始し、後続で追加可能とする。
