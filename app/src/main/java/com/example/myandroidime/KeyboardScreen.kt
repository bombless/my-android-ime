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
import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2

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
@Composable
private fun RowScope.SpaceKey(
    onSpace: (String) -> Unit,
    onCommitText: (String) -> Unit,
    weight: Float = 1f
) {
    var showPunctuation by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    val punctuation = remember { listOf("，", "。", "、", "；", "：", "？", "！", "《", "》", "（", "）") }
    val density = LocalDensity.current

    // 空格键在自身的局部尺寸（像素）
    var keyWidthPx by remember { mutableStateOf(0f) }
    var keyHeightPx by remember { mutableStateOf(0f) }

    // 气泡项尺寸（dp -> px）
    val itemSizeDp = 44.dp
    val itemSizePx = with(density) { itemSizeDp.toPx() }

    // 动态计算圆弧半径：基于空格键宽度的比例，且设定上下限
    // 这样在手机、折叠屏、横屏上都能自适应大小
    val arcRadiusPx = remember(keyWidthPx) {
        if (keyWidthPx > 0) {
            (keyWidthPx * 1.1f).coerceIn(180.dp.value * density.density, 320.dp.value * density.density)
        } else {
            220.dp.value * density.density
        }
    }

    // 圆心坐标（以空格键自身坐标系为基准：X为按键水平中点，Y为按键顶部）
    val arcCenterLocalX = keyWidthPx / 2f
    val arcCenterLocalY = 0f

    // 选中的角度/距离测算（在空格键本地坐标系内直接计算）
    fun selectByPosition(pointerX: Float, pointerY: Float) {
        val dx = pointerX - arcCenterLocalX
        val dy = pointerY - arcCenterLocalY

        // 手指离圆心太近时不误触，也可以直接测算最近的标点项
        var nearestIndex = selectedIndex
        var nearestDistance = Float.MAX_VALUE

        punctuation.indices.forEach { index ->
            val t = index.toFloat() / (punctuation.size - 1)
            // 角度从 -170° 到 -10°（预留两端边距，避免贴平）
            val angleRad = Math.toRadians(-170.0 + 160.0 * t)
            val targetX = arcCenterLocalX + arcRadiusPx * cos(angleRad).toFloat()
            val targetY = arcCenterLocalY + arcRadiusPx * sin(angleRad).toFloat()

            val dist = (pointerX - targetX) * (pointerX - targetX) + (pointerY - targetY) * (pointerY - targetY)
            if (dist < nearestDistance) {
                nearestDistance = dist
                nearestIndex = index
            }
        }
        selectedIndex = nearestIndex
    }

    Box(
        modifier = Modifier
            .weight(weight)
            .height(52.dp)
            .onGloballyPositioned { coordinates ->
                keyWidthPx = coordinates.size.width.toFloat()
                keyHeightPx = coordinates.size.height.toFloat()
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
                            showPunctuation = true
                            selectedIndex = punctuation.size / 2 // 长按默认高亮正中间

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

        // 浮层挂在空格键内部，使用绝对像素偏移渲染，不受限于外层写死的 690dp 宽度
        if (showPunctuation) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                punctuation.forEachIndexed { index, symbol ->
                    val t = index.toFloat() / (punctuation.size - 1)
                    val angleRad = Math.toRadians(-170.0 + 160.0 * t)
                    // 计算每个标点中心相对于空格键左上角的像素坐标
                    val centerX = arcCenterLocalX + arcRadiusPx * cos(angleRad).toFloat()
                    val centerY = arcCenterLocalY + arcRadiusPx * sin(angleRad).toFloat()

                    // 转为左上角像素并换算成 Dp
                    val offsetX = with(density) { (centerX - itemSizePx / 2f).toDp() }
                    val offsetY = with(density) { (centerY - itemSizePx / 2f).toDp() }

                    val selected = index == selectedIndex

                    Surface(
                        modifier = Modifier
                            .offset(x = offsetX, y = offsetY)
                            .size(itemSizeDp),
                        shape = MaterialTheme.shapes.medium,
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
