# Semantic Vector Camera

**写真を保存せず、AIが見た「意味」を896次元で保存するAndroidカメラ。**

Semantic Vector Camera は、CameraXのフレームをGemma 4系Visionモデルでローカル解析し、その解釈を固定Schema **SVCAM-896-V1** に変換して `.svcam.json` として保存します。

保存されたVectorはAndroid共有シートからChatGPT / Gemini / Claudeなどへ送れます。共有時には「このVectorをあなた自身の解釈で一枚の写真として復号する」ための指示文も一緒に送信します。同じVectorでもDecoder AIによって違う風景になること自体がこのアプリの目的です。

## 現在のMVP

- CameraXリアカメラPreview
- 撮影フレームはRAM上だけで処理し、元画像を通常保存しない
- Gemma GGUF + mmproj GGUFを端末からImport
- llama.cpp / libmtmd JNI backend
- Gemmaの疎なSemantic JSONを固定896Dへ決定論的にEncode
- `.svcam.json` Vector Library
- 共有ファイル + Decode用プロンプトをAndroid Share Sheetへ送信
- JSONL診断ログ / Crash log / ZIP Export
- GitHub Releases update checker
- Release APKのSHA-256検証
- 初回から同一Release keyを使い続ける署名Workflow
- Debug APKを生成するGitHub Actions CI

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
Camera frame
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
- NDK 28.1.13356709
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

## Release signing / app update

正式Releaseを一度公開した後に署名鍵を変えると、Android上では同一アプリとして上書き更新できません。

そのためRelease Workflowは最初から固定keystoreをGitHub Actions Secretsから読み込む設計です。秘密鍵はリポジトリへ入れません。

設定方法: [docs/SIGNING.md](docs/SIGNING.md)

Tag `v*` をpushすると、署名済みAPKと `.apk.sha256` をReleaseへ添付します。アプリ内UpdaterはSHA-256を検証した後、Android標準Package Installerを起動します。

## Diagnostics

Settings > Diagnostics:

- Log ZIP export
- Log clear
- Diagnostics copy

記録対象はCamera、Gemma model load/inference、Semantic Encoder、SVCAM save/share、Update、Crashです。

## Project status

`0.1.0` は最初の実装です。特にlibmtmdは上流API変更が多いため、native bridgeをKotlin UI / Semantic Encoderから分離しています。

今後の候補:

- Vulkan acceleration
- Vector Viewer詳細表示
- Semantic Mutation
- 2つのVectorのBlend
- Decoder AI比較
- Image reconstruction compare view
