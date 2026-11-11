package com.tyy.aitranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 译文全屏查看。只保留返回、复制、分享三个操作，不显示菜单和历史入口。
 * 文本通过进程内单例传递，避免超长译文触发 Intent 的 Binder 大小限制。
 */
class FullscreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (FullscreenContent.text.isBlank()) {
            finish()
            return
        }
        setContentView(R.layout.activity_fullscreen)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_copy).setOnClickListener { copyTranslation() }
        findViewById<ImageButton>(R.id.btn_share).setOnClickListener { shareTranslation() }

        findViewById<TextView>(R.id.fullscreen_lang).text =
            getString(R.string.to_lang, FullscreenContent.target)
        findViewById<TextView>(R.id.fullscreen_text).text = FullscreenContent.text
    }

    private fun copyTranslation() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.translation_label), FullscreenContent.text)
        )
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareTranslation() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, FullscreenContent.text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_translation)))
    }
}

object FullscreenContent {
    var text: String = ""
    var target: String = ""
}
