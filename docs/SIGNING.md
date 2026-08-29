# Personal release signing / in-app update

Semantic Vector Camera は個人配布前提なので、AI-VECTOR-GAME と同じく固定の署名鍵をリポジトリ内に置く方式です。

GitHub Secrets / Base64 は使いません。

```text
keystore = app/keys/svcam-release.jks
alias    = svcam
password = svcam2026
package  = com.ikegami.svcam
```

Debug / Release の両方を同じ package id と同じ鍵で署名します。

## 仕組み

`app/build.gradle.kts` の `stableDev` signing config が `app/keys/svcam-release.jks` を直接読み込みます。

```text
Debug APK
  package: com.ikegami.svcam
  key:     app/keys/svcam-release.jks

Release APK
  package: com.ikegami.svcam
  key:     app/keys/svcam-release.jks
```

そのためDebugからRelease、Releaseから次のReleaseへそのままAndroidの上書き更新ができます。

## GitHub Actions

`main` に変更が入るとRelease Workflowが自動で以下を行います。

```text
versionName / versionCode を自動更新
↓
固定署名鍵でRelease APKをビルド
↓
SHA-256を作成
↓
GitHub Latest Releaseへ公開
```

公開物:

```text
SemanticVectorCamera-v0.2.x.apk
SemanticVectorCamera-v0.2.x.apk.sha256
```

アプリ内UpdaterはLatest Releaseを確認し、APKをダウンロード、SHA-256検証後にAndroid標準Package Installerを起動します。

## 端末側で必要なこと

初回だけSemantic Vector Cameraに対してAndroidの「この提供元を許可」をONにします。

以後はSettingsのApp Updateから更新できます。

## バージョン表記

`SVCAM-896-V1` の `V1` はアプリversionではなく896DフォーマットのSchema versionです。

実際のアプリversionは `BuildConfig.VERSION_NAME` を使います。

```text
v0.2.37 · SVCAM-896-V1
```

Debug CI / Release ともGitHub Actionsのrun numberからversionName / versionCodeを自動更新します。

## 注意

この署名鍵とpasswordは公開リポジトリから取得可能です。Play Store配布や第三者へ安全な署名保証を提供する用途には使いません。

個人配布専用の更新互換性を優先した構成です。
