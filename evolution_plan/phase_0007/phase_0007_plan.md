# Phase 7: Packaging and Operations

## Source
- Master: `../master.md`
- Client credential handoff: `../phase_0005/phase_0005_handoff.md`

## Goal
サブコマンド、設定ファイル、DB配置、systemd unit、ログ方針が安定した後に、運用配置とパッケージングを固定する。

## Scope
- systemd unitをサブコマンド/設定ファイル前提へ更新する。
- `/etc/cocoadiskinfo`, `/var/lib/cocoadiskinfo`, `/var/log/cocoadiskinfo` の配置を固定する。
- system service用credentialを`/var/lib/cocoadiskinfo/credentials`へ置く場合の専用user ownershipとpermissionを固定し、Desktop per-user credential directoryと分離する。
- 実行ユーザー/権限方針を明文化する。
- `.deb` パッケージを作る。

## Non-Goals
- CLIやconfig formatが不安定な段階でpackagingを先行しない。
- `smartctl` 権限要件を曖昧にしたまま専用ユーザー運用を固定しない。

## Validation
```text
./gradlew :diskinfo-agent:compileKotlin
```

## Tasks
- [Phase 7A: Client Profile and Credential Management](tasks/phase-7a-client-profile-credential-management.md)（Phase 7開始まで検討保留）
