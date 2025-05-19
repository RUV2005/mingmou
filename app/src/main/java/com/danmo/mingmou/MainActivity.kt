package com.danmo.mingmou

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.graphics.drawable.AnimationDrawable
import android.view.View
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 主活动类，负责相机预览、拍照、图像识别和语音播报等功能。
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    // 定义拍照模式枚举
    private enum class CaptureMode {
        AUTO, // 自动拍照模式
        MANUAL // 手动拍照模式
    }

    // 定义语音状态枚举
    private enum class SpeechStatus {
        UPLOAD_START,    // 开始上传图片
        PROCESSING,      // 正在识别
        SUCCESS,         // 识别成功
        FAILURE,         // 识别失败
        NAVIGATING,      // 正在跳转
        OFFLINE_MODE,    // 离线模式
        SERVICE_EXPIRED, // 在线服务到期
        STREAM_MODE,     // 视频流模式
        CAMERA_MODE      // 摄像头模式
    }

    // 全局变量
    private var currentMode = CaptureMode.MANUAL // 当前拍照模式
    private lateinit var modeButton: ImageView // 模式切换按钮
    private var isFlashOn = false // 闪光灯状态
    private lateinit var flashButton: ImageView // 闪光灯按钮
    private lateinit var cameraExecutor: ExecutorService // 相机执行器
    private lateinit var previewView: PreviewView // 预览视图
    private var imageCapture: ImageCapture? = null // 图像捕获对象
    private var cameraControl: CameraControl? = null // 相机控制对象
    private var hasNavigated = false // 是否已跳转
    private var lastCaptureTime = 0L // 上一次拍照时间
    private val captureCooldown = 2000L // 拍照冷却时间（毫秒）
    private var isProcessing = false // 是否正在处理识别
    private val SERVICE_EXPIRY_DATE = Calendar.getInstance().apply {
        set(2025, Calendar.JULY, 24) // 在线服务到期时间
    }.timeInMillis
    private val client = OkHttpClient()
    private var isStreaming = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var boundary: String
    private val TAG = "MjpegStream"
    private var leftoverData = ByteArray(0)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var streamingJob: Job? = null

    private enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }
    private var connectionState = ConnectionState.DISCONNECTED
    private var connectionStartTime = 0L
    private var timerJob: Job? = null

    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private var isCameraMode = true // true 表示摄像头模式，false 表示视频流模式
    private lateinit var streamView: ImageView
    private var isCaptureRequested = false // 是否需要处理图像帧

    // API和外置设备 配置
    companion object {
        private const val API_KEY = "1d68a7b7f999dbdd55c2de07204f982e" // API 密钥
        private const val API_SECRET = "YzVlMjcxMGNhMWQ5YzExMTBlOGY0OTdj" // API 密钥
        private const val APP_ID = "0a6d43e9" // 应用 ID
        const val RECONNECT_DELAY = 5000L
        const val MAX_RETRIES = 5
        const val STREAM_URL = "http://192.168.4.1:81/stream" // 视频流地址
    }

    // 语音播报相关变量
    private lateinit var tts: TextToSpeech // 文字转语音对象
    private var isTTSInitialized = false // TTS 是否初始化完成
    private var isTTSBound = false // TTS 是否绑定完成
    private val ttsQueue = LinkedList<SpeechStatus>() // 语音队列
    private var paragraphs = mutableListOf<String>() // 识别结果段落列表
    private var isOfflineModeReported = false // 是否已报告离线模式
    private var isSpeechEnabled = true // 是否启用语音播报
    private val speechStatusSharedPreferences by lazy {
        getSharedPreferences(
            "SpeechStatusPrefs",
            Context.MODE_PRIVATE
        )
    }
    private val preferences by lazy { getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) }

    /**
     * 活动创建时调用，初始化界面和相机。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化视图
        streamView = findViewById(R.id.stream_view)

        setupImmersiveMode()

        // 初始化状态指示组件
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        timerText = findViewById(R.id.timerText)

        // 初始化语音播报状态
        isSpeechEnabled = speechStatusSharedPreferences.getBoolean("isSpeechEnabled", true)

        // 初始化语音播报切换按钮
        val toggleSpeechButton = findViewById<ImageView>(R.id.toggle_speech_button)
        toggleSpeechButton.setOnClickListener { toggleSpeech() }
        updateToggleSpeechButtonIcon()

        // 初始化 TTS
        tts = TextToSpeech(this, this)

        // 初始化闪光灯按钮
        flashButton = findViewById(R.id.flash_button)
        flashButton.setOnClickListener { toggleFlash() }

        // 初始化模式切换按钮
        modeButton = findViewById(R.id.mode_button)
        modeButton.setOnClickListener { toggleCaptureMode() }

        // 初始化摄像头切换按钮
        val cameraSwitchButton = findViewById<ImageView>(R.id.camera_switch_button)
        cameraSwitchButton.setOnClickListener { switchCameraMode() }
        // 初始化相机预览视图
        previewView = findViewById(R.id.preview_view)
        val captureButton: ImageView = findViewById(R.id.capture_button)

        // 初始化相机执行器
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 检查并请求权限
        checkAndRequestPermissions()

        // 设置拍照按钮点击事件
        captureButton.setOnClickListener {
            if (isCameraMode) {
                // 如果是摄像头模式，正常调用拍照逻辑
                takePhoto()
            } else {
                // 如果是视频流模式，设置标志变量为 true
                isCaptureRequested = true
            }
        }

        // 添加对焦功能
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                val meteringPoint = previewView.meteringPointFactory.createPoint(x, y)
                val focusMeteringAction = FocusMeteringAction.Builder(meteringPoint).build()
                cameraControl?.startFocusAndMetering(focusMeteringAction)
                true
            } else {
                false
            }
        }
        captureButton.setOnClickListener {
            if (isCameraMode) {
                // 如果是摄像头模式，正常调用拍照逻辑
                takePhoto()
            } else {
                // 如果是视频流模式，设置标志变量为 true
                isCaptureRequested = true
            }
        }

        // 加载保存的识别模式
        currentMode = loadCaptureMode()
        updateModeUI()
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // 修改后的switchCameraMode方法
    private fun switchCameraMode() {
        runOnUiThread {
            if (isCameraMode) {
                // 切换到视频流模式
                previewView.visibility = View.GONE
                streamView.visibility = View.VISIBLE
                stopCamera()
                startStream()
                isCameraMode = false
                addSpeechToQueue(SpeechStatus.STREAM_MODE)
            } else {
                // 切换到摄像头模式
                previewView.visibility = View.VISIBLE
                streamView.visibility = View.GONE
                stopStream()
                startCamera()
                isCameraMode = true
                addSpeechToQueue(SpeechStatus.CAMERA_MODE)
            }
        }
    }

    // 停止摄像头预览
    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll() // 确保解除所有绑定
        }, ContextCompat.getMainExecutor(this))

        cameraExecutor.shutdownNow() // 立即终止线程池
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    // 停止视频流
    private fun stopStream() {
        isStreaming = false
        streamingJob?.cancel()
        handler.post {
            streamView.setImageBitmap(null) // 清除残留图像
        }
        updateConnectionState(ConnectionState.DISCONNECTED)

        // 清理 OkHttpClient 的连接
        client.dispatcher.executorService.shutdown()
        try {
            client.dispatcher.executorService.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e("Stream", "Failed to shutdown OkHttpClient dispatcher", e)
        }
    }

    /**
     * 检查网络是否可用。
     * @return 网络是否可用
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        return networkCapabilities != null &&
                (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    /**
     * TTS 初始化回调。
     * @param status 初始化状态
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTTSInitialized = true
            when (tts.setLanguage(Locale.CHINESE)) {
                TextToSpeech.LANG_MISSING_DATA,
                TextToSpeech.LANG_NOT_SUPPORTED -> {
                    Toast.makeText(this, "不支持中文语音", Toast.LENGTH_SHORT).show()
                }

                else -> {
                    isTTSBound = true
                    processSpeechQueue() // 初始化完成后处理队列
                }
            }
        }
    }

    /**
     * 切换拍照模式。
     */
    private fun toggleCaptureMode() {
        currentMode = when (currentMode) {
            CaptureMode.AUTO -> CaptureMode.MANUAL
            CaptureMode.MANUAL -> CaptureMode.AUTO // 添加缺失的分支
        }
        updateModeUI()
        setupAnalysisUseCase()

        val modeName = when (currentMode) {
            CaptureMode.AUTO -> "自动"
            CaptureMode.MANUAL -> "手动"
        }

        if (isSpeechEnabled) {
            synchronized(ttsQueue) {
                ttsQueue.clear()
            }
            speakWithCallback("当前模式已切换为${modeName}模式") {
                processSpeechQueue()
            }
        }

        saveCaptureMode(currentMode)
        Toast.makeText(this, "当前模式: ${currentMode.name}", Toast.LENGTH_SHORT).show()
    }

    /**
     * 更新模式切换按钮的图标。
     */
    private fun updateModeUI() {
        val iconRes = when (currentMode) {
            CaptureMode.AUTO -> R.drawable.ic_mode_auto
            CaptureMode.MANUAL -> R.drawable.ic_mode_manual
        }
        modeButton.setImageResource(iconRes)
    }

    /**
     * 检查并请求相机权限。
     */
    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA
                )
            )
        } else {
            startCamera()
        }
    }

    /**
     * 权限请求回调。
     */
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val cameraPermissionGranted = permissions[Manifest.permission.CAMERA] ?: false

            if (cameraPermissionGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }

    /**
     * 切换闪光灯状态。
     */
    private fun toggleFlash() {
        if (!isCameraMode) {
            Toast.makeText(this, "视频流模式下不可用", Toast.LENGTH_SHORT).show()
            return
        }

        isFlashOn = !isFlashOn
        try {
            cameraControl?.enableTorch(isFlashOn)
            updateFlashState()
        } catch (e: Exception) {
            Log.e("Flash", "闪光灯控制失败", e)
            Toast.makeText(this, "闪光灯开启失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 更新闪光灯按钮的图标。
     */
    private fun updateFlashState() {
        val iconRes = if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        flashButton.setImageResource(iconRes)
    }

    /**
     * 启动相机。
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll() // 解绑所有用例

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                cameraControl = camera.cameraControl

                // 设置自动对焦
                val meteringPointFactory = previewView.meteringPointFactory
                val point = meteringPointFactory.createPoint(0.5f, 0.5f) // 默认对焦在画面中心
                val focusMeteringAction = FocusMeteringAction.Builder(point).build()

                cameraControl?.startFocusAndMetering(focusMeteringAction)
            } catch (ex: Exception) {
                Log.e("Camera", "Binding failed", ex)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 保存当前拍照模式。
     * @param mode 拍照模式
     */
    private fun saveCaptureMode(mode: CaptureMode) {
        val editor = preferences.edit()
        editor.putString("capture_mode", mode.name)
        editor.apply()
    }

    /**
     * 加载保存的拍照模式。
     * @return 拍照模式
     */
    private fun loadCaptureMode(): CaptureMode {
        val modeName = preferences.getString("capture_mode", CaptureMode.MANUAL.name)
        return CaptureMode.valueOf(modeName!!)
    }

    /**
     * 设置图像分析用例。
     */
    private fun setupAnalysisUseCase() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            if (currentMode == CaptureMode.AUTO) {
                val analysisUseCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysisUseCase.setAnalyzer(
                    cameraExecutor,
                    AutoCaptureAnalyzer { hasText, _ ->
                        runOnUiThread {
                            if (hasText) autoCapture()
                        }
                    }
                )

                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, analysisUseCase
                )
            } else {
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 自动拍照逻辑。
     */
    private fun autoCapture() {
        if (currentMode != CaptureMode.AUTO) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureTime < captureCooldown) {
            return
        }

        lastCaptureTime = currentTime

        val captureButton = findViewById<ImageView>(R.id.capture_button)
        captureButton.isEnabled = false
        takePhoto()
        Handler(Looper.getMainLooper()).postDelayed({
            captureButton.isEnabled = true
        }, captureCooldown)
    }

    /**
     * 拍照。
     */
    private fun takePhoto() {
        if (isProcessing) {
            Log.d("Camera", "识别正在进行，暂停拍照")
            return
        }

        if (!isNetworkAvailable()) {
            addSpeechToQueue(SpeechStatus.OFFLINE_MODE)
        }

        val imageCapture = imageCapture ?: return

        imageCapture.flashMode = if (isFlashOn) ImageCapture.FLASH_MODE_ON
        else ImageCapture.FLASH_MODE_OFF

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.use { it.toBitmap() } // 使用 use 确保资源释放
                    image.close()

                    isProcessing = true
                    processImage(bitmap)

                    findViewById<ImageView>(R.id.capture_button).isEnabled = true
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("Camera", "Photo capture failed: ${exception.message}")
                    findViewById<ImageView>(R.id.capture_button).isEnabled = true
                }
            }
        )
    }

    /**
     * 处理图像。
     * @param image 图像数据（可以是 Bitmap 或 ByteArray）
     */
    private fun processImage(image: Any) {
        val bitmap: Bitmap? = when (image) {
            is Bitmap -> image
            is ByteArray -> BitmapFactory.decodeByteArray(image, 0, image.size)
            else -> null
        }

        if (bitmap == null) {
            Log.e(TAG, "Invalid image data")
            addSpeechToQueue(SpeechStatus.FAILURE)
            return
        }

        Thread {
            try {
                if (isServiceExpired()) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "在线服务已到期，已切换至离线模式，请等待开发者更新在线服务",
                            Toast.LENGTH_LONG
                        ).show()
                        addSpeechToQueue(SpeechStatus.SERVICE_EXPIRED)
                    }
                    processImageOffline(bitmap)
                    return@Thread
                }

                if (isNetworkAvailable()) {
                    processImageOnline(bitmap)
                } else {
                    if (!isOfflineModeReported) {
                        runOnUiThread {
                            addSpeechToQueue(SpeechStatus.OFFLINE_MODE)
                            isOfflineModeReported = true
                        }
                    }
                    processImageOffline(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                runOnUiThread {
                    addSpeechToQueue(SpeechStatus.FAILURE)
                }
            } finally {
                isProcessing = false
                isOfflineModeReported = false
            }
        }.start()
    }

    /**
     * 检查服务是否到期。
     * @return 是否到期
     */
    private fun isServiceExpired(): Boolean {
        return System.currentTimeMillis() >= SERVICE_EXPIRY_DATE
    }

    /**
     * 离线模式下处理图像。
     * @param bitmap 图像位图
     */
    private fun processImageOffline(bitmap: Bitmap) {
        addSpeechToQueue(SpeechStatus.PROCESSING)

        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                paragraphs.clear()
                val currentParagraph = StringBuilder()

                visionText.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        val lineText = line.text
                        currentParagraph.append(lineText).append(" ")

                        if (lineText.endsWith("。") || lineText.endsWith("!") || lineText.endsWith("?")) {
                            paragraphs.add(currentParagraph.toString().trim())
                            currentParagraph.clear()
                        }
                    }
                }

                if (currentParagraph.isNotEmpty()) {
                    paragraphs.add(currentParagraph.toString().trim())
                }

                runOnUiThread {
                    addSpeechToQueue(SpeechStatus.SUCCESS)
                    addSpeechToQueue(SpeechStatus.NAVIGATING)
                }
            }
            .addOnFailureListener {
                runOnUiThread {
                    addSpeechToQueue(SpeechStatus.FAILURE)
                }
            }
    }



    private fun captureFromStream() {
        if (!isStreaming) {
            Toast.makeText(this, "视频流未启动", Toast.LENGTH_SHORT).show()
            return
        }

        // 获取当前视频流的图片
        val bitmap = streamView.drawable.toBitmap()
        processImageOffline(bitmap)
    }

    /**
     * 在线模式下处理图像。
     * @param bitmap 图像位图
     */
    private fun processImageOnline(bitmap: Bitmap) {
        try {
            if (isServiceExpired()) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "在线服务已到期，已切换至离线模式，请等待开发者更新在线服务",
                        Toast.LENGTH_LONG
                    ).show()
                    addSpeechToQueue(SpeechStatus.SERVICE_EXPIRED)
                }
                processImageOffline(bitmap)
                return
            }

            addSpeechToQueue(SpeechStatus.UPLOAD_START)

            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
            val imageBase64 =
                Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT)

            addSpeechToQueue(SpeechStatus.PROCESSING)

            val url = buildRequestUrl()
            val jsonBody = buildRequestBody(imageBase64)

            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                addSpeechToQueue(SpeechStatus.SUCCESS)
                parseResponse(responseBody)
            } else {
                addSpeechToQueue(SpeechStatus.FAILURE)
            }
        } catch (e: Exception) {
            addSpeechToQueue(SpeechStatus.FAILURE)
        } finally {
            isProcessing = false
        }
    }

    /**
     * 构建请求 URL。
     * @return 请求 URL
     */
    private fun buildRequestUrl(): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())

        val signature = generateSignature(date)
        val authString =
            "api_key=\"$API_KEY\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.encodeToString(authString.toByteArray(), Base64.NO_WRAP)

        return "https://api.xf-yun.com/v1/private/sf8e6aca1" +
                "?authorization=${URLEncoder.encode(authorization, "UTF-8")}&" +
                "host=api.xf-yun.com&" +
                "date=${URLEncoder.encode(date, "UTF-8")}"
    }

    /**
     * 生成签名。
     * @param date 日期
     * @return 签名
     */
    private fun generateSignature(date: String): String {
        val signatureOrigin = """
            host: api.xf-yun.com
            date: $date
            POST /v1/private/sf8e6aca1 HTTP/1.1
        """.trimIndent()

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(API_SECRET.toByteArray(), "HmacSHA256"))
        val signatureSha = mac.doFinal(signatureOrigin.toByteArray())
        return Base64.encodeToString(signatureSha, Base64.NO_WRAP)
    }

    /**
     * 构建请求体。
     * @param imageBase64 图像 Base64 字符串
     * @return 请求体 JSON 字符串
     */
    private fun buildRequestBody(imageBase64: String): String {
        return JSONObject().apply {
            put("header", JSONObject().apply {
                put("app_id", APP_ID)
                put("status", 3)
            })
            put("parameter", JSONObject().apply {
                put("sf8e6aca1", JSONObject().apply {
                    put("category", "ch_en_public_cloud")
                    put("result", JSONObject().apply {
                        put("encoding", "utf8")
                        put("compress", "raw")
                        put("format", "json")
                    })
                })
            })
            put("payload", JSONObject().apply {
                put("sf8e6aca1_data_1", JSONObject().apply {
                    put("encoding", "jpg")
                    put("status", 3)
                    put("image", imageBase64)
                })
            })
        }.toString()
    }

    /**
     * 解析 API 响应。
     * @param response 响应字符串
     */
    private fun parseResponse(response: String) {
        try {
            val json = JSONObject(response)
            val base64Text = json.getJSONObject("payload")
                .getJSONObject("result")
                .getString("text")

            val decodedBytes = Base64.decode(base64Text, Base64.DEFAULT)
            val decodedText = String(decodedBytes, Charsets.UTF_8)

            val resultJson = JSONObject(decodedText)
            val pages = resultJson.getJSONArray("pages")
            val paragraphEndings = setOf('。', '！', '？', '.', '!', '?', ';', '…')
            paragraphs = mutableListOf()
            val currentParagraph = StringBuilder()

            for (i in 0 until pages.length()) {
                val page = pages.getJSONObject(i)
                val lines = page.getJSONArray("lines")

                for (j in 0 until lines.length()) {
                    val line = lines.getJSONObject(j)
                    val lineContent = buildLineContent(line)

                    currentParagraph.append(lineContent)

                    when {
                        lineContent.endsWithAny(paragraphEndings) -> {
                            paragraphs.add(currentParagraph.toString())
                            currentParagraph.clear()
                        }

                        lineContent.endsWith("-") || lineContent.length < 15 -> {
                            currentParagraph.append(" ")
                        }

                        else -> {
                            currentParagraph.append(" ")
                        }
                    }
                }
            }

            if (currentParagraph.isNotEmpty()) {
                paragraphs.add(currentParagraph.toString().trim())
            }

            if (isSpeechEnabled) {
                addSpeechToQueue(SpeechStatus.NAVIGATING)
            } else {
                triggerNavigation()
            }
        } catch (e: Exception) {
            Log.e("Parse Error", "解析响应失败: ${e.message}")
            if (isSpeechEnabled) {
                addSpeechToQueue(SpeechStatus.FAILURE)
            } else {
                runOnUiThread {
                    Toast.makeText(this, "识别失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 检查字符串是否以指定字符集中的任意字符结尾。
     * @param chars 字符集
     * @return 是否以指定字符结尾
     */
    private fun String.endsWithAny(chars: Set<Char>): Boolean {
        return if (isNotEmpty()) chars.contains(last()) else false
    }

    /**
     * 构建行内容。
     * @param line 行数据
     * @return 行内容字符串
     */
    private fun buildLineContent(line: JSONObject): String {
        return try {
            line.getJSONArray("word_units").let { wordUnits ->
                (0 until wordUnits.length()).joinToString("") { index ->
                    wordUnits.getJSONObject(index).getString("content")
                }
            }
        } catch (e: Exception) {
            line.optString("content", "")
        }
    }

    /**
     * 将语音状态添加到队列。
     * @param status 语音状态
     */
    private fun addSpeechToQueue(status: SpeechStatus) {
        if (!isSpeechEnabled) return
        synchronized(ttsQueue) {
            if (!ttsQueue.contains(status)) {
                ttsQueue.add(status)
            }
        }
        runOnUiThread { processSpeechQueue() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupImmersiveMode()
    }

    /**
     * 更新语音播报切换按钮的图标。
     */
    private fun updateToggleSpeechButtonIcon() {
        val toggleSpeechButton = findViewById<ImageView>(R.id.toggle_speech_button)
        val iconRes = if (isSpeechEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off
        toggleSpeechButton.setImageResource(iconRes)
    }

    /**
     * 切换语音播报状态。
     */
    private fun toggleSpeech() {
        isSpeechEnabled = !isSpeechEnabled
        speechStatusSharedPreferences.edit().putBoolean("isSpeechEnabled", isSpeechEnabled).apply()
        updateToggleSpeechButtonIcon()

        if (isSpeechEnabled) {
            Toast.makeText(this, "语音播报已启用", Toast.LENGTH_SHORT).show()
            speakWithCallback("语音播报已启用") {
                // 语音播报完成后不做额外操作
            }
        } else {
            Toast.makeText(this, "语音播报已禁用", Toast.LENGTH_SHORT).show()
            speakWithCallback("语音播报已禁用") {
                // 语音播报完成后不做额外操作
            }
        }
    }

    /**
     * 处理语音队列。
     */
    private fun processSpeechQueue() {
        if (!isTTSInitialized || !isTTSBound) return

        synchronized(ttsQueue) {
            if (ttsQueue.isNotEmpty() && !tts.isSpeaking) {
                val status = ttsQueue.poll() ?: return
                when (status) {
                    SpeechStatus.UPLOAD_START -> speakWithCallback("开始上传图片") { processSpeechQueue() }
                    SpeechStatus.OFFLINE_MODE -> speakWithCallback("网络异常，已切换至离线模式") {
                        ttsQueue.removeAll { it == SpeechStatus.OFFLINE_MODE }
                        processSpeechQueue()
                    }

                    SpeechStatus.PROCESSING -> speakWithCallback("正在识别内容") { processSpeechQueue() }
                    SpeechStatus.SUCCESS -> speakWithCallback("识别成功") { processSpeechQueue() }
                    SpeechStatus.NAVIGATING -> speakAndNavigate()
                    SpeechStatus.FAILURE -> speakWithCallback("识别失败，请重试") { processSpeechQueue() }
                    SpeechStatus.SERVICE_EXPIRED -> speakWithCallback("在线服务已到期，已切换至离线模式，请等待开发者更新在线服务") {
                        processSpeechQueue()
                    }
                    SpeechStatus.STREAM_MODE -> speakWithCallback("已切换至外置摄像头") {
                        processSpeechQueue()
                    }
                    SpeechStatus.CAMERA_MODE -> speakWithCallback("已切换至内置摄像头") {
                        processSpeechQueue()
                    }
                }
            }
        }
    }

    /**
     * 带回调的语音播报。
     * @param text 播报文本
     * @param callback 播报完成后的回调
     */
    private fun speakWithCallback(text: String, callback: () -> Unit) {
        val utteranceId = "speech_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                callback()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                callback()
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    /**
     * 语音播报并跳转到结果页面。
     */
    private fun speakAndNavigate() {
        val utteranceId = "navigate_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (!hasNavigated) {
                    hasNavigated = true
                    runOnUiThread {
                        val intent = Intent(this@MainActivity, ResultActivity::class.java).apply {
                            putExtra("ocr_result", paragraphs.toTypedArray())
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
            }
        })
        tts.speak("正在跳转结果页面", TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    /**
     * 触发跳转到结果页面。
     */
    private fun triggerNavigation() {
        runOnUiThread {
            if (!hasNavigated) {
                hasNavigated = true
                val intent = Intent(this@MainActivity, ResultActivity::class.java).apply {
                    putExtra("ocr_result", paragraphs.toTypedArray())
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        }
    }

    /**
     * 活动恢复时调用，重置跳转标志。
     */
    override fun onResume() {
        super.onResume()
        hasNavigated = false
        setupImmersiveMode()
    }

    /**
     * 活动暂停时调用，关闭闪光灯。
     */
    override fun onPause() {
        super.onPause()
        if (isFlashOn) {
            isFlashOn = false
            updateFlashState()
            restartCameraWithFlash()
        }
    }

    /**
     * 活动停止时调用，关闭闪光灯。
     */
    override fun onStop() {
        super.onStop()
        if (isFlashOn) {
            isFlashOn = false
            restartCameraWithFlash()
        }
    }

    /**
     * 重新启动相机并关闭闪光灯。
     */
    private fun restartCameraWithFlash() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            cameraProvider.unbindAll()

            val imageCapture = ImageCapture.Builder()
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            try {
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                this.imageCapture = imageCapture
            } catch (ex: Exception) {
                Log.e("Camera", "Use case binding failed", ex)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 活动销毁时调用，释放资源。
     */
    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        updateConnectionState(ConnectionState.DISCONNECTED)
        isStreaming = false
        streamingJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    private fun updateConnectionState(newState: ConnectionState) {
        if (connectionState == newState) return
        connectionState = newState

        runOnUiThread {
            when (newState) {
                ConnectionState.CONNECTING -> {
                    statusIndicator.setBackgroundResource(R.drawable.status_connecting)
                    (statusIndicator.background as? AnimationDrawable)?.start()
                    statusText.text = "正在连接..."
                    timerText.text = "00:00"
                }
                ConnectionState.CONNECTED -> {
                    (statusIndicator.background as? AnimationDrawable)?.stop()
                    statusIndicator.setBackgroundResource(R.drawable.status_connected)
                    statusText.text = "已连接"
                    connectionStartTime = System.currentTimeMillis()
                    startTimer()
                }
                ConnectionState.DISCONNECTED -> {
                    (statusIndicator.background as? AnimationDrawable)?.stop()
                    statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
                    statusText.text = "未连接"
                    timerJob?.cancel()
                    timerText.text = "00:00"
                }
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = CoroutineScope(Dispatchers.Main).launch {
            while (isStreaming && connectionState == ConnectionState.CONNECTED) {
                val elapsed = System.currentTimeMillis() - connectionStartTime
                val minutes = (elapsed / 1000) / 60
                val seconds = (elapsed / 1000) % 60
                timerText.text = "连接时间：%02d:%02d".format(minutes, seconds)
                delay(1000)
            }
        }
    }

    private fun startStream() {
        if (isStreaming) return
        isStreaming = true

        streamingJob = scope.launch {
            var retryCount = 0
            while (isStreaming) {
                try {
                    updateConnectionState(ConnectionState.CONNECTING)
                    retryCount = 0
                    val request = Request.Builder()
                        .url(STREAM_URL)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Unexpected code ${response.code}")
                        }
                        updateConnectionState(ConnectionState.CONNECTED)

                        leftoverData = ByteArray(0)

                        val contentType = response.header("Content-Type") ?: ""
                        boundary = contentType.split("boundary=").last().trim()
                        Log.d(TAG, "Using boundary: --$boundary")

                        response.body?.byteStream()?.let { stream ->
                            parseMjpegStream(stream)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Stream error: ${e.message}")

                    if (++retryCount > MAX_RETRIES) {
                        updateConnectionState(ConnectionState.DISCONNECTED)
                        showToast("Max retries reached")
                        isStreaming = false
                        return@launch
                    }

                    if (isStreaming) {
                        showToast("正在重连... Attempt $retryCount/$MAX_RETRIES")
                        delay(RECONNECT_DELAY)
                    }
                }
            }
        }
    }

    private fun parseMjpegStream(stream: InputStream) {
        val readBuffer = ByteArray(4096)
        try {
            while (isStreaming) {
                val bytesRead = stream.read(readBuffer)
                if (bytesRead == -1) {
                    Log.d(TAG, "End of stream reached")
                    throw IOException("Stream ended unexpectedly")
                }

                val data = leftoverData + readBuffer.copyOfRange(0, bytesRead)
                processData(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream read error: ${e.message}")
            throw e
        }
    }

    private fun processData(data: ByteArray) {
        var processedIndex = 0
        while (true) {
            val boundaryIndex = findBoundary(data, processedIndex)
            if (boundaryIndex == -1) break

            val sectionData = data.copyOfRange(boundaryIndex, data.size)
            val headerEndIndex = findHeaderEnd(sectionData)

            if (headerEndIndex == -1) {
                leftoverData = sectionData
                return
            }

            val headers = String(sectionData, 0, headerEndIndex)
            val contentLength = extractContentLength(headers)
            if (contentLength == -1) {
                Log.e(TAG, "Invalid Content-Length")
                return
            }

            val imageStart = headerEndIndex + 4
            val imageEnd = imageStart + contentLength

            if (imageEnd > sectionData.size) {
                leftoverData = sectionData
                return
            }

            val imageData = sectionData.copyOfRange(imageStart, imageEnd)
            if (isValidJpeg(imageData)) {
                // 显示图像帧
                displayImage(imageData)

                // 只有在 isCaptureRequested 为 true 时才触发识别逻辑
                if (isCaptureRequested) {
                    processImage(imageData) // 调用图像处理逻辑
                    isCaptureRequested = false // 重置标志变量
                }
            }

            processedIndex = boundaryIndex + imageEnd
        }
        leftoverData = data.copyOfRange(processedIndex, data.size)
    }


    private fun displayImage(imageData: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            bitmap?.let {
                // 显示图片
                handler.post {
                    streamView.setImageBitmap(it)
                    Log.d(TAG, "Displayed image ${it.width}x${it.height}")
                }
            } ?: Log.e(TAG, "Failed to decode JPEG")
        } catch (e: Exception) {
            Log.e(TAG, "Image processing error: ${e.message}")
        }
    }

    private fun findBoundary(data: ByteArray, startIndex: Int): Int {
        val boundaryPattern = "--$boundary".toByteArray()
        for (i in startIndex..data.size - boundaryPattern.size) {
            if (data.copyOfRange(i, i + boundaryPattern.size)
                    .contentEquals(boundaryPattern)) return i
        }
        return -1
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if (data[i] == '\r'.code.toByte() &&
                data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() &&
                data[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }

    private fun extractContentLength(headers: String): Int {
        return Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(headers)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: -1
    }

    private fun isValidJpeg(data: ByteArray): Boolean {
        return data.size >= 2 &&
                data[0] == 0xFF.toByte() &&
                data[1] == 0xD8.toByte() &&
                data.last() == 0xD9.toByte()
    }

    private fun processImage(imageData: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            bitmap?.let {
                // 显示图片
                handler.post {
                    streamView.setImageBitmap(it)
                    Log.d(TAG, "Displayed image ${it.width}x${it.height}")
                }

                // 调用离线识别逻辑
                processImageOffline(it)
            } ?: Log.e(TAG, "Failed to decode JPEG")
        } catch (e: Exception) {
            Log.e(TAG, "Image processing error: ${e.message}")
        }
    }
    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }
}