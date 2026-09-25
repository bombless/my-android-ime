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
    private lateinit var userDictionaryRepository: UserDictionaryRepository
    // Compose must observe composing changes; a plain StringBuilder does not
    // trigger recomposition, which previously left the candidate strip empty.
    private val composing = mutableStateOf("")
    private val continuationContext = mutableStateOf("")
    private val deepSeekCandidates = mutableStateOf<List<String>>(emptyList())
    private val baiduRevision = mutableIntStateOf(0)
    private val historyRevision = mutableIntStateOf(0)
    private val rimeRevision = mutableIntStateOf(0)
    private var lastCommittedCandidate: String? = null
    private var currentEditorInfo: android.view.inputmethod.EditorInfo? = null
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var savedStateRegistryController: SavedStateRegistryController

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        Log.d(TAG, "onCreate START")
        super.onCreate()
        deepSeekAi = DeepSeekImeAi(applicationContext)
        baiduSuggest = BaiduImeSuggest()
        inputHistoryStore = InputHistoryStore(applicationContext)
        userDictionaryRepository = UserDictionaryRepository(applicationContext)
        try {
            imeEngine = PinyinImeEngine(
                loadDictionary(),
                { emptyList() },
                { name, duration, size -> ImeTelemetry.record(name, duration, size) }
            )
        }
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
        currentEditorInfo = attribute
        composing.value = ""
        continuationContext.value = ""
        deepSeekCandidates.value = emptyList()
        lastCommittedCandidate = null
        historyRevision.intValue++
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        Log.d(TAG, "onStartInputView restarting=$restarting")
        currentEditorInfo = info
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInput() { composing.value = ""; currentEditorInfo = null; Log.d(TAG, "onFinishInput"); super.onFinishInput() }
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
                    baiduRevision.intValue
                    historyRevision.intValue
                    rimeRevision.intValue
                    val pinyin = composing.value
                    KeyboardScreen(
                        composing = pinyin,
                        rimeCandidates = run {
                            val t = System.nanoTime(); val value = imeEngine.localCandidates(pinyin).map { it.text }
                            ImeTelemetry.record("rime_candidates", System.nanoTime() - t, value.size); value
                        },
                        userDictionaryCandidates = run {
                            val t = System.nanoTime(); val value = userDictionaryRepository.candidates(pinyin).map { it.text }
                            ImeTelemetry.record("user_dictionary_candidates", System.nanoTime() - t, value.size); value
                        },
                        baiduCandidates = run {
                            val t = System.nanoTime(); val value = baiduSuggest.candidates(continuationContext.value, pinyin)
                            ImeTelemetry.record("baidu_cache", System.nanoTime() - t, value.size); value
                        },
                        deepSeekCandidates = run {
                            val t = System.nanoTime(); val value = if (deepSeekAi.isEnabled()) deepSeekCandidates.value else emptyList()
                            ImeTelemetry.record("deepseek_cache", System.nanoTime() - t, value.size); value
                        },
                        showDeepSeek = deepSeekAi.isEnabled(),
                        historyCandidates = run {
                            val t = System.nanoTime(); val value = inputHistoryStore.candidates(pinyin)
                            ImeTelemetry.record("history_lookup", System.nanoTime() - t, value.size); value
                        },
                        nextWordHistoryCandidates = run {
                            val previous = lastCommittedCandidate
                            val t = System.nanoTime()
                            val value = if (pinyin.isEmpty() && previous != null) inputHistoryStore.nextWordCandidates(previous) else emptyList()
                            ImeTelemetry.record("next_word_history_lookup", System.nanoTime() - t, value.size); value
                        },
                        onKey = ::handleKey,
                        onEmoji = ::commitEmoji,
                        onCandidate = ::commitCandidate
                    )
                }
            })
            Log.d(TAG, "onCreateInputView END")
        }
    }

    override fun onDestroy() {
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
            "⌫" -> {
                val selected = c.getSelectedText(0)?.toString().orEmpty()
                if (selected.isNotEmpty()) {
                    Log.d(TAG, "delete selection length=${selected.length}")
                    c.commitText("", 1)
                } else if (composing.value.isNotEmpty()) {
                    composing.value = composing.value.dropLast(1)
                    Log.d(TAG, "composingAfter=${composing.value}")
                    Log.d(TAG, "setComposingText text=${composing.value}")
                    c.setComposingText(composing.value, 1)
                } else {
                    Log.d(TAG, "deleteSurroundingTextInCodePoints")
                    c.deleteSurroundingTextInCodePoints(1, 0)
                }
            }
            "↵" -> {
                if (composing.value.isNotEmpty()) {
                    val text = composing.value
                    if (text.all { it in 'A'..'Z' || it in 'a'..'z' }) {
                        Log.d(TAG, "enter commit raw latin composing=$text")
                        c.commitText(text, 1)
                        composing.value = ""
                        lastCommittedCandidate = null
                        Log.d(TAG, "composingAfter=${composing.value}")
                    } else {
                        val result = logCandidates(text)
                        val candidate = result.firstOrNull()
                        if (candidate != null) {
                            Log.d(TAG, "enter commit candidate=${candidate.text}")
                            commitCandidate(candidate.text)
                        } else {
                            Log.d(TAG, "enter commit raw composing=$text")
                            Log.d(TAG, "commitText text=$text")
                            c.commitText(text, 1)
                            composing.value = ""
                            Log.d(TAG, "composingAfter=${composing.value}")
                        }
                    }
                }

                if (!performEditorAction(c)) {
                    Log.d(TAG, "enter commit newline")
                    Log.d(TAG, "commitText text=\\n")
                    c.commitText("\n", 1)
                }
            }
            "空格" -> {
                if (composing.value.isNotEmpty()) {
                    val text = composing.value
                    val result = logCandidates(text)
                    val candidate = result.firstOrNull()
                    if (candidate != null) {
                        Log.d(TAG, "space commit candidate=${candidate.text}")
                        commitCandidate(candidate.text)
                    } else {
                        Log.d(TAG, "space commit raw composing=$text")
                        c.commitText(text, 1)
                        composing.value = ""
                        lastCommittedCandidate = null
                        Log.d(TAG, "composingAfter=${composing.value}")
                    }
                }
                Log.d(TAG, "commitText text= ")
                c.commitText(" ", 1)
                continuationContext.value = c.getTextBeforeCursor(256, 0)?.toString().orEmpty()
            }
            "，", "。", "、", "；", "：", "？", "！", "《", "》", "（", "）" -> {
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
                        lastCommittedCandidate = null
                    }
                }
                lastCommittedCandidate = null
                Log.d(TAG, "punctuation commitText text=$key")
                c.commitText(key, 1)
                val punctuationContext = c.getTextBeforeCursor(256, 0)?.toString().orEmpty()
                continuationContext.value = punctuationContext
                Log.d(TAG, "punctuation continuation prefix=${punctuationContext.takeLast(80)}")
                if (punctuationContext.isNotEmpty()) {
                    requestDeepSeekContinuation(c)
                    baiduSuggest.requestIfNeeded(punctuationContext, "") { baiduRevision.intValue++ }
                }
            }
            "~", "～" -> {
                // Symbols selected from the punctuation wheel are literal input,
                // not pinyin composing text.
                Log.d(TAG, "symbol commitText text=$key")
                if (composing.value.isNotEmpty()) {
                    val result = logCandidates(composing.value)
                    val candidate = result.firstOrNull()
                    if (candidate != null) {
                        commitCandidate(candidate.text)
                    } else {
                        c.commitText(composing.value, 1)
                        composing.value = ""
                        lastCommittedCandidate = null
                    }
                }
                c.commitText(key, 1)
                lastCommittedCandidate = null
                continuationContext.value = c.getTextBeforeCursor(256, 0)?.toString().orEmpty()
            }
            in "0123456789" -> {
                Log.d(TAG, "number wheel commitText text=$key")
                c.commitText(key, 1)
                lastCommittedCandidate = null
                continuationContext.value = c.getTextBeforeCursor(256, 0)?.toString().orEmpty()
            }
            else -> {
                composing.value += key.lowercase()
                Log.d(TAG, "composingAfter=${composing.value}")
                Log.d(TAG, "setComposingText text=${composing.value}")
                c.setComposingText(composing.value, 1)
                val query = composing.value
                val context = committedContext(c, query)
                continuationContext.value = context
                Log.d(TAG, "continuation context=${context.takeLast(80)}")
                val candidateStart = System.nanoTime()
                val candidates = logCandidates(query)
                ImeTelemetry.record("candidate_query", System.nanoTime() - candidateStart, candidates.size)
                if (deepSeekAi.isEnabled()) deepSeekAi.requestIfNeeded(context, query) { result ->
                    val updated = result.map { it.text }
                    if (continuationContext.value == context && composing.value == query) {
                        deepSeekCandidates.value = updated
                        Log.d(TAG, "DeepSeek UI candidates updated context='${context.takeLast(40)}' pinyin=$query count=${updated.size} top=${updated.take(8)}")
                    } else {
                        Log.d(TAG, "DeepSeek UI result stale context='${context.takeLast(40)}' pinyin=$query currentContext='${continuationContext.value.takeLast(40)}' currentPinyin=${composing.value}")
                    }
                }
                baiduSuggest.requestIfNeeded(context, query) { baiduRevision.intValue++ }
            }
        }
        ImeTelemetry.record("handle_key", System.nanoTime() - handleStart, key.length)
    }

    /**
     * Respect the action requested by the focused editor. Search boxes, for
     * example, expose IME_ACTION_SEARCH instead of expecting a literal newline.
     */
    private fun performEditorAction(c: android.view.inputmethod.InputConnection): Boolean {
        val info = currentEditorInfo ?: return false
        val action = info.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
        if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NONE ||
            action == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
            return false
        }
        Log.d(TAG, "enter performEditorAction action=$action imeOptions=${info.imeOptions}")
        return c.performEditorAction(action)
    }

    private fun committedContext(c: android.view.inputmethod.InputConnection, composingText: String): String {
        val before = c.getTextBeforeCursor(256, 0)?.toString().orEmpty()
        return if (composingText.isNotEmpty() && before.endsWith(composingText)) {
            before.dropLast(composingText.length).trim()
        } else {
            before.trim()
        }
    }

    private fun commitCandidate(text: String) {
        val commitStart = System.nanoTime()
        Log.d(TAG, "commitCandidate text=$text composingBefore=${composing.value}")
        deepSeekCandidates.value = emptyList()
        baiduRevision.intValue++
        Log.d(TAG, "stale Baidu/DeepSeek candidates cleared after selection")
        val c = currentInputConnection
        if (c == null) { Log.w(TAG, "commitCandidate no currentInputConnection text=$text"); return }
        val previousCandidate = lastCommittedCandidate
        inputHistoryStore.recordSelection(composing.value, text, previousCandidate)
        if (composing.value.isNotEmpty()) {
            userDictionaryRepository.incrementFrequency(composing.value, text)
        }
        historyRevision.intValue++
        lastCommittedCandidate = text
        Log.d(TAG, "history selection recorded text=$text previous=$previousCandidate")
        Log.d(TAG, "commitText text=$text")
        c.commitText(text, 1)
        composing.value = ""
        requestDeepSeekContinuation(c)
        requestBaiduContinuation(c)
        ImeTelemetry.record("commit_candidate", System.nanoTime() - commitStart, text.length)
        Log.d(TAG, "commitCandidate SUCCESS text=$text")
        Log.d(TAG, "composingAfter=${composing.value}")
    }

    private fun commitEmoji(emoji: String) {
        val commitStart = System.nanoTime()
        Log.d(TAG, "commitEmoji emoji=$emoji composingBefore=${composing.value}")
        deepSeekCandidates.value = emptyList()
        baiduRevision.intValue++
        val c = currentInputConnection
        if (c == null) {
            Log.w(TAG, "commitEmoji no currentInputConnection emoji=$emoji")
            return
        }
        if (composing.value.isNotEmpty()) {
            c.setComposingText("", 1)
            composing.value = ""
        }
        c.commitText(emoji, 1)
        continuationContext.value = c.getTextBeforeCursor(256, 0)?.toString().orEmpty()
        ImeTelemetry.record("commit_emoji", System.nanoTime() - commitStart, emoji.length)
        Log.d(TAG, "commitEmoji SUCCESS emoji=$emoji composingAfter=${composing.value}")
    }

    private fun logCandidates(pinyin: String): List<com.example.ime.core.Candidate> {
        Log.d(TAG, "candidate query pinyin=$pinyin")
        val rimeStart = System.nanoTime()
        val rime = imeEngine.candidates(pinyin)
        ImeTelemetry.record("candidate_rime_total", System.nanoTime() - rimeStart, rime.size)
        val userStart = System.nanoTime()
        val user = userDictionaryRepository.candidates(pinyin, 8)
        ImeTelemetry.record("candidate_user_dictionary", System.nanoTime() - userStart, user.size)
        val result = (user + rime).distinctBy { it.text }.take(9)
        Log.d(TAG, "candidate result count=${result.size} userDictionary=${user.size} rime=${rime.size}")
        Log.d(TAG, "candidate result top=${result.take(10).map { it.text }}")
        return result
    }

    private fun requestDeepSeekContinuation(c: android.view.inputmethod.InputConnection) {
        val context = c.getTextBeforeCursor(256, 0)?.toString().orEmpty().trim()
        if (context.isEmpty()) return
        continuationContext.value = context
        if (!deepSeekAi.isEnabled()) return
        Log.d(TAG, "continuation DeepSeek request context=" + context.takeLast(80))
        deepSeekAi.requestIfNeeded(context, "") { result ->
            if (continuationContext.value == context && composing.value.isEmpty()) {
                deepSeekCandidates.value = result.map { it.text }
                Log.d(TAG, "DeepSeek completion UI candidates updated context='${context.takeLast(40)}' count=${result.size} top=${result.take(8).map { it.text }}")
            } else {
                Log.d(TAG, "DeepSeek completion result stale context='${context.takeLast(40)}' currentContext='${continuationContext.value.takeLast(40)}' currentPinyin=${composing.value}")
            }
        }
    }

    private fun requestBaiduContinuation(c: android.view.inputmethod.InputConnection) {
        val context = c.getTextBeforeCursor(256, 0)?.toString().orEmpty().trim()
        if (context.isEmpty()) return
        continuationContext.value = context
        Log.d(TAG, "continuation request Baidu context=" + context.takeLast(80))
        baiduSuggest.requestIfNeeded(context, "") { baiduRevision.intValue++ }
    }

    private fun loadDictionary(): RimeDictionary {
        Log.d(TAG, "dictionary loading START mode=background-preload")
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
        dictionary.preloadAllAsync {
            Log.d(TAG, "dictionary preload COMPLETE")
            rimeRevision.intValue++
        }
        Log.d(TAG, "dictionary loading END mode=background-preload")
        return dictionary
    }
}
