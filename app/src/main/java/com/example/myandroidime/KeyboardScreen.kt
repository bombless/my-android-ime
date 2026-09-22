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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.waitForUpOrCancellation

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2

import androidx.compose.ui.platform.LocalConfiguration
@Composable
fun KeyboardScreen(
    composing: String,
    rimeCandidates: List<String>,
    baiduCandidates: List<String>,
    deepSeekCandidates: List<String>,
    onKey: (String) -> Unit,
    onCandidate: (String) -> Unit
) {
    // 控制标点浮层的全局状态
    var showPunctuation by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    val punctuation = remember { listOf("，", "。", "、", "；", "：", "？", "！", "《", "》", "（", "）") }

    // 记录键盘整体在屏幕上的坐标及高度，用于锚定左下角
    var keyboardWindowPos by remember { mutableStateOf(Offset.Zero) }
    var keyboardHeightPx by remember { mutableStateOf(0f) }

    val density = LocalDensity.current

    // 圆盘与气泡尺寸定义
    val itemSizeDp = 44.dp
    val itemSizePx = with(density) { itemSizeDp.toPx() }
    val circleRadiusDp = 110.dp // 完整圆形的半径，足够大且不会超出键盘高度
    val circleRadiusPx = with(density) { circleRadiusDp.toPx() }

    // 圆心位置：贴在左下角（距离左边和底边各保留 itemRadius + 12dp，确保整圈都在屏幕内）
    val paddingPx = with(density) { 12.dp.toPx() }
    val centerLocalX = circleRadiusPx + itemSizePx / 2f + paddingPx
    val centerLocalY = keyboardHeightPx - (circleRadiusPx + itemSizePx / 2f + paddingPx)

    // 全局触摸坐标转换为选中项
    fun updateSelection(windowTouchPos: Offset) {
        // 转为键盘内的本地坐标
        val localX = windowTouchPos.x - keyboardWindowPos.x
        val localY = windowTouchPos.y - keyboardWindowPos.y

        val dx = localX - centerLocalX
        val dy = localY - centerLocalY

        // 通过极坐标反切角求对应索引 (atan2 输出 -π ~ π)
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        if (angle < 0) angle += 360.0 // 转为 0° ~ 360°

        // 11个符号均匀切分 360 度
        val step = 360.0 / punctuation.size
        // 偏置半个 step，使得角度区间正对中心
        val index = (((angle + step / 2) % 360) / step).toInt().coerceIn(0, punctuation.lastIndex)
        selectedIndex = index
    }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    keyboardWindowPos = coordinates.positionInWindow()
                    keyboardHeightPx = coordinates.size.height.toFloat()
                }
        ) {
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
                    modifier = Modifier.fillMaxWidth().height(30.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(composing, modifier = Modifier.fillMaxWidth(), maxLines = 1)
                }

                listOf("QWERTYUIOP", "ASDFGHJKL").forEach { row ->
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
                    SpaceKey(
                        onSpace = onKey,
                        onCommitText = onKey,
                        weight = 3f,
                        onLongPressStart = { windowTouchPos ->
                            showPunctuation = true
                            updateSelection(windowTouchPos)
                        },
                        onDrag = { windowTouchPos ->
                            updateSelection(windowTouchPos)
                        },
                        onRelease = {
                            if (showPunctuation) {
                                onKey(punctuation[selectedIndex])
                                showPunctuation = false
                            }
                        }
                    )
                    Key("↵", onKey, 1.5f)
                }
            }

            // 贴在左下方的完整圆形面板
            if (showPunctuation) {
                Box(modifier = Modifier.fillMaxSize()) {
                    punctuation.forEachIndexed { index, symbol ->
                        val step = 360.0 / punctuation.size
                        val angleRad = Math.toRadians(index * step)

                        val itemCenterX = centerLocalX + circleRadiusPx * cos(angleRad).toFloat()
                        val itemCenterY = centerLocalY + circleRadiusPx * sin(angleRad).toFloat()

                        val offsetX = with(density) { (itemCenterX - itemSizePx / 2f).toDp() }
                        val offsetY = with(density) { (itemCenterY - itemSizePx / 2f).toDp() }

                        val selected = index == selectedIndex

                        Surface(
                            modifier = Modifier
                                .offset(x = offsetX, y = offsetY)
                                .size(itemSizeDp),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            tonalElevation = if (selected) 8.dp else 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = symbol,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
private fun RowScope.SpaceKey(
    onSpace: (String) -> Unit,
    onCommitText: (String) -> Unit,
    weight: Float = 1f,
    onLongPressStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onRelease: () -> Unit
) {
    var keyWindowPos by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .weight(weight)
            .height(52.dp)
            .onGloballyPositioned { coordinates ->
                keyWindowPos = coordinates.positionInWindow()
            }
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
                            // 换算为空格键当前手指在 Window 的绝对位置并向上传递
                            onLongPressStart(keyWindowPos + down.position)

                            drag(down.id) { change ->
                                onDrag(keyWindowPos + change.position)
                                change.consume()
                            }

                            onRelease()
                        }
                    }
                },
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("空格", maxLines = 1)
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

@Composable private fun RowScope.Key(label: String, onClick: (String) -> Unit, weight: Float = 1f) {
    if (label == "⌫") {
        Button(
            onClick = {}, // 点击逻辑完全交给下面的手势处理
            modifier = Modifier
                .weight(weight)
                .height(52.dp)
                .pointerInput(label) {
                    coroutineScope { // 外层是普通协程作用域，launch/delay 都能用
                        while (true) {
                            // 只在受限作用域内做指针相关的挂起调用
                            awaitPointerEventScope {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume() // 提前消费掉，防止 Button 内置 clickable 抢事件
                            }

                            onClick(label) // 按下立即删一次

                            val repeatJob = launch {
                                delay(350)
                                var repeatCount = 0
                                while (true) {
                                    onClick(label)
                                    repeatCount++
                                    val interval = when {
                                        repeatCount < 6 -> 120L
                                        repeatCount < 14 -> 80L
                                        else -> 50L
                                    }
                                    delay(interval)
                                }
                            }

                            awaitPointerEventScope {
                                waitForUpOrCancellation() // 等待抬起或手势被取消
                            }
                            repeatJob.cancel()
                        }
                    }
                },
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1)
            }
        }
    } else {
        Button(
            onClick = { onClick(label) },
            modifier = Modifier.weight(weight).height(52.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1)
            }
        }
    }
}
