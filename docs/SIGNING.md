# Release signing

Androidの上書き更新を成立させるため、最初の正式Releaseから同じkeystoreを永続的に使用します。

## 1. Keystoreを一度だけ作る

例:

```bash
keytool -genkeypair \
  -keystore svcam-release.jks \
  -alias svcam \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

keystoreそのものはGitへコミットしません。

## 2. GitHub Actions Secrets

Repository Settings > Secrets and variables > Actions に以下を登録します。

- `ANDROID_KEYSTORE_BASE64`: `base64`化したkeystore本体
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

この4値は初回正式Release後に別の鍵へ変更しないでください。

## 3. Release

`v0.1.0` のようなtagをpushすると `release.yml` が署名済みAPKと `.apk.sha256` をGitHub Releaseへ添付します。

アプリ内UpdaterはRelease APIを確認し、APKをダウンロード後にSHA-256を検証してAndroid標準Package Installerへ渡します。最終的な署名・package整合性はAndroid OSが検証します。
