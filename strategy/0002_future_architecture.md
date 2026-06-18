## Master-Agent / Collector 集約アーキテクチャ計画（Cache-First）

### Summary
将来構成として、**Master Agent が複数 Collector を集約して Client に返す**方針は妥当。  
設計は以下で固定する。

- デフォルトは **Collector履歴キャッシュ返却（cache-first）**
- Collector は **Masterへ自己登録 + heartbeat**
- 一部Collector障害時は **部分成功で返却**
- 各Collector結果に **鮮度メタデータ** を付与
- 障害Collectorは **前回キャッシュを返しつつ stale と error を明示**（部分成功方針と整合）

### Key Changes
- 役割分離:
    - `Collector Agent`: ローカルSMART収集・ローカル履歴保持・Master送信
    - `Master Agent`: Collector管理、集約キャッシュ保持、Client API提供
- Masterの内部モデル（最小）:
    - `CollectorRegistry`: collectorId, endpoint, status, lastHeartbeatAt
    - `CollectorCache`: collectorId + deviceKey ごとの latest snapshot と受信時刻
    - `CollectorErrorState`: collectorId, lastError, lastFailureAt
- 返却ロジック:
    - デフォルト `source=cache`（省略時）
    - 将来拡張で `source=live` を許可する場合は Collector へ同期問い合わせ（初期は未実装でも可）
    - 集約時に失敗Collectorは stale cache を返し、`errors[]` に理由を格納
- APIレスポンス（集約）に鮮度情報を追加:
    - `collectedAt`（Collector採取時刻）
    - `receivedAt`（Master受信時刻）
    - `ageMs`
    - `stale`（閾値超過 or 取得失敗でキャッシュ返却）
- Collector参加:
    - `POST /collectors/register`
    - `POST /collectors/heartbeat`
    - 将来のサービスディスカバリ連携を見据え、`collectorId` と `capabilities` を登録payloadに含める

### Public Interfaces
- Client向け（Master）:
    - `GET /api/v1/snapshots/latest`（全Collector集約）
    - `GET /api/v1/snapshots/latest?collectorId=...`（単一Collector）
- Collector向け（Master）:
    - `POST /api/v1/collectors/register`
    - `POST /api/v1/collectors/heartbeat`
    - （push型採用時）`POST /api/v1/collectors/snapshots`
- 応答スキーマ方針:
    - `data[]`（collectorごとのsnapshot+freshness）
    - `errors[]`（collector単位の失敗情報）
    - `partial: true|false`

### Test Plan
- 正常系:
    1. 複数Collector登録後、集約APIで全件返却
    2. 鮮度メタデータが各Collectorに付与される
- 障害系:
    1. 1台停止時、APIは`partial=true`で成功、`errors[]`に停止Collector
    2. 停止Collectorは前回キャッシュがあれば`stale=true`で返る
    3. キャッシュなし停止Collectorは`data`欠落 + `errors[]`のみ
- 回帰:
    - 単体Agent（非集約）モードの既存Oneshot/Agent/Migration挙動は不変

### Assumptions
- 初期は **cache-first 固定** で運用し、live同期問い合わせは後段追加。
- stale判定閾値は暫定で「Collector収集周期の2倍」。
- 認証/認可は後続フェーズで導入（初期は閉域/ローカル運用前提）。
