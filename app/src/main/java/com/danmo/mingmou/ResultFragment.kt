package com.danmo.mingmou

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*

class ResultFragment : Fragment(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var adapter: ResultActivity.ParagraphAdapter
    private var currentTextSize = 16f
    private var isTTsInitialized = false
    private var isTTsBound = false
    private var isAutoReadingEnabled = false
    private var isAutoReading = false
    private var currentAutoReadIndex = 0
    private val paragraphs = mutableListOf<String>()
    private val prefs by lazy { requireContext().getSharedPreferences("ResultPrefs", Context.MODE_PRIVATE) }

    companion object {
        fun newInstance(paragraphs: Array<String>): ResultFragment {
            val fragment = ResultFragment()
            val args = Bundle()
            args.putStringArray("ocr_result", paragraphs)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getStringArray("ocr_result")?.let { paragraphs.addAll(it) }
        loadTextSizeSettings()
        loadAutoReadSettings()
        tts = TextToSpeech(requireContext(), this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_result, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        initRecyclerView(recyclerView)
        initFontControls(view)
        initToggleDirectionButton(view, recyclerView)
        initAutoReadControl(view)
        // 关闭按钮
        view.findViewById<ImageView?>(R.id.btnClose)?.setOnClickListener {
            // 1. 调用Activity方法隐藏右侧区域
            (activity as? MainActivity)?.hideResultContainer()
            // 2. 移除自己
            parentFragmentManager.beginTransaction().remove(this).commit()
        }
    }

    private fun loadAutoReadSettings() {
        isAutoReadingEnabled = prefs.getBoolean("autoReadEnabled", false)
    }

    private fun saveAutoReadSettings(enabled: Boolean) {
        prefs.edit().putBoolean("autoReadEnabled", enabled).apply()
    }

    private fun startAutoRead() {
        if (isTTsBound && paragraphs.isNotEmpty()) {
            isAutoReading = true
            currentAutoReadIndex = 0
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (isAutoReading && currentAutoReadIndex < paragraphs.size - 1) {
                        currentAutoReadIndex++
                        speakParagraph(currentAutoReadIndex, TextToSpeech.QUEUE_ADD)
                    } else {
                        isAutoReading = false
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { isAutoReading = false }
            })
            speakParagraph(currentAutoReadIndex, TextToSpeech.QUEUE_FLUSH)
        }
    }

    private fun initRecyclerView(recyclerView: RecyclerView) {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = ResultActivity.ParagraphAdapter(paragraphs, currentTextSize) { position ->
            speakParagraph(position)
        }
        recyclerView.adapter = adapter
    }

    private fun initAutoReadControl(root: View) {
        val autoReadButton = root.findViewById<Button>(R.id.btnAutoRead)
        autoReadButton.text = if (isAutoReadingEnabled) "关闭自动播报" else "开启自动播报"
        autoReadButton.setOnClickListener {
            isAutoReadingEnabled = !isAutoReadingEnabled
            saveAutoReadSettings(isAutoReadingEnabled)
            if (isAutoReadingEnabled) {
                autoReadButton.text = "关闭自动播报"
                if (isTTsInitialized && isTTsBound) startAutoRead()
            } else {
                autoReadButton.text = "开启自动播报"
                isAutoReading = false
                tts.stop()
            }
        }
    }

    private fun initToggleDirectionButton(root: View, recyclerView: RecyclerView) {
        val toggleButton = root.findViewById<Button>(R.id.btnToggleDirection)
        toggleButton.text = "切换为横向滑动"
        toggleButton.setOnClickListener {
            val lm = recyclerView.layoutManager as LinearLayoutManager
            val isVertical = lm.orientation == LinearLayoutManager.VERTICAL
            lm.orientation = if (isVertical) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL
            adapter.notifyDataSetChanged()
            toggleButton.text = if (isVertical) "切换为纵向滑动" else "切换为横向滑动"
        }
    }

    private fun loadTextSizeSettings() {
        currentTextSize = prefs.getFloat("textSize", 16f)
    }

    private fun saveTextSizeSettings() {
        prefs.edit().putFloat("textSize", currentTextSize).apply()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun initFontControls(root: View) {
        val seekBar = root.findViewById<SeekBar>(R.id.seekBarFontSize)
        val btnIncrease = root.findViewById<Button>(R.id.btnIncrease)
        val btnDecrease = root.findViewById<Button>(R.id.btnDecrease)
        val tvFontSize = root.findViewById<TextView>(R.id.tvFontSize)
        tvFontSize.text = "字号: ${currentTextSize.toInt()}"
        seekBar.min = 12; seekBar.max = 30; seekBar.progress = currentTextSize.toInt()
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                currentTextSize = progress.toFloat()
                tvFontSize.text = "字号: $progress"
                adapter.updateTextSize(currentTextSize)
                saveTextSizeSettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        btnIncrease.setOnClickListener {
            currentTextSize = (currentTextSize + 2).coerceAtMost(30f)
            seekBar.progress = currentTextSize.toInt()
            tvFontSize.text = "字号: ${currentTextSize.toInt()}"
        }
        btnDecrease.setOnClickListener {
            currentTextSize = (currentTextSize - 2).coerceAtLeast(12f)
            seekBar.progress = currentTextSize.toInt()
            tvFontSize.text = "字号: ${currentTextSize.toInt()}"
        }
    }

    private fun speakParagraph(position: Int, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (position in paragraphs.indices && isTTsInitialized && isTTsBound) {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "para_$position")
            tts.speak(paragraphs[position], queueMode, params, "para_$position")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTTsInitialized = true
            when (tts.setLanguage(Locale.CHINESE)) {
                TextToSpeech.LANG_MISSING_DATA,
                TextToSpeech.LANG_NOT_SUPPORTED -> Toast.makeText(requireContext(), "不支持中文语音", Toast.LENGTH_SHORT).show()
                else -> {
                    isTTsBound = true
                    if (isAutoReadingEnabled) startAutoRead()
                }
            }
        } else {
            Toast.makeText(requireContext(), "TTS初始化失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts.stop()
        tts.shutdown()
    }
}