package com.tyy.aitranslate

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 自定义规则编辑页（提示词 / 剪贴板过滤规则）。
 * 初始化时显示「用户已保存的规则」或「内置默认规则」，两者都可直接修改。
 * 保存后完全以用户内容为准，内置默认不再使用；恢复默认则清除自定义内容。
 */
class PromptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE = "extra_image"
        const val EXTRA_FILTER = "extra_filter"
    }

    private lateinit var input: EditText
    private var isImage = false
    private var isFilter = false
    private lateinit var defaultPrompt: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prompt)

        isImage = intent.getBooleanExtra(EXTRA_IMAGE, false)
        isFilter = intent.getBooleanExtra(EXTRA_FILTER, false)
        defaultPrompt = when {
            isImage -> Prompts.DEFAULT_IMAGE
            isFilter -> Prompts.DEFAULT_FILTER
            else -> Prompts.DEFAULT_TEXT
        }

        input = findViewById(R.id.prompt_input)
        findViewById<TextView>(R.id.prompt_title).text = getString(
            when {
                isImage -> R.string.prompt_image_title
                isFilter -> R.string.filter_rules_title
                else -> R.string.prompt_text_title
            }
        )

        val saved = when {
            isImage -> Prefs.promptImage(this)
            isFilter -> Prefs.promptFilter(this)
            else -> Prefs.promptText(this)
        }
        input.setHint(if (isFilter) R.string.filter_hint else R.string.prompt_hint)
        // 编辑框上方的说明文字也要按模式分流（布局里写死 prompt_hint，这里覆盖）
        findViewById<TextView>(R.id.prompt_hint_text).text = getString(
            if (isFilter) R.string.filter_hint else R.string.prompt_hint
        )
        input.setText(if (saved.isBlank()) defaultPrompt else saved)
        input.setSelection(input.text.length)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        findViewById<View>(R.id.btn_reset).setOnClickListener { confirmReset() }
    }

    private fun save() {
        val value = input.text?.toString().orEmpty()
        when {
            isImage -> Prefs.setPromptImage(this, value)
            isFilter -> Prefs.setPromptFilter(this, value)
            else -> Prefs.setPromptText(this, value)
        }
        Toast.makeText(
            this,
            if (isFilter) R.string.filter_saved else R.string.prompt_saved,
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.prompt_reset)
            .setMessage(
                if (isFilter) R.string.filter_reset_message else R.string.prompt_reset_message
            )
            .setPositiveButton(R.string.prompt_reset) { _, _ ->
                when {
                    isImage -> Prefs.setPromptImage(this, "")
                    isFilter -> Prefs.setPromptFilter(this, "")
                    else -> Prefs.setPromptText(this, "")
                }
                input.setText(defaultPrompt)
                input.setSelection(input.text.length)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
