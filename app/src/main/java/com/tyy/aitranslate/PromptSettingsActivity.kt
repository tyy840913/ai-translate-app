package com.tyy.aitranslate

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 自定义规则入口页：翻译提示词（文本 / 图片）与剪贴板过滤规则分格展示。
 */
class PromptSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prompt_settings)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.row_prompt_text).setOnClickListener { openPrompt(image = false) }
        findViewById<View>(R.id.row_prompt_image).setOnClickListener { openPrompt(image = true) }
        findViewById<View>(R.id.row_prompt_filter).setOnClickListener { openFilter() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        findViewById<TextView>(R.id.setting_prompt_text_summary).text =
            getString(
                if (Prefs.promptText(this).isBlank()) {
                    R.string.prompt_summary_default
                } else {
                    R.string.prompt_summary_custom
                }
            )
        findViewById<TextView>(R.id.setting_prompt_image_summary).text =
            getString(
                if (Prefs.promptImage(this).isBlank()) {
                    R.string.prompt_summary_default
                } else {
                    R.string.prompt_summary_custom
                }
            )
        findViewById<TextView>(R.id.setting_prompt_filter_summary).text =
            getString(
                if (Prefs.promptFilter(this).isBlank()) {
                    R.string.filter_summary_default
                } else {
                    R.string.prompt_summary_custom
                }
            )
    }

    private fun openPrompt(image: Boolean) {
        startActivity(
            Intent(this, PromptActivity::class.java)
                .putExtra(PromptActivity.EXTRA_IMAGE, image)
        )
    }

    private fun openFilter() {
        startActivity(
            Intent(this, PromptActivity::class.java)
                .putExtra(PromptActivity.EXTRA_FILTER, true)
        )
    }

}
