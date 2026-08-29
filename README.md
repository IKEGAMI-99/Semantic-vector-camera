# Semantic Vector Camera

**写真を保存せず、AIが見た「意味」を896次元で保存するAndroidカメラ。**

Semantic Vector Camera は、CameraXのフレームをGemma 4系Visionモデルでローカル解析し、その解釈を固定Schema **SVCAM-896-V1** に変換して `.svcam.json` として保存します。

保存されたVectorはAndroid共有シートからChatGPT / Gemini / Claudeなどへ送れます。共有時には「このVectorをあなた自身の解釈で一枚の写真として復号する」ための指示文も一緒に送信します。同じVectorでもDecoder AIによって違う風景になること自体がこのアプリの目的です。

## 現在のMVP

- CameraXリアカメラPreview
- Galleryから画像を選択して896Dへ変換
- 撮影フレームはRAM上だけで処理し、元画像を通常保存しない
- Gemma GGUF + mmproj GGUFを端末からImport
- llama.cpp / libmtmd JNI backend
- Gemmaの疎なSemantic JSONを固定896Dへ決定論的にEncode
- 処理中は実ログ連動の全画面Processing Consoleを表示
- `.svcam.json` Vector Library
- 共有ファイル + Decode用プロンプトをAndroid Share Sheetへ送信
- JSONL診断ログ / Crash log / ZIP Export
- GitHub Releases update checker
- Release APKのSHA-256検証
- 個人配布向け固定Release key Workflow
- Debug APKを生成するGitHub Actions CI

## Processing Console

シャッターまたはGallery画像を選ぶと、通常のスピナーではなくターミナル風の全画面Processing Consoleへ切り替わります。

画面に出る内容は演出専用の疑似ログではなく、JSONL診断ログと同じ `AppLogger` のライブイベントです。

Consoleには以下を表示します。

- 実際のアプリversion (`BuildConfig.VERSION_NAME`)
- Semantic Schema (`SVCAM-896-V1`)
- 現在Stage
- 経過時間
- Stageベースの進捗
- 最大180行のライブイベント
- Native heartbeat
- Vision Encoder / Text Prefill / Image Prefill の切り分け
- エラー内容
- `896 / 896 VALID`
- `ORIGINAL IMAGE DESTROYED`
- `SEMANTIC MEMORY SAVED`

`SVCAM-896-V1` の `V1` はアプリversionではなく、896Dデータ形式のSchema versionです。アプリversionは例えば次のように別表示します。

```text
v0.2.31-debug · SVCAM-896-V1
```

## SVCAM-896-V1

```text
Global Scene     256D
Objects          16 slots × 32D = 512D
Relations        128D
---------------------
TOTAL            896D
```

Gemmaに896個の数値を直接自由生成させません。

```text
Camera frame / Gallery image
  ↓
Gemma 4 Vision
  ↓
Sparse structured scene JSON
  ↓
Deterministic SemanticEncoder
  ↓
SVCAM-896-V1 (exactly 896 values)
```

この方式なら、将来Encoder側のVisionモデルを変更してもSVCAMの次元・意味Schemaを固定できます。

## GGUF model

Settingsから2ファイルをImportします。

1. Gemma 4 Vision対応の `model.gguf`
2. そのモデルと組になる `mmproj.gguf`

ファイルはアプリ内部領域へコピーされます。APKそのものには巨大なモデルを同梱しません。

初期backendは **arm64-v8a / CPU-safe profile** です。CMakeには将来Vulkan backendを有効化できる分離点を用意しています。

## Decode共有

Vector Libraryまたは撮影直後の `Share Decode` から `.svcam.json` を共有します。

共有Intentには以下が含まれます。

- `EXTRA_STREAM`: `.svcam.json`
- `EXTRA_TEXT`: Decoder AI向け復号指示
- MIME: `application/json`

元画像はAIへ送られません。存在するのは896DのSemantic Memoryだけです。

## Privacy

通常撮影では元画像をファイルへ保存しません。CameraXから得たBitmapはGemma推論とEncode終了後に破棄します。

Galleryから読み込んだ場合も、アプリがデコードしたRAM上のBitmapだけを破棄します。端末のGalleryにある元ファイルそのものは削除しません。

ログにも以下を保存しません。

- 撮影画像
- 896D Vector全文
- APIキー / Token

ログにはSchema、次元数、Object数、推論時間、Vector統計値、エラーなどだけを記録します。

## Build

Toolchain baseline:

- Android API 37
- AGP 9.3.0
- Gradle 9.5.0
- JDK 21 in CI
- NDK 28.2.13676358
- CMake 3.22.1
- Jetpack Compose BOM 2026.08.00
- CameraX 1.6.2
- arm64-v8a

llama.cppはGit submoduleとして固定Commitを参照します。clone時はsubmoduleも取得してください。

```bash
git clone --recurse-submodules <repository-url>
cd Semantic-vector-camera
gradle :app:assembleDebug
```

Debug CI では GitHub Actions の run number から自動的に versionName / versionCode を注入します。

```text
versionName = 0.2.<run number>-debug
versionCode = <run number>
```

## Personal Release / app update

個人配布前提なので、GitHub側で管理する署名Secretは **1個だけ**です。

```text
ANDROID_KEYSTORE_BASE64
```

alias / password はPersonal Release Workflow側で固定しています。

```text
alias    = svcam
password = svcam-personal-release
```

Release Workflow は `main` のアプリ関連ファイル更新時、または手動実行時に署名済みAPKと `.apk.sha256` をGitHub ReleasesのLatestとして公開します。

アプリ内UpdaterはLatest Releaseを確認し、APKをダウンロード、SHA-256を検証してAndroid標準Package Installerを起動します。

Debug APK は `com.ikegami.svcam.debug`、Personal Release APK は `com.ikegami.svcam` のため、最初のPersonal Releaseだけは手動インストールが必要です。以後は同じ署名鍵を使うためアプリ内更新できます。

端末側では初回だけ Semantic Vector Camera に対して「この提供元を許可」をONにします。

設定手順: [docs/SIGNING.md](docs/SIGNING.md)

## Diagnostics

Settings > Diagnostics:

- Log ZIP export
- Log clear
- Diagnostics copy

記録対象はCamera、Gallery、Gemma model load/inference、Semantic Encoder、SVCAM save/share、Update、Crashです。

## Project status

アプリversionとSemantic Schema versionは分離しています。アプリversionはCI/Releaseごとに増えますが、互換性を維持する限りSemantic formatは `SVCAM-896-V1` のままです。

今後の候補:

- Vulkan acceleration
- Vision projector最適化
- Vector Viewer詳細表示
- Semantic Mutation
- 2つのVectorのBlend
- Decoder AI比較
- Image reconstruction compare view
