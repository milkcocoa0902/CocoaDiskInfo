# Phase 6: Health Policy

## Source
- Master: `../master.md`

## Goal
`GOOD`, `CAUTION`, `BAD`, `UNKNOWN` の互換性と説明可能性を保ちながら、Health判定を名前付きpolicyとして扱えるようにする。

## Scope
- policy識別子を導入する。
- `ruleKey` と `reason` を整理する。
- `AttributeEvaluation` の説明性を上げる。
- しきい値の設定ファイル化を検討する。

## Non-Goals
- 最初から複数policyを完全実装しない。
- Health判定を説明不能なスコアに置き換えない。

## Validation
```text
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
```

## Tasks
- No active task document in this phase.
