package com.tyy.aitranslate

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.widget.NestedScrollView
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private companion object {
        const val AUTO_DELAY_MS = 900L
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var inputText: EditText
    private lateinit var inputArea: FrameLayout
    private lateinit var outputText: TextView
    private lateinit var outputError: TextView
    private lateinit var outputLang: TextView
    private lateinit var outputArea: View
    private lateinit var divider: View
    private lateinit var contentScroll: NestedScrollView
    private lateinit var progress: ProgressBar
    private lateinit var targetValue: TextView
    private lateinit var apiBanner: MaterialCardView
    private lateinit var btnCopy: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnCamera: ImageButton
    private lateinit var btnTranslate: ImageButton
    private lateinit var photoContainer: View
    private lateinit var photoPreview: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val translateRunnable = Runnable { translateNow(false) }

    private var targetLang = Languages.SMART
    private var lastOutputTarget = ""
    private var requestSeq = 0
    private var restoring = false
    private var hasTranslation = false
    private var lastKeyboardHeight = 0
    private var pendingPhotoUri: Uri? = null
    private var photoUri: Uri? = null
    private var photoBase64: String? = null
    private var keyboardHeight = 0

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = pendingPhotoUri
            pendingPhotoUri = null
            if (result.resultCode == RESULT_OK && uri != null) {
                showPhoto(uri)
                translatePhoto()
            }
        }

    private val historyLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val input = data.getStringExtra(HistoryActivity.EXTRA_INPUT).orEmpty()
            val output = data.getStringExtra(HistoryActivity.EXTRA_OUTPUT).orEmpty()
            val isImage = data.getBooleanExtra(HistoryActivity.EXTRA_IMAGE, false)
            val restoredTarget = Languages.normalizeTarget(
                data.getStringExtra(HistoryActivity.EXTRA_TARGET)
            )

            clearPhotoState()
            requestSeq++
            handler.removeCallbacks(translateRunnable)

            if (isImage) {
                restoring = true
                inputText.setText("")
                restoring = false
                targetLang =
                    if (restoredTarget == Languages.SMART) Languages.SMART else restoredTarget
                updateTargetViews()
                if (output.isBlank()) {
                    showIdle()
                } else {
                    showResult(
                        output,
                        if (targetLang == Languages.SMART) getString(R.string.smart_target)
                        else targetLang
                    )
                }
                toast(getString(R.string.image_history_hint))
                return@registerForActivityResult
            }

            restoring = true
            inputText.setText(input)
            inputText.setSelection(inputText.text.length)
            restoring = false

            // 如果历史译文正好符合智能模式的默认目标，保持「智能」不变。
            targetLang =
                if (targetLang == Languages.SMART &&
                    restoredTarget == Languages.resolveTarget(input, Languages.SMART)
                ) {
                    Languages.SMART
                } else {
                    restoredTarget
                }
            updateTargetViews()

            if (output.isBlank()) showIdle() else showResult(output, restoredTarget)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        inputText = findViewById(R.id.input_text)
        inputArea = findViewById(R.id.input_area)
        outputText = findViewById(R.id.output_text)
        outputError = findViewById(R.id.output_error)
        outputLang = findViewById(R.id.output_lang)
        outputArea = findViewById(R.id.output_area)
        divider = findViewById(R.id.divider)
        contentScroll = findViewById(R.id.content_scroll)
        progress = findViewById(R.id.translate_progress)
        targetValue = findViewById(R.id.target_lang_value)
        apiBanner = findViewById(R.id.api_banner)
        btnCopy = findViewById(R.id.btn_copy)
        btnShare = findViewById(R.id.btn_share)
        btnFullscreen = findViewById(R.id.btn_fullscreen)
        btnClear = findViewById(R.id.btn_clear)
        btnCamera = findViewById(R.id.btn_camera)
        btnTranslate = findViewById(R.id.btn_translate)
        photoContainer = findViewById(R.id.photo_container)
        photoPreview = findViewById(R.id.photo_preview)

        targetLang = Prefs.targetLang(this)
        updateTargetViews()
        showIdle()

        // 无译文时，整个卡片区域（包括 NestedScrollView 的空白部分）都能弹键盘。
        // 有译文后，只保留 input_text 自己的点击行为。
        contentScroll.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && !hasTranslation) {
                inputText.requestFocus()
                inputText.postDelayed({
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(inputText, InputMethodManager.SHOW_IMPLICIT)
                }, 50)
                return@setOnTouchListener true
            }
            false
        }
        inputArea.setOnClickListener {
            inputText.requestFocus()
            inputText.postDelayed({
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(inputText, InputMethodManager.SHOW_IMPLICIT)
            }, 100)
        }
        inputText.setOnClickListener {
            scrollInputToCursor()
        }

        // 监听窗口高度变化，估算键盘高度并做定位。
        // 键盘弹出/收起时，等 viewport 实际收缩/恢复后再重滚到 40% 位置，
        // 避免 adjustResize 把分隔线挤出可视区。
        contentScroll.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            contentScroll.getWindowVisibleDisplayFrame(rect)
            val visibleHeight = rect.bottom - rect.top
            if (visibleHeight <= 0) return@addOnGlobalLayoutListener
            val windowHeight = contentScroll.rootView?.height ?: 0
            keyboardHeight = (windowHeight - visibleHeight).coerceAtLeast(0)

            when {
                // 键盘弹出（0 → >0）：等 viewport 收缩后重滚，译文占 60% 可视区。
                lastKeyboardHeight == 0 && keyboardHeight > 0 && hasTranslation ->
                    rescrollAfterKeyboardChange()

                // 键盘收起（>0 → 0）：等 viewport 恢复后重滚，定位准确。
                lastKeyboardHeight > 0 && keyboardHeight == 0 && hasTranslation ->
                    rescrollAfterKeyboardChange()
            }
            lastKeyboardHeight = keyboardHeight
        }

        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (restoring) return
                if (photoUri != null) {
                    if (text.isBlank()) return
                    clearPhotoState()
                }
                updateTargetViews()
                handler.removeCallbacks(translateRunnable)
                requestSeq++
                if (text.isBlank()) {
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

        findViewById<View>(R.id.target_lang).setOnClickListener { pickTargetLanguage() }
        findViewById<View>(R.id.btn_menu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.btn_history).setOnClickListener { openHistory() }
        findViewById<View>(R.id.btn_remove_photo).setOnClickListener { clearAll() }
        btnClear.setOnClickListener { clearAll() }
        findViewById<View>(R.id.api_banner).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnTranslate.setOnClickListener { translateNow(true) }
        btnCamera.setOnClickListener { takePhoto() }
        btnCopy.setOnClickListener { copyTranslation() }
        btnShare.setOnClickListener { shareTranslation() }
        btnFullscreen.setOnClickListener { openFullscreen() }

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
        if (Prefs.profiles(this).isEmpty()) return
        when {
            photoUri != null && !hasTranslation -> {
                handler.removeCallbacks(translateRunnable)
                handler.postDelayed({ translatePhoto() }, 300)
            }
            inputText.text.isNotBlank() && !hasTranslation -> {
                handler.removeCallbacks(translateRunnable)
                handler.postDelayed(translateRunnable, 300)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(translateRunnable)
        photoPreview.setImageDrawable(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun updateApiBanner() {
        apiBanner.visibility =
            if (Prefs.profiles(this).isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateTargetViews() {
        targetValue.text =
            if (targetLang == Languages.SMART) getString(R.string.smart_target) else targetLang

        if (outputArea.visibility != View.VISIBLE) return
        val shownTarget = when {
            photoUri != null ->
                if (targetLang == Languages.SMART) getString(R.string.smart_target) else targetLang
            else -> {
                val text = inputText.text.toString()
                if (text.isBlank()) targetLang else Languages.resolveTarget(text, targetLang)
            }
        }
        outputLang.text = getString(R.string.to_lang, shownTarget)
    }

    private fun pickTargetLanguage() {
        val values = Languages.target
        val labels = values.map {
            if (it == Languages.SMART) getString(R.string.smart_target_recommended) else it
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_target_language)
            .setItems(labels.toTypedArray()) { _, which ->
                val value = values[which]
                targetLang = value
                Prefs.setTargetLang(this, value)
                updateTargetViews()
                when {
                    photoUri != null -> translatePhoto()
                    inputText.text.isNotBlank() -> translateNow(false)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun translateNow(fromButton: Boolean) {
        handler.removeCallbacks(translateRunnable)
        if (photoUri != null) {
            translatePhoto()
            return
        }
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
        val requestTarget = Languages.resolveTarget(text, targetLang)
        showLoading(requestTarget)

        executor.execute {
            val result = TranslateClient.translate(profile, text, requestTarget)
            handler.post {
                if (seq != requestSeq || isFinishing || isDestroyed) return@post
                val translated = result.getOrNull()
                if (!translated.isNullOrBlank()) {
                    showResult(translated, requestTarget)
                    Prefs.addHistory(
                        this,
                        Languages.AUTO,
                        requestTarget,
                        text,
                        translated
                    )
                } else {
                    showErrorText(
                        getString(R.string.translate_failed, friendlyError(result.exceptionOrNull()))
                    )
                }
            }
        }
    }

    private fun translatePhoto() {
        handler.removeCallbacks(translateRunnable)
        val uri = photoUri ?: return
        val profile = Prefs.activeProfile(this)
        if (profile == null) {
            updateApiBanner()
            showErrorText(getString(R.string.api_not_configured))
            return
        }

        val smart = targetLang == Languages.SMART
        val requestTarget = if (smart) Languages.SMART else Languages.normalizeTarget(targetLang)
        val displayTarget =
            if (smart) getString(R.string.smart_target) else requestTarget
        val seq = ++requestSeq
        showLoading(displayTarget)

        val cached = photoBase64
        if (cached != null) {
            requestPhotoTranslation(profile, cached, requestTarget, smart, displayTarget, seq)
            return
        }

        executor.execute {
            val prepared = runCatching { PhotoUtils.prepare(this, uri) }
            handler.post {
                if (seq != requestSeq || isFinishing || isDestroyed) return@post
                val data = prepared.getOrNull()
                if (data == null) {
                    showErrorText(
                        getString(
                            R.string.translate_failed,
                            friendlyError(prepared.exceptionOrNull())
                        )
                    )
                    return@post
                }
                photoPreview.setImageBitmap(data.preview)
                photoBase64 = data.base64
                requestPhotoTranslation(
                    profile,
                    data.base64,
                    requestTarget,
                    smart,
                    displayTarget,
                    seq
                )
            }
        }
    }

    private fun requestPhotoTranslation(
        profile: ApiProfile,
        base64: String,
        target: String,
        smart: Boolean,
        displayTarget: String,
        seq: Int
    ) {
        executor.execute {
            val result = TranslateClient.translateImage(profile, base64, target, smart)
            handler.post {
                if (seq != requestSeq || isFinishing || isDestroyed) return@post
                val translated = result.getOrNull()
                if (!translated.isNullOrBlank()) {
                    showResult(translated, displayTarget)
                    Prefs.addHistory(
                        this,
                        Languages.AUTO,
                        target,
                        getString(R.string.image_translation),
                        translated,
                        image = true
                    )
                } else {
                    showErrorText(
                        getString(R.string.translate_failed, friendlyError(result.exceptionOrNull()))
                    )
                }
            }
        }
    }

    private fun showPhoto(uri: Uri) {
        photoUri = uri
        photoBase64 = null
        photoContainer.visibility = View.VISIBLE
        inputText.visibility = View.GONE
        photoPreview.setImageDrawable(null)
        restoring = true
        inputText.setText("")
        restoring = false
        hideKeyboard()
        showIdle()
    }

    private fun clearPhotoState() {
        photoUri = null
        photoBase64 = null
        photoContainer.visibility = View.GONE
        photoPreview.setImageDrawable(null)
        inputText.visibility = View.VISIBLE
    }

    private fun takePhoto() {
        val dir = File(cacheDir, "images")
        if (!dir.exists() && !dir.mkdirs()) {
            toast(getString(R.string.photo_read_failed))
            return
        }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (_: Exception) {
            toast(getString(R.string.photo_read_failed))
            return
        }
        pendingPhotoUri = uri

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("photo", uri)
        }
        try {
            cameraLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            pendingPhotoUri = null
            toast(getString(R.string.camera_unavailable))
        } catch (_: Exception) {
            pendingPhotoUri = null
            toast(getString(R.string.camera_unavailable))
        }
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(inputText.windowToken, 0)
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

    private fun showLoading(target: String) {
        lastOutputTarget = target
        divider.visibility = View.VISIBLE
        outputArea.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        outputError.visibility = View.GONE
        outputLang.visibility = View.VISIBLE
        outputLang.text = getString(R.string.to_lang, target)
        outputText.text = getString(R.string.translating)
        outputText.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        hasTranslation = false
        setActionsEnabled(false)
        scrollToResult()
    }

    private fun showResult(text: String, target: String) {
        lastOutputTarget = target
        divider.visibility = View.VISIBLE
        outputArea.visibility = View.VISIBLE
        hasTranslation = true
        outputText.text = text
        outputText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        outputError.visibility = View.GONE
        progress.visibility = View.GONE
        outputLang.visibility = View.VISIBLE
        outputLang.text = getString(R.string.to_lang, target)
        setActionsEnabled(true)
        scrollToResult()
    }

    private fun showIdle() {
        hasTranslation = false
        lastOutputTarget = ""
        divider.visibility = View.GONE
        outputArea.visibility = View.GONE
        progress.visibility = View.GONE
        outputError.visibility = View.GONE
        outputText.text = ""
        setActionsEnabled(false)
        contentScroll.scrollTo(0, 0)
        updateTargetViews()
    }

    private fun showErrorText(message: String) {
        divider.visibility = View.VISIBLE
        outputArea.visibility = View.VISIBLE
        outputError.text = message
        outputError.visibility = View.VISIBLE
        progress.visibility = View.GONE
        setActionsEnabled(hasTranslation)
        if (!hasTranslation) {
            outputText.text = ""
            outputText.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        btnCopy.isEnabled = enabled
        btnShare.isEnabled = enabled
        btnFullscreen.isEnabled = enabled
        btnCopy.alpha = if (enabled) 1f else 0.35f
        btnShare.alpha = if (enabled) 1f else 0.35f
        btnFullscreen.alpha = if (enabled) 1f else 0.35f
    }

    private fun openFullscreen() {
        if (!hasTranslation) {
            toast(getString(R.string.no_translation_yet))
            return
        }
        val text = outputText.text.toString()
        if (text.isBlank()) {
            toast(getString(R.string.no_translation_yet))
            return
        }
        FullscreenContent.text = text
        FullscreenContent.target =
            lastOutputTarget.ifBlank { targetValue.text.toString() }
        startActivity(Intent(this, FullscreenActivity::class.java))
    }

    private fun scrollToResult() {
        contentScroll.post {
            // 键盘弹出时 viewport 会被压缩（adjustResize），用估算的键盘高度
            // 算收缩后的可视高度，再把分隔线定位到 40% 处（译文占 60%）。
            val visibleHeight = if (keyboardHeight > 0) {
                (contentScroll.height - keyboardHeight).coerceAtLeast(100)
            } else {
                contentScroll.height
            }
            val targetY = (divider.top - visibleHeight * 0.4f).toInt().coerceAtLeast(0)
            contentScroll.smoothScrollTo(0, targetY)
        }
    }

    private fun rescrollAfterKeyboardChange() {
        // 等 viewport 实际收缩/恢复后再重滚，定位才准确。
        contentScroll.post { scrollToResult() }
    }

    private fun scrollInputToCursor() {
        val selection = inputText.selectionStart.coerceAtLeast(inputText.selectionEnd)
        val layout = inputText.layout ?: return
        val line = layout.getLineForOffset(selection)
        val lineBottom = layout.getLineBottom(line)
        val cursorYInEditText = lineBottom + inputText.paddingTop
        val inputAreaTopInScroll = inputArea.top
        val cursorYInScroll = inputAreaTopInScroll + cursorYInEditText

        // 键盘弹出时，用估算的键盘高度算可视区高度；未弹出时用当前 viewport。
        val visibleHeight = if (keyboardHeight > 0) {
            (contentScroll.height - keyboardHeight).coerceAtLeast(100)
        } else {
            contentScroll.height
        }
        // 光标定位到键盘上方可视区的 1/3 处（偏上，给下方留空间看后续内容）
        val targetY = (cursorYInScroll - visibleHeight / 3).coerceAtLeast(0)
        contentScroll.smoothScrollTo(0, targetY)
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

    private fun clearAll() {
        restoring = true
        inputText.setText("")
        restoring = false
        clearPhotoState()
        requestSeq++
        handler.removeCallbacks(translateRunnable)
        showIdle()
        updateApiBanner()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_current)
            .setMessage(R.string.clear_current_message)
            .setPositiveButton(R.string.clear) { _, _ -> clearAll() }
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
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.3.15"
        } catch (_: Exception) {
            "1.3.15"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
