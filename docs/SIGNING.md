# Personal release signing / in-app update

Semantic Vector Camera は個人配布前提なので、署名設定を最小化しています。

必要な GitHub Secret は **1個だけ**です。

```text
ANDROID_KEYSTORE_BASE64
```

alias と password は Personal Release Workflow 側で固定しています。

```text
alias    = svcam
password = svcam-personal-release
```

Android は package 名と署名が同じ APK だけを既存アプリへの更新として受け入れます。そのため、最初に作った `svcam-release.jks` は今後も同じものを使い続けます。

## 1. 署名鍵を一度だけ作る

Termux / Linux / macOS など `keytool` が使える環境で実行します。

```bash
keytool -genkeypair -v \
  -keystore svcam-release.jks \
  -storetype JKS \
  -alias svcam \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass 'svcam-personal-release' \
  -keypass 'svcam-personal-release' \
  -dname "CN=Semantic Vector Camera, OU=Android, O=IKEGAMI-99, C=JP"
```

この `svcam-release.jks` は Git にコミットしません。バックアップだけ取ってください。

## 2. Base64 にする

Termux / Linux:

```bash
base64 -w 0 svcam-release.jks > svcam-release.jks.b64
```

macOS:

```bash
base64 -i svcam-release.jks | tr -d '\n' > svcam-release.jks.b64
```

表示:

```bash
cat svcam-release.jks.b64
```

## 3. GitHub Secret を1個だけ登録

Repository:

```text
Settings
→ Secrets and variables
→ Actions
→ New repository secret
```

Name:

```text
ANDROID_KEYSTORE_BASE64
```

Value:

```text
svcam-release.jks.b64 の中身全部
```

これで署名設定は終了です。

## 4. 以後は自動

`main` にアプリ関連の変更が入ると Release Workflow が自動で以下を実行します。

```text
同じ署名鍵を復元
↓
versionName / versionCode を自動更新
↓
Release APK をビルド
↓
SHA-256 を作成
↓
GitHub Latest Release に公開
```

公開物:

```text
SemanticVectorCamera-v0.2.x.apk
SemanticVectorCamera-v0.2.x.apk.sha256
```

アプリ内Updaterは Latest Release を確認して、APKをダウンロード、SHA-256確認後にAndroid標準インストーラを起動します。

## 最初の1回だけ

Debug APK は `com.ikegami.svcam.debug`、Personal Release は `com.ikegami.svcam` なので、最初の移行だけ上書きできません。

```text
Debug版をアンインストール
↓
最初のPersonal Release APKを手動インストール
↓
以後はアプリ内アップデート
```

Android側では初回だけ「この提供元を許可」をONにしてください。

## バージョン表記

`SVCAM-896-V1` の `V1` は **アプリのバージョンではなく896DフォーマットのSchema version** です。

アプリの実際のバージョンは `BuildConfig.VERSION_NAME` を使い、Processing Console と Settings に例えば次のように表示します。

```text
v0.2.31-debug · SVCAM-896-V1
```

Debug CI / Personal Release とも、GitHub Actions の run number から versionName / versionCode を自動更新します。

## 絶対に消さないもの

```text
svcam-release.jks
```

これだけは安全な場所にバックアップしてください。同じ鍵を失うと、既にインストール済みのPersonal Releaseへ上書き更新できなくなります。
