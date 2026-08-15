# Phase 7A: Client Profile and Credential Management

## Status

Phase 7まで検討保留。Phase 5/6の完了条件にはせず、packaging/operations、OS別credential保護、Desktop profile UXを同時に再評価する。現時点ではsource implementationへ着手しない。

## Source Context

- Primary: `../../master.md`
- Phase plan: `../phase_0007_plan.md`
- Implemented baseline: `../../phase_0005/tasks/phase-5g-client-auth-hubless.md`
- Handoff: `../../phase_0005/phase_0005_handoff.md`
- External: [Jetpack DataStore release notes](https://developer.android.com/jetpack/androidx/releases/datastore)
- External: [DataStore KMP setup](https://developer.android.com/kotlin/multiplatform/datastore)

## Goal

利用者がcredential file pathを通常操作で入力せずにClient pairingを完了でき、複数のStandalone/Hub connection profileを一覧から安全に選択、追加、再pair、削除できるようにする。

## Non-Goals

- private keyをJava Preferencesやprofile catalogへ保存しない。
- credential fileの一覧だけをprofile catalogとして扱わない。
- Desktop Clientの通常保存先に`/usr/lib`、`/etc`、`/var/lib`を使わない。
- profile削除をserver-side Principal失効と暗黙に同一視しない。
- Phase 7AでOS keychain、online key rotation、server管理者APIをすべて必須にしない。platformごとの実現可能性を確認してscopeを確定する。
- Hub/Standaloneのpairing wire contractやsigned read APIを変更しない。

## Current State

- `ConnectionProfileStore`は単一profileの`load()` / `save()`だけを提供する。
- `PreferencesConnectionProfileStore`は1件のversioned JSONとlegacy `agent_url`をJava Preferencesへ保存する。
- `diskinfo-client`はJetpack DataStoreへ依存しておらず、profile/settingsのtransactional updateと`Flow`を持たない。
- `ConnectionProfile.id`はserverから返る`hubId`をそのまま使い、local profile identityとserver authority identityが分かれていない。
- Settingsはcredential pathの手入力を必須とする。
- pairing時にClientがEd25519 key pairを生成し、profile公開前にowner-only JSON credential fileへ保存する。
- credential fileにはendpoint、`hubId`、`kid`、private/public keyがあり、profile display name、HTTP opt-in、PEM CA reference、UI設定は含まない。

## Handoff Direction

以下はPhase 7開始時にversion、platform support、packaging配置と合わせて再確認する第一候補であり、Phase 5/6中の実装決定ではない。

- Owner boundary: Desktop `Client`とpackaging/operations。
- profile metadataのprimary storeはtyped `DataStore<ConnectionProfileCatalog>`を第一候補とし、秘密鍵は`ClientCredentialStore`へ分離する。
- DataStoreはapplication lifecycleでfileごとに1 instanceだけ生成し、Composableから直接read/writeせずrepository/ViewModel boundaryから`Flow`と更新操作を公開する。
- `ConnectionProfileCatalog`はimmutable `@Serializable` modelとし、初期serializerは既存model/migrationを再利用しやすいKotlin Serialization JSONを第一候補とする。CBOR/ProtoBufへの変更は`Serializer`差し替えだけでなくon-disk migrationを伴うため、実測上の理由が出た後に行う。
- credential directoryのscanはorphan recovery補助に限定し、通常のprofile一覧はcatalogをauthorityとする。
- local `profileId`を生成し、serverの`AuthorityId`（wire互換上の`hubId`）と分離する。これにより同じserverへの複数profile、表示名変更、再pairを表現できる。
- active profileはcatalogへ明示保存し、Client起動時に復元する。
- credential pathはplatform path providerが自動生成し、通常UIから隠す。変更はAdvanced設定に置く。
- credential storeは将来OS keychain実装へ差し替えられるinterfaceを維持し、profileがprivate key bytesを知らない構造を保つ。

## DataStore and Tink Boundary

- Jetpack DataStore 1.3.0-alpha系の`androidx.datastore:datastore-tink`は、既存`Serializer<T>`をTink AEADで包む`AeadSerializer`をJVM/Android向けに提供する。採用時は実装開始時点のrelease notesとbinary compatibilityを再確認する。
- profile catalogはendpoint、表示名、HTTP opt-in、credential referenceなどの非secret metadataだけを持つため、Tink暗号化をprofile管理の必須条件にはしない。
- private keyを将来encrypted DataStoreへ入れる場合は、`AeadSerializer`だけでなくAEAD key自体をOS keychain/keystoreなどで保護する必要がある。ciphertextと復号keyを同じowner-only directoryへ置くだけの構成を「暗号化済み」として過信しない。
- Tinkを使う場合はDataStore fileごとに一意なassociated data（安定したfile identity）を設定し、ciphertext swappingを防ぐ。
- 1.3.0-alphaのAPIをproductionへ採用するかはfocused spikeで決める。DataStore profile catalogの設計はTink有無へ依存させない。

## Default Locations

Desktop Clientのper-user defaultは次とする。

| Platform | Credential directory |
| --- | --- |
| Linux | `$XDG_DATA_HOME/cocoadiskinfo/credentials/`。未設定時は`~/.local/share/cocoadiskinfo/credentials/` |
| macOS | `~/Library/Application Support/CocoaDiskInfo/credentials/` |
| Windows | `%LOCALAPPDATA%\CocoaDiskInfo\credentials\` |

- filenameはopaqueなlocal `profileId`を使い、例を`<profileId>.json`とする。
- UI文字列の`~`、`$HOME`、`%LOCALAPPDATA%`をそのまま`Path.of`へ渡さず、platform path providerが実pathへ解決する。
- 新規directory/fileはowner-onlyで作り、既存symlink、broad permission、owner-only検証不能filesystemではfail closedを維持する。
- package管理対象で通常ユーザーが書き込めない`/usr/lib`は、private credentialの保存先にしない。
- system serviceとして明示運用するcredentialはPhase 7で`/var/lib/cocoadiskinfo/credentials/`を候補とし、専用service user ownershipを必須にする。Desktopのdefaultとは混ぜない。

## Task Breakdown

### Task 1: Spike DataStore 1.3 and Tink on Desktop JVM

- Objective: alpha APIとDesktop JVMでのserializer/encryption boundaryをproduction採用前に確認する。
- Affected modules/files: Gradle version catalog、Desktop-only spike/focused tests。
- Expected behavior:
  - `DataStore.Builder`をapplication-scoped `CoroutineContext`で1 file/1 instanceとして構築できる。
  - Kotlin Serialization JSON serializerでcatalogをround-tripできる。
  - `AeadSerializer`がJVM targetで動作し、wrong key/associated dataをcorruptionとして扱える。
  - Tink key protectionを提供できないplatformではprofile catalogを平文metadata、credentialを既存owner-only storeへ分離したままにする。
- Validation: JVM round-trip、atomic update、corruption、wrong key、wrong associated data、cancel/reopen。

### Task 2: Add Platform-Aware Client Paths

- Objective: OS別のcredential directoryとdefault file pathを1か所で解決する。
- Affected modules/files: Desktop platform path provider、credential store tests。
- Expected behavior:
  - LinuxはXDG override/fallbackを正しく解決する。
  - macOS/Windowsはuser-local application data directoryを使う。
  - pathにcredential secretやdisplay nameを含めない。
  - custom pathはAdvancedから明示指定できる。
- Validation: OS/environment/homeを注入したpure test、対象OS smoke test、unsupported filesystem failure。

### Task 3: Replace the Singleton Profile Store with Typed DataStore

- Objective: 複数profileとactive selectionをversionedに永続化する。
- Affected modules/files: `ConnectionProfile`、`ConnectionProfileStore`、DataStore builder/JSON serializer、Preferences migration、tests。
- Expected behavior:
  - list/get/save/delete/set-activeをprofile ID単位で扱う。
  - local `profileId`とserver `authorityId`を分離する。
  - legacy URLと現行single-profile JSONを1回だけidempotentに移行する。
  - profile catalogはcredential path/referenceだけを持ち、private keyを含まない。
  - application-scoped singleton DataStoreからcatalog `Flow`を公開し、atomic `updateData`で更新する。
- Validation: empty/one/multiple profile、concurrent update、active復元、duplicate authority、corrupt catalog、旧profile migration、application scope cancellation。

### Task 4: Make Pairing Allocate Credential Storage

- Objective: 通常pairingでcredential path入力を不要にする。
- Affected modules/files: `ClientPairingClient`、Settings state、profile/credential transaction boundary。
- Expected behavior:
  - Add profile開始時にlocal `profileId`とdefault credential pathを生成する。
  - credentialをowner-onlyで保存してからcatalogへprofileを公開する現行順序を維持する。
  - pairing failure時にpartial profileを残さず、作成済みsecret fileも安全にcleanupする。
  - operator指定custom pathはAdvancedでのみ上書きする。
- Validation: success、network/server mismatch、credential save failure、catalog save failure、retry、secret cleanup。

### Task 5: Add Profile List UX

- Objective: Client内で接続先を一覧から管理、選択できるようにする。
- Affected modules/files: Desktop navigation/settings/dashboard state。
- Expected behavior:
  - profile listでname、endpoint、connection stateを表示する。
  - Add/Pair、Select、Rename、Edit transport trust、Re-pair、Removeを提供する。
  - PEM CAとcustom credential pathはAdvancedへ置く。
  - profile切替時は旧`HttpClient`/nonce sessionをcloseし、新profile credentialで再構築する。
  - pairing token ID/secretは試行後にUI stateへ残さない。
- Validation: profile switch、restart、missing credential、disabled Principal、HTTP opt-in、PEM error。

### Task 6: Define Removal and Recovery Semantics

- Objective: local file削除とserver-side失効を誤認させない。
- Affected modules/files: profile removal dialog、operator documentation、re-pair flow。
- Expected behavior:
  - profileだけを外す操作と、local credentialも削除する操作を明示的に分ける。
  - credential削除はexact pathを表示して確認し、symlinkを追わない。
  - local削除だけではHub/Standalone Principalがactiveのままであると説明する。
  - server-side失効は既存`principal disable --kid ...`手順を案内する。
- Validation: remove-only、remove+credential、shared/custom path refusal、missing file、re-pair。

## CLI/API Compatibility

- Hub/Standalone pairing token CLIと`POST /api/v1/clients/pair`は変更しない。
- current single profileとlegacy URLを自動migrationし、既存credential pathを移動しない。
- signed read wire、`hubId` field、Client credential JSONは必要がなければ変更しない。
- profile listはDesktop local featureであり、serverへprofile metadataを同期しない。

## Data and Persistence Impact

- typed DataStoreにはversioned profile catalog、active profile ID、非secret UI metadataだけを置く。
- Java Preferencesはlegacy importerとしてだけ読み、migration完了後のprimary storeにしない。
- private keyはowner-only credential storeにだけ置く。
- default credential pathへ既存custom credentialを無断移動しない。
- catalog更新とcredential作成のfailure orderingをtestし、参照先のないpartial profileを公開しない。
- credential file deletionはmaterially destructiveなので明示確認し、server Principal失効とは分ける。

## Validation Plan

```text
./gradlew :diskinfo-client:desktopTest
./gradlew :diskinfo-client:compileKotlinDesktop
```

LinuxのXDG set/unset、macOS/Windows path selection、single-profile migration、multi-profile switch、owner-only permission、pairing rollback、remove/re-pairをfocused testで確認する。Windows ACLとmacOS directory behaviorは対象OSのsmoke testをrelease evidenceとして残す。

## Risks and Open Questions

- profile catalogのprimary storeはper-user application data内のtyped DataStoreを第一候補とする。採用時はJava Preferencesをlegacy migration sourceとしてだけ残す。
- credential fileを複数profileから共有させると削除が危険になるため、初期実装では1 profile : 1 credentialを推奨する。
- server PrincipalをClientから直接disableする管理APIは現状ない。local removeとremote revokeを一操作に統合しない。
- OS keychainへ移行する場合、file pathではなくcredential referenceをprofile modelへ導入するmigrationが必要になる。
- `datastore-tink` 1.3.0-alphaをproduction dependencyにするか、stable DataStore + owner-only credential storeで開始するかはspike後に決定する。Tink採用可否でcatalog schemaを変えない。
- 公式KMP setupは現時点でPreferences DataStore中心の案内であり、TinkはJVM/Android対象である。現在のDesktop JVMには適用可能だが、将来iOS targetへ共有する場合は再評価する。

## Implementation Order

1. Phase 7開始時点のDataStore/Tink stable・alpha version、KMP/JVM support、OS key protectionを再調査する。
2. DataStore、JSON serializer、TinkのDesktop JVM focused spikeを行う。
3. platform path providerとstable local `profileId`を追加する。
4. typed DataStore catalogとsingle-profile/legacy Preferences migrationを実装する。
5. pairingへdefault credential allocationとrollbackを接続する。
6. profile list、active selection、Add/Pair、Advanced settingsを実装する。
7. remove/local credential deletion/re-pair UXを実装する。
8. Linux/macOS/Windows path、permission、migration、switchのtestとoperator docsを完成させる。
