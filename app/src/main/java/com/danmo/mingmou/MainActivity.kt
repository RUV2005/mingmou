package com.danmo.mingmou

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
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
import androidx.core.net.toUri
import com.google.android.material.button.MaterialButton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity() {
    private var isFlashOn = false
    private lateinit var flashButton: MaterialButton
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView // 使用 PreviewView 类型
    private var imageCapture: ImageCapture? = null
    private val REQUEST_CAMERA_PERMISSION = 1001
    private var cameraControl: CameraControl? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化闪光灯按钮
        flashButton = findViewById(R.id.flash_button)
        flashButton.setOnClickListener { toggleFlash() }

        // 初始化视图
        previewView = findViewById(R.id.preview_view) // 确保 ID 匹配
        val captureButton: Button = findViewById(R.id.capture_button)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 检查并请求权限
        checkAndRequestPermissions()

        captureButton.setOnClickListener {
            takePhoto()
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
        flashButton.icon = ContextCompat.getDrawable(this, iconRes)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
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
                // 压缩图片并转换为Base64
                val byteArrayOutputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
                val imageBase64 = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT)

                // 构造请求
                val url = buildRequestUrl()
                val jsonBody = buildRequestBody(imageBase64)

                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .post(RequestBody.create("application/json".toMediaType(), jsonBody))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                Log.d("API Response", responseBody ?: "Empty response")

                // 解析响应
                responseBody?.let { parseResponse(it) }
            } catch (e: Exception) {
                Log.e("API Error", e.message ?: "Unknown error")
            }
        }.start()
    }

    private fun buildRequestUrl(): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())

        // 替换为你的API密钥
        val apiKey = "1d68a7b7f999dbdd55c2de07204f982e"
        val apiSecret = "YzVlMjcxMGNhMWQ5YzExMTBlOGY0OTdj"

        // 生成签名
        val signature = generateSignature(date, apiSecret)
        val authString = "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.encodeToString(authString.toByteArray(), Base64.NO_WRAP)

        return "https://api.xf-yun.com/v1/private/sf8e6aca1" +
                "?authorization=${URLEncoder.encode(authorization, "UTF-8")}&" +
                "host=api.xf-yun.com&" +
                "date=${URLEncoder.encode(date, "UTF-8")}"
    }

    private fun generateSignature(date: String, apiSecret: String): String {
        val signatureOrigin = """
            host: api.xf-yun.com
            date: $date
            POST /v1/private/sf8e6aca1 HTTP/1.1
        """.trimIndent()

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256"))
        val signatureSha = mac.doFinal(signatureOrigin.toByteArray())
        return Base64.encodeToString(signatureSha, Base64.NO_WRAP)
    }

    private fun buildRequestBody(imageBase64: String): String {
        return JSONObject().apply {
            put("header", JSONObject().apply {
                put("app_id", "0a6d43e9") // 替换为你的App ID
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
            val paragraphEndings = setOf('。', '！', '？', '.', '!', '?', ';', ';', '…')
            val paragraphs = mutableListOf<String>()
            var currentParagraph = StringBuilder()

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

            runOnUiThread {
                val intent = Intent(this@MainActivity, ResultActivity::class.java).apply {
                    putExtra("ocr_result", paragraphs.toTypedArray())
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("Parse Error", "解析响应失败: ${e.message}")
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
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

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
    }
}