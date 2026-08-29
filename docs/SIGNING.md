# Release signing / in-app update setup

Semantic Vector Camera のアプリ内アップデートを成立させるには、**最初の正式Releaseから同じ Android 署名鍵を使い続けること**が必須です。

Android は package 名と署名が一致する APK だけを既存アプリへの更新として受け入れます。Debug APK は `com.ikegami.svcam.debug`、Release APK は `com.ikegami.svcam` なので、Debug版からRelease版への初回移行だけは手動インストールが必要です。

## 必須設定

GitHub Repository の

`Settings > Secrets and variables > Actions > Repository secrets`

に、以下の4つを登録してください。

| Secret名 | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `svcam-release.jks` を Base64 化した文字列 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore のパスワード |
| `ANDROID_KEY_ALIAS` | 署名キーの alias。例: `svcam` |
| `ANDROID_KEY_PASSWORD` | alias の秘密鍵パスワード |

この4値、特に keystore は**初回正式Release後に変更しないでください**。紛失すると、既存インストールへ同一アプリとして更新できなくなります。

## 1. Keystore を一度だけ作成する

PC または Android の Termux など、`keytool` が使える環境で一度だけ作成します。

```bash
keytool -genkeypair \
  -keystore svcam-release.jks \
  -alias svcam \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

`svcam-release.jks` は Git にコミットしません。安全な場所へバックアップしてください。

## 2. Keystore を Base64 化する

Linux / Termux:

```bash
base64 -w 0 svcam-release.jks > svcam-release.jks.b64
```

macOS:

```bash
base64 -i svcam-release.jks | tr -d '\n' > svcam-release.jks.b64
```

`svcam-release.jks.b64` の中身を GitHub Secret `ANDROID_KEYSTORE_BASE64` に貼り付けます。

元の `.jks` とパスワードは別々の安全な場所にも保存してください。

## 3. GitHub Actions Secrets を登録する

GitHub の Repository 画面で以下を開きます。

1. `Settings`
2. `Secrets and variables`
3. `Actions`
4. `New repository secret`

以下を1つずつ登録します。

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

現在の `.github/workflows/release.yml` はこの4つが1つでも欠けていると `Validate signing secrets` で停止します。

## 4. Release APK を作る

現在の Release Workflow は `main` のアプリ関連ファイルが更新されるたび、または `workflow_dispatch` で手動実行したときに署名済み Release APK を作ります。

Release version は Workflow 内で自動生成されます。

```text
versionName = 0.2.<GitHub Actions run number>
versionCode = <GitHub Actions run number>
```

成功すると GitHub Releases の Latest Release に次の2ファイルが公開されます。

```text
SemanticVectorCamera-vX.Y.Z.apk
SemanticVectorCamera-vX.Y.Z.apk.sha256
```

アプリ内Updaterは GitHub Releases の `latest` API を確認し、この2ファイルを使用します。

## 5. 最初の Release 版だけ手動インストールする

開発中の Debug APK は package 名と署名が異なるため、Release APKへそのまま上書きできません。

最初の一度だけ次の手順で移行します。

1. 必要なら Debug 版の設定やログを退避
2. Debug版をアンインストール
3. GitHub Releases から署名済み Release APK をダウンロード
4. Release APK をインストール

以後、同じ keystore で作られた新しい Release APK はアプリ内Updaterから更新できます。

## 6. Android 側の「この提供元を許可」

アプリ内Updaterが APK をAndroid標準インストーラへ渡すには、端末側で Semantic Vector Camera に「不明なアプリのインストール」を許可する必要があります。

Updater は権限がOFFの場合、自動で Android の設定画面を開きます。

そこで

```text
この提供元を許可
```

をONにしてアプリへ戻り、もう一度インストールを実行してください。

Manifest には既に次の権限が入っています。

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

APK は `FileProvider` 経由で Android 標準 Package Installer へ渡します。

## 7. アプリ内Updaterの動作

Updaterは次の順番で処理します。

1. GitHub Releases `latest` を取得
2. `tag_name` と現在の `BuildConfig.VERSION_NAME` を比較
3. Release APK と `.apk.sha256` を探す
4. APK をダウンロード
5. SHA-256 を検証
6. Android の「不明なアプリのインストール」権限を確認
7. Android 標準 Package Installer を起動
8. Android OS が package 名と署名を最終検証

アプリ自身が署名検証を迂回して強制インストールすることはありません。

## 更新が動くためのチェックリスト

- [ ] `ANDROID_KEYSTORE_BASE64` を登録済み
- [ ] `ANDROID_KEYSTORE_PASSWORD` を登録済み
- [ ] `ANDROID_KEY_ALIAS` を登録済み
- [ ] `ANDROID_KEY_PASSWORD` を登録済み
- [ ] Release Workflow が成功している
- [ ] GitHub Releases に `.apk` がある
- [ ] 同じ Release に `.apk.sha256` がある
- [ ] 端末には Debug版ではなく Release版をインストールしている
- [ ] Android の「この提供元を許可」がON
- [ ] 新Releaseの `versionCode` が現在のアプリより大きい
- [ ] keystore を以前のReleaseから変更していない

## 重要: keystore を失った場合

GitHub Secrets は登録後に値を読み戻せません。

そのため `svcam-release.jks` 本体とパスワードは GitHub Secrets だけに依存せず、安全なオフライン保管先にもバックアップしてください。

同じ package 名 `com.ikegami.svcam` を維持したまま署名鍵を失うと、既存ユーザーへ通常のAPK更新を配布できなくなります。
