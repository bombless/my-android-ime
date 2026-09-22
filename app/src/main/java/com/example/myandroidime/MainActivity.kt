package com.example.myandroidime

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast

/**
 * A small setup screen for the IME service.
 *
 * An InputMethodService is not a normal launchable screen: installing the APK
 * does not enable it or make it the current keyboard.  This activity makes
 * that required system setup visible instead of leaving the user with an app
 * that appears to do nothing when they press Run.
 */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var inputMethodManager: InputMethodManager
    private lateinit var deepSeekApiKeyView: EditText
    private lateinit var deepSeekEndpointView: EditText
    private lateinit var deepSeekModelView: Spinner
    private lateinit var deepSeekAi: DeepSeekImeAi

    private val serviceId: String
        get() = ComponentName(this, MyInputMethodService::class.java)
            .flattenToShortString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputMethodManager = getSystemService(InputMethodManager::class.java)
        deepSeekAi = DeepSeekImeAi(applicationContext)
        setContentView(createContentView())
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) updateStatus()
    }

    private fun createContentView(): LinearLayout {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val spacing = (12 * resources.displayMetrics.density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)

            addView(TextView(context).apply {
                text = getString(R.string.app_name)
                textSize = 24f
                gravity = Gravity.CENTER
            }, matchParentWrapContent())

            addView(TextView(context).also { statusView = it }.apply {
                textSize = 16f
                setPadding(0, spacing, 0, spacing)
            }, matchParentWrapContent())

            addView(Button(context).apply {
                text = getString(R.string.open_input_method_settings)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
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
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                addView(TextView(context).apply {
                    text = getString(R.string.deepseek_title)
                    textSize = 16f
                    setPadding(0, spacing, 0, spacing / 2)
                }, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
            }, matchParentWrapContent())

            addView(EditText(context).also { deepSeekEndpointView = it }.apply {
                hint = getString(R.string.deepseek_endpoint_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
                setText(deepSeekAi.endpoint())
            }, matchParentWrapContent())

            addView(TextView(context).apply {
                text = getString(R.string.deepseek_model_hint)
                setPadding(0, spacing / 2, 0, spacing / 4)
            }, matchParentWrapContent())

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
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setText(deepSeekAi.apiKey())
            }, matchParentWrapContent())

            addView(Button(context).apply {
                text = getString(R.string.deepseek_save)
                setOnClickListener {
                    deepSeekAi.saveApiKey(deepSeekApiKeyView.text.toString())
                    deepSeekAi.saveEndpoint(deepSeekEndpointView.text.toString())
                    deepSeekAi.saveModel(deepSeekModelView.selectedItem.toString())
                    deepSeekEndpointView.setText(deepSeekAi.endpoint())
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.deepseek_save_done),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }, matchParentWrapContent())

            addView(Button(context).apply {
                text = getString(R.string.deepseek_clear_cache)
                setOnClickListener {
                    deepSeekAi.clearAllPatches()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.deepseek_clear_cache_done),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }, matchParentWrapContent())

            addView(TextView(context).apply {
                text = getString(R.string.ime_test_title)
                textSize = 16f
                setPadding(0, spacing, 0, spacing / 2)
            }, matchParentWrapContent())

            addView(EditText(context).apply {
                hint = getString(R.string.ime_test_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(false)
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
                setSelectAllOnFocus(false)
            }, matchParentWrapContent())

            addView(TextView(context).apply {
                text = getString(R.string.ime_setup_help)
                textSize = 14f
                setPadding(0, spacing, 0, 0)
            }, matchParentWrapContent())
        }
    }

    private fun updateStatus() {
        val enabled = inputMethodManager.enabledInputMethodList.any { it.id == serviceId }
        val selected = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ) == serviceId

        statusView.text = when {
            selected -> getString(R.string.ime_status_selected)
            enabled -> getString(R.string.ime_status_enabled)
            else -> getString(R.string.ime_status_disabled)
        }
    }

    private fun matchParentWrapContent(): ViewGroup.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
}
