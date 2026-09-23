package com.example.myandroidime

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var inputMethodManager: InputMethodManager
    private lateinit var deepSeekApiKeyView: EditText
    private lateinit var deepSeekEndpointView: EditText
    private lateinit var deepSeekModelView: Spinner
    private lateinit var deepSeekAi: DeepSeekImeAi
    private lateinit var dictionaryRepository: UserDictionaryRepository
    private lateinit var dictionaryList: LinearLayout
    private lateinit var dictionarySearch: EditText

    private val serviceId: String
        get() = ComponentName(this, MyInputMethodService::class.java).flattenToShortString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputMethodManager = getSystemService(InputMethodManager::class.java)
        deepSeekAi = DeepSeekImeAi(applicationContext)
        dictionaryRepository = UserDictionaryRepository(applicationContext)
        setContentView(createContentView())
        refreshDictionary()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) updateStatus()
        if (::dictionaryList.isInitialized) refreshDictionary()
    }

    private fun createContentView(): ScrollView {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val spacing = (10 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            gravity = Gravity.CENTER
        }, matchParentWrapContent())

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val configButton = Button(this).apply { text = "配置" }
        val dictionaryButton = Button(this).apply { text = "词库" }
        tabs.addView(configButton, weightWrapContent(1f))
        tabs.addView(dictionaryButton, weightWrapContent(1f))
        root.addView(tabs, matchParentWrapContent())

        val configPanel = createConfigPanel(spacing)
        val dictionaryPanel = createDictionaryPanel(spacing)
        root.addView(configPanel, matchParentWrapContent())
        root.addView(dictionaryPanel, matchParentWrapContent())

        fun showTab(config: Boolean) {
            configPanel.visibility = if (config) android.view.View.VISIBLE else android.view.View.GONE
            dictionaryPanel.visibility = if (config) android.view.View.GONE else android.view.View.VISIBLE
            configButton.alpha = if (config) 1f else 0.55f
            dictionaryButton.alpha = if (config) 0.55f else 1f
        }
        configButton.setOnClickListener { showTab(true) }
        dictionaryButton.setOnClickListener { showTab(false) }
        showTab(true)

        return ScrollView(this).apply {
            addView(root)
            isFillViewport = true
        }
    }

    private fun createConfigPanel(spacing: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).also { statusView = it }.apply {
                textSize = 16f
                setPadding(0, spacing, 0, spacing)
            }, matchParentWrapContent())
            addView(Button(context).apply {
                text = getString(R.string.open_input_method_settings)
                setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            }, matchParentWrapContent())
            addView(Button(context).apply {
                text = getString(R.string.choose_input_method)
                setOnClickListener { inputMethodManager.showInputMethodPicker() }
            }, matchParentWrapContent())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(CheckBox(context).apply {
                    isChecked = deepSeekAi.isEnabled()
                    setOnCheckedChangeListener { _, checked -> deepSeekAi.saveEnabled(checked) }
                })
                addView(TextView(context).apply {
                    text = getString(R.string.deepseek_title)
                    textSize = 16f
                }, weightWrapContent(1f))
            }, matchParentWrapContent())
            addView(EditText(context).also { deepSeekEndpointView = it }.apply {
                hint = getString(R.string.deepseek_endpoint_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
                setText(deepSeekAi.endpoint())
            }, matchParentWrapContent())
            addView(TextView(context).apply { text = getString(R.string.deepseek_model_hint) }, matchParentWrapContent())
            addView(Spinner(context).also { deepSeekModelView = it }.apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    listOf("DeepSeek-V4.1-Flash", "deepseek-flash"),
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                setSelection(if (deepSeekAi.model() == "deepseek-flash") 1 else 0)
            }, matchParentWrapContent())
            addView(EditText(context).also { deepSeekApiKeyView = it }.apply {
                hint = getString(R.string.deepseek_api_key_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setText(deepSeekAi.apiKey())
            }, matchParentWrapContent())
            addView(Button(context).apply {
                text = getString(R.string.deepseek_save)
                setOnClickListener {
                    deepSeekAi.saveApiKey(deepSeekApiKeyView.text.toString())
                    deepSeekAi.saveEndpoint(deepSeekEndpointView.text.toString())
                    deepSeekAi.saveModel(deepSeekModelView.selectedItem.toString())
                    Toast.makeText(this@MainActivity, getString(R.string.deepseek_save_done), Toast.LENGTH_SHORT).show()
                }
            }, matchParentWrapContent())
            addView(Button(context).apply {
                text = getString(R.string.telemetry_clear)
                setOnClickListener {
                    ImeTelemetry.clear()
                    Toast.makeText(this@MainActivity, getString(R.string.telemetry_clear_done), Toast.LENGTH_SHORT).show()
                }
            }, matchParentWrapContent())
            addView(TextView(context).apply {
                text = getString(R.string.ime_test_title)
                textSize = 16f
                setPadding(0, spacing, 0, spacing / 2)
            }, matchParentWrapContent())
            addView(EditText(context).apply {
                hint = getString(R.string.ime_test_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            }, matchParentWrapContent())
            addView(TextView(context).apply {
                text = getString(R.string.ime_setup_help)
                textSize = 14f
                setPadding(0, spacing, 0, 0)
            }, matchParentWrapContent())
        }
    }

    private fun createDictionaryPanel(spacing: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "用户词库"
                textSize = 20f
                setPadding(0, spacing, 0, spacing / 2)
            }, matchParentWrapContent())
            addView(EditText(context).also { dictionarySearch = it }.apply {
                hint = "搜索词或拼音，例如 wjt"
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
                addTextChangedListener { refreshDictionary() }
            }, matchParentWrapContent())
            addView(Button(context).apply {
                text = "添加词条"
                setOnClickListener { showEntryDialog(null) }
            }, matchParentWrapContent())
            dictionaryList = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, spacing, 0, 0)
            }
            addView(dictionaryList, matchParentWrapContent())
        }
    }

    private fun refreshDictionary() {
        if (!::dictionaryList.isInitialized) return
        dictionaryList.removeAllViews()
        val entries = dictionaryRepository.search(dictionarySearch.text?.toString().orEmpty())
        if (entries.isEmpty()) {
            dictionaryList.addView(TextView(this).apply { text = "暂无词条"; setPadding(0, 8, 0, 8) })
            return
        }
        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            row.addView(TextView(this).apply { text = entry.pinyin; setSingleLine(true) }, weightWrapContent(2f))
            row.addView(TextView(this).apply { text = entry.word; setSingleLine(true) }, weightWrapContent(3f))
            row.addView(TextView(this).apply { text = entry.frequency.toString(); gravity = Gravity.CENTER }, weightWrapContent(1f))
            row.addView(Button(this).apply {
                text = "编辑"
                setOnClickListener { showEntryDialog(entry) }
            })
            row.addView(Button(this).apply {
                text = "删除"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("删除词条")
                        .setMessage("确定删除词条“" + entry.word + " / " + entry.pinyin + "”吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            dictionaryRepository.delete(entry.key)
                            refreshDictionary()
                        }.show()
                }
            })
            dictionaryList.addView(row, matchParentWrapContent())
        }
    }

    private fun showEntryDialog(existing: UserDictionaryEntry?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 8, 36, 0)
        }
        val pinyin = EditText(this).apply {
            hint = "拼音（必填）"
            setSingleLine(true)
            setText(existing?.pinyin.orEmpty())
        }
        val word = EditText(this).apply {
            hint = "词（必填）"
            setSingleLine(true)
            setText(existing?.word.orEmpty())
        }
        val frequency = EditText(this).apply {
            hint = "频率（默认 1）"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(existing?.frequency?.toString() ?: "1")
        }
        box.addView(pinyin, matchParentWrapContent())
        box.addView(word, matchParentWrapContent())
        box.addView(frequency, matchParentWrapContent())
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加词条" else "编辑词条")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val entry = UserDictionaryEntry(
                    pinyin.text.toString().trim().lowercase(Locale.ROOT),
                    word.text.toString().trim(),
                    frequency.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1,
                )
                runCatching {
                    if (existing == null) dictionaryRepository.add(entry)
                    else dictionaryRepository.update(existing.key, entry)
                }.onSuccess {
                    refreshDictionary()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "保存失败", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun updateStatus() {
        val enabled = inputMethodManager.enabledInputMethodList.any { it.id == serviceId }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == serviceId
        statusView.text = when {
            selected -> getString(R.string.ime_status_selected)
            enabled -> getString(R.string.ime_status_enabled)
            else -> getString(R.string.ime_status_disabled)
        }
    }

    private fun matchParentWrapContent() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun weightWrapContent(weight: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

    private fun EditText.addTextChangedListener(action: () -> Unit) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }
}
