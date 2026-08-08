# DB Cleanup Strategy

## Summary
DB cleanupは、SQLiteを中心にしたローカル履歴の肥大化を防ぐためのstorage maintenance機能として扱う。

この文書は初期案の `--cleanup` / `--retention-days` / `ExecutionMode.Cleanup` 前提を置き換える。現在のCLI方針では、cleanupは `db cleanup` サブコマンドと `standalone` / `hub` の定期maintenanceに寄せる。

詳細なデータ種別ごとの保持期間は `0007_db_data_lifetime_policy.md` に従う。

## Scope
- `disk_snapshot` のraw snapshot retention。
- 起動時cleanupと定期cleanup。
- 手動 `db cleanup`。
- dry-run。
- SQLiteの `VACUUM` を含むDB backend別maintenance。

## Non-Goals
- 初期実装で集約履歴、device inventory、event tableを同時に作らない。
- raw snapshotを年単位保存するために保持期間だけを伸ばさない。
- cleanupをOS cronだけに依存させない。
- migrationと破壊的cleanupを同じ操作として扱わない。

## Command Direction
将来のCLIは次の形に寄せる。

```text
cocoadiskinfo-agent db cleanup
cocoadiskinfo-agent db cleanup --raw-snapshot-days 14
cocoadiskinfo-agent db cleanup --dry-run
cocoadiskinfo-agent db cleanup --vacuum
```

`db cleanup` はschema operationではなくdata maintenanceである。`db migrate` と同じDB command familyに置くが、実行目的とvalidationは分ける。

## Runtime Direction
- `standalone` は起動時と定期実行でcleanupしてよい。
- `hub` は導入後、同じstorage maintenance方針に従う。
- `oneshot` は自動cleanupしない。
- `node-agent` はローカル永続bufferを持つ場合だけ、そのbuffer cleanupを行う。

## Configuration Direction
初期候補:

```toml
[retention]
rawSnapshotDays = 30

[maintenance]
cleanupOnStartup = true
cleanupIntervalHours = 24
vacuumAfterCleanup = false
```

Phase 2Bのconfig contractにはまだ含めない。retention/maintenanceを実装するPhaseでは、unknown key拒否方針とexample TOMLを同時に更新する。

Phase 4では分割して実装する。Phase 4Dは手動`db cleanup`に必要な`retention.rawSnapshotDays`と`maintenance.vacuumAfterCleanup`を先に追加し、`maintenance.cleanupOnStartup`と`maintenance.cleanupIntervalHours`はPhase 4Fのstandalone自動cleanupで追加する。

## Storage Design
- cleanupロジックはRepositoryまたはStorage Maintenance層に置く。
- 削除条件は `collect_time` に基づく。
- SQLiteでは必要に応じて `VACUUM` を提供する。
- PostgreSQL/MySQLではVACUUM相当の意味が異なるため、DB backendごとの差分をstorage層に閉じ込める。
- dry-runでは削除対象件数、対象期間、対象テーブル、vacuum予定を表示する。
- Phase 4C以降、DB操作のtransaction開始はUseCase相当層で扱う。Storage Maintenance repositoryはcount/deleteなどのDB操作だけを持ち、SQLite `VACUUM`はtransaction外で実行する。

## Validation Plan
- 一時SQLite DBに複数時点のsnapshotを入れ、retention境界より古い行だけ削除されること。
- `--dry-run` では削除されないこと。
- `db cleanup` はsmartctl target、HTTP、output設定を要求しないこと。
- cleanup結果が運用ログに出ること。
- schema migrationとcleanupを混ぜないこと。

## Open Questions
- `rawSnapshotDays` のallowed rangeを `1..365` とするか、0日をテスト専用に許可するか。
- `vacuumAfterCleanup` を自動実行可能にするか、常に明示 `--vacuum` にするか。
