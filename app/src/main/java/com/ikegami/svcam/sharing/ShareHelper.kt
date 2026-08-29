package com.ikegami.svcam.sharing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ShareHelper {
    val decoderPrompt: String = """
添付ファイルは Semantic Vector Camera が生成した SVCAM-896-V1 形式の896次元Semantic Vectorです。元画像は保存されておらず、存在しません。

ファイル内の labels と vector を読み、Global 256D、Object 16×32D、Relations 128D の値をできるだけ忠実に解釈してください。objects の位置・サイズ・確信度、relations の空間関係、uncertainty 系の値を考慮してください。曖昧な情報は合理的に補完できますが、Vectorに根拠のない特徴を過度に追加しないでください。

目的は元画像を数学的に復元することではありません。このSemantic Vectorをあなた自身が解釈した「一枚の写真」として視覚化することです。まず内部でシーンを構成し、最終的に写真として画像生成してください。Vector値やJSONの説明を最終回答の主役にはしないでください。
""".trimIndent()

    fun shareCapture(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, decoderPrompt)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Decode with AI"))
    }

    fun shareLogZip(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Semantic Vector Camera diagnostic logs")
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export logs"))
    }
}
