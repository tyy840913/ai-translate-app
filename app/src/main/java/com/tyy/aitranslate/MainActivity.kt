package com.tyy.aitranslate

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
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
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private companion object {
        const val AUTO_DELAY_MS = 300L
        const val CLIPBOARD_BANNER_MS = 6000L
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
    private lateinit var btnSpeak: ImageButton
    private lateinit var btnRetry: TextView
    private lateinit var clipboardBanner: MaterialCardView
    private lateinit var clipboardPreview: TextView
    private lateinit var btnClear: ImageButton
    private lateinit var btnCamera: ImageButton
    private lateinit var btnTranslate: ImageButton
    private lateinit var photoContainer: View
    private lateinit var photoPreview: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val translateRunnable = Runnable { translateNow(false) }
    private val hideClipboardBanner = Runnable { clipboardBanner.visibility = View.GONE }

    private var targetLang = Languages.SMART
    private var lastOutputTarget = ""
    private var requestSeq = 0
    private var restoring = false
    private var hasTranslation = false
    private var pendingPhotoUri: Uri? = null
    private var photoUri: Uri? = null
    private var photoBase64: String? = null
    private var lastViewportHeight = 0
    // 收起键盘后的全屏视口高：键盘态定位用它预留收起后的滚动上限，
    // 防止收起键盘时 scrollY 超限被系统强制回弹
    private var fullViewportHeight = 0
    private var scrollTouchDownX = 0f
    private var scrollTouchDownY = 0f
    private var keyboardVisible = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var pendingSpeak: String? = null
    private var ttsEnginePackage: String? = null
    private var ttsTriedEngines = mutableSetOf<String>()
    private var lastTtsError = ""
    private var lastClipboardPrompt = ""

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = pendingPhotoUri
            pendingPhotoUri = null
            if (result.resultCode == RESULT_OK && uri != null) {
                processPickedImage(uri, fallback = true)
            }
        }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            processPickedImage(uri, fallback = false)
        }

    /**
     * 拍照/相册统一入口：解码 → EXIF 校正 → 缩到最长边 1600px → JPEG 85% 转码落盘，
     * 再走 FileProvider。相册几十 MB 的原图/HEIC 也只写几百 KB，避免超长复制与 cache 膨胀。
     * 相册 URI 的读权限只限当次，转码进 cache 后切换目标语言重译仍可读。
     * 拍照路径转码失败时回退用相机直出文件（相机已写好，可正常读）；
     * 相册失败只能提示重选。
     */
    private fun processPickedImage(source: Uri, fallback: Boolean) {
        executor.execute {
            val processed =
                File(File(cacheDir, "images"), "photo_${System.currentTimeMillis()}.jpg")
            val ok = PhotoUtils.processToFile(this, source, processed)
            if (!ok) processed.delete()
            handler.post {
                if (isFinishing || isDestroyed) return@post
                val target = if (ok) {
                    runCatching {
                        FileProvider.getUriForFile(this, "$packageName.fileprovider", processed)
                    }.getOrNull()
                } else {
                    null
                }
                when {
                    target != null -> {
                        showPhoto(target)
                        translatePhoto()
                    }
                    fallback -> {
                        showPhoto(source)
                        translatePhoto()
                    }
                    else -> toast(getString(R.string.photo_read_failed))
                }
            }
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
        btnSpeak = findViewById(R.id.btn_speak)
        btnRetry = findViewById(R.id.btn_retry)
        clipboardBanner = findViewById(R.id.clipboard_banner)
        clipboardPreview = findViewById(R.id.clipboard_preview)
        initTts()
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
                // viewport 恢复（键盘收起）：有译文/翻译中则滚动到固定位置
                // （分隔线定位全屏视口 1/3 处，译文占下方 2/3，完整可见）。
                // 试过收起后保持原位，但系统在窗口 resize 时会自行调整滚动，
                // 长文本下译文仍会被顶出可视区；改为直接定位最直观稳定。
                newViewport > previous -> {
                    keyboardVisible = false
                    // 记录全屏视口高（App 启动后首次 layout 也会走到这里完成初始化）
                    fullViewportHeight = newViewport
                    // 只要译文区可见就滚：翻译进行中（「正在翻译…」）还没出结果时
                    // 同样需要，长输入会把 loading 挤到屏幕下方。
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
        btnCamera.setOnClickListener { choosePhotoSource() }
        btnCopy.setOnClickListener { copyTranslation() }
        btnShare.setOnClickListener { shareTranslation() }
        btnFullscreen.setOnClickListener { openFullscreen() }
        btnSpeak.setOnClickListener { speakTranslation() }
        btnRetry.setOnClickListener {
            if (photoUri != null) translatePhoto() else translateNow(false)
        }
        clipboardBanner.setOnClickListener { translateClipboard() }

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
        if (ttsFailed) reinitTts()
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
        handler.removeCallbacks(hideClipboardBanner)
        photoPreview.setImageDrawable(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        pendingSpeak = null
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+ 只允许有焦点的应用读剪贴板，onResume 时未必拿到焦点，等这里再检测
        if (hasFocus) checkClipboard()
    }

    private fun checkClipboard() {
        // 图片模式不打扰
        if (photoUri != null) {
            clipboardBanner.visibility = View.GONE
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
        // 超长内容不像要翻译的文本；同一内容只提示一次
        if (text.isEmpty() || text.length > 5000) return
        if (text == lastClipboardPrompt) return
        // 输入框已有相同内容、或就是本页刚复制的译文，不再打扰
        if (text == inputText.text.toString().trim()) return
        if (text == outputText.text.toString().trim()) return
        // 明显不需要翻译的内容（URL、域名、邮箱、纯数字/符号）不打扰
        if (!worthPrompting(text)) return
        lastClipboardPrompt = text
        clipboardPreview.text = text
        clipboardBanner.visibility = View.VISIBLE
        // 横幅不再常驻：几秒后自动消失，避免一直挂在界面上
        handler.removeCallbacks(hideClipboardBanner)
        handler.postDelayed(hideClipboardBanner, CLIPBOARD_BANNER_MS)
    }

    /**
     * 剪贴板内容值得提示翻译吗：
     * - 没有任何字母/汉字（纯数字、号码、金额、符号、标点）→ 不提示；
     * - 整段就是一个 URL / 域名 / 邮箱 → 不提示；
     * - 混合文本里的 URL 不误伤（含其他可译内容，翻译时 URL 本来就保持原样）。
     */
    private fun worthPrompting(text: String): Boolean {
        if (text.none { it.isLetter() }) return false
        if (text.startsWith("http://", true) ||
            text.startsWith("https://", true) ||
            text.startsWith("www.", true)
        ) return false
        if (!text.contains(' ') && (
                text.matches(Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")) ||
                    text.matches(Regex("[\\w-]+(\\.[\\w-]+)+(/[^\\s]*)?"))
            )
        ) return false
        return true
    }

    private fun translateClipboard() {
        val text = clipboardPreview.text.toString().trim()
        handler.removeCallbacks(hideClipboardBanner)
        clipboardBanner.visibility = View.GONE
        if (text.isEmpty()) return
        inputText.setText(text)
        inputText.setSelection(text.length)
        translateNow(false)
    }

    private fun initTts(enginePackage: String? = null) {
        // 朗读引擎异步初始化：失败标记不可用；就绪后自动补播刚才点的朗读。
        // enginePackage 为空用系统默认引擎；指定的引擎绑定失败时自动换下一个。
        tts?.shutdown()
        tts = null
        ttsReady = false
        ttsFailed = false
        ttsEnginePackage = enginePackage
        tts = if (enginePackage == null) {
            TextToSpeech(this) { status -> onTtsInit(status) }
        } else {
            TextToSpeech(this, { status -> onTtsInit(status) }, enginePackage)
        }
    }

    private fun onTtsInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            ttsTriedEngines.clear()
            // speak() 排队成功不代表一定出声：引擎运行期失败只有进度回调能抓到，
            // 记下诊断码，下次不可用弹窗里能看到
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) {
                    lastTtsError = "utterance pkg=${ttsEnginePackage ?: "default"}"
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    lastTtsError = "utterance=$errorCode pkg=${ttsEnginePackage ?: "default"}"
                }
            })
            pendingSpeak?.let { text ->
                pendingSpeak = null
                doSpeak(text)
            }
            return
        }
        // 默认引擎失败：枚举设备上所有 TTS 引擎逐个试（默认引擎可能没装数据或损坏）
        lastTtsError = "init status=$status pkg=${ttsEnginePackage ?: "default"}"
        ttsTriedEngines.add(ttsEnginePackage ?: "default")
        val next = installedTtsEngines()
            .map { it.packageName }
            .firstOrNull { it !in ttsTriedEngines }
        if (next != null) {
            initTts(next)
        } else {
            ttsFailed = true
        }
    }

    private fun reinitTts() {
        // 从系统设置启用/更换引擎后回到前台：失败状态重连一次
        ttsTriedEngines.clear()
        initTts(ttsEnginePackage?.takeIf { it !in ttsTriedEngines })
    }

    /** 设备上所有已安装的 TTS 引擎（不依赖当前 TTS 连接状态） */
    private fun installedTtsEngines(): List<EngineLabel> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return try {
            packageManager.queryIntentServices(intent, 0)
                .mapNotNull { info ->
                    val pkg = info.serviceInfo?.packageName ?: return@mapNotNull null
                    val label = try {
                        info.loadLabel(packageManager).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    EngineLabel(pkg, label)
                }
                .sortedWith(compareByDescending<EngineLabel> { it.packageName == ttsEnginePackage }.thenBy { it.label })
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun speakTranslation() {
        val text = outputText.text.toString().trim()
        if (text.isBlank()) return
        val engine = tts
        if (engine == null || ttsFailed) {
            // 引擎确认不可用（设备没装 TTS 或初始化失败），引导去系统设置
            showTtsUnavailable()
            return
        }
        if (!ttsReady) {
            // 只是还没初始化完：记住这段译文，回调到达后自动播
            pendingSpeak = text
            toast(getString(R.string.tts_loading))
            return
        }
        doSpeak(text)
    }

    private fun doSpeak(text: String) {
        val engine = tts ?: return
        engine.stop()
        // 按译文内容推断朗读语言：含汉字读中文，其余按英文朗读
        val locale =
            if (text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
                Locale.SIMPLIFIED_CHINESE
            } else {
                Locale.US
            }
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 引擎在但缺语音数据（常见：只有中文没有英文），说清楚缺哪种；
            // 换一个已装引擎自动重试，全不行才弹引导
            lastTtsError = "setLanguage=$result pkg=${ttsEnginePackage ?: "default"}"
            val langName = if (locale == Locale.SIMPLIFIED_CHINESE) "中文" else "英文"
            toast(getString(R.string.tts_lang_missing, langName))
            retryWithNextEngine(text)
            return
        }
        // 引擎一切正常但媒体音量是 0：不会报错就是无声，提前提醒别误以为 App 坏了
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            toast(getString(R.string.tts_volume_zero))
        }
        val spoken = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation")
        if (spoken == TextToSpeech.ERROR) {
            // init/setLanguage 都成功但 speak 被拒（引擎服务僵死等）：
            // 原来这里直接静默无声，现在记诊断码并换引擎自动重试
            lastTtsError = "speak=-1 pkg=${ttsEnginePackage ?: "default"}"
            retryWithNextEngine(text)
            return
        }
    }

    /** 换下一个没试过的引擎重连；重连成功后自动接着播 text，全试完弹引导 */
    private fun retryWithNextEngine(text: String) {
        ttsTriedEngines.add(ttsEnginePackage ?: "default")
        val next = installedTtsEngines()
            .map { it.packageName }
            .firstOrNull { it !in ttsTriedEngines }
        if (next == null) {
            ttsFailed = true
            showTtsUnavailable()
            return
        }
        pendingSpeak = text
        initTts(next)
    }

    private fun showTtsUnavailable() {
        // 用 PackageManager 列引擎清单（不依赖 TTS 连接，连不上也能列出设备上的引擎），
        // 附上失败原因码，方便远程定位
        val engines = installedTtsEngines()
            .map { info -> "${info.label}（${info.packageName}）" }
            .distinct()
        val message = when {
            engines.isEmpty() -> getString(R.string.tts_no_engines_message)
            else -> getString(R.string.tts_engines_message, engines.joinToString("\n"))
        } + if (lastTtsError.isNotBlank()) "\n\n（${lastTtsError}）" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tts_unavailable_title)
            .setMessage(message)
            .setPositiveButton(R.string.tts_open_settings) { _, _ -> openTtsSettings() }
            .setNeutralButton(R.string.tts_market) { _, _ -> openMarketForTts() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openMarketForTts() {
        // 没有引擎时引导去应用市场装一个（国内 ROM 均支持 market 协议）
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=文字转语音 TTS"))
            )
        } catch (_: Exception) {
            toast(getString(R.string.tts_market_failed))
        }
    }

    private fun openTtsSettings() {
        // 公开 SDK 里没有直达 TTS 设置的官方常量：
        // 先用广泛支持的系统 action 直达「文字转语音」页，
        // 失败再退到公开的「语音输入与输出」设置页。
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            return
        } catch (_: Exception) {
        }
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Exception) {
            toast(getString(R.string.tts_no_settings))
        }
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

    /** TTS 引擎的包名与显示名，用于诊断列表 */
    private data class EngineLabel(val packageName: String, val label: String)

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
        // 每次换图都清一次旧文件，cache/images 只保留当前这张，防止越用越大
        cleanupOldPhotos(uri)
    }

    /** 删除 cache/images 下除当前图片外的历史 photo_*.jpg。 */
    private fun cleanupOldPhotos(keep: Uri) {
        val keepPath = keep.lastPathSegment ?: return
        executor.execute {
            val dir = File(cacheDir, "images")
            val files = dir.listFiles { f ->
                f.name.startsWith("photo_") && f.name.endsWith(".jpg")
            } ?: return@execute
            val keepFile = File(cacheDir, keepPath)
            for (f in files) {
                if (f.absolutePath != keepFile.absolutePath) f.delete()
            }
        }
    }

    private fun clearPhotoState() {
        photoUri = null
        photoBase64 = null
        photoContainer.visibility = View.GONE
        photoPreview.setImageDrawable(null)
        inputText.visibility = View.VISIBLE
    }

    private fun choosePhotoSource() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.photo_source_title)
            .setItems(
                arrayOf(
                    getString(R.string.photo_from_camera),
                    getString(R.string.photo_from_gallery)
                )
            ) { _, which ->
                if (which == 0) takePhoto() else galleryLauncher.launch("image/*")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        btnRetry.visibility = View.GONE
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
        btnRetry.visibility = View.GONE
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
        btnRetry.visibility = View.GONE
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
        // 失败后给一键重试入口（有图片重译图片，否则重发文本）
        btnRetry.visibility = View.VISIBLE
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
        btnSpeak.isEnabled = enabled
        btnCopy.alpha = if (enabled) 1f else 0.35f
        btnShare.alpha = if (enabled) 1f else 0.35f
        btnFullscreen.alpha = if (enabled) 1f else 0.35f
        btnSpeak.alpha = if (enabled) 1f else 0.35f
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
        // 键盘弹出时 viewport 被压缩：把分隔线定位到可视区下方 1/4 处，
        // 保证「翻译中」/译文开头可见、输入尾部（含光标行）占上 3/4；
        // 输入较短（分隔线本来就在屏内）时公式自动归零不滚，输入不会被顶出去。
        // 3/4 位置若深过「收起后的滚动上限」就提前底部补白撑大上限，
        // 收起键盘零回弹、内容保持原位。
        if (keyboardVisible) {
            contentScroll.post {
                val viewportHeight = contentScroll.height
                if (viewportHeight == 0) return@post
                val targetY = (divider.top - viewportHeight * 3f / 4f)
                    .toInt()
                    .coerceAtLeast(0)
                // 3/4 定位可能超过「收起键盘后的滚动上限」（收起时视口恢复、
                // 上限收紧），超限会被系统强制回弹——内容突兀下跳、译文区被
                // 顶离原位；但直接卡住上限又会把「翻译中」挤出键盘上方的
                // 可见区（翻译中分隔线下只有一行 loading，几乎必然超限）。
                // 解法：提前给内容底部补白把上限撑大——newPadding =
                // 全屏视口 + targetY - 内容实际高，与当前 padding 无关、
                // 天然幂等不累加，译文出来后高度变大时自动收敛变小。
                // 补白后收起键盘 scrollY 必然合法，零回弹、内容纹丝不动。
                if (fullViewportHeight > 0) {
                    val fullMax =
                        (scrollContent.height - fullViewportHeight).coerceAtLeast(0)
                    val needed = targetY - fullMax
                    val newPadding =
                        (scrollContent.paddingBottom + needed).coerceAtLeast(0)
                    if (newPadding != scrollContent.paddingBottom) {
                        scrollContent.setPadding(0, 0, 0, newPadding)
                        // 等补白生效（高度重算）后再滚，否则上限仍按旧高度算
                        scrollContent.post {
                            contentScroll.smoothScrollTo(0, targetY)
                        }
                        return@post
                    }
                }
                contentScroll.smoothScrollTo(0, targetY)
            }
            return
        }
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
            packageManager.getPackageInfo(packageName, 0).versionName ?: "2.3.14"
        } catch (_: Exception) {
            "2.3.14"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
