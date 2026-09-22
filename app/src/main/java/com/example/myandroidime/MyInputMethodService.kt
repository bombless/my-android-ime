package com.example.myandroidime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import com.example.ime.core.PinyinImeEngine
import com.example.ime.core.RimeDictionary
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import android.widget.FrameLayout
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * InputMethodService is a Service, not a ComponentActivity, so the window
 * created by the input-method framework has no ViewTreeLifecycleOwner.  A
 * ComposeView needs that owner to create its window recomposer; without it the
 * framework crashes while attaching the view and immediately hides the IME.
 */
class MyInputMethodService : InputMethodService(), SavedStateRegistryOwner {
    private companion object { const val TAG = "MyAndroidIME" }

    private lateinit var imeEngine: PinyinImeEngine
    private lateinit var deepSeekAi: DeepSeekImeAi
    private lateinit var baiduSuggest: BaiduImeSuggest
    private lateinit var inputHistoryStore: InputHistoryStore
    // Compose must observe composing changes; a plain StringBuilder does not
    // trigger recomposition, which previously left the candidate strip empty.
    private val composing = mutableStateOf("")
    private val aiRevision = mutableIntStateOf(0)
    private val baiduRevision = mutableIntStateOf(0)
    private val historyRevision = mutableIntStateOf(0)
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var savedStateRegistryController: SavedStateRegistryController

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        Log.d(TAG, "onCreate START")
        super.onCreate()
        ImeTelemetry.start()
        deepSeekAi = DeepSeekImeAi(applicationContext)
        baiduSuggest = BaiduImeSuggest()
        inputHistoryStore = InputHistoryStore(applicationContext)
        try { imeEngine = PinyinImeEngine(loadDictionary()) { pinyin -> deepSeekAi.candidates(pinyin) } }
        catch (e: Exception) { Log.e(TAG, "dictionary loading FAILED", e); throw e }
        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        Log.d(TAG, "onCreate END")
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        Log.d(TAG, "onStartInput restarting=$restarting")
        super.onStartInput(attribute, restarting)
        composing.value = ""
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        Log.d(TAG, "onStartInputView restarting=$restarting")
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInput() { composing.value = ""; Log.d(TAG, "onFinishInput"); super.onFinishInput() }
    override fun onFinishInputView(finishingInput: Boolean) { Log.d(TAG, "onFinishInputView finishingInput=$finishingInput"); super.onFinishInputView(finishingInput) }

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView START")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return ImeRootView(this, this).apply {
            addView(ComposeView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    aiRevision.intValue
                    baiduRevision.intValue
                    historyRevision.intValue
                    val pinyin = composing.value
                    KeyboardScreen(
                        composing = pinyin,
                        rimeCandidates = run {
                            val t = System.nanoTime(); val value = imeEngine.localCandidates(pinyin).map { it.text }
                            ImeTelemetry.record("rime_candidates", System.nanoTime() - t, value.size); value
                        },
                        baiduCandidates = run {
                            val t = System.nanoTime(); val value = baiduSuggest.candidates(pinyin)
                            ImeTelemetry.record("baidu_cache", System.nanoTime() - t, value.size); value
                        },
                        deepSeekCandidates = run {
                            val t = System.nanoTime(); val value = imeEngine.remoteCandidates(pinyin).map { it.text }
                            ImeTelemetry.record("deepseek_cache", System.nanoTime() - t, value.size); value
                        },
                        historyCandidates = run {
                            val t = System.nanoTime(); val value = inputHistoryStore.candidates(pinyin)
                            ImeTelemetry.record("history_lookup", System.nanoTime() - t, value.size); value
                        },
                        onKey = ::handleKey,
                        onCandidate = ::commitCandidate
                    )
                }
            })
            Log.d(TAG, "onCreateInputView END")
        }
    }

    override fun onDestroy() {
        ImeTelemetry.stop()
        if (::lifecycleRegistry.isInitialized) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        super.onDestroy()
    }

    /**
     * The IME framework inserts the view below a private window hierarchy. Its
     * content child (where Compose looks for the owner) is created only after
     * onCreateInputView returns, so install the owner on that hierarchy before
     * ComposeView's attach callback creates the composition.
     */
    private class ImeRootView(
        context: Context,
        private val owner: SavedStateRegistryOwner,
    ) : FrameLayout(context) {
        override fun onAttachedToWindow() {
            var root: View = this
            while (root.parent is View) {
                root = root.parent as View
            }
            root.setViewTreeLifecycleOwner(owner)
            root.setViewTreeSavedStateRegistryOwner(owner)
            super.onAttachedToWindow()
        }
    }

    private fun handleKey(key: String) {
        val handleStart = System.nanoTime()
        Log.d(TAG, "handleKey key=$key composingBefore=$composing")
        val c = currentInputConnection
        if (c == null) { Log.w(TAG, "handleKey no currentInputConnection key=$key"); return }
        when (key) {
            "⌫" -> if (composing.value.isNotEmpty()) { composing.value = composing.value.dropLast(1); Log.d(TAG, "composingAfter=${composing.value}"); Log.d(TAG, "setComposingText text=${composing.value}"); c.setComposingText(composing.value, 1) } else { Log.d(TAG, "deleteSurroundingTextInCodePoints"); c.deleteSurroundingTextInCodePoints(1, 0) }
            "↵" -> if (composing.value.isNotEmpty()) { val result = logCandidates(composing.value); val candidate = result.firstOrNull(); if (candidate != null) { Log.d(TAG, "enter commit candidate=${candidate.text}"); commitCandidate(candidate.text) } else { Log.d(TAG, "enter commit raw composing=${composing.value}"); Log.d(TAG, "commitText text=${composing.value}"); c.commitText(composing.value, 1); composing.value = ""; Log.d(TAG, "composingAfter=${composing.value}") } } else { Log.d(TAG, "enter commit newline"); Log.d(TAG, "commitText text=\\n"); c.commitText("\n", 1) }
            "空格" -> { Log.d(TAG, "commitText text= "); c.commitText(" ", 1) }
            "，", "。", "、", "；", "：", "？", "！", "《", "》", "（", "）" -> {
                // Punctuation-wheel selections are terminal actions: commit the
                // current composing text (using its first candidate when one
                // exists) and then commit the selected punctuation immediately.
                if (composing.value.isNotEmpty()) {
                    val result = logCandidates(composing.value)
                    val candidate = result.firstOrNull()
                    if (candidate != null) {
                        Log.d(TAG, "punctuation commit candidate=${candidate.text}")
                        commitCandidate(candidate.text)
                    } else {
                        Log.d(TAG, "punctuation commit raw composing=${composing.value}")
                        c.commitText(composing.value, 1)
                        composing.value = ""
                    }
                }
                Log.d(TAG, "punctuation commitText text=$key")
                c.commitText(key, 1)
            }
            else -> {
                composing.value += key.lowercase()
                Log.d(TAG, "composingAfter=${composing.value}")
                Log.d(TAG, "setComposingText text=${composing.value}")
                c.setComposingText(composing.value, 1)
                val query = composing.value
                val candidateStart = System.nanoTime()
                val candidates = logCandidates(query)
                ImeTelemetry.record("candidate_query", System.nanoTime() - candidateStart, candidates.size)
                deepSeekAi.requestIfNeeded(query) { aiRevision.intValue++ }
                baiduSuggest.requestIfNeeded(query) { baiduRevision.intValue++ }
            }
        }
        ImeTelemetry.record("handle_key", System.nanoTime() - handleStart, key.length)
    }

    private fun commitCandidate(text: String) {
        val commitStart = System.nanoTime()
        Log.d(TAG, "commitCandidate text=$text composingBefore=${composing.value}")
        val c = currentInputConnection
        if (c == null) { Log.w(TAG, "commitCandidate no currentInputConnection text=$text"); return }
        inputHistoryStore.recordSelection(composing.value, text)
        historyRevision.intValue++
        Log.d(TAG, "history selection recorded text=$text")
        Log.d(TAG, "commitText text=$text")
        c.commitText(text, 1)
        composing.value = ""
        ImeTelemetry.record("commit_candidate", System.nanoTime() - commitStart, text.length)
        Log.d(TAG, "commitCandidate SUCCESS text=$text")
        Log.d(TAG, "composingAfter=${composing.value}")
    }

    private fun logCandidates(pinyin: String): List<com.example.ime.core.Candidate> {
        Log.d(TAG, "candidate query pinyin=$pinyin")
        val result = imeEngine.candidates(pinyin)
        Log.d(TAG, "candidate result count=${result.size}")
        Log.d(TAG, "candidate result top=${result.take(10).map { it.text }}")
        return result
    }

    private fun loadDictionary(): RimeDictionary {
        Log.d(TAG, "dictionary loading START mode=lazy-shards")
        val source = object : RimeDictionary.ShardSource {
            override fun list(initial: Char): List<String> {
                val dir = "dict-shards/$initial"
                val files = assets.list(dir)?.filter { it.endsWith(".dict.yaml") }?.sorted().orEmpty()
                Log.d(TAG, "dictionary shard list initial=" + initial + " count=" + files.size)
                return files.map { "$dir/$it" }
            }

            override fun open(path: String): java.io.InputStream {
                Log.d(TAG, "dictionary shard parse START file=" + path)
                return assets.open(path)
            }
        }
        val dictionary = RimeDictionary.fromShards(source)
        Log.d(TAG, "dictionary loaded successfully: mode=lazy-shards")
        Log.d(TAG, "dictionary loading END")
        return dictionary
    }
}
