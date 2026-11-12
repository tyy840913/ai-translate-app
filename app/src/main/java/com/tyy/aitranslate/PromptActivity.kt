package com.tyy.aitranslate

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 提示词编辑页。
 * 初始化时显示「用户已保存的提示词」或「内置默认提示词」，两者都可直接修改。
 * 保存后完全以用户内容为准，内置提示词不再发送给模型；恢复默认则清除自定义内容。
 */
class PromptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE = "extra_image"
    }

    private lateinit var input: EditText
    private var isImage = false
    private lateinit var defaultPrompt: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prompt)

        isImage = intent.getBooleanExtra(EXTRA_IMAGE, false)
        defaultPrompt = if (isImage) Prompts.DEFAULT_IMAGE else Prompts.DEFAULT_TEXT

        input = findViewById(R.id.prompt_input)
        findViewById<TextView>(R.id.prompt_title).text =
            getString(if (isImage) R.string.prompt_image_title else R.string.prompt_text_title)

        val saved = if (isImage) Prefs.promptImage(this) else Prefs.promptText(this)
        input.setText(if (saved.isBlank()) defaultPrompt else saved)
        input.setSelection(input.text.length)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        findViewById<View>(R.id.btn_reset).setOnClickListener { confirmReset() }
    }

    private fun save() {
        val value = input.text?.toString().orEmpty()
        if (isImage) Prefs.setPromptImage(this, value) else Prefs.setPromptText(this, value)
        Toast.makeText(this, R.string.prompt_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.prompt_reset)
            .setMessage(R.string.prompt_reset_message)
            .setPositiveButton(R.string.prompt_reset) { _, _ ->
                if (isImage) Prefs.setPromptImage(this, "") else Prefs.setPromptText(this, "")
                input.setText(defaultPrompt)
                input.setSelection(input.text.length)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
