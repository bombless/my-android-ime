package com.example.myandroidime
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable fun KeyboardScreen(composing: String, rimeCandidates: List<String>, baiduCandidates: List<String>, deepSeekCandidates: List<String>, onKey: (String) -> Unit, onCandidate: (String) -> Unit) {
    LaunchedEffect(composing, rimeCandidates, baiduCandidates, deepSeekCandidates) { android.util.Log.d("MyAndroidIME", "KeyboardScreen composing=$composing rime=${rimeCandidates.take(5)} baidu=${baiduCandidates.take(5)} deepseek=${deepSeekCandidates.take(5)}") }
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CandidateSourceRow("小狼毫", rimeCandidates, onCandidate)
            CandidateSourceRow("百度", baiduCandidates, onCandidate)
            CandidateSourceRow("DeepSeek", deepSeekCandidates, onCandidate)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(composing, modifier = Modifier.fillMaxWidth(), maxLines = 1)
            }

            listOf("QWERTYUIOP","ASDFGHJKL").forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) { row.forEach { Key(it.toString(), onKey) } }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                "ZXCVBNM".forEach { Key(it.toString(), onKey) }
                Key("⌫", onKey, 1.5f)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                SpaceKey(onKey, onCandidate, 3f)
                Key("↵",onKey,1.5f)
            }
        }
    }
}

@Composable private fun CandidateSourceRow(label: String, candidates: List<String>, onCandidate: (String) -> Unit) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().height(38.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(58.dp), maxLines = 1)
        if (candidates.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxHeight())
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                candidates.take(8).forEach { text ->
                    Button(
                        onClick = { onCandidate(text) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 64.dp)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun RowScope.SpaceKey(
    onSpace: (String) -> Unit,
    onCommitText: (String) -> Unit,
    weight: Float = 1f
) {
    var showPunctuation by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(1) }
    val punctuation = listOf("，", "。", "、", "；", "：", "？", "！", "《", "》", "（", "）")
    val arcRadius = 178f
    val arcCenterX = 24f
    val arcCenterY = 210f

    fun selectByPosition(x: Float, y: Float) {
        val dx = x - arcCenterX
        val dy = y - arcCenterY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance < 80f) return

        // Upper-right quarter arc: -90° .. 0° in screen coordinates.
        val angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
        val normalized = ((angle + 90f).coerceIn(0f, 90f)) / 90f
        selectedIndex = (normalized * (punctuation.lastIndex)).toInt()
            .coerceIn(0, punctuation.lastIndex)
    }

    Box(
        modifier = Modifier
            .weight(weight)
            .height(52.dp)
    ) {
        Button(
            onClick = {},
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress == null) {
                            onSpace("空格")
                        } else {
                            showPunctuation = true
                            selectedIndex = 1

                            drag(down.id) { change ->
                                selectByPosition(change.position.x, change.position.y)
                                change.consume()
                            }

                            onCommitText(punctuation[selectedIndex])
                            showPunctuation = false
                        }
                    }
                },
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("空格", maxLines = 1)
            }
        }

        if (showPunctuation) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-8).dp, y = (-42).dp)
                    .size(250.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    // A large quarter-circle fan gives each punctuation mark much
                    // more physical room for the thumb to target.
                    punctuation.forEachIndexed { index, symbol ->
                        val t = index.toFloat() / punctuation.lastIndex
                        val angle = Math.toRadians((-90.0 + 90.0 * t))
                        val x = arcCenterX + arcRadius * kotlin.math.cos(angle).toFloat()
                        val y = arcCenterY + arcRadius * kotlin.math.sin(angle).toFloat()
                        val selected = index == selectedIndex

                        Surface(
                            modifier = Modifier
                                .offset(
                                    x = (x - 24f - 25f).dp,
                                    y = (y - 210f - 25f).dp
                                )
                                .size(50.dp),
                            shape = MaterialTheme.shapes.large,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (selected) 6.dp else 1.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    symbol,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun RowScope.Key(label:String,onClick:(String)->Unit,weight:Float=1f) {
    Button(
        onClick = { onClick(label) },
        modifier = Modifier
            .weight(weight)
            .height(52.dp)
            .takeIf { label != "⌫" }
            ?: Modifier
                .weight(weight)
                .height(52.dp)
                .pointerInput(label) {
                    detectTapGestures(
                        onPress = {
                            coroutineScope {
                                val repeatJob = launch {
                                    delay(400)
                                    while (true) {
                                        onClick(label)
                                        delay(60)
                                    }
                                }
                                tryAwaitRelease()
                                repeatJob.cancel()
                            }
                        }
                    )
                },
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, maxLines = 1)
        }
    }
}


