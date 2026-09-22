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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

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

    private val serviceId: String
        get() = ComponentName(this, MyInputMethodService::class.java)
            .flattenToShortString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputMethodManager = getSystemService(InputMethodManager::class.java)
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
