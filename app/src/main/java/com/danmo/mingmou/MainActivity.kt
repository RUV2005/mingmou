package com.danmo.mingmou

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var isFlashOn = false
    private lateinit var flashButton: ImageView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView // 使用 PreviewView 类型
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null
    // 修复后的跳转方法
    private var hasNavigated = false // 添加跳转标志


    companion object {
        private const val API_KEY = "1d68a7b7f999dbdd55c2de07204f982e"
        private const val API_SECRET = "YzVlMjcxMGNhMWQ5YzExMTBlOGY0OTdj"
        private const val APP_ID = "0a6d43e9"
    }

    // 添加语音状态常量
    private enum class SpeechStatus {
        UPLOAD_START,    // 开始上传
        PROCESSING,      // 正在识别
        SUCCESS,         // 识别成功
        FAILURE,         // 识别失败
        NAVIGATING       // 正在跳转
    }

    private lateinit var tts: TextToSpeech
    private var isTTSInitialized = false
    private var isTTSBound = false
    private val ttsQueue = LinkedList<SpeechStatus>() // 语音提示队列
    private var paragraphs = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化 TTS
        tts = TextToSpeech(this, this)

        // 初始化闪光灯按钮
        flashButton =  findViewById(R.id.flash_button)
        val flashButton: ImageView = findViewById(R.id.flash_button)
        flashButton.setOnClickListener { toggleFlash() }

        // 初始化视图
        previewView = findViewById(R.id.preview_view) // 确保 ID 匹配
        val captureButton: ImageView = findViewById(R.id.capture_button)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 检查并请求权限
        checkAndRequestPermissions()

        captureButton.setOnClickListener {
            takePhoto()
        }
    }

    // 新增 TTS 初始化回调
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


    private fun toggleFlash() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            Toast.makeText(this, "设备不支持闪光灯", Toast.LENGTH_SHORT).show()
            return
        }

        isFlashOn = !isFlashOn
        try {
            cameraControl?.enableTorch(isFlashOn) // 直接控制闪光灯
            updateFlashState()
        } catch (e: Exception) {
            Log.e("Flash", "闪光灯控制失败", e)
            Toast.makeText(this, "闪光灯开启失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFlashState() {
        val iconRes = if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        flashButton.setImageResource(iconRes) // 使用 setImageResource 替代 icon
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder().build()

            try {
                // 绑定并获取 Camera 实例
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                cameraControl = camera.cameraControl // 获取 CameraControl
            } catch (ex: Exception) {
                Log.e("Camera", "绑定失败", ex)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        addSpeechToQueue(SpeechStatus.UPLOAD_START)
        addSpeechToQueue(SpeechStatus.PROCESSING)
        // 确保使用当前闪光灯状态
        imageCapture.flashMode = if (isFlashOn) ImageCapture.FLASH_MODE_ON
        else ImageCapture.FLASH_MODE_OFF

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    image.close()
                    processImage(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("Camera", "Photo capture failed: ${exception.message}")
                }
            }
        )
    }

    private fun processImage(bitmap: Bitmap) {
        Thread {
            try {
                addSpeechToQueue(SpeechStatus.UPLOAD_START) // 开始上传

                // 压缩图片并转换为 Base64
                val byteArrayOutputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
                val imageBase64 = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT)

                addSpeechToQueue(SpeechStatus.PROCESSING) // 正在识别
                // 构造请求
                val url = buildRequestUrl()
                val jsonBody = buildRequestBody(imageBase64)

                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    addSpeechToQueue(SpeechStatus.SUCCESS)
                    parseResponse(responseBody) // parseResponse 内部会添加 NAVIGATING 状态
                } else {
                    addSpeechToQueue(SpeechStatus.FAILURE)
                }
            } catch (e: Exception) {
                addSpeechToQueue(SpeechStatus.FAILURE)
            }
        }.start()
    }

    // 新增语音队列管理
    private fun addSpeechToQueue(status: SpeechStatus) {
        synchronized(ttsQueue) {
            ttsQueue.add(status)
        }
        runOnUiThread { processSpeechQueue() }
    }

    // 处理语音队列
    private fun processSpeechQueue() {
        if (!isTTSInitialized || !isTTSBound) return

        synchronized(ttsQueue) {
            if (ttsQueue.isNotEmpty() && !tts.isSpeaking) {
                val status = ttsQueue.poll() ?: return // 如果为 null 直接返回
                when (status) {
                    SpeechStatus.UPLOAD_START -> speakWithCallback("开始上传图片") {
                        processSpeechQueue() // 处理下一个状态
                    }
                    SpeechStatus.PROCESSING -> speakWithCallback("正在识别内容") {
                        processSpeechQueue()
                    }
                    SpeechStatus.SUCCESS -> speakWithCallback("识别成功") {
                        processSpeechQueue()
                    }
                    SpeechStatus.FAILURE -> speakWithCallback("识别失败，请重试") {
                        processSpeechQueue()
                    }
                    SpeechStatus.NAVIGATING -> speakAndNavigate() // 跳转逻辑
                }
            }
        }
    }

    private fun speakWithCallback(text: String, callback: () -> Unit) {
        val utteranceId = "speech_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                callback()
            }
            @Deprecated("Deprecated in Java", ReplaceWith("callback()"))
            override fun onError(utteranceId: String?) {
                callback()
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    // 语音播报并跳转
    private fun speakAndNavigate() {
        val utteranceId = "navigate_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (!hasNavigated) {
                    hasNavigated = true
                    runOnUiThread {
                        startActivity(Intent(this@MainActivity, ResultActivity::class.java).apply {
                            putExtra("ocr_result", paragraphs.toTypedArray())
                        })
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        tts.speak("正在跳转结果页面", TextToSpeech.QUEUE_ADD, null, utteranceId)
    }


    private fun buildRequestUrl(): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())



        // 生成签名
        val signature = generateSignature(date)
        val authString = "api_key=\"$API_KEY\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.encodeToString(authString.toByteArray(), Base64.NO_WRAP)

        return "https://api.xf-yun.com/v1/private/sf8e6aca1" +
                "?authorization=${URLEncoder.encode(authorization, "UTF-8")}&" +
                "host=api.xf-yun.com&" +
                "date=${URLEncoder.encode(date, "UTF-8")}"
    }

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

    private fun buildRequestBody(imageBase64: String): String {
        return JSONObject().apply {
            put("header", JSONObject().apply {
                put("app_id", APP_ID)  // 修复这里，添加键名参数
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

    // 解析 API 响应
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

                    // 智能合并逻辑
                    when {
                        // 情况1：以段落符号结尾
                        lineContent.endsWithAny(paragraphEndings) -> {
                            paragraphs.add(currentParagraph.toString())
                            currentParagraph.clear()
                        }
                        // 情况2：行末有连字符或内容很短（可能是换行单词）
                        lineContent.endsWith("-") || lineContent.length < 15 -> {
                            currentParagraph.append(" ")
                        }
                        // 情况3：其他情况加空格继续
                        else -> {
                            currentParagraph.append(" ")
                        }
                    }
                }
            }

            // 添加最后一个段落
            if (currentParagraph.isNotEmpty()) {
                paragraphs.add(currentParagraph.toString().trim())
            }

            // 添加跳转逻辑到语音队列
            addSpeechToQueue(SpeechStatus.NAVIGATING)

        } catch (e: Exception) {
            Log.e("Parse Error", "解析响应失败: ${e.message}")
            addSpeechToQueue(SpeechStatus.FAILURE) // 添加失败提示到队列
        }
    }

    private fun String.endsWithAny(chars: Set<Char>): Boolean {
        return if (isNotEmpty()) chars.contains(last()) else false
    }

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

    override fun onPause() {
        super.onPause()
        if (isFlashOn) {
            isFlashOn = false
            updateFlashState()
            restartCameraWithFlash()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFlashOn) {
            isFlashOn = false
            restartCameraWithFlash()
        }
    }

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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}