package com.tyy.aitranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private companion object {
        const val MAX_CHARS = 2000
        const val AUTO_DELAY_MS = 900L
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var inputText: EditText
    private lateinit var outputText: TextView
    private lateinit var outputError: TextView
    private lateinit var outputLang: TextView
    private lateinit var progress: ProgressBar
    private lateinit var charCount: TextView
    private lateinit var sourceValue: TextView
    private lateinit var targetValue: TextView
    private lateinit var apiBanner: MaterialCardView
    private lateinit var btnCopy: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnTranslate: View

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val translateRunnable = Runnable { translateNow(false) }

    private var sourceLang = Languages.AUTO
    private var targetLang = "中文"
    private var requestSeq = 0
    private var restoring = false
    private var hasTranslation = false

    private val historyLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val input = data.getStringExtra(HistoryActivity.EXTRA_INPUT).orEmpty()
            val output = data.getStringExtra(HistoryActivity.EXTRA_OUTPUT).orEmpty()
            sourceLang = data.getStringExtra(HistoryActivity.EXTRA_SOURCE) ?: sourceLang
            targetLang = Languages.normalizeTarget(data.getStringExtra(HistoryActivity.EXTRA_TARGET))
            updateLanguageLabels()

            restoring = true
            inputText.setText(input)
            inputText.setSelection(inputText.text.length)
            restoring = false

            requestSeq++
            handler.removeCallbacks(translateRunnable)
            if (output.isBlank()) showIdle() else showResult(output)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        inputText = findViewById(R.id.input_text)
        outputText = findViewById(R.id.output_text)
        outputError = findViewById(R.id.output_error)
        outputLang = findViewById(R.id.output_lang)
        progress = findViewById(R.id.translate_progress)
        charCount = findViewById(R.id.char_count)
        sourceValue = findViewById(R.id.source_lang_value)
        targetValue = findViewById(R.id.target_lang_value)
        apiBanner = findViewById(R.id.api_banner)
        btnCopy = findViewById(R.id.btn_copy)
        btnShare = findViewById(R.id.btn_share)
        btnTranslate = findViewById(R.id.btn_translate)

        sourceLang = Prefs.sourceLang(this)
        targetLang = Prefs.targetLang(this)
        updateLanguageLabels()
        showIdle()

        inputText.filters = arrayOf(InputFilter.LengthFilter(MAX_CHARS))
        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                charCount.text = getString(R.string.char_count, s?.length ?: 0, MAX_CHARS)
                if (restoring) return
                handler.removeCallbacks(translateRunnable)
                requestSeq++
                if (s.isNullOrBlank()) {
                    requestSeq++
                    showIdle()
                    updateApiBanner()
                    return
                }
                if (Prefs.profiles(this@MainActivity).isEmpty()) {
                    updateApiBanner()
                    return
                }
                handler.postDelayed(translateRunnable, AUTO_DELAY_MS)
            }
        })

        findViewById<View>(R.id.source_lang).setOnClickListener { pickLanguage(true) }
        findViewById<View>(R.id.target_lang).setOnClickListener { pickLanguage(false) }
        findViewById<View>(R.id.btn_swap).setOnClickListener { swapLanguages() }
        findViewById<View>(R.id.btn_menu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.btn_history).setOnClickListener { openHistory() }
        findViewById<View>(R.id.btn_clear).setOnClickListener {
            restoring = true
            inputText.setText("")
            restoring = false
            requestSeq++
            handler.removeCallbacks(translateRunnable)
            showIdle()
            updateApiBanner()
        }
        findViewById<View>(R.id.api_banner).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnTranslate.setOnClickListener { translateNow(true) }
        btnCopy.setOnClickListener { copyTranslation() }
        btnShare.setOnClickListener { shareTranslation() }

        findViewById<View>(R.id.nav_api).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.nav_history).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            openHistory()
        }
        findViewById<View>(R.id.nav_clear).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            confirmClear()
        }
        findViewById<View>(R.id.nav_about).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showAbout()
        }

        findViewById<TextView>(R.id.version_text).text =
            getString(R.string.version_format, appVersionName())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updateApiBanner()
        if (Prefs.profiles(this).isNotEmpty() &&
            inputText.text.isNotBlank() &&
            !hasTranslation
        ) {
            handler.removeCallbacks(translateRunnable)
            handler.postDelayed(translateRunnable, 300)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(translateRunnable)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun updateApiBanner() {
        apiBanner.visibility =
            if (Prefs.profiles(this).isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateLanguageLabels() {
        sourceValue.text = sourceLang
        targetValue.text = targetLang
        outputLang.text = getString(R.string.to_lang, targetLang)
        outputLang.visibility = View.VISIBLE
    }

    private fun pickLanguage(source: Boolean) {
        val list = if (source) Languages.source else Languages.target
        val current = if (source) sourceLang else targetLang
        val checked = list.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (source) R.string.pick_source_language else R.string.pick_target_language)
            .setSingleChoiceItems(list.toTypedArray(), checked) { dialog, which ->
                val value = list[which]
                if (source) {
                    sourceLang = value
                    Prefs.setSourceLang(this, value)
                } else {
                    targetLang = value
                    Prefs.setTargetLang(this, value)
                }
                updateLanguageLabels()
                dialog.dismiss()
                if (inputText.text.isNotBlank()) {
                    handler.removeCallbacks(translateRunnable)
                    translateNow(false)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun swapLanguages() {
        val oldSource = sourceLang
        val oldTarget = targetLang
        if (oldSource == Languages.AUTO) {
            sourceLang = oldTarget
            targetLang = if (oldTarget == "中文") "英语" else "中文"
        } else {
            sourceLang = oldTarget
            targetLang = oldSource
        }
        Prefs.setSourceLang(this, sourceLang)
        Prefs.setTargetLang(this, targetLang)
        updateLanguageLabels()
        if (inputText.text.isNotBlank()) {
            handler.removeCallbacks(translateRunnable)
            translateNow(false)
        }
    }

    private fun translateNow(fromButton: Boolean) {
        handler.removeCallbacks(translateRunnable)
        val text = inputText.text.toString().trim()
        if (text.isEmpty()) {
            if (fromButton) toast(getString(R.string.please_input_text))
            return
        }

        val profile = Prefs.activeProfile(this)
        if (profile == null) {
            updateApiBanner()
            showErrorText(getString(R.string.api_not_configured))
            if (fromButton) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }

        val seq = ++requestSeq
        showLoading()
        val requestSource = sourceLang
        val requestTarget = targetLang

        executor.execute {
            val result = TranslateClient.translate(profile, text, requestSource, requestTarget)
            handler.post {
                if (seq != requestSeq || isFinishing || isDestroyed) return@post
                val translated = result.getOrNull()
                if (!translated.isNullOrBlank()) {
                    showResult(translated)
                    Prefs.addHistory(this, requestSource, requestTarget, text, translated)
                } else {
                    showErrorText(
                        getString(R.string.translate_failed, friendlyError(result.exceptionOrNull()))
                    )
                }
            }
        }
    }

    private fun friendlyError(error: Throwable?): String {
        val message = error?.message?.trim().orEmpty()
        if (message.isBlank()) return getString(R.string.network_error)
        return when {
            message.contains("401") -> getString(R.string.error_401)
            message.contains("403") -> getString(R.string.error_403)
            message.contains("404") -> getString(R.string.error_404)
            message.contains("429") -> getString(R.string.error_429)
            message.contains("timeout", true) || message.contains("timed out", true) ->
                getString(R.string.error_timeout)
            message.contains("Unable to resolve host", true) ->
                getString(R.string.error_host)
            else -> message.take(300)
        }
    }

    private fun showLoading() {
        progress.visibility = View.VISIBLE
        outputError.visibility = View.GONE
        setActionsEnabled(hasTranslation)
    }

    private fun showResult(text: String) {
        hasTranslation = true
        outputText.text = text
        outputText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        outputError.visibility = View.GONE
        progress.visibility = View.GONE
        setActionsEnabled(true)
    }

    private fun showIdle() {
        hasTranslation = false
        outputText.text = getString(R.string.output_placeholder)
        outputText.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        outputError.visibility = View.GONE
        progress.visibility = View.GONE
        setActionsEnabled(false)
    }

    private fun showErrorText(message: String) {
        outputError.text = message
        outputError.visibility = View.VISIBLE
        progress.visibility = View.GONE
        setActionsEnabled(hasTranslation)
        if (!hasTranslation) {
            outputText.text = getString(R.string.output_placeholder)
            outputText.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        btnCopy.isEnabled = enabled
        btnShare.isEnabled = enabled
        btnCopy.alpha = if (enabled) 1f else 0.35f
        btnShare.alpha = if (enabled) 1f else 0.35f
    }

    private fun copyTranslation() {
        if (!hasTranslation) {
            toast(getString(R.string.no_translation_yet))
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.translation_label), outputText.text)
        )
        toast(getString(R.string.copied))
    }

    private fun shareTranslation() {
        if (!hasTranslation) {
            toast(getString(R.string.no_translation_yet))
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, outputText.text.toString())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_translation)))
    }

    private fun openHistory() {
        historyLauncher.launch(Intent(this, HistoryActivity::class.java))
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_current)
            .setMessage(R.string.clear_current_message)
            .setPositiveButton(R.string.clear) { _, _ ->
                restoring = true
                inputText.setText("")
                restoring = false
                requestSeq++
                handler.removeCallbacks(translateRunnable)
                showIdle()
                updateApiBanner()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_message, appVersionName()))
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun appVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
