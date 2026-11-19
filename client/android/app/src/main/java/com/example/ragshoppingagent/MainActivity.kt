package com.example.ragshoppingagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.graphics.Typeface
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private companion object {
        const val SPEECH_REQUEST_CODE = 1001
        const val RECORD_AUDIO_PERMISSION_CODE = 1002
    }

    // 图片缓存（LRU，最多 60 张）+ 线程池
    private val imageCache = object : android.util.LruCache<String, android.graphics.Bitmap>(60) {}
    private val imageExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

    private val serverBaseUrl = "http://192.168.1.100:8000"
    private val chatClient = ChatClient(serverBaseUrl)
    private val mdRenderer by lazy { MarkdownRenderer(this) }
    private var sessionId = UUID.randomUUID().toString()

    private lateinit var queryInput: EditText
    private lateinit var sendButton: Button
    private lateinit var micButton: Button
    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var pcmData = ByteArrayOutputStream()
    private var recordStartTime = 0L
    private var voiceOverlay: View? = null
    private var voiceBubbleText: TextView? = null
    private lateinit var chatScreen: LinearLayout
    private lateinit var detailScreen: LinearLayout
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var statusText: TextView
    private lateinit var detailBackButton: TextView
    private lateinit var detailImage: ImageView
    private lateinit var detailName: TextView
    private lateinit var detailPrice: TextView
    private lateinit var detailDescription: TextView
    private lateinit var detailTags: LinearLayout
    private lateinit var detailAttributes: LinearLayout
    private lateinit var detailSkuSection: LinearLayout
    private lateinit var detailSkuList: LinearLayout
    private lateinit var detailFaqSection: LinearLayout
    private lateinit var detailFaqList: LinearLayout
    private lateinit var detailReviewsSection: LinearLayout
    private lateinit var detailReviewsList: LinearLayout
    private lateinit var detailChatTab: LinearLayout
    private lateinit var cartScreen: LinearLayout
    private lateinit var cartListContainer: LinearLayout
    private lateinit var cartTotalText: TextView
    private lateinit var cartFooter: LinearLayout
    private lateinit var cartTabCart: TextView
    private lateinit var cartTabFav: TextView
    private lateinit var chatCartBadge: TextView
    private lateinit var detailCartBadge: TextView
    private lateinit var detailWishlistButton: TextView
    private lateinit var checkoutScreen: LinearLayout
    private lateinit var checkoutItemList: LinearLayout
    private lateinit var checkoutAddressList: LinearLayout
    private lateinit var checkoutSummary: TextView
    private lateinit var checkoutTotalText: TextView
    private var selectedAddress: String? = null
    private var checkoutFromCart = false  // 结算页来源：true=购物车，false=对话
    private var currentDetailProduct: JSONObject? = null
    private var selectedSpecs = mutableMapOf<String, String>()
    private var currentSkuCount = 0
    private var detailOpenedFromCart = false  // 详情页来源：true=购物车页，false=对话页
    private var cartShowingFav = false
    private val cartChangeListener: () -> Unit = { onCartChanged() }
    private var activeTextBubble: TextView? = null
    private var activeAiColumn: LinearLayout? = null
    private var activeProductRow: LinearLayout? = null
    private val typingHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeChatThread: Thread? = null
    private var userScrolledUp = false  // 用户手动上滑时暂停自动滚动
    private val renderQueue = ArrayDeque<RenderOp>()
    private val currentProducts = mutableListOf<JSONObject>()
    private val currentRawText = StringBuilder()
    private val chatHistory = mutableListOf<JSONObject>()
    private var currentIntentDebug: JSONObject? = null
    private var currentClarify: JSONObject? = null
    private var currentAssistantColumn: LinearLayout? = null
    private var currentUserQuery = ""
    private var currentResponseFinalized = false
    private var thinkingBubble: View? = null
    private var thinkingStep = 0
    private var isThinking = false
    private val thinkingDots = mutableListOf<View>()
    private val thinkingAnimators = mutableListOf<ObjectAnimator>()
    private var isRendering = false
    private var streamFinished = true
    private var bubbleIndex = 0                              // 当前气泡序号
    private val bubbleViews = mutableListOf<TextView>()      // 所有气泡引用
    private val bubbleSource = HashMap<TextView, StringBuilder>()  // 每个气泡的 Markdown 源
    private var clarifyRendered = false
    private var activeBodyContainer: LinearLayout? = null    // body 富文本容器（bubbleIndex==1）
    private var lastBodyContainer: LinearLayout? = null     // 最后一个 body 容器引用（finalization 用）
    private val bodyFullSource = StringBuilder()             // body 容器完整 markdown 源
    private val bodyRenderCache = HashMap<TextView, CharSequence>() // 流式阶段已完成行的 markdown 渲染缓存
    private val insertedImageUrls = mutableSetOf<String>()   // 已插入的图片 URL（防重复）
    private val pendingSkuProducts = ArrayDeque<JSONObject>() // 批量加购时待选规格的商品队列
    // 对话加购：累积本次会话所有商品（跨轮次保留）
    private val allSessionProducts = LinkedHashMap<String, JSONObject>()
    private val lastTurnProductIds = mutableListOf<String>()
    private val cardFavHearts = mutableMapOf<String, MutableList<TextView>>()  // 商品ID → 卡片心形列表
    private val mdImageRegex = Regex("^!\\[([^\\]]*)\\]\\(([^)]+)\\)$")
    private val mdTableSepRegex = Regex("^:?-{2,}:?$")

    // 渲染队列单元：文字 / 商品卡片 / 气泡分隔，按到达顺序消费
    private sealed class RenderOp {
        class Text(val text: StringBuilder) : RenderOp()
        class Product(val product: JSONObject) : RenderOp()
        object Break : RenderOp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        queryInput = findViewById(R.id.queryInput)
        sendButton = findViewById(R.id.sendButton)
        chatScreen = findViewById(R.id.chatScreen)
        detailScreen = findViewById(R.id.detailScreen)
        chatContainer = findViewById<LinearLayout>(R.id.chatStreamContainer).apply {
            clipChildren = false
            clipToPadding = false
        }
        chatScrollView = findViewById<ScrollView>(R.id.chatScrollView).apply {
            clipChildren = false
            clipToPadding = false
            // 降低鼠标滚轮/触控板滚动步长（默认太大，轻轻一滑就跳一大页）
            setOnGenericMotionListener { v, event ->
                if (event.action == MotionEvent.ACTION_SCROLL) {
                    val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    (v as ScrollView).scrollBy(0, (-delta * dp(3)).toInt())
                    true
                } else false
            }
            // 用户手动滑动时暂停自动滚动
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        userScrolledUp = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 如果用户滑回了底部附近，恢复自动滚动
                        post {
                            val contentH = getChildAt(0)?.height ?: 0
                            val distFromBottom = contentH - scrollY - height
                            if (distFromBottom <= dp(200)) {
                                userScrolledUp = false
                            }
                        }
                    }
                }
                false  // 不消费事件，让 ScrollView 正常滚动
            }
        }
        statusText = findViewById(R.id.statusText)
        detailBackButton = findViewById(R.id.detailBackButton)
        detailImage = findViewById(R.id.detailImage)
        detailName = findViewById(R.id.detailName)
        detailPrice = findViewById(R.id.detailPrice)
        detailDescription = findViewById(R.id.detailDescription)
        detailTags = findViewById(R.id.detailTags)
        detailAttributes = findViewById(R.id.detailAttributes)
        detailSkuSection = findViewById(R.id.detailSkuSection)
        detailSkuList = findViewById(R.id.detailSkuList)
        detailFaqSection = findViewById(R.id.detailFaqSection)
        detailFaqList = findViewById(R.id.detailFaqList)
        detailReviewsSection = findViewById(R.id.detailReviewsSection)
        detailReviewsList = findViewById(R.id.detailReviewsList)
        detailChatTab = findViewById(R.id.detailChatTab)
        showWelcomeMessage()
        showSplashAnimation()

        // 演示模式初始化
        chatClient.context = this
        // 左侧：长按小鸟头像切换模式：录制 → 演示 → 正常 → 录制 ...
        findViewById<View>(R.id.titleAvatar).setOnLongClickListener {
            when {
                chatClient.demoMode -> {
                    chatClient.demoMode = false
                    chatClient.recordMode = false
                    toast("已切换：正常模式")
                }
                chatClient.recordMode -> {
                    chatClient.recordMode = false
                    chatClient.demoMode = true
                    toast("已切换：演示模式（本地回放）")
                }
                else -> {
                    chatClient.recordMode = true
                    toast("已切换：录制模式（缓存回复）")
                }
            }
            true
        }
        // 右侧：长按清屏
        findViewById<View>(R.id.titleRightArea).setOnLongClickListener {
            activeChatThread?.interrupt()
            activeChatThread = null
            typingHandler.removeCallbacksAndMessages(null)
            renderQueue.clear()
            isRendering = false
            streamFinished = true
            currentResponseFinalized = true
            chatHistory.clear()
            allSessionProducts.clear()
            lastTurnProductIds.clear()
            currentRawText.clear()
            bubbleViews.clear()
            bubbleSource.clear()
            bodyFullSource.clear()
            bodyRenderCache.clear()
            pendingSkuProducts.clear()
            sessionId = java.util.UUID.randomUUID().toString()
            showWelcomeMessage()
            toast("已清屏")
            true
        }

        micButton = findViewById(R.id.micButton)
        sendButton.setOnClickListener {
            startChat(queryInput.text.toString().trim())
        }
        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startClientRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopClientRecording()
                    true
                }
                else -> false
            }
        }
        detailBackButton.setOnClickListener {
            showChatScreen()
        }
        detailChatTab.setOnClickListener {
            showChatScreen()
        }

        // ── 购物车 / 收藏 ──
        CartStore.init(this)
        cartScreen = findViewById(R.id.cartScreen)
        cartListContainer = findViewById(R.id.cartListContainer)
        cartTotalText = findViewById(R.id.cartTotalText)
        cartFooter = findViewById(R.id.cartFooter)
        cartTabCart = findViewById(R.id.cartTabCart)
        cartTabFav = findViewById(R.id.cartTabFav)
        chatCartBadge = findViewById(R.id.chatCartBadge)
        detailCartBadge = findViewById(R.id.detailCartBadge)
        detailWishlistButton = findViewById(R.id.detailWishlistButton)
        checkoutScreen = findViewById(R.id.checkoutScreen)
        checkoutItemList = findViewById(R.id.checkoutItemList)
        checkoutAddressList = findViewById(R.id.checkoutAddressList)
        checkoutSummary = findViewById(R.id.checkoutSummary)
        checkoutTotalText = findViewById(R.id.checkoutTotalText)
        val detailBuyButton = findViewById<TextView>(R.id.detailBuyButton)

        chatCartBadge.background = redBadgeBg()
        detailCartBadge.background = redBadgeBg()

        findViewById<View>(R.id.chatCartTab).setOnClickListener { showCartScreen() }
        findViewById<View>(R.id.detailCartTab).setOnClickListener { showCartScreen() }
        findViewById<View>(R.id.cartBackButton).setOnClickListener { showChatScreen() }
        findViewById<View>(R.id.cartChatTab).setOnClickListener { showChatScreen() }
        cartTabCart.setOnClickListener { selectCartTab(showFav = false) }
        cartTabFav.setOnClickListener { selectCartTab(showFav = true) }
        findViewById<View>(R.id.cartCheckoutButton).setOnClickListener {
            if (CartStore.cartItems().isEmpty()) toast("购物车是空的") else showCheckoutScreen(fromCart = true)
        }
        findViewById<View>(R.id.checkoutBackButton).setOnClickListener { exitCheckoutScreen() }
        findViewById<View>(R.id.checkoutSubmitButton).setOnClickListener { submitOrder() }
        detailWishlistButton.setOnClickListener {
            val p = currentDetailProduct ?: return@setOnClickListener
            val faved = CartStore.toggleFav(p)
            syncAllFavHearts(p.optString("id"), faved)
            toast(if (faved) "已收藏" else "已取消收藏")
        }
        detailBuyButton.setOnClickListener {
            val p = currentDetailProduct ?: return@setOnClickListener
            if (currentSkuCount > 0 && selectedSpecs.size < currentSkuCount) {
                toast("请先选择规格")
                return@setOnClickListener
            }
            if (selectedSpecs.isNotEmpty()) {
                val specObj = JSONObject()
                selectedSpecs.forEach { (k, v) -> specObj.put(k, v) }
                p.put("selected_specs", specObj.toString())
                p.put("price", detailPrice.text.toString())
            }
            CartStore.addToCart(p)
            toast("已加入购物车")
            // 加购成功后自动返回来源页
            val returnToCart = detailOpenedFromCart
            detailBuyButton.isEnabled = false  // 防重复点击
            detailScreen.postDelayed({
                detailBuyButton.isEnabled = true
                if (returnToCart) showCartScreen() else showChatScreen()
            }, 800)
        }

        CartStore.addListener(cartChangeListener)
        onCartChanged()
    }

    // ── 客户端录音：按住麦克风录音，松手上传 ──

    @android.annotation.SuppressLint("MissingPermission")
    private fun startClientRecording() {
        // 检查权限
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
            return
        }
        if (isRecording) return
        isRecording = true
        pcmData.reset()
        recordStartTime = System.currentTimeMillis()
        micButton.text = "⏹"

        // 显示录音浮层
        showVoiceOverlay()

        // 启动 AudioRecord
        val sampleRate = 16000
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            4096
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        audioRecord = recorder
        recorder.startRecording()

        recordingThread = Thread {
            val buf = ByteArray(bufSize)
            while (isRecording) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    synchronized(pcmData) { pcmData.write(buf, 0, read) }
                }
            }
        }.also { it.start() }
    }

    @Volatile private var voiceProcessing = false

    private fun stopClientRecording() {
        if (!isRecording) return
        isRecording = false
        if (voiceProcessing) return
        voiceProcessing = true
        micButton.text = "🎙"
        hideVoiceOverlay()

        // 停止录音
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        recordingThread = null

        val durationMs = System.currentTimeMillis() - recordStartTime
        if (durationMs < 500) {
            voiceProcessing = false
            toast("说话时间太短")
            return
        }

        val durationSec = (durationMs / 1000).toInt()
        val pcmBytes = synchronized(pcmData) { pcmData.toByteArray() }
        val wavBytes = pcmToWav(pcmBytes)

        // 添加语音气泡
        val bubbleText = addVoiceBubble(durationSec)
        voiceBubbleText = bubbleText
        scrollToBottom(force = true)

        // 后台上传 WAV → ASR → startChat
        statusText.text = "● 识别中…"
        Thread {
            try {
                val text = chatClient.uploadAsr(wavBytes)
                val cleaned = text.replace(Regex("[\\p{Punct}\\s\uFF0C\u3002\uFF01\uFF1F\u3001\uFF1B\uFF1A\u201C\u201D\u2018\u2019\uFF08\uFF09\u2026\u2014\u00B7]+"), "")
                runOnUiThread {
                    voiceProcessing = false
                    if (cleaned.length >= 2) {
                        bubbleText.text = text
                        statusText.text = "● 在线"
                        startChat(text, skipUserBubble = true)
                    } else {
                        bubbleText.text = "（没听清，请再说一次）"
                        statusText.text = "● 在线"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    voiceProcessing = false
                    bubbleText.text = "（识别失败）"
                    statusText.text = "● 识别失败：${e.message}"
                }
            }
        }.start()
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val dataLen = pcm.size
        val totalLen = 36 + dataLen
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(44 + dataLen)
        fun writeShort(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun writeInt(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        out.write("RIFF".toByteArray()); writeInt(totalLen)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); writeInt(16)
        writeShort(1); writeShort(channels)
        writeInt(sampleRate); writeInt(byteRate)
        writeShort(blockAlign); writeShort(bitsPerSample)
        out.write("data".toByteArray()); writeInt(dataLen)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun showVoiceOverlay() {
        val root = findViewById<FrameLayout>(android.R.id.content)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // 中心圆角卡片
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundResource(R.drawable.bg_product_card)
                setPadding(dp(32), dp(24), dp(32), dp(24))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                addView(TextView(this@MainActivity).apply {
                    text = "🎙"
                    textSize = 36f
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = "正在语音输入"
                    setTextColor(getColor(R.color.app_text))
                    textSize = 15f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) }
                })
            }
            addView(card)
            // 淡入
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
        }
        root.addView(overlay)
        voiceOverlay = overlay
    }

    private fun hideVoiceOverlay() {
        voiceOverlay?.let { ov ->
            ov.animate().alpha(0f).setDuration(150).withEndAction {
                (ov.parent as? ViewGroup)?.removeView(ov)
            }.start()
        }
        voiceOverlay = null
    }

    private fun addVoiceBubble(durationSec: Int): TextView {
        val bubble = TextView(this).apply {
            text = "🎙 ${durationSec}s"
            setBackgroundResource(R.drawable.bg_message_user)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(getColor(android.R.color.white))
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.65f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                bottomMargin = dp(12)
            }
        }
        chatContainer.addView(bubble)
        animateEntrance(bubble)
        return bubble
    }

    override fun onDestroy() {
        // 停止录音
        if (isRecording) {
            isRecording = false
            try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }
        // 移除 CartStore 监听
        CartStore.removeListener(cartChangeListener)
        // 清除打字动画回调
        typingHandler.removeCallbacksAndMessages(null)
        // 关闭图片线程池
        imageExecutor.shutdownNow()
        // 中断 SSE 流
        activeChatThread?.interrupt()
        activeChatThread = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (checkoutScreen.visibility == View.VISIBLE) {
            exitCheckoutScreen()
        } else if (detailScreen.visibility == View.VISIBLE || cartScreen.visibility == View.VISIBLE) {
            showChatScreen()
        } else {
            super.onBackPressed()
        }
    }

    private var skipCartIntentOnce = false

    private fun startChat(query: String, skipUserBubble: Boolean = false) {
        if (query.isEmpty()) {
            statusText.text = "● 请输入购物需求"
            return
        }

        // 确保回到对话页面
        showChatScreen()
        hideKeyboard()

        // 对话加购：拦截加购意图，不发后端（fallback 时跳过）
        if (!skipCartIntentOnce && tryHandleCartIntent(query, skipUserBubble)) return
        skipCartIntentOnce = false

        sendButton.isEnabled = false
        statusText.text = "● 正在回复"

        // 清理上一轮未完成的回复（连续发消息时）
        stopThinkingAnimation(clearText = false, removeBubble = true)
        // 如果上一轮 AI 回复还没结束，移除其未完成的气泡列
        if (!currentResponseFinalized && currentAssistantColumn != null) {
            chatContainer.removeView(currentAssistantColumn)
        }

        userScrolledUp = false
        streamFinished = false
        currentResponseFinalized = false
        currentUserQuery = query
        currentProducts.clear()
        currentRawText.clear()
        currentIntentDebug = null
        currentClarify = null
        clarifyRendered = false
        currentAssistantColumn = null
        activeTextBubble = null
        activeAiColumn = null
        activeProductRow = null
        activeBodyContainer = null
        lastBodyContainer = null
        bodyFullSource.clear()
        bodyRenderCache.clear()
        insertedImageUrls.clear()
        bubbleIndex = 0
        bubbleViews.clear()
        if (!skipUserBubble) addUserBubble(query)
        showThinkingBubble()
        scrollToBottom(force = true)
        queryInput.setText("")

        // 中断旧的 SSE 流
        activeChatThread?.interrupt()

        val thread = Thread {
            try {
                streamChat(query)
                if (!Thread.currentThread().isInterrupted) {
                    runOnUiThread {
                        streamFinished = true
                        finishResponseIfIdle()
                    }
                }
            } catch (error: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    runOnUiThread {
                        streamFinished = true
                        removeThinkingBubble()
                        statusText.text = "● 连接失败：${error.message}"
                        sendButton.isEnabled = true
                    }
                }
            } finally {
                if (activeChatThread == Thread.currentThread()) {
                    activeChatThread = null
                }
            }
        }
        activeChatThread = thread
        thread.start()
    }

    private fun streamChat(query: String) {
        chatClient.streamChat(query, sessionId, chatHistory, object : ChatClient.Listener {
            override fun onText(content: String) {
                runOnUiThread { enqueueText(content) }
            }
            override fun onBubbleBreak() {
                runOnUiThread { handleBubbleBreak() }
            }
            override fun onProduct(product: JSONObject, rawMarker: String) {
                if (rawMarker.isNotEmpty()) currentRawText.append(rawMarker)
                runOnUiThread { enqueueProductCard(product) }
            }
            override fun onClarify(clarify: JSONObject) {
                runOnUiThread { currentClarify = clarify; renderClarifyIfReady() }
            }
            override fun onDebugIntent(debug: JSONObject) {
                runOnUiThread { currentIntentDebug = debug }
            }
        })
    }

    // ── 统一有序渲染队列 ──
    // 文字、商品卡片、气泡分隔严格按流式到达顺序消费，与 test_chat.html 的交错渲染一致。
    // 解决"多商品时文字未按卡片切分、卡片全堆到末尾"的问题。
    private fun enqueueText(content: String) {
        if (content.isEmpty()) {
            return
        }

        currentRawText.append(content)
        val visible = streamingVisibleText(content)
        if (visible.isEmpty()) {
            return
        }
        // 连续文字合并进队尾同一个 Text op（从队首打字、往队尾追加，互不影响）
        val tail = renderQueue.lastOrNull()
        if (tail is RenderOp.Text) {
            tail.text.append(visible)
        } else {
            renderQueue.addLast(RenderOp.Text(StringBuilder(visible)))
        }
        ensureRenderPump()
    }

    private fun enqueueProductCard(product: JSONObject) {
        currentProducts.add(product)
        product.optString("id").takeIf { it.isNotBlank() }?.let {
            allSessionProducts[it] = JSONObject(product.toString())
        }
        renderQueue.addLast(RenderOp.Product(product))
        ensureRenderPump()
    }

    private fun ensureRenderPump() {
        if (!isRendering) {
            isRendering = true
            renderStep()
        }
    }

    private fun renderStep() {
        when (val head = renderQueue.firstOrNull()) {
            null -> {
                isRendering = false
                renderClarifyIfReady()
                finishResponseIfIdle()
            }
            is RenderOp.Text -> {
                if (head.text.isEmpty()) {
                    renderQueue.removeFirst()
                    renderStep()
                    return
                }
                // 匀速输出：每次 1 字符，间隔 20ms（约 50 字/秒）
                val next = head.text.substring(0, 1)
                head.text.delete(0, 1)
                appendText(next)
                typingHandler.postDelayed({ renderStep() }, 20L)
            }
            is RenderOp.Product -> {
                renderQueue.removeFirst()
                insertInlineProductCard(head.product)
                renderStep()
            }
            RenderOp.Break -> {
                renderQueue.removeFirst()
                applyBubbleBreak()
                renderStep()
            }
        }
    }

    private fun insertInlineProductCard(product: JSONObject) {
        if (product.length() == 0) {
            return
        }

        if (thinkingBubble != null && activeTextBubble == null) {
            stopThinkingAnimation(clearText = false, removeBubble = true)
        }

        // 自动升级：如果商品卡到达时还在 bubbleIndex==0（模型没输出 <bubble> 标签），
        // 自动切换到 body 模式，让卡片和后续文字进入富文本容器
        if (bubbleIndex == 0) {
            applyBubbleBreak()  // bubbleIndex → 1
        }

        val card = newInlineProductCard(product)
        if (bubbleIndex == 1) {
            // Body 区域：确保 body 容器存在（product 事件可能先于 body 文本到达）
            if (activeBodyContainer == null) {
                val column = currentAssistantColumn ?: activeAiColumn ?: getAiColumn()
                currentAssistantColumn = column
                val container = newBodyContainer()
                column.addView(container)
                activeBodyContainer = container
                lastBodyContainer = container
            }
            // 卡片宽度改为 MATCH_PARENT 填满容器内宽（容器有 dp14 左右 padding）。
            (card.layoutParams as? LinearLayout.LayoutParams)?.width = LinearLayout.LayoutParams.MATCH_PARENT
            activeBodyContainer!!.addView(card)
            activeTextBubble = null  // 后续文字会创建新的子 TextView，但仍在 body 容器内
        } else {
            // 非 body 区域：保持原有逻辑
            val column = currentAssistantColumn ?: activeAiColumn ?: getAiColumn()
            currentAssistantColumn = column
            column.addView(card)
            activeTextBubble = null
        }
        activeProductRow = null
        scrollToBottom()
    }

    private fun finishResponseIfIdle() {
        if (streamFinished && !isRendering && renderQueue.isEmpty()) {
            finalizeAssistantResponse()
            statusText.text = "● 在线"
            sendButton.isEnabled = true
        }
    }

    private fun appendText(content: String) {
        activeProductRow = null
        // 跳过纯空白内容，避免创建空壳气泡
        if (activeTextBubble == null && content.isBlank()) return

        // 自动升级：bubbleIndex==0 时文本超过 100 字，自动切换到 body 富文本模式
        // 解决模型未输出 <bubble> 标签时长文本没有 markdown 渲染的问题
        if (bubbleIndex == 0 && activeTextBubble != null) {
            val currentLen = bubbleSource[activeTextBubble]?.length ?: 0
            if (currentLen > 100) {
                // 把已有的短气泡内容迁移到 body 容器
                val existingSrc = bubbleSource[activeTextBubble]?.toString() ?: ""
                val column = currentAssistantColumn ?: activeAiColumn ?: getAiColumn()
                currentAssistantColumn = column
                // 移除旧的短气泡
                activeTextBubble?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                bubbleViews.remove(activeTextBubble)
                bubbleSource.remove(activeTextBubble)
                // 切到 body 模式
                applyBubbleBreak()  // bubbleIndex → 1
                activeTextBubble = null
                // 创建 body 容器和 TextView，回填已有文本
                val container = newBodyContainer()
                column.addView(container)
                activeBodyContainer = container
                lastBodyContainer = container
                val tv = newBodyTextView()
                container.addView(tv)
                activeTextBubble = tv
                bubbleViews.add(tv)
                val newSrc = StringBuilder(existingSrc)
                bubbleSource[tv] = newSrc
                bodyFullSource.append(existingSrc)
                // 渲染已有内容的 markdown
                val lastNl = existingSrc.lastIndexOf('\n')
                if (lastNl >= 0) {
                    val completed = existingSrc.substring(0, lastNl + 1)
                    try {
                        bodyRenderCache[tv] = mdRenderer.render(completed.trimEnd())
                    } catch (_: Exception) {
                        bodyRenderCache[tv] = completed
                    }
                    val pending = existingSrc.substring(lastNl + 1)
                    tv.text = if (pending.isEmpty()) bodyRenderCache[tv] else android.text.SpannableStringBuilder().append(bodyRenderCache[tv] ?: "").append(pending)
                } else {
                    tv.text = existingSrc
                }
            }
        }

        if (activeTextBubble == null) {
            val column = getAiColumn()
            currentAssistantColumn = column
            // 移除思考动画容器
            stopThinkingAnimation(clearText = false, removeBubble = true)
            if (bubbleIndex == 1) {
                // Body 富文本
                if (activeBodyContainer == null) {
                    val container = newBodyContainer()
                    column.addView(container)
                    activeBodyContainer = container
                    lastBodyContainer = container
                }
                val tv = newBodyTextView()
                activeBodyContainer!!.addView(tv)
                activeTextBubble = tv
                bubbleViews.add(tv)
                bubbleSource[tv] = StringBuilder()
            } else {
                // 短气泡（引导语 / 钩子）
                val bubble = newShortAiBubble("").also { column.addView(it) }
                activeTextBubble = bubble
                bubbleViews.add(bubble)
                bubbleSource[bubble] = StringBuilder()
                restoreAiTextBubbleStyle(activeTextBubble)
            }
        }
        val bubble = activeTextBubble ?: return
        val src = bubbleSource.getOrPut(bubble) { StringBuilder() }
        src.append(content)

        // Body 容器内检测表格/图片，即时拆分为独立 View
        if (bubbleIndex == 1 && activeBodyContainer != null) {
            bodyFullSource.append(content)
            if (processBodyTables(bubble, src)) {
                scrollToBottom()
                return
            }
            if (processBodyImages(bubble, src)) {
                // 图片已处理，文字已拆分渲染，直接返回
                scrollToBottom()
                return
            }
        }

        // 流式阶段：body 区域按行增量 markdown 渲染，其他气泡纯文本
        if (bubbleIndex == 1 && activeBodyContainer != null) {
            val srcStr = src.toString()
            val lastNl = srcStr.lastIndexOf('\n')
            if (lastNl >= 0) {
                // 有已完成的行 → 仅在新换行到达时重新渲染
                if (content.contains('\n')) {
                    val completed = srcStr.substring(0, lastNl + 1)
                    try {
                        bodyRenderCache[bubble] = mdRenderer.render(completed.trimEnd())
                    } catch (_: Exception) {
                        bodyRenderCache[bubble] = completed
                    }
                }
                val cached = bodyRenderCache[bubble]
                if (cached != null) {
                    val pending = srcStr.substring(lastNl + 1)
                    if (pending.isEmpty()) {
                        bubble.text = cached
                    } else {
                        val combined = android.text.SpannableStringBuilder(cached)
                        combined.append(pending)
                        bubble.text = combined
                    }
                } else {
                    bubble.text = srcStr
                }
            } else {
                // 还没有换行，纯文本显示
                bubble.text = srcStr
            }
        } else {
            bubble.text = src.toString().trimEnd()
        }
        scrollToBottom()
    }


    /** 检测 body 文字中的完整图片行 ![alt](url)，拆分为 text + ImageView。
     *  返回 true 表示已处理（caller 应跳过后续渲染）。 */
    private fun processBodyImages(currentBubble: TextView, src: StringBuilder): Boolean {
        val container = activeBodyContainer ?: return false
        val text = src.toString()
        val lines = text.split("\n")

        var imageLineIdx = -1
        for (idx in lines.indices) {
            val line = lines[idx].trim()
            val m = mdImageRegex.find(line)
            if (m != null && !insertedImageUrls.contains(m.groupValues[2])) {
                imageLineIdx = idx
                break
            }
        }
        if (imageLineIdx < 0) return false

        val imgLine = lines[imageLineIdx].trim()
        val m = mdImageRegex.find(imgLine) ?: return false
        val imageUrl = m.groupValues[2]
        insertedImageUrls.add(imageUrl)

        val beforeText = lines.subList(0, imageLineIdx).joinToString("\n").trimEnd()
        val afterText = lines.subList(imageLineIdx + 1, lines.size).joinToString("\n")

        // 当前 bubble 只保留图片之前的文字
        bubbleSource[currentBubble] = StringBuilder(beforeText)
        currentBubble.text = mdRenderer.render(beforeText)

        // 插入 ImageView
        val imageView = newBodyImageView()
        container.addView(imageView)
        loadImageInto(imageUrl, imageView)

        // 图片之后的文字放入新的子 TextView
        val newTv = newBodyTextView()
        container.addView(newTv)
        val newSrc = StringBuilder(afterText)
        bubbleSource[newTv] = newSrc
        bubbleViews.add(newTv)
        activeTextBubble = newTv
        newTv.text = mdRenderer.render(afterText)

        return true
    }

    /** 检测 body 文字中的完整表格块（连续 | 开头的行），拆分为 TableLayout View。
     *  返回 true 表示已处理。 */
    private fun processBodyTables(currentBubble: TextView, src: StringBuilder, forceFinalize: Boolean = false): Boolean {
        val container = activeBodyContainer ?: return false
        val text = src.toString()
        val lines = text.split("\n")

        var tableStart = -1
        var tableEnd = -1
        for (idx in lines.indices) {
            val trimmed = lines[idx].trim()
            if (trimmed.startsWith("|")) {
                if (tableStart < 0) tableStart = idx
                tableEnd = idx + 1
            } else if (tableStart >= 0) {
                break
            }
        }
        if (tableStart < 0) return false
        if (tableEnd - tableStart < 2) return false
        // 流式阶段：表格必须已完整结束（后面出现了非空的正文行）才切出来，否则继续等更多行。
        // 否则表头/部分行会先被切成一个表、剩余行再切成另一个表 → 多个互不联动的滚动表格。
        // 注意：仅靠 tableEnd >= lines.size 不行，源串结尾的 "\n" 会产生一个空元素被误判为"表后有内容"。
        val hasContentAfter = (tableEnd until lines.size).any { lines[it].isNotBlank() }
        if (!forceFinalize && !hasContentAfter) return false

        val tableLines = lines.subList(tableStart, tableEnd).map { it.trim() }
        val beforeText = lines.subList(0, tableStart).joinToString("\n").trimEnd()
        val afterText = if (tableEnd < lines.size) lines.subList(tableEnd, lines.size).joinToString("\n") else ""

        // 当前 bubble 只保留表格前的文字
        bubbleSource[currentBubble] = StringBuilder(beforeText)
        currentBubble.text = mdRenderer.render(beforeText)

        // 插入 TableLayout
        container.addView(newTableView(tableLines))

        // 表格后的文字放入新子 TextView
        val newTv = newBodyTextView()
        container.addView(newTv)
        val newSrc = StringBuilder(afterText)
        bubbleSource[newTv] = newSrc
        bubbleViews.add(newTv)
        activeTextBubble = newTv
        newTv.text = mdRenderer.render(afterText)

        return true
    }

    /** 将 Markdown 表格行渲染为真实 TableLayout（支持 CJK 对齐） */
    private fun newTableView(tableLines: List<String>): HorizontalScrollView {
        val rows = tableLines
            .map { row -> row.trim().trim('|').split("|").map { it.trim() } }
            .filterNot { cells -> cells.all { it.isEmpty() || it.matches(mdTableSepRegex) } }
        if (rows.isEmpty()) return HorizontalScrollView(this)

        val cols = rows.maxOf { it.size }

        val tableLayout = TableLayout(this).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.WRAP_CONTENT,
                TableLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        rows.forEachIndexed { ri, cells ->
            val tr = TableRow(this).apply {
                if (ri == 0) {
                    setBackgroundColor(0xFFE8F5E9.toInt())
                } else if (ri % 2 == 0) {
                    setBackgroundColor(0xFFFAFAFA.toInt())
                } else {
                    setBackgroundColor(0xFFFFFFFF.toInt())
                }
            }
            for (c in 0 until cols) {
                val cellText = cells.getOrElse(c) { "" }.replace("**", "")
                tr.addView(TextView(this).apply {
                    text = cellText
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    textSize = 12f
                    setTextColor(getColor(R.color.app_text))
                    // 限制单元格最大宽度：长文本在格内换行、列宽有界，
                    // 整张表不会被无限撑宽；总宽超出可视区时由外层 HorizontalScrollView 横滑
                    maxWidth = dp(120)
                    maxLines = 6
                    if (ri == 0) {
                        // 表头第一行 = 商品名称：绿色加粗
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(0xFF007A58.toInt())
                    } else if (c == 0) {
                        // 第一列 = 对比方面：加粗作行首标签
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(getColor(R.color.app_muted_text))
                    }
                    layoutParams = TableRow.LayoutParams(
                        TableRow.LayoutParams.WRAP_CONTENT,
                        TableRow.LayoutParams.WRAP_CONTENT,
                    )
                })
            }
            tableLayout.addView(tr)
        }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            clipToPadding = false
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1), 0xFFE0E0E0.toInt())
                cornerRadius = dp(8).toFloat()
            }
            background = bg
            addView(tableLayout)
            // 显式绑定到 body 容器内宽（容器宽 bubbleMaxWidth() - 左右 padding dp14*2），
            // 比 MATCH_PARENT 更确定，避免动态插入时拿不到约束而横向溢出气泡；
            // 内部表格更宽时由本 ScrollView 自身裁剪并横向滚动
            layoutParams = LinearLayout.LayoutParams(
                bubbleMaxWidth() - dp(28),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
    }

    /** Finalization 阶段：将 body 容器中残留的表格文本替换为 TableLayout */
    private fun finalizeBodyTables(container: LinearLayout) {
        val savedActiveBody = activeBodyContainer
        activeBodyContainer = container  // processBodyTables 依赖此字段
        var i = 0
        while (i < container.childCount) {
            val child = container.getChildAt(i)
            if (child !is TextView) { i++; continue }
            val src = bubbleSource[child]?.toString() ?: ""
            if (!src.contains("|")) { i++; continue }

            // 尝试强制处理表格
            val srcBuilder = bubbleSource.getOrPut(child) { StringBuilder(src) }
            if (processBodyTables(child, srcBuilder, forceFinalize = true)) {
                // processBodyTables 会修改 container 的子 View，重新扫描
                continue
            }
            i++
        }
        activeBodyContainer = savedActiveBody
    }

    private fun handleBubbleBreak() {
        renderQueue.addLast(RenderOp.Break)
        ensureRenderPump()
    }

    private fun applyBubbleBreak() {
        bubbleIndex++
        activeTextBubble = null
        activeProductRow = null
        // body 容器在 break 时不清空——它在 bubbleIndex==1 期间保持活跃
        // 只有离开 body（进入 index 2+）时才停止往容器里添加内容
        if (bubbleIndex >= 2) {
            activeBodyContainer = null
        }
    }

    private fun renderClarifyIfReady(force: Boolean = false) {
        val clarify = currentClarify ?: return
        if (clarifyRendered || (clarify.optJSONArray("questions")?.length() ?: 0) == 0) {
            return
        }
        if (!force && isRendering) {
            return
        }

        val column = currentAssistantColumn ?: activeAiColumn ?: getAiColumn()
        currentAssistantColumn = column
        stopThinkingAnimation(clearText = true, removeBubble = false)

        // Clarify 卡片插入到最后一个气泡（钩子）之前
        if (bubbleViews.size >= 2) {
            // 找到含 [question: 的气泡并移除（旧的 raw clarify 文本）
            val qBubbleIndex = bubbleViews.indexOfFirst { it.text.contains("[question:") }
            if (qBubbleIndex >= 0) {
                val qBubble = bubbleViews[qBubbleIndex]
                (qBubble.parent as? LinearLayout)?.removeView(qBubble)
                bubbleViews.removeAt(qBubbleIndex)
                bubbleSource.remove(qBubble)
            }
            // 在钩子（最后一个 bubble）之前插入 clarify 卡片
            val lastBubble = bubbleViews.lastOrNull()
            val posInColumn = lastBubble?.let { column.indexOfChild(it) } ?: -1
            if (posInColumn >= 0) {
                column.addView(newGuideCard(clarify), posInColumn)
            } else {
                column.addView(newGuideCard(clarify))
            }
        } else {
            val clarifyText = clarify.optString("text", "").trim()
            if (clarifyText.isNotBlank() && bubbleViews.isEmpty()) {
                val bubble = activeTextBubble ?: newTextBubble().also {
                    column.addView(it)
                    bubbleViews.add(it)
                }
                restoreAiTextBubbleStyle(bubble)
                bubble.text = mdRenderer.render(clarifyText)
            }
            column.addView(newGuideCard(clarify))
        }

        clarifyRendered = true
        activeTextBubble = null
        activeProductRow = null
        scrollToBottom()
    }

    private fun finalizeAssistantResponse() {
        if (currentResponseFinalized) {
            return
        }
        currentResponseFinalized = true
        bodyRenderCache.clear()

        stopThinkingAnimation(clearText = currentRawText.isNotBlank(), removeBubble = currentRawText.isBlank())
        val column = currentAssistantColumn ?: activeAiColumn ?: getAiColumn()
        val rawText = currentRawText.toString()
        val cleaned = cleanAssistantText(rawText)

        // 将 body 容器中残留的表格文本替换为真实 TableLayout
        lastBodyContainer?.let { finalizeBodyTables(it) }

        // 用各气泡自己的 Markdown 源重新渲染（保留标题/加粗等富文本）
        // 高亮块（> **结论**：xxx）保留在气泡内部，不提取出来
        if (bubbleViews.size <= 1) {
            // 单气泡（没有 bubble_break）
            val singleBubble = activeTextBubble ?: bubbleViews.lastOrNull()
            val src = singleBubble?.let { bubbleSource[it]?.toString() } ?: cleaned.mainText
            singleBubble?.text = mdRenderer.render(src.trim())
        } else {
            // 多气泡：每个气泡按各自的 Markdown 源渲染
            val emptyBubbles = mutableListOf<TextView>()
            for (bubble in bubbleViews) {
                val src = (bubbleSource[bubble]?.toString() ?: bubble.text.toString()).trim()
                if (src.isBlank()) {
                    emptyBubbles.add(bubble)
                } else {
                    bubble.text = mdRenderer.render(src)
                }
            }
            // 移除空白气泡
            for (bubble in emptyBubbles) {
                (bubble.parent as? LinearLayout)?.removeView(bubble)
                bubbleViews.remove(bubble)
                bubbleSource.remove(bubble)
            }
        }

        // 清理 body 容器内的空子 View
        lastBodyContainer?.let { container ->
            val emptyChildren = mutableListOf<View>()
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child is TextView && child.text.toString().trim().isEmpty()) {
                    emptyChildren.add(child)
                    bubbleViews.remove(child)
                    bubbleSource.remove(child as? TextView)
                }
            }
            emptyChildren.forEach { container.removeView(it) }
            // 如果 body 容器完全空了，移除整个容器
            if (container.childCount == 0) {
                (container.parent as? LinearLayout)?.removeView(container)
            }
        }

        val clarify = currentClarify
        if (clarify != null && (clarify.optJSONArray("questions")?.length() ?: 0) > 0) {
            val clarifyText = clarify.optString("text", "")
            val historyText = CleanedAssistantText(
                mainText = clarifyText.ifBlank { cleaned.mainText },
                tailQuestion = "",
            )
            appendCurrentTurnToHistory(historyText)
            renderClarifyIfReady(force = true)
            activeProductRow = null
            scrollToBottom()
            return
        }

        // 更新最近一轮商品列表（供对话加购的序数/指示词匹配）
        lastTurnProductIds.clear()
        lastTurnProductIds.addAll(currentProducts.mapNotNull {
            it.optString("id").takeIf { id -> id.isNotBlank() }
        }.distinct())

        appendCurrentTurnToHistory(cleaned)

        // 内嵌卡片已在流式阶段由 product 事件实时插入
        // 这里只处理末尾 RECOMMEND 场景的大卡（没有 bubble_break 或产品数 ≤2）
        val hasInlineProducts = rawText.contains(Regex("<!--\\s*PRODUCT:"))
        if (!hasInlineProducts) {
            val productsToShow = currentProducts
                .distinctBy { it.optString("id") }
                .take(3)

            if (productsToShow.isNotEmpty()) {
                // 如果有钩子气泡（bubbleViews.size >= 3），卡片插到钩子之前
                val hookBubble = if (bubbleViews.size >= 3) bubbleViews.last() else null
                val insertIndex = if (hookBubble != null) column.indexOfChild(hookBubble) else -1

                when {
                    productsToShow.size <= 2 -> {
                        for (p in productsToShow) {
                            val card = newSingleProductCard(p)
                            if (insertIndex >= 0) column.addView(card, insertIndex) else column.addView(card)
                        }
                    }
                    else -> {
                        val carousel = newProductCarousel(productsToShow)
                        if (insertIndex >= 0) column.addView(carousel, insertIndex) else column.addView(carousel)
                    }
                }
            }
        }

        activeProductRow = null
        scrollToBottom()
    }

    private fun appendCurrentTurnToHistory(cleaned: CleanedAssistantText) {
        if (currentUserQuery.isBlank()) {
            return
        }
        val assistant = listOf(cleaned.mainText, cleaned.tailQuestion)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        chatHistory.add(JSONObject().put("role", "user").put("content", currentUserQuery))
        val clarifyQuestions = clarifyQuestionsForHistory()
        // 保留本轮推荐商品的 ID 标记，供收窄路径从历史中提取（与 test_chat.html 一致；
        // 清洗后的正文不含 ID，缺这个标记会导致收窄时提取到 0 个历史商品）
        val productMarkers = currentProducts
            .map { it.optString("id") }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("") { "<!--PRODUCT: $it-->" }
        val assistantHistory = listOf(assistant, clarifyQuestions, productMarkers)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        if (assistantHistory.isNotBlank()) {
            chatHistory.add(JSONObject().put("role", "assistant").put("content", assistantHistory))
        }
        while (chatHistory.size > 10) {
            chatHistory.removeAt(0)
        }
    }

    private fun clarifyQuestionsForHistory(): String {
        val questions = currentClarify?.optJSONArray("questions") ?: return ""
        val lines = mutableListOf<String>()
        for (index in 0 until questions.length()) {
            val item = questions.optJSONObject(index) ?: continue
            val question = item.optString("question", "").trim()
            val answers = jsonArrayToStrings(item.optJSONArray("answers") ?: JSONArray())
            if (question.isNotBlank() && answers.isNotEmpty()) {
                lines.add("$question 可选：${answers.joinToString("、")}")
            }
        }
        return lines.joinToString("\n")
    }

    private data class CleanedAssistantText(
        val mainText: String,
        val tailQuestion: String,
    )

    private fun cleanAssistantText(raw: String): CleanedAssistantText {
        val withoutMarker = raw
            .replace(Regex("<!--\\s*RECOMMEND:[\\s\\S]*?-->", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!--\\s*PRODUCT:\\s*\\S+?\\s*-->", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</?bubble>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?is)\\n?\\s*\\[\\s*question\\s*[:：][\\s\\S]*"), "")
        val withoutImages = withoutMarker
            .lines()
            .filterNot { it.trim().startsWith("![") }
            .joinToString("\n")
        val normalized = withoutImages
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        // 不再做尾部拆分——bubble_break 已在流式阶段处理
        return CleanedAssistantText(mainText = normalized, tailQuestion = "")
    }

    private fun streamingVisibleText(content: String): String {
        return content
            .replace(Regex("<!--\\s*RECOMMEND:[\\s\\S]*?-->", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!--\\s*PRODUCT:\\s*\\S+?\\s*-->", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</?bubble>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?is)\\n?\\s*\\[\\s*question\\s*[:：][\\s\\S]*"), "")
    }

    // ── 轻量 Markdown 渲染：读取 Markdown 后手动映射为字号/字重/颜色 ──
    // 覆盖 test_chat.html(marked.js) 的主要效果：标题分级、加粗、斜体、行内代码、引用、列表、表格。
    private fun newProductCarousel(products: List<JSONObject>): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        products.forEach { row.addView(newSmallProductCard(it)) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            addView(row)
        }
    }

    private fun newSmallProductCard(product: JSONObject): LinearLayout {
        val productId = product.optString("id", "")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_product_card)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = productId.isNotBlank()
            layoutParams = LinearLayout.LayoutParams(
                dp(130),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(10)
            }
            elevation = dp(2).toFloat()

            val imageUrl = product.optString("image_url", "")
            if (imageUrl.isNotBlank()) {
                val imageView = newProductImage()
                addView(imageView)
                loadImageInto(imageUrl, imageView)
            }
            addView(newProductName(product.optString("name", "未知商品")))
            addView(newProductPrice(productPriceText(product)))
            addView(newDetailLink())
            setOnClickListener {
                if (productId.isNotBlank()) {
                    loadProductDetail(productId)
                }
            }
        }
    }

    private fun newInlineProductCard(product: JSONObject): LinearLayout {
        val productId = product.optString("id", "")
        val displayName = product.optString("short_name").ifBlank {
            product.optString("name", "未知商品")
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_product_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = productId.isNotBlank()
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
                marginStart = dp(4)
                marginEnd = dp(4)
            }

            // 第一行：图片 + 商品信息 + 收藏心形
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )

                val imageView = ImageView(this@MainActivity).apply {
                    setBackgroundResource(R.drawable.bg_image_placeholder)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "商品图片"
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                        marginEnd = dp(12)
                    }
                }
                addView(imageView)
                product.optString("image_url", "").takeIf { it.isNotBlank() }?.let {
                    loadImageInto(it, imageView)
                }

                // 商品名 + 价格 + 评分
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(newProductName(displayName).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    })
                    addView(newProductPrice(productPriceText(product)).apply {
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(4) }
                    })
                    // 仅显示评分
                    if (product.has("rating") && !product.isNull("rating")) {
                        addView(TextView(this@MainActivity).apply {
                            text = "⭐ ${product.optDouble("rating")}"
                            setTextColor(getColor(R.color.app_hint_text))
                            textSize = 10f
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(2) }
                        })
                    }
                })

                // 收藏心形
                addView(newCardFavHeart(product))
            })

            // 第二行：按钮横排
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }

                addView(TextView(this@MainActivity).apply {
                    text = "查看详情"
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_card_button)
                    setTextColor(getColor(android.R.color.white))
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                        marginEnd = dp(8)
                    }
                    applyPressScaleEffect(this)
                    setOnClickListener {
                        if (productId.isNotBlank()) loadProductDetail(productId)
                    }
                })
                addView(TextView(this@MainActivity).apply {
                    text = "加入购物车"
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_chip)
                    setTextColor(getColor(R.color.app_primary_dark))
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f)
                    applyPressScaleEffect(this)
                    setOnClickListener {
                        val pid = product.optString("id")
                        if (pid.isNotBlank()) loadProductDetail(pid) else toast("商品信息不完整")
                    }
                })
            })
        }
    }

    /** 商品卡右上角的收藏心形（inline / single 卡共用） */
    private fun newCardFavHeart(product: JSONObject): TextView {
        val id = product.optString("id")
        return TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                this.gravity = Gravity.TOP
                marginStart = dp(2)
                marginEnd = dp(6)
                topMargin = dp(6)
            }
            applyFavHeartState(this, CartStore.isFav(id))
            // 注册到心形跟踪 map，以便收藏状态变化时统一刷新
            cardFavHearts.getOrPut(id) { mutableListOf() }.add(this)
            setOnClickListener {
                val faved = CartStore.toggleFav(product)
                syncAllFavHearts(id, faved)
                toast(if (faved) "已收藏" else "已取消收藏")
            }
        }
    }

    private fun applyFavHeartState(heart: TextView, faved: Boolean) {
        heart.text = if (faved) "❤" else "🤍"
        heart.textSize = 16f
    }

    /** 同步所有同商品 ID 的心形状态（卡片 + 详情页） */
    private fun syncAllFavHearts(productId: String, faved: Boolean) {
        cardFavHearts[productId]?.forEach { applyFavHeartState(it, faved) }
        if (currentDetailProduct?.optString("id") == productId) {
            updateWishlistHeart(productId)
        }
    }

    private fun newSingleProductCard(product: JSONObject): LinearLayout {
        val productId = product.optString("id", "")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_product_card)
            isClickable = productId.isNotBlank()
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                bubbleMaxWidth(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }

        val imageView = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_image_placeholder)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "商品图片"
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(126))
        }
        card.addView(imageView)
        product.optString("image_url", "").takeIf { it.isNotBlank() }?.let {
            loadImageInto(it, imageView)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        body.addView(newProductName(product.optString("name", "未知商品")))
        body.addView(newProductPrice(productPriceText(product)))
        val singleMetaParts = mutableListOf<String>()
        product.optString("brand").takeIf { it.isNotBlank() }?.let { singleMetaParts.add(it) }
        product.optString("sub_category").takeIf { it.isNotBlank() }?.let { singleMetaParts.add(it) }
        if (product.has("rating") && !product.isNull("rating")) {
            singleMetaParts.add("⭐ ${product.optDouble("rating")}")
        }
        body.addView(TextView(this).apply {
            text = singleMetaParts.joinToString(" · ").ifBlank { product.optString("category", "商品") }
            setTextColor(getColor(R.color.app_hint_text))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
            }
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            }
        }
        actions.addView(newActionButton("查看详情", primary = true).apply {
            setOnClickListener {
                if (productId.isNotBlank()) {
                    loadProductDetail(productId)
                }
            }
        })
        actions.addView(newActionButton("加入购物车", primary = false).apply {
            setOnClickListener {
                val pid = product.optString("id")
                if (pid.isNotBlank()) loadProductDetail(pid) else toast("商品信息不完整")
            }
        })
        body.addView(actions)
        card.addView(body)
        card.addView(newCardFavHeart(product))

        card.setOnClickListener {
            if (productId.isNotBlank()) {
                loadProductDetail(productId)
            }
        }
        return card
    }

    private fun animateEntrance(view: View) {
        view.alpha = 0f
        view.translationY = dp(12).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun addUserBubble(text: String) {
        val bubble = TextView(this).apply {
            this.text = text
            setBackgroundResource(R.drawable.bg_message_user)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(getColor(android.R.color.white))
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.65f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                bottomMargin = dp(12)
            }
        }
        chatContainer.addView(bubble)
        animateEntrance(bubble)
        activeTextBubble = null
        activeAiColumn = null
        activeProductRow = null
        scrollToBottom()
    }

    private fun showThinkingBubble() {
        val column = getAiColumn()
        currentAssistantColumn = column

        // 容器：小鸟 + "球球正在思考" + 弹跳圆点
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            background = getDrawable(R.drawable.bg_message)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }

        // 小鸟图片 + 上下弹跳动画
        val bird = ImageView(this).apply {
            setImageResource(R.drawable.avatar_bird)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginEnd = dp(6)
            }
        }
        container.addView(bird)
        val birdAnim = ObjectAnimator.ofFloat(bird, "translationY", 0f, -dp(3).toFloat()).apply {
            duration = 500
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
        }
        thinkingAnimators.clear()
        birdAnim.start()
        thinkingAnimators.add(birdAnim)

        // "球球正在思考"
        val text = TextView(this).apply {
            setText("球球正在思考")
            textSize = 12f
            setTextColor(getColor(R.color.app_muted_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(3) }
        }
        container.addView(text)

        // 3 个弹跳圆点
        thinkingDots.clear()
        val dotColor = getColor(R.color.app_primary)
        for (i in 0..2) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(4)).apply {
                    marginStart = dp(2)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(dotColor)
                }
            }
            container.addView(dot)
            thinkingDots.add(dot)
            // 交错弹跳动画
            val dotAnim = ObjectAnimator.ofFloat(dot, "translationY", 0f, -dp(4).toFloat()).apply {
                duration = 400
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                startDelay = (i * 150).toLong()
                interpolator = DecelerateInterpolator()
                start()
            }
            thinkingAnimators.add(dotAnim)
        }

        column.addView(container)
        animateEntrance(container)
        thinkingBubble = container
        activeTextBubble = null
        thinkingStep = 0
        isThinking = true
        scrollToBottom()
    }

    private fun animateThinkingBubble() {
        // 动画由 ObjectAnimator 自动驱动，不再需要手动循环
    }

    private fun removeThinkingBubble() {
        stopThinkingAnimation(clearText = false, removeBubble = true)
    }

    private fun stopThinkingAnimation(clearText: Boolean, removeBubble: Boolean) {
        isThinking = false
        val bubble = thinkingBubble ?: return
        if (removeBubble) {
            (bubble.parent as? LinearLayout)?.removeView(bubble)
        }
        // 停止所有动画
        thinkingAnimators.forEach { it.cancel() }
        thinkingAnimators.clear()
        if (bubble is ViewGroup) {
            for (i in 0 until bubble.childCount) {
                bubble.getChildAt(i).animate().cancel()
                bubble.getChildAt(i).clearAnimation()
            }
        }
        thinkingDots.clear()
        thinkingBubble = null
    }

    // ── 开场动画（小鸟弹跳入场 + 文字淡入 + 整体淡出）──
    private fun showSplashAnimation() {
        val contentView = findViewById<FrameLayout>(android.R.id.content)

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F2F5F0"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            isClickable = true  // 拦截点击事件
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }

        // 小鸟图片
        val bird = ImageView(this).apply {
            setImageResource(R.drawable.avatar_bird)
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        column.addView(bird)

        // "你好，我是球球！"
        val title = TextView(this).apply {
            text = "你好，我是球球！"
            textSize = 20f
            setTextColor(Color.parseColor("#00A878"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        column.addView(title)

        // "你的专属导购助手"
        val sub = TextView(this).apply {
            text = "你的专属导购助手"
            textSize = 14f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        column.addView(sub)

        overlay.addView(column)
        contentView.addView(overlay)

        // 小鸟弹跳入场（从上方掉落 + 缩放 + OvershootInterpolator）
        bird.alpha = 0f
        bird.translationY = -dp(100).toFloat()
        bird.scaleX = 0.3f
        bird.scaleY = 0.3f
        bird.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()

        // 标题淡入（延迟 800ms）
        title.alpha = 0f
        title.animate().alpha(1f).setStartDelay(800).setDuration(500).start()

        // 副标题淡入（延迟 1200ms）
        sub.alpha = 0f
        sub.animate().alpha(1f).setStartDelay(1200).setDuration(500).start()

        // 3000ms 后整体淡出
        Handler(Looper.getMainLooper()).postDelayed({
            overlay.animate()
                .alpha(0f)
                .setDuration(800)
                .withEndAction { contentView.removeView(overlay) }
                .start()
        }, 3000)
    }

    private fun restoreAiTextBubbleStyle(bubble: TextView?) {
        bubble ?: return
        bubble.textSize = 14f
        bubble.setTextColor(getColor(R.color.app_text))
    }

    private fun getProductRow(): LinearLayout {
        activeProductRow?.let { return it }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val scrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
            addView(row)
        }
        getAiColumn().addView(scrollView)
        activeProductRow = row
        return row
    }

    private fun showWelcomeMessage() {
        chatContainer.removeAllViews()
        activeTextBubble = null
        activeAiColumn = null
        activeProductRow = null
        activeBodyContainer = null
        addTimeDivider()

        val column = getAiColumn()
        val welcomeText = "你好！我是你的智能导购助手球球\uD83D\uDC26 选品不求人，购物找球球 \uD83D\uDC4B"
        val bubble = newTextBubble().apply { text = "" }
        column.addView(bubble)

        val chipsContainer = newQuickChips()
        column.addView(chipsContainer)
        // 收集所有 chip（每行前 2 个子 View，第 3 个是留白 spacer）
        val allChips = mutableListOf<android.view.View>()
        for (r in 0 until chipsContainer.childCount) {
            val row = chipsContainer.getChildAt(r) as? LinearLayout ?: continue
            for (c in 0 until minOf(row.childCount, 2)) {
                allChips.add(row.getChildAt(c))
            }
        }
        // 先隐藏所有 chip
        allChips.forEach {
            it.alpha = 0f
            it.translationY = dp(12).toFloat()
        }

        // 等开场动画结束后（3800ms）再开始打字和按钮动画
        val splashDelay = 3800L
        val handler = android.os.Handler(mainLooper)

        // 逐字打字效果
        welcomeText.forEachIndexed { i, _ ->
            handler.postDelayed({
                bubble.text = welcomeText.substring(0, i + 1)
            }, splashDelay + i * 45L)
        }

        // 打字完成后，依次浮现快捷按钮
        val typewriterEnd = splashDelay + welcomeText.length * 45L
        allChips.forEachIndexed { i, chip ->
            handler.postDelayed({
                chip.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }, typewriterEnd + 100 + i * 80L)
        }

        activeTextBubble = null
        activeAiColumn = null
        activeProductRow = null
    }

    private fun addTimeDivider() {
        val divider = TextView(this).apply {
            text = "今天"
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.app_disabled_text))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12)
            }
        }
        chatContainer.addView(divider)
    }

    private fun getAiColumn(): LinearLayout {
        activeAiColumn?.let { return it }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(14)
            }
            addView(newAiAvatar())
            addView(column)
        }
        chatContainer.addView(row)
        activeAiColumn = column
        return column
    }

    private fun newAiAvatar(): ImageView {
        return ImageView(this).apply {
            setImageResource(R.drawable.avatar_bird)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(8)
            }
        }
    }

    /** 返回包含 6 个 chip 的容器，每行 2 个，右侧留白 */
    private fun newQuickChips(): LinearLayout {
        data class QuickChip(val label: String, val query: String)
        val rows = listOf(
            listOf(
                QuickChip("\uD83D\uDCBB 笔记本电脑选购", "我想要一款笔记本电脑"),
                QuickChip("\uD83E\uDDF4 护肤精华推荐", "给我推荐一款护肤精华"),
            ),
            listOf(
                QuickChip("\uD83D\uDC5F 跑步鞋推荐", "推荐一双跑步鞋"),
                QuickChip("\u26F0\uFE0F 户外徒步搭配", "推荐一整套户外徒步用的装备"),
            ),
            listOf(
                QuickChip("\u2728 神仙水介绍", "神仙水怎么样"),
                QuickChip("\uD83D\uDD0D 小棕瓶vs小黑瓶", "小棕瓶和小黑瓶哪个好"),
            ),
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }

            for (row in rows) {
                val rowLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                for (chip in row) {
                    rowLayout.addView(newCategoryChip(chip.label, chip.query))
                }
                // 无右侧留白，按钮平分整行宽度
                addView(rowLayout)
            }
        }
    }

    private fun newCategoryChip(label: String, query: String): TextView {
        return TextView(this).apply {
            text = label
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextColor(getColor(R.color.app_primary))
            textSize = 12f
            maxLines = 1
            gravity = Gravity.CENTER
            isClickable = true
            applyPressScaleEffect(this)
            setOnClickListener {
                startChat(query)
            }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
        }
    }

    private fun newHighlightBlock(content: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_highlight_bg))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            // 圆角通过 GradientDrawable
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.app_highlight_bg))
                cornerRadius = dp(8).toFloat()
            }
            background = bg

            addView(TextView(this@MainActivity).apply {
                text = "选购建议"
                setTextColor(getColor(R.color.app_highlight_title))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(4)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = content
                setTextColor(getColor(R.color.app_text))
                textSize = 13f
                setLineSpacing(0f, 1.2f)
            })
        }
    }

    private fun newShortAiBubble(text: String): TextView {
        return newTextBubble().apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.app_text))
        }
    }

    private fun newGuideCard(clarify: JSONObject): LinearLayout {
        val questions = clarify.optJSONArray("questions") ?: JSONArray()
        val selections = mutableMapOf<Int, MutableSet<String>>()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_message)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                bubbleMaxWidth(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }

            val doneButton = newClarifyDoneButton().apply {
                visibility = View.GONE
                setOnClickListener {
                    val answer = buildClarifyAnswer(questions, selections)
                    if (answer.isBlank()) {
                        return@setOnClickListener
                    }
                    isEnabled = false
                    alpha = 0.65f
                    startChat(answer)
                }
            }

            var renderedQuestionCount = 0
            for (index in 0 until questions.length()) {
                val item = questions.optJSONObject(index) ?: continue
                val question = item.optString("question", "").trim()
                val answers = jsonArrayToStrings(item.optJSONArray("answers") ?: JSONArray())
                if (question.isBlank() || answers.isEmpty()) {
                    continue
                }
                renderedQuestionCount += 1
                val qIdx = index
                addView(newGuideSection(question, answers) { label, isSelected ->
                    val set = selections.getOrPut(qIdx) { mutableSetOf() }
                    if (isSelected) set.add(label) else set.remove(label)
                    if (set.isEmpty()) selections.remove(qIdx)
                    updateClarifyDoneButton(doneButton, renderedQuestionCount, selections.size)
                })
            }

            addView(doneButton)
        }
    }

    private fun newGuideSection(title: String, options: List<String>, onToggle: (String, Boolean) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
            }
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(getColor(R.color.app_text))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(FlowLayout(this@MainActivity).apply {
                gapX = dp(6)
                gapY = dp(6)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(7)
                }
                options.forEach { label ->
                    val option = newGuideOption(label)
                    option.setOnClickListener {
                        val wasSelected = option.tag == true
                        val nowSelected = !wasSelected
                        option.tag = nowSelected
                        setGuideOptionSelected(option, nowSelected)
                        onToggle(label, nowSelected)
                    }
                    addView(option)
                }
            })
        }
    }

    private fun newGuideOption(label: String): TextView {
        return TextView(this).apply {
            text = label
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setTextColor(getColor(R.color.app_primary_dark))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(6)
            }
        }
    }

    private fun setGuideOptionSelected(option: TextView, selected: Boolean) {
        option.setBackgroundResource(if (selected) R.drawable.bg_card_button else R.drawable.bg_chip)
        option.setTextColor(getColor(if (selected) android.R.color.white else R.color.app_primary_dark))
    }

    // 自动换行的标签容器（等价 CSS flex-wrap: wrap）：长选项放不下时整块换到下一行
    private class FlowLayout(context: Context) : ViewGroup(context) {
        var gapX = 0
        var gapY = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
            val childWidthSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val rightEdge = maxWidth - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                measureChild(child, childWidthSpec, childHeightSpec)
                val w = child.measuredWidth
                val h = child.measuredHeight
                if (x > paddingLeft && x + w > rightEdge) {
                    x = paddingLeft
                    y += rowHeight + gapY
                    rowHeight = 0
                }
                x += w + gapX
                rowHeight = maxOf(rowHeight, h)
            }
            val totalHeight = y + rowHeight + paddingBottom
            setMeasuredDimension(maxWidth, View.resolveSize(totalHeight, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val rightEdge = (r - l) - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                val w = child.measuredWidth
                val h = child.measuredHeight
                if (x > paddingLeft && x + w > rightEdge) {
                    x = paddingLeft
                    y += rowHeight + gapY
                    rowHeight = 0
                }
                child.layout(x, y, x + w, y + h)
                x += w + gapX
                rowHeight = maxOf(rowHeight, h)
            }
        }
    }

    private fun newClarifyDoneButton(): TextView {
        return TextView(this).apply {
            text = "选好了"
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_card_button)
            setTextColor(getColor(android.R.color.white))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36),
            ).apply {
                topMargin = dp(4)
            }
        }
    }

    private fun updateClarifyDoneButton(button: TextView, questionCount: Int, selectedCount: Int) {
        val ready = questionCount > 0 && selectedCount >= questionCount
        button.visibility = if (ready) View.VISIBLE else View.GONE
        button.isEnabled = ready
    }

    private fun buildClarifyAnswer(questions: JSONArray, selections: Map<Int, MutableSet<String>>): String {
        // 只发选中的条件，不重复原始 query（后端从对话历史获取上下文）
        val parts = mutableListOf<String>()
        for (index in 0 until questions.length()) {
            val selected = selections[index]?.filter { it.isNotBlank() } ?: continue
            if (selected.isEmpty()) continue
            parts.addAll(selected)
        }
        return parts.joinToString("，")
    }

    private fun jsonArrayToStrings(array: JSONArray): List<String> {
        val result = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index, "").trim()
            if (value.isNotBlank()) {
                result.add(value)
            }
        }
        return result
    }

    // 文字气泡 / 商品卡的统一最大宽度（屏宽 78%），保证两者视觉对齐
    private fun bubbleMaxWidth(): Int = (resources.displayMetrics.widthPixels * 0.78f).toInt()

    private fun newTextBubble(): TextView {
        return TextView(this).apply {
            setBackgroundResource(R.drawable.bg_message)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(getColor(R.color.app_text))
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            maxWidth = bubbleMaxWidth()
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = if (chatContainer.childCount == 0) 0 else dp(4)
                bottomMargin = dp(6)
            }
        }
    }

    /** Body 富文本容器：带圆角背景的 LinearLayout，内含多个子 View（文字/图片/商品卡） */
    private fun newBodyContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_message)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            elevation = dp(3).toFloat()
            // clipChildren=true：父容器 clipChildren=false 会让内部 HorizontalScrollView 静止时
            // 不裁剪自身内容，导致表格滚动后超出气泡。卡片已 MATCH_PARENT 被 padding 内缩、阴影在
            // 容器范围内，无需溢出，故启用裁剪。clipToPadding=false 保留 padding 内的阴影绘制。
            clipChildren = true
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                bubbleMaxWidth(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
        }
    }

    /** Body 容器内的子 TextView（无背景、无 padding，纯文字） */
    private fun newBodyTextView(): TextView {
        return TextView(this).apply {
            setTextColor(getColor(R.color.app_text))
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    /** Body 容器内的图片 View：等比缩放（约 80% 宽度），不裁剪 */
    private fun newBodyImageView(): ImageView {
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundResource(R.drawable.bg_image_placeholder)
            minimumHeight = dp(120)
            contentDescription = "图片"
            // 宽度约 80%，高度自适应
            val imgWidth = (bubbleMaxWidth() * 0.8f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                imgWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
    }

    private fun newProductName(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.app_text))
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
            }
        }
    }

    private fun newProductImage(): ImageView {
        return ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_image_placeholder)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "商品图片"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(104),
            ).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun newProductPrice(priceText: String): TextView {
        return TextView(this).apply {
            text = priceText
            setTextColor(getColor(R.color.app_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
        }
    }

    private fun productPriceText(product: JSONObject): String {
        return when (val raw = product.opt("price")) {
            is Number -> String.format(Locale.CHINA, "¥%.2f", raw.toDouble())
            is String -> if (raw.startsWith("¥")) raw else "¥$raw"
            else -> product.optString("price_range", "价格待确认").ifBlank { "价格待确认" }
        }
    }

    /** 按压缩放触摸反馈：按下缩到 0.92，松开弹回 1.0 */
    private fun applyPressScaleEffect(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
            false // 不消费事件，让 onClick 继续触发
        }
    }

    /** 加入购物车按钮的成功动画：缩放弹跳 + 文字切换 */
    private fun animateCartButton(btn: TextView) {
        btn.text = "✓ 已加入"
        btn.setBackgroundResource(R.drawable.bg_card_button)
        btn.setTextColor(getColor(android.R.color.white))
        btn.isEnabled = false

        // 先缩小再弹出
        val scaleX = ObjectAnimator.ofFloat(btn, "scaleX", 1f, 0.8f, 1.1f, 1f)
        val scaleY = ObjectAnimator.ofFloat(btn, "scaleY", 1f, 0.8f, 1.1f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 400
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun newActionButton(label: String, primary: Boolean): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setBackgroundResource(if (primary) R.drawable.bg_card_button else R.drawable.bg_chip)
            setTextColor(getColor(if (primary) android.R.color.white else R.color.app_primary_dark))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(28),
                1f,
            ).apply {
                marginEnd = if (primary) dp(6) else 0
            }
            applyPressScaleEffect(this)
        }
    }

    private fun newDetailLink(): TextView {
        return TextView(this).apply {
            text = "查看详情"
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_card_button)
            setTextColor(getColor(android.R.color.white))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(28),
            ).apply {
                topMargin = dp(8)
            }
            applyPressScaleEffect(this)
        }
    }

    private fun loadImageInto(imagePath: String, imageView: ImageView) {
        val cacheKey = imagePath
        // 命中缓存 → 直接显示，不走网络
        imageCache.get(cacheKey)?.let {
            imageView.setImageBitmap(it)
            return
        }

        val shimmer = ShimmerDrawable()
        imageView.foreground = shimmer
        shimmer.start()

        imageExecutor.execute {
            try {
                // 再检查一次（可能另一个线程刚下载完）
                val cached = imageCache.get(cacheKey)
                if (cached != null) {
                    runOnUiThread {
                        shimmer.stop()
                        imageView.foreground = null
                        imageView.setImageBitmap(cached)
                    }
                    return@execute
                }
                val absUrl = toAbsoluteImageUrl(imagePath)
                android.util.Log.d("IMG_DEBUG", "Loading image: $absUrl (original: $imagePath)")
                val url = URL(absUrl)
                val bitmap = url.openStream().use { input ->
                    BitmapFactory.decodeStream(input)
                }
                android.util.Log.d("IMG_DEBUG", "Bitmap result: ${bitmap?.width}x${bitmap?.height} for $imagePath")
                if (bitmap != null) {
                    imageCache.put(cacheKey, bitmap)
                    runOnUiThread {
                        shimmer.stop()
                        imageView.foreground = null
                        imageView.setImageBitmap(bitmap)
                    }
                } else {
                    android.util.Log.w("IMG_DEBUG", "decodeStream returned null for $imagePath")
                    runOnUiThread {
                        shimmer.stop()
                        imageView.foreground = null
                        imageView.visibility = android.view.View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("IMG_DEBUG", "FAILED to load image: ${toAbsoluteImageUrl(imagePath)}", e)
                runOnUiThread {
                    shimmer.stop()
                    imageView.foreground = null
                    // 加载失败时隐藏 ImageView，避免灰块
                    imageView.visibility = android.view.View.GONE
                }
            }
        }
    }

    // Shimmer 高光扫过动画（纯代码实现）
    private inner class ShimmerDrawable : Drawable() {
        private val paint = Paint()
        private var translateX = 0f
        private var animator: ObjectAnimator? = null

        fun start() {
            animator = ObjectAnimator.ofFloat(this, "shimmerTranslate", -1f, 2f).apply {
                duration = 1200
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        @Suppress("unused") // called by ObjectAnimator
        fun setShimmerTranslate(value: Float) {
            translateX = value
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0 || h <= 0) return
            val shimmerW = w * 0.4f
            val left = translateX * w
            paint.shader = LinearGradient(
                left, 0f, left + shimmerW, 0f,
                intArrayOf(0x00FFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun toAbsoluteImageUrl(path: String): String {
        val raw = when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> "$serverBaseUrl$path"
            else -> "$serverBaseUrl/static/$path"
        }
        // Encode non-ASCII chars (e.g. Chinese directory names) so java.net.URL can parse
        return raw.replace(Regex("[^\\x00-\\x7F]")) { java.net.URLEncoder.encode(it.value, "UTF-8") }
    }

    private fun loadProductDetail(productId: String) {
        statusText.text = "● 正在加载详情"
        Thread {
            try {
                val detail = chatClient.fetchProductDetail(productId).apply {
                    if (optString("id").isBlank()) put("id", productId)
                }
                runOnUiThread {
                    showProductDetail(detail)
                    statusText.text = "● 在线"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "● 详情加载失败：${error.message}"
                }
            }
        }.start()
    }

    private fun showProductDetail(detail: JSONObject) {
        selectedSpecs.clear()
        currentSkuCount = 0
        val priceText = if (detail.has("price_range")) {
            detail.optString("price_range")
        } else {
            String.format(Locale.CHINA, "¥%.2f", detail.optDouble("price", 0.0))
        }
        detailPrice.text = priceText
        detailName.text = detail.optString("name", "未知商品")
        detailDescription.text = detail.optString("description", "")

        val imageUrl = detail.optString("image_url", "")
        detailImage.setImageDrawable(null)
        if (imageUrl.isNotBlank()) {
            loadImageInto(imageUrl, detailImage)
        }

        renderDetailTags(detail)
        renderAttributes(detail)
        renderSkuSelector(detail)
        renderFaq(detail.optJSONArray("faq") ?: JSONArray())
        renderReviews(detail)

        currentDetailProduct = cartProductFromDetail(detail)
        updateWishlistHeart(detail.optString("id"))

        detailOpenedFromCart = cartScreen.visibility == View.VISIBLE
        detailScreen.visibility = View.VISIBLE
        chatScreen.visibility = View.GONE
        cartScreen.visibility = View.GONE
    }

    private fun renderDetailTags(detail: JSONObject) {
        detailTags.removeAllViews()

        val tags = linkedSetOf<String>()
        detail.optString("category", "").takeIf { it.isNotBlank() }?.let { tags.add(it) }

        val attributes = detail.optJSONObject("attributes")
        attributes?.optString("子类目", "")?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
        attributes?.optString("品牌", "")?.takeIf { it.isNotBlank() }?.let { tags.add(it) }

        if (tags.isEmpty()) {
            detailTags.visibility = View.GONE
            return
        }

        detailTags.visibility = View.VISIBLE
        tags.take(4).forEach { tag ->
            detailTags.addView(newTag(tag))
        }
    }

    private fun renderAttributes(detail: JSONObject) {
        detailAttributes.removeAllViews()

        val category = detail.optString("category", "")
        if (category.isNotBlank()) {
            detailAttributes.addView(newInfoRow("类目", category))
        }

        val attributes = detail.optJSONObject("attributes") ?: JSONObject()
        val keys = attributes.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = attributes.optString(key, "")
            if (value.isNotBlank()) {
                detailAttributes.addView(newInfoRow(key, value))
            }
        }

        val priceRange = detail.optString("price_range", "")
        if (priceRange.isNotBlank()) {
            detailAttributes.addView(newInfoRow("价格区间", priceRange))
        }
    }

    private fun renderSkuSelector(detail: JSONObject) {
        detailSkuList.removeAllViews()

        val skus = detail.optJSONArray("skus")
        if (skus == null || skus.length() <= 1) {
            detailSkuSection.visibility = View.GONE
            return
        }

        // 提取所有规格维度和可选值（如 尺码→[S,M,L], 颜色→[黑,白]）
        val dimensions = LinkedHashMap<String, LinkedHashSet<String>>() // 维度名→可选值
        for (i in 0 until skus.length()) {
            val sku = skus.optJSONObject(i) ?: continue
            val props = sku.optJSONObject("properties") ?: continue
            val keys = props.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = props.optString(key, "")
                if (value.isNotBlank()) {
                    dimensions.getOrPut(key) { LinkedHashSet() }.add(value)
                }
            }
        }

        if (dimensions.isEmpty()) {
            detailSkuSection.visibility = View.GONE
            return
        }

        detailSkuSection.visibility = View.VISIBLE
        selectedSpecs.clear()
        currentSkuCount = dimensions.size

        // 单选项维度自动选中，不计入 currentSkuCount
        for ((dimName, values) in dimensions) {
            if (values.size == 1) {
                selectedSpecs[dimName] = values.first()
                currentSkuCount--
            }
        }

        for ((dimName, values) in dimensions) {
            // 维度标题
            detailSkuList.addView(TextView(this).apply {
                text = dimName
                setTextColor(getColor(R.color.app_muted_text))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8); bottomMargin = dp(4) }
            })

            // Chip 流式布局（用 FlowLayout 简化为 wrap 的水平容器）
            val chipContainer = object : android.widget.FrameLayout(this) {
                override fun onMeasure(wSpec: Int, hSpec: Int) {
                    val maxW = MeasureSpec.getSize(wSpec)
                    var x = 0; var y = 0; var rowH = 0
                    for (i in 0 until childCount) {
                        val child = getChildAt(i)
                        child.measure(
                            MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        )
                        if (x + child.measuredWidth > maxW) { x = 0; y += rowH + dp(6); rowH = 0 }
                        rowH = maxOf(rowH, child.measuredHeight)
                        x += child.measuredWidth + dp(8)
                    }
                    setMeasuredDimension(maxW, y + rowH)
                }
                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    val maxW = r - l
                    var x = 0; var y = 0; var rowH = 0
                    for (i in 0 until childCount) {
                        val child = getChildAt(i)
                        if (x + child.measuredWidth > maxW) { x = 0; y += rowH + dp(6); rowH = 0 }
                        child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                        rowH = maxOf(rowH, child.measuredHeight)
                        x += child.measuredWidth + dp(8)
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }

            val chips = mutableListOf<TextView>()
            for (value in values) {
                val chip = TextView(this).apply {
                    text = value
                    textSize = 12f
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setBackgroundResource(R.drawable.bg_chip)
                    setTextColor(getColor(R.color.app_text))
                }
                chips.add(chip)
                chipContainer.addView(chip)
            }

            // 点击选中逻辑
            fun updateChipStates(selected: String) {
                selectedSpecs[dimName] = selected
                for (c in chips) {
                    if (c.text == selected) {
                        c.setTextColor(0xFFFFFFFF.toInt())
                        c.setBackgroundResource(R.drawable.bg_card_button)
                    } else {
                        c.setTextColor(getColor(R.color.app_text))
                        c.setBackgroundResource(R.drawable.bg_chip)
                    }
                }
                // 更新价格
                updatePriceBySelectedSku(skus, selectedSpecs)
            }

            for (chip in chips) {
                chip.setOnClickListener { updateChipStates(chip.text.toString()) }
            }

            // 单选项维度默认选中样式
            if (values.size == 1) {
                updateChipStates(values.first())
            }

            detailSkuList.addView(chipContainer)
        }
    }

    private fun updatePriceBySelectedSku(skus: JSONArray, selectedSpecs: Map<String, String>) {
        if (selectedSpecs.isEmpty()) return
        // 找匹配所有已选维度的 SKU
        val matched = mutableListOf<Double>()
        for (i in 0 until skus.length()) {
            val sku = skus.optJSONObject(i) ?: continue
            val props = sku.optJSONObject("properties") ?: continue
            var allMatch = true
            for ((key, value) in selectedSpecs) {
                if (props.optString(key, "") != value) { allMatch = false; break }
            }
            if (allMatch) matched.add(sku.optDouble("price", 0.0))
        }
        if (matched.isNotEmpty()) {
            val min = matched.min()
            val max = matched.max()
            detailPrice.text = if (min == max) {
                String.format(Locale.CHINA, "¥%.0f", min)
            } else {
                String.format(Locale.CHINA, "¥%.0f-¥%.0f", min, max)
            }
        }
    }

    private fun renderFaq(faq: JSONArray) {
        detailFaqList.removeAllViews()
        if (faq.length() > 0) {
            detailFaqSection.visibility = View.VISIBLE
            for (i in 0 until minOf(faq.length(), 10)) {
                val item = faq.opt(i)
                if (item is JSONObject) {
                    // 结构化 Q/A
                    val q = item.optString("question", "")
                    val a = item.optString("answer", "")
                    detailFaqList.addView(newFaqItem(q, a))
                } else {
                    // 兜底：旧格式纯字符串
                    val content = item?.toString() ?: ""
                    val (q, a) = splitQuestionAnswer(content)
                    detailFaqList.addView(newFaqItem(q, a))
                }
            }
        } else {
            detailFaqSection.visibility = View.GONE
        }
    }

    private fun renderReviews(detail: JSONObject) {
        detailReviewsList.removeAllViews()

        val reviewItems = detail.optJSONArray("review_items")
        if (reviewItems != null && reviewItems.length() > 0) {
            detailReviewsSection.visibility = View.VISIBLE
            for (i in 0 until minOf(reviewItems.length(), 3)) {
                val item = reviewItems.optJSONObject(i) ?: continue
                detailReviewsList.addView(
                    newReviewItem(
                        content = item.optString("content", ""),
                        rating = if (item.isNull("rating")) null else item.optInt("rating"),
                        index = i,
                    ),
                )
            }
            return
        }

        val reviews = detail.optJSONArray("reviews") ?: JSONArray()
        if (reviews.length() == 0) {
            detailReviewsSection.visibility = View.GONE
            return
        }

        detailReviewsSection.visibility = View.VISIBLE
        for (i in 0 until minOf(reviews.length(), 3)) {
            detailReviewsList.addView(newReviewItem(reviews.optString(i), rating = null, index = i))
        }
    }

    private fun newTag(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(9), dp(3), dp(9), dp(3))
            setTextColor(getColor(R.color.app_primary))
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(6)
            }
        }
    }

    private fun newInfoRow(key: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(TextView(this).apply {
            text = key
            setTextColor(getColor(R.color.app_hint_text))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(dp(82), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(this).apply {
            text = value
            gravity = Gravity.END
            setTextColor(getColor(R.color.app_text))
            textSize = 13f
            setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun newFaqItem(question: String, answer: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = "Q：$question"
                setTextColor(getColor(R.color.app_text))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (answer.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = "A：$answer"
                    setTextColor(getColor(R.color.app_muted_text))
                    textSize = 13f
                    setLineSpacing(0f, 1.2f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(4)
                    }
                })
            }
        }
    }

    private fun newReviewItem(content: String, rating: Int?, index: Int): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "用户${index + 1}"
            setTextColor(getColor(R.color.app_text))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = rating?.let { starText(it) } ?: "评价"
            setTextColor(if (rating == null) getColor(R.color.app_hint_text) else getColor(R.color.app_star))
            textSize = 12f
        })
        item.addView(header)
        item.addView(TextView(this).apply {
            text = content
            setTextColor(getColor(R.color.app_muted_text))
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(5)
            }
        })
        return item
    }

    private fun splitQuestionAnswer(content: String): Pair<String, String> {
        val index = listOf("？", "?").map { content.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (index == null) {
            return content to ""
        }

        val question = content.substring(0, index + 1).trim()
        val answer = content.substring(index + 1).trim()
        return question to answer
    }

    private fun starText(rating: Int): String {
        val safeRating = rating.coerceIn(0, 5)
        return "★".repeat(safeRating) + "☆".repeat(5 - safeRating)
    }

    private fun showChatScreen() {
        detailScreen.visibility = View.GONE
        cartScreen.visibility = View.GONE
        checkoutScreen.visibility = View.GONE
        chatScreen.visibility = View.VISIBLE
    }

    private fun showCartScreen() {
        detailScreen.visibility = View.GONE
        chatScreen.visibility = View.GONE
        checkoutScreen.visibility = View.GONE
        cartScreen.visibility = View.VISIBLE
        selectCartTab(showFav = false)
    }

    // ── 结算页 ──────────────────────────────────────────────

    private val presetAddresses = listOf(
        "\uD83C\uDFE0 家\u3000北京市朝阳区建国路 88 号幸福小区 6 号楼 302",
        "\uD83C\uDFE2 公司\u3000北京市海淀区中关村大街 1 号创新大厦 12 层",
        "\uD83D\uDCCD 其他\u3000上海市浦东新区陆家嘴环路 1000 号",
    )

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val view = currentFocus ?: window.decorView
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showCheckoutScreen(fromCart: Boolean) {
        if (CartStore.cartItems().isEmpty()) {
            toast("购物车是空的")
            return
        }
        checkoutFromCart = fromCart
        selectedAddress = null

        chatScreen.visibility = View.GONE
        detailScreen.visibility = View.GONE
        cartScreen.visibility = View.GONE
        checkoutScreen.visibility = View.VISIBLE

        // 恢复可能被下单成功页隐藏/替换的 scroll content
        val scrollContent = checkoutItemList.parent as? LinearLayout
        if (scrollContent != null) {
            // 移除成功页动态添加的 view
            val toRemove = mutableListOf<View>()
            for (i in 0 until scrollContent.childCount) {
                val child = scrollContent.getChildAt(i)
                if (child.tag == "checkout_success") toRemove.add(child)
            }
            toRemove.forEach { scrollContent.removeView(it) }
            // 恢复原有子 view 的可见性
            for (i in 0 until scrollContent.childCount) {
                scrollContent.getChildAt(i).visibility = View.VISIBLE
            }
        }
        // 恢复提交按钮状态
        findViewById<TextView>(R.id.checkoutSubmitButton).apply {
            text = "提交订单"
            setOnClickListener { submitOrder() }
        }

        // 填充商品列表（图文卡片）
        checkoutItemList.removeAllViews()
        val items = CartStore.cartItems()
        items.forEach { item ->
            checkoutItemList.addView(newCheckoutItemCard(item))
        }

        // 填充地址选项
        checkoutAddressList.removeAllViews()
        presetAddresses.forEach { addr ->
            val tv = TextView(this).apply {
                text = addr
                textSize = 13f
                setTextColor(getColor(R.color.app_text))
                setBackgroundResource(R.drawable.bg_product_card)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                setOnClickListener { v ->
                    selectedAddress = addr
                    // 高亮选中，其余恢复
                    for (i in 0 until checkoutAddressList.childCount) {
                        val child = checkoutAddressList.getChildAt(i)
                        child.setBackgroundResource(R.drawable.bg_product_card)
                        (child as? TextView)?.setTextColor(getColor(R.color.app_text))
                    }
                    v.setBackgroundColor(0xFFE8F5E9.toInt())
                    (v as TextView).setTextColor(0xFF007A58.toInt())
                }
            }
            checkoutAddressList.addView(tv)
        }

        // 汇总
        val total = CartStore.cartTotal()
        val count = items.sumOf { it.optInt("qty", 1) }
        checkoutSummary.text = "共 $count 件商品"
        checkoutTotalText.text = "\u00A5$total"
    }

    private fun exitCheckoutScreen() {
        checkoutScreen.visibility = View.GONE
        if (checkoutFromCart) {
            cartScreen.visibility = View.VISIBLE
        } else {
            chatScreen.visibility = View.VISIBLE
        }
    }

    private fun submitOrder() {
        if (selectedAddress == null) {
            toast("请先选择收货地址")
            return
        }
        if (CartStore.cartItems().isEmpty()) {
            toast("购物车是空的")
            return
        }

        val orderId = "SO-${System.currentTimeMillis() / 1000}"
        val total = CartStore.cartTotal()
        val itemCount = CartStore.cartItems().sumOf { it.optInt("qty", 1) }
        val addressText = selectedAddress!!.substring(2).trim()

        // 清空购物车（保留收藏）
        CartStore.clearCart()

        if (checkoutFromCart) {
            // 从购物车进来 → 结算页内显示成功，用户手动返回购物车
            showCheckoutSuccess(orderId, itemCount, total, addressText)
        } else {
            // 从对话进来 → 回对话页 + AI 气泡
            checkoutScreen.visibility = View.GONE
            chatScreen.visibility = View.VISIBLE
            showLocalAiRichMessage(
                "\uD83C\uDF89 下单成功！\n\n" +
                "- **订单号：** $orderId\n" +
                "- **商品：** $itemCount 件  合计 \u00A5$total\n" +
                "- **收货地址：** $addressText\n" +
                "- **预计送达：** 明天 14:00-18:00\n\n" +
                "有任何问题随时找我。"
            )
        }
    }

    /** 结算页内原地显示下单成功（购物车入口） */
    private fun showCheckoutSuccess(orderId: String, itemCount: Int, total: Int, address: String) {
        val scrollContent = checkoutItemList.parent as? LinearLayout ?: return
        // 隐藏原有子 view（不移除，保留下次打开时恢复）
        for (i in 0 until scrollContent.childCount) {
            scrollContent.getChildAt(i).visibility = View.GONE
        }

        // 成功图标 + 标题合并为一个居中容器
        scrollContent.addView(LinearLayout(this).apply {
            tag = "checkout_success"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(48), 0, dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            // 绿色圆形对勾
            addView(TextView(this@MainActivity).apply {
                text = "\u2705"
                textSize = 40f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(12) }
            })
            addView(TextView(this@MainActivity).apply {
                text = "下单成功"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.app_text))
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = "感谢你的购买"
                textSize = 13f
                setTextColor(getColor(R.color.app_muted_text))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) }
            })
        })

        // 订单详情卡片，用 KV 行排列
        scrollContent.addView(LinearLayout(this).apply {
            tag = "checkout_success"
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_product_card)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(24)
            }

            fun addRow(label: String, value: String) {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) }
                    addView(TextView(this@MainActivity).apply {
                        text = label
                        textSize = 13f
                        setTextColor(getColor(R.color.app_muted_text))
                        layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = value
                        textSize = 13f
                        setTextColor(getColor(R.color.app_text))
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                })
            }

            addRow("订单号", orderId)
            addRow("商品", "$itemCount 件")
            addRow("合计", "\u00A5$total")
            addRow("收货地址", address)
            addRow("预计送达", "明天 14:00-18:00")
        })

        // 底栏改为"返回购物车"按钮
        checkoutTotalText.text = ""
        findViewById<TextView>(R.id.checkoutSubmitButton).apply {
            text = "返回购物车"
            setOnClickListener {
                // 恢复按钮状态
                text = "提交订单"
                setOnClickListener { submitOrder() }
                // 返回购物车页
                checkoutScreen.visibility = View.GONE
                cartScreen.visibility = View.VISIBLE
                selectCartTab(showFav = false)
            }
        }
    }

    private fun newCheckoutItemCard(item: JSONObject): LinearLayout {
        val name = item.optString("short_name").ifBlank { item.optString("name", "未知商品") }
        val qty = item.optInt("qty", 1)
        val price = item.optString("price", "")
        val imageUrl = item.optString("image_url", "")

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_product_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            // 商品图片
            val img = ImageView(this@MainActivity).apply {
                setBackgroundResource(R.drawable.bg_image_placeholder)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginEnd = dp(10)
                }
            }
            addView(img)
            if (imageUrl.isNotBlank()) loadImageInto(imageUrl, img)

            // 商品信息（名称 + 规格 + 价格×数量）
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = name
                    textSize = 13f
                    setTextColor(getColor(R.color.app_text))
                    maxLines = 2
                })
                // 选中的规格
                item.optString("selected_specs").takeIf { it.isNotBlank() }?.let { raw ->
                    val specsLine = try {
                        val obj = JSONObject(raw)
                        obj.keys().asSequence().map { "${it}: ${obj.optString(it)}" }.joinToString("  ")
                    } catch (_: Exception) { "" }
                    if (specsLine.isNotBlank()) {
                        addView(TextView(this@MainActivity).apply {
                            text = specsLine
                            textSize = 11f
                            setTextColor(getColor(R.color.app_hint_text))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(2) }
                        })
                    }
                }
                addView(TextView(this@MainActivity).apply {
                    text = "$price  \u00D7$qty"
                    textSize = 12f
                    setTextColor(getColor(R.color.app_muted_text))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(2) }
                })
            })
        }
    }

    // ── 购物车页：tab 切换 + 列表渲染 ──
    private fun selectCartTab(showFav: Boolean) {
        cartShowingFav = showFav
        cartTabCart.setTextColor(getColor(if (showFav) R.color.app_hint_text else R.color.app_primary))
        cartTabCart.setTypeface(null, if (showFav) Typeface.NORMAL else Typeface.BOLD)
        cartTabFav.setTextColor(getColor(if (showFav) R.color.app_primary else R.color.app_hint_text))
        cartTabFav.setTypeface(null, if (showFav) Typeface.BOLD else Typeface.NORMAL)
        cartFooter.visibility = if (showFav) View.GONE else View.VISIBLE
        renderCartList()
    }

    private fun renderCartList() {
        cartListContainer.removeAllViews()
        if (cartShowingFav) {
            val favs = CartStore.favItems()
            if (favs.isEmpty()) { cartListContainer.addView(newCartEmptyView("还没有收藏的商品")); return }
            favs.forEach { cartListContainer.addView(newFavItemRow(it)) }
        } else {
            val items = CartStore.cartItems()
            if (items.isEmpty()) { cartListContainer.addView(newCartEmptyView("购物车是空的，去对话里挑几件吧")); return }
            items.forEach { cartListContainer.addView(newCartItemRow(it)) }
        }
    }

    /** 购物车/收藏数据变化的统一回调：刷新角标、tab 计数、合计、心形、可见列表。 */
    private fun onCartChanged() {
        val n = CartStore.cartCount()
        for (badge in listOf(chatCartBadge, detailCartBadge)) {
            if (n > 0) { badge.text = n.toString(); badge.visibility = View.VISIBLE }
            else badge.visibility = View.GONE
        }
        cartTabCart.text = "购物车 (${CartStore.cartCount()})"
        cartTabFav.text = "收藏 (${CartStore.favCount()})"
        cartTotalText.text = "合计 ¥${CartStore.cartTotal()}"
        currentDetailProduct?.let { updateWishlistHeart(it.optString("id")) }
        if (cartScreen.visibility == View.VISIBLE) renderCartList()
    }

    private fun updateWishlistHeart(id: String) {
        val faved = CartStore.isFav(id)
        detailWishlistButton.text = if (faved) "♥" else "♡"
        detailWishlistButton.setTextColor(getColor(if (faved) R.color.app_badge else R.color.app_hint_text))
    }

    private fun newCartEmptyView(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.app_hint_text))
            textSize = 14f
            setPadding(0, dp(80), 0, dp(80))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun newCartItemRow(item: JSONObject): LinearLayout {
        val id = item.optString("id")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_product_card)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }

            val img = ImageView(this@MainActivity).apply {
                setBackgroundResource(R.drawable.bg_image_placeholder)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(12) }
            }
            addView(img)
            item.optString("image_url").takeIf { it.isNotBlank() }?.let { loadImageInto(it, img) }
            if (id.isNotBlank()) setOnClickListener { loadProductDetail(id) }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = item.optString("short_name").ifBlank { item.optString("name", "商品") }
                    setTextColor(getColor(R.color.app_text)); textSize = 13f; maxLines = 2
                })
                // 规格信息（优先显示选中的规格，其次显示 sub_category）
                val specsText = item.optString("selected_specs").let { raw ->
                    if (raw.isNotBlank()) {
                        try {
                            val obj = JSONObject(raw)
                            obj.keys().asSequence().map { "${it}: ${obj.optString(it)}" }.joinToString("  ")
                        } catch (_: Exception) { "" }
                    } else ""
                }
                val subCat = item.optString("sub_category")
                val specLine = if (specsText.isNotBlank()) specsText else subCat
                if (specLine.isNotBlank()) {
                    addView(TextView(this@MainActivity).apply {
                        text = specLine
                        setTextColor(getColor(R.color.app_hint_text)); textSize = 11f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(4) }
                    })
                }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) }
                    addView(TextView(this@MainActivity).apply {
                        text = item.optString("price")
                        setTextColor(getColor(R.color.app_primary)); textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    val qty = item.optInt("qty", 1)
                    addView(newQtyButton("−") { CartStore.setQty(id, qty - 1) })
                    addView(TextView(this@MainActivity).apply {
                        text = qty.toString()
                        setTextColor(getColor(R.color.app_text)); textSize = 13f; gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT)
                    })
                    addView(newQtyButton("+") { CartStore.setQty(id, qty + 1) })
                })
            })
        }
    }

    private fun newQtyButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_chip)
            setTextColor(getColor(R.color.app_muted_text))
            textSize = 15f
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            setOnClickListener { onClick() }
        }
    }

    private fun newFavItemRow(item: JSONObject): LinearLayout {
        val id = item.optString("id")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_product_card)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            if (id.isNotBlank()) setOnClickListener { loadProductDetail(id) }

            val img = ImageView(this@MainActivity).apply {
                setBackgroundResource(R.drawable.bg_image_placeholder)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(12) }
            }
            addView(img)
            item.optString("image_url").takeIf { it.isNotBlank() }?.let { loadImageInto(it, img) }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = item.optString("short_name").ifBlank { item.optString("name", "商品") }
                    setTextColor(getColor(R.color.app_text)); textSize = 13f; maxLines = 2
                })
                addView(TextView(this@MainActivity).apply {
                    text = item.optString("price")
                    setTextColor(getColor(R.color.app_primary)); textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(6); bottomMargin = dp(8) }
                })
                addView(TextView(this@MainActivity).apply {
                    text = "加入购物车"
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_card_button)
                    setTextColor(getColor(android.R.color.white))
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    isClickable = true
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    setOnClickListener {
                        val pid = item.optString("id")
                        if (pid.isNotBlank()) loadProductDetail(pid) else toast("商品信息不完整")
                    }
                })
            })
        }
    }

    private fun redBadgeBg(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(getColor(R.color.app_badge))
        }
    }

    // ── 对话购物车操作 ────────────────────────────────────────

    private fun tryHandleCartIntent(query: String, skipUserBubble: Boolean = false): Boolean {
        val intent = CartIntentParser.classify(
            query,
            hasProducts = allSessionProducts.isNotEmpty(),
            hasCartItems = CartStore.cartItems().isNotEmpty(),
        )
        if (intent == CartIntentParser.Intent.NONE) return false

        if (!skipUserBubble) addUserBubble(query)
        activeAiColumn = null  // 重置，确保后续 AI 消息在用户气泡下方创建新列
        queryInput.setText("")

        when (intent) {
            CartIntentParser.Intent.ADD_AND_CHECKOUT -> {
                val keyword = CartIntentParser.extractCartKeyword(query)
                val matches = CartIntentParser.matchProducts(keyword, query, lastTurnProductIds, allSessionProducts)
                when {
                    matches.isEmpty() -> fallbackToBackend(query)
                    matches.size == 1 -> handleCartSingleMatchThenCheckout(matches[0], query)
                    else -> handleCartAmbiguous(matches, query)
                }
            }
            CartIntentParser.Intent.CHECKOUT -> {
                hideKeyboard()
                if (CartStore.cartItems().isEmpty()) {
                    showLocalAiMessage("购物车还是空的哦，先挑几件心仪的商品加入购物车吧。")
                } else {
                    showLocalAiMessage("好的，正在为你打开结算页\u2026")
                    checkoutScreen.postDelayed({ showCheckoutScreen(fromCart = false) }, 2000)
                }
            }
            CartIntentParser.Intent.CLEAR_CART -> {
                CartStore.clearCart()
                showLocalAiMessage("\u2705  购物车已清空\n\n还想继续逛逛吗？告诉我你的需求，我帮你挑。")
            }
            CartIntentParser.Intent.VIEW_CART -> {
                val items = CartStore.cartItems()
                if (items.isEmpty()) {
                    showLocalAiMessage("购物车还是空的哦，先去逛逛吧~")
                } else {
                    val sb = StringBuilder("购物车里有 ${items.size} 件商品：\n\n")
                    items.forEachIndexed { i, item ->
                        val name = item.optString("short_name").ifBlank { item.optString("name") }
                        val price = item.optString("price", "")
                        val qty = item.optInt("qty", 1)
                        val specs = item.optString("selected_specs", "")
                        sb.append("${i + 1}. **$name**")
                        if (specs.isNotBlank()) {
                            runCatching {
                                val specObj = org.json.JSONObject(specs)
                                val specParts = specObj.keys().asSequence().map { k -> "${specObj.optString(k)}" }.toList()
                                if (specParts.isNotEmpty()) sb.append("（${specParts.joinToString("/")}）")
                            }
                        }
                        sb.append("  ${price} ×$qty\n")
                    }
                    sb.append("\n合计：¥${CartStore.cartTotal()}")
                    showLocalAiRichMessage(sb.toString())
                }
            }
            CartIntentParser.Intent.REMOVE -> handleCartRemove(query)
            CartIntentParser.Intent.CHANGE_QTY -> handleCartChangeQty(query)
            CartIntentParser.Intent.ADD -> {
                val isBatch = CartIntentParser.batchAllPattern.containsMatchIn(query)
                val hasMultiOrd = CartIntentParser.ordinalPattern.findAll(query).count() > 1
                val keyword = if (isBatch && !hasMultiOrd) "" else CartIntentParser.extractCartKeyword(query)
                val matches = CartIntentParser.matchProducts(keyword, query, lastTurnProductIds, allSessionProducts)
                val statedCount = CartIntentParser.batchCountPattern.find(query)
                    ?.let { CartIntentParser.parseChineseOrdinal(it.groupValues[1]) }

                when {
                    (isBatch || hasMultiOrd) && matches.size > 1 -> {
                        val isTail = CartIntentParser.batchTailPattern.containsMatchIn(query)
                        if (statedCount != null && statedCount < matches.size) {
                            // "前N款" → 取前 N 个，"后N款" → 取后 N 个
                            val selected = if (isTail) matches.takeLast(statedCount) else matches.take(statedCount)
                            handleCartBatchAdd(selected, query)
                        } else if (statedCount != null && statedCount > matches.size) {
                            showLocalAiMessage("你说的是 ${statedCount} 款，但目前只有 ${matches.size} 款商品，全部帮你加购：")
                            handleCartBatchAdd(matches, query)
                        } else {
                            handleCartBatchAdd(matches, query)
                        }
                    }
                    matches.size == 1 -> handleCartSingleMatch(matches[0], query)
                    matches.size > 1 -> handleCartAmbiguous(matches, query)
                    else -> fallbackToBackend(query)
                }
            }
            else -> {}
        }
        return true
    }

    private fun handleCartBatchAdd(products: List<JSONObject>, userQuery: String) {
        val needSpec = products.filter { p ->
            val skus = p.optJSONArray("skus"); skus != null && skus.length() > 1
        }
        val noSpec = products.filter { p ->
            val skus = p.optJSONArray("skus"); skus == null || skus.length() <= 1
        }
        // 无需选规格的直接加购
        val addedNames = mutableListOf<String>()
        for (p in noSpec) {
            val name = p.optString("short_name").ifBlank { p.optString("name") }
            CartStore.addToCart(p)
            addedNames.add(name)
        }
        if (addedNames.isNotEmpty() && needSpec.isEmpty()) {
            val listing = addedNames.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n")
            val msg = "\u2705  已将 ${addedNames.size} 款商品全部加入购物车：\n\n$listing\n\n可以随时说\"下单\"去结算哦。"
            showLocalAiMessage(msg)
            appendLocalCartExchange(userQuery, "已批量加入购物车 ${addedNames.size} 款商品。")
            return
        }
        // 有需要选规格的商品 → 先汇报已直接加购的，再逐个选规格
        if (addedNames.isNotEmpty()) {
            val listing = addedNames.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n")
            showLocalAiMessage("\u2705  已直接加购 ${addedNames.size} 款（无需选规格）：\n\n$listing")
        }
        showLocalAiMessage("还有 ${needSpec.size} 款商品需要选择规格，逐个为你确认：")
        // 放入队列，启动第一个
        pendingSkuProducts.clear()
        needSpec.forEach { pendingSkuProducts.addLast(it) }
        startNextBatchSkuSelection(userQuery)
    }

    /** 从待选规格队列中取出下一个商品，开始规格选择流程 */
    private fun startNextBatchSkuSelection(userQuery: String) {
        if (pendingSkuProducts.isEmpty()) {
            showLocalAiMessage("\u2705  所有商品规格已选完，全部加入购物车！可以随时说\"下单\"去结算哦。")
            appendLocalCartExchange(userQuery, "已批量加购所有商品。")
            return
        }
        val product = pendingSkuProducts.removeFirst()
        startSkuSelectionFlow(product, userQuery, checkoutAfter = false, batchMode = true)
    }

    private fun handleCartSingleMatch(product: JSONObject, userQuery: String) {
        val name = product.optString("short_name").ifBlank { product.optString("name") }

        // 有多 SKU 时在对话内选规格
        val skus = product.optJSONArray("skus")
        if (skus != null && skus.length() > 1) {
            startSkuSelectionFlow(product, userQuery, checkoutAfter = false)
            return
        }

        // 解析加购数量："加2个小棕瓶"/"加购两个" → qty=2
        val addQty = Regex("([一二两三四五六七八九十\\d]+)\\s*[个份件款]").find(userQuery)
            ?.let { CartIntentParser.parseChineseOrdinal(it.groupValues[1]) } ?: 1
        val qty = addQty.coerceIn(1, 99)

        CartStore.addToCart(product)
        if (qty > 1) {
            val id = product.optString("id")
            CartStore.setQty(id, qty)
        }

        val qtyDesc = if (qty > 1) " ×$qty" else ""
        val hook = CartIntentParser.cartFollowUpHook(product)
        val msg = "\u2705  已成功将「$name」$qtyDesc 加入购物车\n\n$hook"
        showLocalAiMessage(msg)
        appendLocalCartExchange(userQuery, "已将${name}${qtyDesc}加入购物车。$hook")
    }

    /** 复合意图：加购 → 确认气泡 → 自动跳转下单页 */
    private fun handleCartSingleMatchThenCheckout(product: JSONObject, userQuery: String) {
        val name = product.optString("short_name").ifBlank { product.optString("name") }

        // 有多 SKU 时在对话内选规格
        val skus = product.optJSONArray("skus")
        if (skus != null && skus.length() > 1) {
            startSkuSelectionFlow(product, userQuery, checkoutAfter = true)
            return
        }

        hideKeyboard()
        CartStore.addToCart(product)

        val msg = "\u2705  已成功将「$name」加入购物车，马上为你打开结算页\u2026"
        showLocalAiMessage(msg)
        appendLocalCartExchange(userQuery, "已将${name}加入购物车并跳转结算。")

        // 延迟后自动打开结算页，让用户看到确认气泡
        checkoutScreen.postDelayed({ showCheckoutScreen(fromCart = false) }, 2000)
    }

    /**
     * 对话内 SKU 选规格流程：逐个维度出 chip，选完自动加购。
     * checkoutAfter=true 时加购完自动跳结算页。
     * batchMode=true 时加购完继续处理下一个待选规格的商品。
     */
    private fun startSkuSelectionFlow(product: JSONObject, userQuery: String, checkoutAfter: Boolean, batchMode: Boolean = false) {
        val skus = product.optJSONArray("skus") ?: return
        val name = product.optString("short_name").ifBlank { product.optString("name") }

        // 提取所有规格维度和可选值
        val dimensions = LinkedHashMap<String, LinkedHashSet<String>>()
        for (i in 0 until skus.length()) {
            val sku = skus.optJSONObject(i) ?: continue
            val props = sku.optJSONObject("properties") ?: continue
            val keys = props.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = props.optString(key, "")
                if (value.isNotBlank()) {
                    dimensions.getOrPut(key) { LinkedHashSet() }.add(value)
                }
            }
        }
        if (dimensions.isEmpty()) return

        val dimList = dimensions.entries.toList()
        val chosen = mutableMapOf<String, String>()
        val column = getAiColumn()
        currentAssistantColumn = column

        // 递归地展示下一个维度的选项
        fun showNextDimension(dimIndex: Int) {
            if (dimIndex >= dimList.size) {
                // 所有维度都选完了，加购
                val specObj = JSONObject()
                chosen.forEach { (k, v) -> specObj.put(k, v) }
                // 找匹配的 SKU 价格
                var matchedPrice = ""
                for (i in 0 until skus.length()) {
                    val sku = skus.optJSONObject(i) ?: continue
                    val props = sku.optJSONObject("properties") ?: continue
                    val allMatch = chosen.all { (k, v) -> props.optString(k) == v }
                    if (allMatch) {
                        matchedPrice = "¥${sku.optDouble("price", 0.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else String.format("%.2f", it) }}"
                        break
                    }
                }
                val cartProduct = JSONObject(product.toString()).apply {
                    put("selected_specs", specObj.toString())
                    if (matchedPrice.isNotBlank()) put("price", matchedPrice)
                }
                CartStore.addToCart(cartProduct)
                val specsDesc = chosen.values.joinToString(" / ")
                if (batchMode) {
                    // 批量模式：简短确认，继续下一个商品
                    column.addView(newTextBubble().apply {
                        text = "\u2705  已将「$name」($specsDesc) 加入购物车"
                    })
                    scrollToBottom()
                    // 延迟一下再开始下一个，让用户看到确认
                    column.postDelayed({ startNextBatchSkuSelection(userQuery) }, 500)
                } else {
                    val hook = CartIntentParser.cartFollowUpHook(product)
                    column.addView(newTextBubble().apply {
                        text = "\u2705  已将「$name」($specsDesc) 加入购物车\n\n$hook"
                    })
                    appendLocalCartExchange(userQuery, "已将${name}($specsDesc)加入购物车。")
                    scrollToBottom()
                    if (checkoutAfter) {
                        checkoutScreen.postDelayed({ showCheckoutScreen(fromCart = false) }, 2000)
                    }
                }
                return
            }

            val (dimName, values) = dimList[dimIndex]
            // 只有一个选项 → 自动选中，跳到下一维度
            if (values.size == 1) {
                chosen[dimName] = values.first()
                showNextDimension(dimIndex + 1)
                return
            }
            // 显示问题气泡
            column.addView(newTextBubble().apply {
                text = "请选择「$name」的$dimName："
            })

            // Chip 容器
            val chipLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4); bottomMargin = dp(8) }
            }

            // 用 FlowLayout 包裹 chips
            val flowContainer = object : android.widget.FrameLayout(this@MainActivity) {
                override fun onMeasure(wSpec: Int, hSpec: Int) {
                    val maxW = MeasureSpec.getSize(wSpec)
                    var x = 0; var y = 0; var rowH = 0
                    for (i in 0 until childCount) {
                        val child = getChildAt(i)
                        child.measure(
                            MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        )
                        if (x + child.measuredWidth > maxW) { x = 0; y += rowH + dp(6); rowH = 0 }
                        rowH = maxOf(rowH, child.measuredHeight)
                        x += child.measuredWidth + dp(8)
                    }
                    setMeasuredDimension(maxW, y + rowH)
                }
                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    val maxW = r - l
                    var x = 0; var y = 0; var rowH = 0
                    for (i in 0 until childCount) {
                        val child = getChildAt(i)
                        if (x + child.measuredWidth > maxW) { x = 0; y += rowH + dp(6); rowH = 0 }
                        child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                        rowH = maxOf(rowH, child.measuredHeight)
                        x += child.measuredWidth + dp(8)
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    bubbleMaxWidth(),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4); bottomMargin = dp(8) }
            }

            for (value in values) {
                val chip = TextView(this@MainActivity).apply {
                    text = value
                    textSize = 13f
                    setTextColor(getColor(R.color.app_primary))
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    isClickable = true
                    setOnClickListener {
                        chosen[dimName] = value
                        // 置灰所有 chip，高亮选中项
                        for (i in 0 until flowContainer.childCount) {
                            val c = flowContainer.getChildAt(i)
                            c.isEnabled = false
                            if (c == this) {
                                c.setBackgroundResource(R.drawable.bg_card_button)
                                (c as TextView).setTextColor(0xFFFFFFFF.toInt())
                                c.alpha = 1f
                            } else {
                                c.alpha = 0.4f
                            }
                        }
                        // 展示下一个维度
                        showNextDimension(dimIndex + 1)
                    }
                }
                flowContainer.addView(chip)
            }

            column.addView(flowContainer)
            scrollToBottom()
        }

        // 从第一个维度开始
        showNextDimension(0)
    }

    private fun handleCartAmbiguous(products: List<JSONObject>, userQuery: String) {
        val column = getAiColumn()
        currentAssistantColumn = column

        val bubble = newTextBubble()
        bubble.text = "你想加购的是哪一款？"
        column.addView(bubble)

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                bubbleMaxWidth(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
        }

        products.take(5).forEach { product ->
            val name = product.optString("short_name").ifBlank { product.optString("name") }
            val price = product.optString("price", "")
            val displayText = if (price.isNotBlank()) "$name  $price" else name

            val option = TextView(this).apply {
                text = displayText
                textSize = 13f
                setTextColor(getColor(R.color.app_primary))
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(6)
                }
                setOnClickListener { v ->
                    // 有多 SKU 时在对话内选规格
                    val skus = product.optJSONArray("skus")
                    if (skus != null && skus.length() > 1) {
                        startSkuSelectionFlow(product, userQuery, checkoutAfter = false)
                        return@setOnClickListener
                    }
                    CartStore.addToCart(product)
                    // 置灰其余选项
                    for (i in 0 until listLayout.childCount) {
                        val child = listLayout.getChildAt(i)
                        child.isEnabled = false
                        child.alpha = 0.4f
                    }
                    v.alpha = 1.0f
                    // 确认气泡（合并确认+钩子）
                    val hook = CartIntentParser.cartFollowUpHook(product)
                    column.addView(newTextBubble().apply {
                        text = "\u2705  已成功将「$name」加入购物车\n\n$hook"
                    })
                    appendLocalCartExchange(userQuery, "已将${name}加入购物车。$hook")
                    scrollToBottom()
                }
            }
            listLayout.addView(option)
        }

        column.addView(listLayout)
        activeTextBubble = null
        activeAiColumn = null
        scrollToBottom()
    }

    /** 购物车意图匹配失败 → fallback 到后端当正常查询处理（用户气泡已添加） */
    private fun fallbackToBackend(query: String) {
        skipCartIntentOnce = true
        startChat(query, skipUserBubble = true)
    }

    private fun handleCartRemove(query: String) {
        val items = CartStore.cartItems()
        if (items.isEmpty()) {
            showLocalAiMessage("购物车已经是空的了哦。")
            return
        }
        val keyword = CartIntentParser.extractCartOperationKeyword(query)
        val matches = CartIntentParser.matchCartProducts(keyword, query, items)

        when {
            matches.size == 1 -> {
                val item = matches[0]
                val name = item.optString("short_name").ifBlank { item.optString("name") }
                CartStore.removeFromCart(item.optString("id"))
                showLocalAiMessage("\u2705  已将「$name」从购物车移除\n\n${CartIntentParser.cartFollowUpHook(item)}")
                appendLocalCartExchange(query, "已将${name}从购物车移除")
            }
            matches.size > 1 -> {
                val column = getAiColumn()
                currentAssistantColumn = column
                column.addView(newTextBubble().apply { text = "你想删除哪一个？" })
                val listLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(bubbleMaxWidth(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4); bottomMargin = dp(8)
                    }
                }
                matches.take(5).forEach { item ->
                    val name = item.optString("short_name").ifBlank { item.optString("name") }
                    listLayout.addView(TextView(this).apply {
                        text = name
                        textSize = 13f; setTextColor(getColor(R.color.app_primary))
                        setBackgroundResource(R.drawable.bg_chip)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                        setOnClickListener { v ->
                            CartStore.removeFromCart(item.optString("id"))
                            for (i in 0 until listLayout.childCount) { listLayout.getChildAt(i).isEnabled = false; listLayout.getChildAt(i).alpha = 0.4f }
                            v.alpha = 1.0f
                            column.addView(newTextBubble().apply { this.text = "\u2705  已将「$name」从购物车移除" })
                            appendLocalCartExchange(query, "已将${name}从购物车移除")
                            scrollToBottom()
                        }
                    })
                }
                column.addView(listLayout)
                activeTextBubble = null; activeAiColumn = null; scrollToBottom()
            }
            else -> {
                val cartNames = items.take(5).joinToString("、") { it.optString("short_name").ifBlank { it.optString("name") } }
                showLocalAiMessage("购物车里没有这个商品哦~\n\n当前购物车有：$cartNames\n\n你可以先搜索想要的商品加入购物车。")
            }
        }
    }

    private fun handleCartChangeQty(query: String) {
        val qtyResult = CartIntentParser.extractTargetQtyEx(query)
        if (qtyResult == null) {
            showLocalAiMessage("没听清你想改成几个，可以再说一次吗？")
            return
        }
        val keyword = CartIntentParser.extractCartOperationKeyword(query)
        val matches = CartIntentParser.matchCartProducts(keyword, query, CartStore.cartItems())

        fun applyQtyChange(item: JSONObject): Pair<Int, String> {
            val name = item.optString("short_name").ifBlank { item.optString("name") }
            val currentQty = item.optInt("qty", 1)
            val finalQty = if (qtyResult.isRelative) (currentQty + qtyResult.qty).coerceAtLeast(0) else qtyResult.qty
            if (finalQty <= 0) {
                CartStore.removeFromCart(item.optString("id"))
                return Pair(0, "\u2705  已将「$name」从购物车移除")
            } else {
                CartStore.setQty(item.optString("id"), finalQty)
                return Pair(finalQty, "\u2705  已将「$name」的数量改为 $finalQty 件")
            }
        }

        when {
            matches.size == 1 -> {
                val (_, msg) = applyQtyChange(matches[0])
                showLocalAiMessage(msg)
                appendLocalCartExchange(query, msg)
            }
            matches.size > 1 -> {
                val column = getAiColumn()
                currentAssistantColumn = column
                column.addView(newTextBubble().apply { text = "你想修改哪个商品的数量？" })
                val listLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(bubbleMaxWidth(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4); bottomMargin = dp(8)
                    }
                }
                matches.take(5).forEach { item ->
                    val name = item.optString("short_name").ifBlank { item.optString("name") }
                    listLayout.addView(TextView(this).apply {
                        text = name
                        textSize = 13f; setTextColor(getColor(R.color.app_primary))
                        setBackgroundResource(R.drawable.bg_chip)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                        setOnClickListener { v ->
                            val (_, msg) = applyQtyChange(item)
                            for (i in 0 until listLayout.childCount) { listLayout.getChildAt(i).isEnabled = false; listLayout.getChildAt(i).alpha = 0.4f }
                            v.alpha = 1.0f
                            column.addView(newTextBubble().apply { this.text = msg })
                            appendLocalCartExchange(query, msg)
                            scrollToBottom()
                        }
                    })
                }
                column.addView(listLayout)
                activeTextBubble = null; activeAiColumn = null; scrollToBottom()
            }
            else -> {
                val cartItems = CartStore.cartItems()
                val cartNames = cartItems.take(5).joinToString("、") { it.optString("short_name").ifBlank { it.optString("name") } }
                showLocalAiMessage("购物车里没有这个商品哦~\n\n当前购物车有：$cartNames\n\n你可以告诉我要修改哪个商品的数量。")
            }
        }
    }

    private fun showLocalAiMessage(text: String) {
        val column = getAiColumn()
        currentAssistantColumn = column
        val bubble = newTextBubble()
        bubble.text = text
        column.addView(bubble)
        activeTextBubble = null
        activeAiColumn = null
        scrollToBottom()
    }

    private fun showLocalAiRichMessage(md: String) {
        val column = getAiColumn()
        currentAssistantColumn = column
        val bubble = newTextBubble()
        bubble.text = mdRenderer.render(md)
        column.addView(bubble)
        activeTextBubble = null
        activeAiColumn = null
        scrollToBottom()
    }

    private fun appendLocalCartExchange(userQuery: String, assistantText: String) {
        chatHistory.add(JSONObject().put("role", "user").put("content", userQuery))
        chatHistory.add(JSONObject().put("role", "assistant").put("content", assistantText))
        while (chatHistory.size > 8) chatHistory.removeAt(0)
    }

    // ── 对话加购 END ────────────────────────────────────────

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun cartProductFromDetail(detail: JSONObject): JSONObject {
        val priceStr = detail.optString("price_range").takeIf { it.isNotBlank() }
            ?: ("¥" + formatPriceValue(detail.optDouble("price", 0.0)))
        val attrs = detail.optJSONObject("attributes")
        val spec = attrs?.optString("子类目")?.takeIf { it.isNotBlank() }
            ?: detail.optString("sub_category").takeIf { it.isNotBlank() }
            ?: detail.optString("category", "")
        return JSONObject()
            .put("id", detail.optString("id"))
            .put("name", detail.optString("name"))
            .put("short_name", detail.optString("name"))
            .put("price", priceStr)
            .put("image_url", detail.optString("image_url"))
            .put("sub_category", spec)
    }

    private fun formatPriceValue(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.CHINA, "%.2f", v)

    private fun scrollToBottom(force: Boolean = false) {
        // 双重 post 确保在新 View 布局完成后再滚动
        chatScrollView.post { chatScrollView.post {
            if (force) {
                userScrolledUp = false
                chatScrollView.fullScroll(ScrollView.FOCUS_DOWN)
                return@post
            }
            // 用户手动上滑时不自动滚动
            if (userScrolledUp) return@post
            val scrollY = chatScrollView.scrollY
            val viewportH = chatScrollView.height
            val contentH = chatScrollView.getChildAt(0)?.height ?: 0
            val distanceFromBottom = contentH - scrollY - viewportH
            if (distanceFromBottom <= dp(200)) {
                chatScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        } }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
