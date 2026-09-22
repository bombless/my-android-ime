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

## Performance telemetry

The IME includes a lightweight, local-only HTTP telemetry service for diagnosing input latency. It listens on `127.0.0.1:8765` only and keeps the most recent 2000 events in memory. Telemetry intentionally does **not** record the user's typed text; it records event names, durations, result counts/sizes, timestamps, and success/error status.

### What is measured

- `handle_key`: total key handling time
- `candidate_query`: candidate query time
- `rime_candidates`: Rime/local candidate generation time
- `baidu_cache`: Baidu candidate cache lookup time
- `deepseek_cache`: DeepSeek candidate cache lookup time
- `history_lookup`: history-input lookup time
- `baidu_request`: Baidu request latency
- `deepseek_request`: DeepSeek request latency
- `commit_candidate`: candidate commit time

The JSON endpoint also reports `count`, `avgMs`, `p50Ms`, `p95Ms`, and `maxMs` for each event type.

### Access from a development PC

Forward the IME's localhost port through ADB:

```bash
adb forward tcp:8765 tcp:8765
```

Then open:

- `http://127.0.0.1:8765/telemetry` — JSON summary and recent events
- `http://127.0.0.1:8765/telemetry.csv` — CSV download for offline analysis

The telemetry server starts with the input-method service and stops when the service is destroyed. It is intended for development/performance diagnosis, not as a production network API.
