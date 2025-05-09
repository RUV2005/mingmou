package com.danmo.mingmou

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class ResultActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var adapter: ParagraphAdapter
    private var currentTextSize = 16f
    private val paragraphs = mutableListOf<String>()
    private var isTTsInitialized = false
    private var isTTsBound = false
    private var isVerticalScroll = true // 默认为垂直滑动
    private var isAutoReadingEnabled = false // 默认关闭自动播报
    private val prefs by lazy { getPreferences(Context.MODE_PRIVATE) }
    // 添加自动朗读相关变量
    private var isAutoReading = false
    private var currentAutoReadIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        // 初始化TTS
        tts = TextToSpeech(this, this)

        // 加载用户设置
        loadTextSizeSettings()
        loadAutoReadSettings() // 新增加载自动播报设置

        // 初始化列表数据
        intent.getStringArrayExtra("ocr_result")?.let {
            paragraphs.addAll(it.toList())
        }

        // 初始化RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        initRecyclerView(recyclerView)

        // 初始化字体控制组件
        initFontControls()
        // 初始化滑动方向切换按钮
        initToggleDirectionButton(recyclerView)
        // 初始化自动播报控制按钮
        initAutoReadControl()
    }

    // 新增方法：加载自动播报设置
    private fun loadAutoReadSettings() {
        isAutoReadingEnabled = prefs.getBoolean("autoReadEnabled", false)
    }

    // 修改方法：保存自动播报设置
    private fun saveAutoReadSettings(enabled: Boolean) {
        prefs.edit().putBoolean("autoReadEnabled", enabled).apply()
    }


    private fun startAutoRead() {
        if (isTTsBound && paragraphs.isNotEmpty()) {
            isAutoReading = true
            currentAutoReadIndex = 0

            // 设置UtteranceProgressListener
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d("TTS", "Started utterance: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d("TTS", "Completed utterance: $utteranceId")
                    // 切换到下一个段落
                    if (isAutoReading && currentAutoReadIndex < paragraphs.size - 1) {
                        currentAutoReadIndex++
                        speakParagraph(currentAutoReadIndex, TextToSpeech.QUEUE_ADD)
                    } else {
                        isAutoReading = false
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e("TTS", "Error in utterance: $utteranceId")
                    isAutoReading = false
                }
            })

            // 开始第一个段落
            speakParagraph(currentAutoReadIndex, TextToSpeech.QUEUE_FLUSH)
        }
    }

    private fun initRecyclerView(recyclerView: RecyclerView) {
        // 根据屏幕方向配置布局管理器
        val orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            LinearLayoutManager.VERTICAL
        } else {
            LinearLayoutManager.VERTICAL // 竖屏仍为垂直，但布局文件已调整控件排列
        }

        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            this.orientation = orientation
        }

        // 添加滚动监听解决横屏触摸冲突
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                findViewById<LinearLayout>(R.id.controlPanel).isEnabled = (newState == RecyclerView.SCROLL_STATE_IDLE)
            }
        })

        // 配置适配器
        adapter = ParagraphAdapter(paragraphs, currentTextSize) { position ->
            speakParagraph(position)
        }
        recyclerView.adapter = adapter
    }

    // 修改方法：初始化自动播报控制
    private fun initAutoReadControl() {
        val autoReadButton = findViewById<Button>(R.id.btnAutoRead)

        // 根据保存的状态设置按钮文本
        if (isAutoReadingEnabled) {
            autoReadButton.text = "关闭自动播报"
        } else {
            autoReadButton.text = "开启自动播报"
        }

        autoReadButton.setOnClickListener {
            isAutoReadingEnabled = !isAutoReadingEnabled
            saveAutoReadSettings(isAutoReadingEnabled) // 保存状态

            if (isAutoReadingEnabled) {
                autoReadButton.text = "关闭自动播报"
                if (isTTsInitialized && isTTsBound) {
                    startAutoRead()
                }
            } else {
                autoReadButton.text = "开启自动播报"
                isAutoReading = false
                tts.stop()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun initToggleDirectionButton(recyclerView: RecyclerView) {
        val toggleButton = findViewById<Button>(R.id.btnToggleDirection)

        // 根据屏幕方向初始化按钮文字
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            toggleButton.text = "切换为横向滑动"
        } else {
            toggleButton.text = "切换为纵向滑动"
        }

        toggleButton.setOnClickListener {
            isVerticalScroll = !isVerticalScroll
            val layoutManager = recyclerView.layoutManager as LinearLayoutManager
            layoutManager.orientation = if (isVerticalScroll) {
                LinearLayoutManager.VERTICAL
            } else {
                LinearLayoutManager.HORIZONTAL
            }
            adapter.notifyDataSetChanged()

            // 更新按钮文字
            toggleButton.text = if (isVerticalScroll) {
                "切换为横向滑动"
            } else {
                "切换为纵向滑动"
            }
        }
    }



    private fun loadTextSizeSettings() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        currentTextSize = prefs.getFloat("textSize", 16f)
    }

    private fun saveTextSizeSettings() {
        getPreferences(Context.MODE_PRIVATE).edit().apply {
            putFloat("textSize", currentTextSize)
            apply()
        }
    }

    @SuppressLint("NewApi")
    private fun initFontControls() {
        val seekBar = findViewById<SeekBar>(R.id.seekBarFontSize)
        val btnIncrease = findViewById<Button>(R.id.btnIncrease)
        val btnDecrease = findViewById<Button>(R.id.btnDecrease)
        val tvFontSize = findViewById<TextView>(R.id.tvFontSize) // 新增TextView引用

        // 初始化字体显示
        tvFontSize.text = getString(R.string.font_size_label, currentTextSize.toInt())

        // 设置SeekBar范围（12sp - 30sp）
        seekBar.min = 12
        seekBar.max = 30
        seekBar.progress = currentTextSize.toInt()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                currentTextSize = progress.toFloat()
                tvFontSize.text = getString(R.string.font_size_label, progress)
                adapter.updateTextSize(currentTextSize)
                saveTextSizeSettings()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnIncrease.setOnClickListener {
            currentTextSize = (currentTextSize + 2).coerceAtMost(30f)
            seekBar.progress = currentTextSize.toInt()
            tvFontSize.text = getString(R.string.font_size_label, currentTextSize.toInt())
        }

        btnDecrease.setOnClickListener {
            currentTextSize = (currentTextSize - 2).coerceAtLeast(12f)
            seekBar.progress = currentTextSize.toInt()
            tvFontSize.text = getString(R.string.font_size_label, currentTextSize.toInt())// 按钮点击更新
        }
    }

    // 修改后的朗读方法
    private fun speakParagraph(position: Int, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (position in paragraphs.indices) {
            if (isTTsInitialized && isTTsBound) {
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "para_$position")
                tts.speak(paragraphs[position], queueMode, params, "para_$position")
            } else {
                Toast.makeText(this, "TTS引擎未就绪", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // 正确的onInit实现位置
    // 修改方法：TTS初始化
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTTsInitialized = true
            when (tts.setLanguage(Locale.CHINESE)) {
                TextToSpeech.LANG_MISSING_DATA,
                TextToSpeech.LANG_NOT_SUPPORTED -> {
                    Toast.makeText(this, "不支持中文语音", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    isTTsBound = true

                    // 根据保存的状态决定是否启动自动播报
                    if (isAutoReadingEnabled) {
                        startAutoRead()
                    }
                }
            }
        } else {
            Toast.makeText(this, "TTS初始化失败", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
    }

    class ParagraphAdapter(
        private var paragraphs: List<String>,
        private var textSize: Float,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ParagraphAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(R.id.paragraphText)

            init {
                view.setOnClickListener {
                    onClick(adapterPosition)
                }
            }
        }

        @SuppressLint("NotifyDataSetChanged")
        fun updateTextSize(newSize: Float) {
            textSize = newSize
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_paragraph, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.textView.text = paragraphs[position]
            holder.textView.textSize = textSize
        }

        override fun getItemCount() = paragraphs.size
    }
}