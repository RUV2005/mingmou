package com.danmo.mingmou

import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class AutoCaptureAnalyzer(
    private val onTextDetected: (hasText: Boolean, textBlocks: List<Rect>) -> Unit
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var lastTriggerTime = 0L

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTriggerTime < 2000) { // 2秒防抖
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            imageProxy.image!!, // 使用 @OptIn 标记
            imageProxy.imageInfo.rotationDegrees
        )

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val textBlocks = processTextResult(visionText, imageProxy)
                onTextDetected(textBlocks.isNotEmpty(), textBlocks)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun processTextResult(visionText: Text, imageProxy: ImageProxy): List<Rect> {
        return visionText.textBlocks.map { block ->
            Rect(
                block.boundingBox!!.left,
                block.boundingBox!!.top,
                block.boundingBox!!.right,
                block.boundingBox!!.bottom
            )
        }.filter { rect ->
            // 过滤小文本区域（面积超过画面5%）
            val area = rect.width() * rect.height()
            area > (imageProxy.width * imageProxy.height * 0.05)
        }
    }
}