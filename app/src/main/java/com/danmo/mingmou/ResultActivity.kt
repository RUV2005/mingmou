package com.danmo.mingmou

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.speech.tts.TextToSpeech
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        // 初始化TTS
        tts = TextToSpeech(this, this)

        // 加载用户设置
        loadTextSizeSettings()

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

    private fun speakParagraph(position: Int) {
        if (position in paragraphs.indices) {
            if (isTTsInitialized && isTTsBound) {
                tts.speak(paragraphs[position], TextToSpeech.QUEUE_FLUSH, null, null)
            } else {
                Toast.makeText(this, "TTS引擎未就绪", Toast.LENGTH_SHORT).show()
            }
        }
    }

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