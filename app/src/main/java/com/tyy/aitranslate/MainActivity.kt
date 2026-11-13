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
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private companion object {
        const val AUTO_DELAY_MS = 300L
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
    private lateinit var scrollContent: LinearLayout
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
    private var pendingPhotoUri: Uri? = null
    private var photoUri: Uri? = null
    private var photoBase64: String? = null
    private var lastViewportHeight = 0
    private var scrollTouchDownX = 0f
    private var scrollTouchDownY = 0f
    private var keyboardVisible = false

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
            val entry = HistoryPick.entry ?: return@registerForActivityResult
            HistoryPick.entry = null
            val input = entry.input
            val output = entry.output
            val isImage = entry.image
            val restoredTarget = Languages.normalizeTarget(
                entry.target,
                Prefs.customTargets(this)
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
                    restoredTarget == Languages.resolveTarget(
                        input,
                        Languages.SMART,
                        Prefs.customTargets(this)
                    )
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
        scrollContent = findViewById(R.id.scroll_content)
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

        // 无译文时，点击卡片空白区域（包括 NestedScrollView 的空白部分）弹键盘。
        // 有译文后，只保留 input_text 自己的点击行为。
        // 注意：滑动结束同样是 ACTION_UP，必须用移动距离区分「点击」和「滑动」，
        // 否则滚动译文松手就会误弹键盘（翻译中 hasTranslation 仍为 false，最明显）。
        contentScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scrollTouchDownX = event.x
                    scrollTouchDownY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - scrollTouchDownX
                    val dy = event.y - scrollTouchDownY
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    val isTap = dx * dx + dy * dy <= slop * slop
                    if (isTap && !hasTranslation) {
                        inputText.requestFocus()
                        inputText.postDelayed({
                            val imm =
                                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(inputText, InputMethodManager.SHOW_IMPLICIT)
                        }, 50)
                        return@setOnTouchListener true
                    }
                }
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
            // 首次点击交给 OnGlobalLayout 在 viewport 收缩后定位；
            // 后续点击（viewport 已收缩）直接定位到当前光标位置。
            if (contentScroll.height > 0 && lastViewportHeight == contentScroll.height) {
                scrollInputToCursor()
            }
        }

        // 键盘上的「完成/换行」键也触发翻译（不同输入法行为不同，三层兜底：
        // 这里处理 IME_ACTION_* 和产生 KeyEvent 的回车）。
        inputText.setOnEditorActionListener { _, actionId, event ->
            val enterDown = event != null &&
                event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            val imeAction = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEND
            if (enterDown || imeAction) {
                translateNow(true)
                true
            } else {
                false
            }
        }

        // 监听 contentScroll 的高度变化，只在 viewport 真正收缩/恢复时做一次定位。
        // 用 contentScroll.height 而非 insets 差值：键盘收起时 viewport 必然变大，
        // 比「windowHeight - visibleHeight」更可靠（后者在某些 ROM 上一拍才归 0）。
        contentScroll.viewTreeObserver.addOnGlobalLayoutListener {
            val newViewport = contentScroll.height
            if (newViewport == 0 || newViewport == lastViewportHeight) return@addOnGlobalLayoutListener
            val previous = lastViewportHeight
            lastViewportHeight = newViewport

            when {
                // viewport 收缩（键盘弹出）：把光标定位到新的可视区中间
                newViewport < previous -> {
                    keyboardVisible = true
                    if (inputText.hasFocus()) {
                        contentScroll.post { scrollInputToCursor() }
                    }
                }
                // viewport 恢复（键盘收起）：有译文则重新定位到 1/3 处
                newViewport > previous -> {
                    keyboardVisible = false
                    // 只要译文区可见就重滚：翻译进行中（「正在翻译…」）还没出结果时
                    // hasTranslation 仍为 false，长输入同样会把 loading 挤到屏幕下方。
                    if (outputArea.visibility == View.VISIBLE) {
                        contentScroll.post { scrollToResult() }
                    }
                }
            }
        }

        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (restoring) return
                // 部分输入法在 multiline 下把回车作为字符提交（不产生 KeyEvent）：
                // 键盘可见时视为「回车翻译」，移除该换行并立即请求。
                if (keyboardVisible && text.endsWith("\n")) {
                    val trimmed = text.trimEnd('\n')
                    restoring = true
                    inputText.setText(trimmed)
                    inputText.setSelection(trimmed.length)
                    restoring = false
                    translateNow(true)
                    return
                }
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
                if (keyboardVisible) {
                    // 键盘可见说明是手动输入，不自动翻译（省 token），由用户点按钮触发
                    handler.removeCallbacks(translateRunnable)
                } else {
                    // 无键盘时多为粘贴/程序写入，延迟自动翻译
                    handler.postDelayed(translateRunnable, AUTO_DELAY_MS)
                }
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
        findViewById<View>(R.id.nav_prompt).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, PromptSettingsActivity::class.java))
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
        // 设置页可能删除了自定义语言，重新校验当前选择
        val restoredTarget = Prefs.targetLang(this)
        if (restoredTarget != targetLang) {
            targetLang = restoredTarget
            updateTargetViews()
        }
        if (Prefs.profiles(this).isEmpty()) return
        when {
            photoUri != null && !hasTranslation -> {
                handler.removeCallbacks(translateRunnable)
                handler.postDelayed({ translatePhoto() }, 300)
            }
            // 键盘仍然可见时不自动翻译，避免从后台回来时覆盖用户正在编辑的内容
            inputText.text.isNotBlank() && !hasTranslation && !keyboardVisible -> {
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
                if (text.isBlank()) targetLang
                else Languages.resolveTarget(text, targetLang, Prefs.customTargets(this))
            }
        }
        outputLang.text = getString(R.string.to_lang, shownTarget)
    }

    private fun pickTargetLanguage() {
        val values = Prefs.targetLanguages(this)
        val labels = values.map {
            if (it == Languages.SMART) getString(R.string.smart_target_recommended) else it
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_target_language)
            .setItems(labels.toTypedArray()) { _, which ->
                applyTarget(values[which])
            }
            .setNeutralButton(R.string.custom_target_add) { _, _ ->
                // 添加自定义语言后直接选中，省一次点击
                showAddLanguageDialog { name -> applyTarget(name) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyTarget(value: String) {
        targetLang = value
        Prefs.setTargetLang(this, value)
        updateTargetViews()
        when {
            photoUri != null -> translatePhoto()
            inputText.text.isNotBlank() -> translateNow(false)
        }
    }

    private fun showAddLanguageDialog(onAdded: (String) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.custom_target_add_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        layout.addView(input)
        container.addView(layout)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_target_add_title)
            .setView(container)
            .setPositiveButton(R.string.custom_target_add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    layout.error = getString(R.string.custom_target_invalid)
                    return@setOnClickListener
                }
                if (!Prefs.addCustomTarget(this, name)) {
                    layout.error = getString(R.string.custom_target_duplicate)
                    return@setOnClickListener
                }
                toast(getString(R.string.custom_target_added, name))
                dialog.dismiss()
                onAdded(name)
            }
        }
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        val requestTarget = Languages.resolveTarget(
            text,
            targetLang,
            Prefs.customTargets(this)
        )
        val prompt = Prefs.promptText(this)
        showLoading(requestTarget)

        executor.execute {
            val result = TranslateClient.translate(profile, text, requestTarget, prompt)
            handler.post {
                if (seq != requestSeq || isFinishing || isDestroyed) return@post
                val translated = result.getOrNull()
                if (!translated.isNullOrBlank()) {
                    // 拼写错误的单词：模型输出 [INVALID_WORD] 标记行，剥掉标记只显示修正建议
                    val display = formatInvalidWord(translated)
                    showResult(display, requestTarget)
                    Prefs.addHistory(
                        this,
                        Languages.AUTO,
                        requestTarget,
                        text,
                        display
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
        val requestTarget = if (smart) Languages.SMART
        else Languages.normalizeTarget(targetLang, Prefs.customTargets(this))
        val prompt = Prefs.promptImage(this)
        val displayTarget =
            if (smart) getString(R.string.smart_target) else requestTarget
        val seq = ++requestSeq
        showLoading(displayTarget)

        val cached = photoBase64
        if (cached != null) {
            requestPhotoTranslation(
                profile,
                cached,
                requestTarget,
                smart,
                displayTarget,
                prompt,
                seq
            )
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
                    prompt,
                    seq
                )
            }
        }
    }

    /**
     * 拼写错误/不存在单词的输出处理：模型返回 [INVALID_WORD] 标记行，
     * 后跟目标语言的修正建议。剥掉机器标记只显示建议内容；
     * 模型只回了标记没给内容时，用默认文案兜底。
     */
    private fun formatInvalidWord(output: String): String {
        val trimmed = output.trim()
        if (!trimmed.startsWith(Prompts.INVALID_WORD_MARK)) return output
        return trimmed.removePrefix(Prompts.INVALID_WORD_MARK).trim().ifBlank {
            getString(R.string.invalid_word)
        }
    }

    private fun requestPhotoTranslation(
        profile: ApiProfile,
        base64: String,
        target: String,
        smart: Boolean,
        displayTarget: String,
        prompt: String,
        seq: Int
    ) {
        executor.execute {
            val result = TranslateClient.translateImage(profile, base64, target, smart, prompt)
            handler.post {
                if (seq != requestSeq || isFinishing || isDestroyed) return@post
                val translated = result.getOrNull()
                if (!translated.isNullOrBlank()) {
                    // 新提示词的兜底输出是机器标记，转成本地化文案再显示
                    val display = when (translated.trim()) {
                        Prompts.NO_TEXT_MARK -> getString(R.string.image_no_text)
                        Prompts.INVALID_TARGET_MARK -> getString(R.string.invalid_target)
                        else -> translated
                    }
                    showResult(display, displayTarget)
                    Prefs.addHistory(
                        this,
                        Languages.AUTO,
                        target,
                        getString(R.string.image_translation),
                        display,
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
        // 加载中也滚一次：让「翻译中」占位可见（用户编辑长文本后 viewport 已滚到底，
        // 不滚的话「翻译中」会在屏幕外）；结果到达后 showResult 会再滚到 1/3 处。
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
        scrollContent.setPadding(0, 0, 0, 0)
        contentScroll.smoothScrollTo(0, 0)
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
        // 错误提示也位于译文区，同样定位到可视区内
        scrollToResult()
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
            // 译文占 viewport 下方 2/3：把分隔线定位到 viewport 的 1/3 处，
            // 上方 1/3 保留输入内容尾部，下方 2/3 显示译文。
            val viewportHeight = contentScroll.height
            if (viewportHeight == 0) return@post

            // 输入很长、译文区很短时，内容总高度不足以把分隔线滚到 1/3 处，
            // smoothScrollTo 会被 clamp 到底部，译文仍贴在屏幕最下面。
            // 在内容底部补空白（最多补到「分隔线能到 1/3」所需的距离），
            // 保证目标位置可达；译文比 2/3 屏长时不补，避免底部出现多余空白。
            val outputHeight = if (outputArea.height > 0) {
                outputArea.height
            } else {
                outputArea.minimumHeight
            }
            val paddingBottom = ((viewportHeight * 2f / 3f).toInt() - outputHeight)
                .coerceAtLeast(0)
            scrollContent.setPadding(0, 0, 0, paddingBottom)

            // 等补白后的布局生效再滚，否则 maxScroll 仍按旧高度计算
            scrollContent.post {
                val targetY = (divider.top - viewportHeight / 3f).toInt().coerceAtLeast(0)
                contentScroll.smoothScrollTo(0, targetY)
            }
        }
    }

    private fun scrollInputToCursor() {
        if (!inputText.hasFocus()) return
        val selection = inputText.selectionStart.coerceAtLeast(inputText.selectionEnd)
        val layout = inputText.layout ?: return
        val line = layout.getLineForOffset(selection)
        val lineBottom = layout.getLineBottom(line)
        val cursorYInScroll = inputArea.top + lineBottom + inputText.paddingTop

        // 用当前 contentScroll.height（键盘弹出后已被 adjustResize 收缩）算可视区，
        // 把点击点定位到可视区垂直中间，编辑文字始终在键盘上方居中可见。
        val viewportHeight = contentScroll.height
        if (viewportHeight == 0) return
        val targetY = (cursorYInScroll - viewportHeight / 2).coerceAtLeast(0)
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
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.3.39"
        } catch (_: Exception) {
            "1.3.39"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
