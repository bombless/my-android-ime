# My Android IME

Minimal Android InputMethodService implemented with Jetpack Compose.

- Package: `com.example.myandroidime`
- Min SDK: 26
- Compile/target SDK: 35
- UI: Jetpack Compose + Material 3
- Input bridge: `InputMethodService.currentInputConnection`

## Features
- QWERTY keyboard rendered entirely in Compose
- Backspace, space, enter
- Android registers it as a system input method

## Build
Open this directory in Android Studio with a compatible JDK/Gradle setup, then build/install the `app` module. Launch **My Android IME** once, tap **打开输入法设置** and enable the service, then tap **切换当前输入法** and choose it. An input-method service is not selected automatically by Android for security reasons; until it is the current IME, tapping a text field will continue to show another keyboard.

After the service is enabled and selected, tap any editable text field in another app. The system will call `MyInputMethodService.onCreateInputView()` and show the Compose keyboard.

The debug APK was verified with `gradle.bat :app:assembleDebug --no-daemon` and is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Chinese dictionary and desktop REPL

This project now downloads Rime Ice dictionaries under `.vendor/rime-ice/cn_dicts/` and packages base.dict.yaml, ext.dict.yaml, and tencent.dict.yaml into Android assets during the prepareChineseDictionary Gradle task.

The shared lookup engine lives in :ime-core, while :repl provides a PC-side REPL using the same engine and dictionary data. Run `gradle :repl:run` and enter Pinyin such as `wo`; the REPL prints ranked candidates. Enter `:q` to exit.

The REPL input is Pinyin rather than already-composed Chinese text; the Android IME can reuse the same engine for candidate generation later.
