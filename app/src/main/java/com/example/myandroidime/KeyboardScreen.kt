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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background


@Composable private fun CandidateSourceRow(label: String, candidates: List<String>, onCandidate: (String) -> Unit) {

    val scrollState = rememberScrollState()

    Row(

        modifier = Modifier.fillMaxWidth().height(38.dp),

        horizontalArrangement = Arrangement.spacedBy(3.dp),

        verticalAlignment = Alignment.CenterVertically

    ) {

        AutoResizeText(
            text = label,
            modifier = Modifier.width(58.dp),
            maxFontSize = 14.sp,
            minFontSize = 9.sp
        )

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

                        AutoResizeText(
                            text = text,
                            modifier = Modifier.fillMaxWidth(),
                            maxFontSize = 14.sp,
                            minFontSize = 9.sp,
                            textAlign = TextAlign.Center
                        )

                    }

                }

            }

        }

    }

}


@Composable
private fun AutoResizeText(
    text: String,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit = 14.sp,
    minFontSize: TextUnit = 9.sp,
    textAlign: TextAlign = TextAlign.Start
) {
    var fontSize by remember(text, maxFontSize, minFontSize) { mutableStateOf(maxFontSize) }

    Text(
        text = text,
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        fontSize = fontSize,
        onTextLayout = { result ->
            if (result.didOverflowWidth && fontSize > minFontSize) {
                fontSize = (fontSize.value - 1f).coerceAtLeast(minFontSize.value).sp
            }
        }
    )
}

@Composable
fun KeyboardScreen(
    composing: String,
    rimeCandidates: List<String>,
    userDictionaryCandidates: List<String>,
    baiduCandidates: List<String>,
    deepSeekCandidates: List<String>,
    showDeepSeek: Boolean,
    historyCandidates: List<HistoryCandidate>,
    onKey: (String) -> Unit,
    onCandidate: (String) -> Unit
) {
    var showPunctuation by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    val punctuation = remember { listOf("，", "。", "、", "；", "：", "？", "！", "《", "》", "（", "）") }
    var showNumbers by remember { mutableStateOf(false) }
    var selectedNumberIndex by remember { mutableStateOf(0) }
    val numbers = remember { listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9") }

    var showLetters by remember { mutableStateOf(false) }
    var selectedLetterIndex by remember { mutableStateOf(0) }
    var letterWheelCenter by remember { mutableStateOf(Offset.Zero) }
    val letters = remember { ('A'..'Z').map { it.toString() } }

    var keyboardWindowPos by remember { mutableStateOf(Offset.Zero) }
    var keyboardHeightPx by remember { mutableStateOf(0f) }
    var keyboardWidthPx by remember { mutableStateOf(0f) }

    val density = LocalDensity.current

    // 获取系统底栏/侧栏安全距离
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val bottomInsetPx = with(density) { insets.calculateBottomPadding().toPx() }
    val leftInsetPx = with(density) { insets.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx() }
    val rightInsetPx = with(density) { insets.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx() }

    // 标点项与圆盘几何参数
    val itemSizeDp = 44.dp
    val itemSizePx = with(density) { itemSizeDp.toPx() }
    // 键盘高度通常在 220dp ~ 280dp 之间，圆盘半径取 96dp 既不会超出上方候选区，又有充裕间距
    val circleRadiusDp = 96.dp
    val circleRadiusPx = with(density) { circleRadiusDp.toPx() }

    // 圆心位置：严格靠紧安全区内侧，并保留键盘内部边距 (6.dp 水平, 4.dp 垂直)
    val innerMarginXPx = with(density) { 6.dp.toPx() }
    val innermarginYPx = with(density) { 4.dp.toPx() }

    // 标点轮盘固定在左下角，数字轮盘固定在右下角
    val centerLocalX = leftInsetPx + innerMarginXPx + circleRadiusPx + itemSizePx / 2f
    val centerLocalXRight = keyboardWidthPx - rightInsetPx - innerMarginXPx - circleRadiusPx - itemSizePx / 2f
    val centerLocalY = keyboardHeightPx - (bottomInsetPx + innermarginYPx + circleRadiusPx + itemSizePx / 2f)

    // 滑动手势方向映射选中的符号
    fun updateSelection(windowTouchPos: Offset) {
        val localX = windowTouchPos.x - keyboardWindowPos.x
        val localY = windowTouchPos.y - keyboardWindowPos.y

        val dx = localX - centerLocalX
        val dy = localY - centerLocalY

        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        if (angle < 0) angle += 360.0

        val step = 360.0 / punctuation.size
        val index = (((angle + step / 2) % 360) / step).toInt().coerceIn(0, punctuation.lastIndex)
        selectedIndex = index
    }

    fun updateNumberSelection(windowTouchPos: Offset) {
        val localX = windowTouchPos.x - keyboardWindowPos.x
        val localY = windowTouchPos.y - keyboardWindowPos.y
        val dx = localX - centerLocalXRight
        val dy = localY - centerLocalY
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        if (angle < 0) angle += 360.0
        val step = 360.0 / numbers.size
        selectedNumberIndex = (((angle + step / 2) % 360) / step).toInt().coerceIn(0, numbers.lastIndex)
    }

    fun updateLetterSelection(windowTouchPos: Offset) {
        val localX = windowTouchPos.x - keyboardWindowPos.x
        val localY = windowTouchPos.y - keyboardWindowPos.y
        val dx = localX - letterWheelCenter.x
        val dy = localY - letterWheelCenter.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)

        // 内圈 A-M，外圈 N-Z；中心附近保持当前选择，避免刚长按时误切换。
        val innerRadiusPx = with(density) { 78.dp.toPx() }
        val outerRadiusPx = with(density) { 142.dp.toPx() }
        if (distance < innerRadiusPx * 0.45f) return

        val ringOffset = if (distance <= (innerRadiusPx + outerRadiusPx) / 2f) 0 else 13
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        if (angle < 0) angle += 360.0
        val step = 360.0 / 13.0
        val slot = (((angle + step / 2) % 360) / step).toInt().coerceIn(0, 12)
        selectedLetterIndex = ringOffset + slot
    }

    MaterialTheme {
        // 外层 Box 只由键盘内容确定尺寸，绝不会被浮层撑开
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    keyboardWindowPos = coordinates.positionInWindow()
                    keyboardHeightPx = coordinates.size.height.toFloat()
                    keyboardWidthPx = coordinates.size.width.toFloat()
                }
        ) {
            // 基础键盘主体
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CandidateSourceRow("小狼毫", rimeCandidates, onCandidate)
                CandidateSourceRow("用户词库", userDictionaryCandidates, onCandidate)
                CandidateSourceRow("百度", baiduCandidates, onCandidate)
                if (showDeepSeek) {
                    CandidateSourceRow("DeepSeek", deepSeekCandidates, onCandidate)
                }
                CandidateSourceRow(
                    "历史输入",
                    historyCandidates.map { "${it.text} (${it.count})" },
                    onCandidate = { displayed ->
                        historyCandidates.firstOrNull { "${it.text} (${it.count})" == displayed }?.let { onCandidate(it.text) }
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(composing, modifier = Modifier.fillMaxWidth(), maxLines = 1)
                }

                listOf("QWERTYUIOP", "ASDFGHJKL").forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        row.forEach { letter ->
                            LetterKey(
                                label = letter.toString(),
                                onClick = onKey,
                                onLongPressStart = { pos ->
                                    letterWheelCenter = Offset(
                                        keyboardWidthPx / 2f,
                                        pos.y - keyboardWindowPos.y
                                    )
                                    selectedLetterIndex = letters.indexOf(letter.toString()).coerceAtLeast(0)
                                    showLetters = true
                                    updateLetterSelection(pos)
                                },
                                onDrag = ::updateLetterSelection,
                                onRelease = {
                                    if (showLetters) {
                                        onKey(letters[selectedLetterIndex])
                                        showLetters = false
                                    }
                                }
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    "ZXCVBNM".forEach { letter ->
                        LetterKey(
                            label = letter.toString(),
                            onClick = onKey,
                            onLongPressStart = { pos ->
                                letterWheelCenter = Offset(
                                    keyboardWidthPx / 2f,
                                    pos.y - keyboardWindowPos.y
                                )
                                selectedLetterIndex = letters.indexOf(letter.toString()).coerceAtLeast(0)
                                showLetters = true
                                updateLetterSelection(pos)
                            },
                            onDrag = ::updateLetterSelection,
                            onRelease = {
                                if (showLetters) {
                                    onKey(letters[selectedLetterIndex])
                                    showLetters = false
                                }
                            }
                        )
                    }
                    Key("⌫", onKey, 1.5f)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    SpaceKey(
                        onSpace = onKey,
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
                    EnterKey(
                        onEnter = onKey,
                        weight = 1.5f,
                        onLongPressStart = { pos -> showNumbers = true; updateNumberSelection(pos) },
                        onDrag = ::updateNumberSelection,
                        onRelease = {
                            if (showNumbers) {
                                onKey(numbers[selectedNumberIndex])
                                showNumbers = false
                            }
                        }
                    )
                }
            }

            // 浮层：使用 matchParentSize() 确保绝对重叠在键盘上，完全不撑大容器
            if (showLetters) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                ) {
                    val innerRadiusPx = with(density) { 78.dp.toPx() }
                    val outerRadiusPx = with(density) { 142.dp.toPx() }
                    val letterStep = 360.0 / 13.0

                    letters.forEachIndexed { index, letter ->
                        val isInner = index < 13
                        val radius = if (isInner) innerRadiusPx else outerRadiusPx
                        val slot = if (isInner) index else index - 13
                        val angleRad = Math.toRadians(slot * letterStep)

                        val itemCenterX = letterWheelCenter.x + radius * cos(angleRad).toFloat()
                        val itemCenterY = letterWheelCenter.y + radius * sin(angleRad).toFloat()
                        val offsetX = with(density) { (itemCenterX - itemSizePx / 2f).toDp() }
                        val offsetY = with(density) { (itemCenterY - itemSizePx / 2f).toDp() }
                        val selected = index == selectedLetterIndex

                        Surface(
                            modifier = Modifier
                                .offset(x = offsetX, y = offsetY)
                                .size(itemSizeDp),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (selected) 8.dp else 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = letter,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }
            if (showPunctuation) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.25f)) // 轻微半透明蒙层弱化键盘干扰
                ) {
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
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (selected) 8.dp else 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = symbol,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }
            if (showNumbers) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                ) {
                    numbers.forEachIndexed { index, number ->
                        val step = 360.0 / numbers.size
                        val angleRad = Math.toRadians(index * step)
                        val itemCenterX = centerLocalXRight + circleRadiusPx * cos(angleRad).toFloat()
                        val itemCenterY = centerLocalY + circleRadiusPx * sin(angleRad).toFloat()
                        val offsetX = with(density) { (itemCenterX - itemSizePx / 2f).toDp() }
                        val offsetY = with(density) { (itemCenterY - itemSizePx / 2f).toDp() }
                        val selected = index == selectedNumberIndex
                        Surface(
                            modifier = Modifier.offset(x = offsetX, y = offsetY).size(itemSizeDp),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            tonalElevation = if (selected) 8.dp else 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = number,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
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

@Composable
private fun RowScope.EnterKey(
    onEnter: (String) -> Unit,
    weight: Float,
    onLongPressStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onRelease: () -> Unit
) {
    var keyWindowPos by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .weight(weight)
            .height(52.dp)
            .onGloballyPositioned { coordinates -> keyWindowPos = coordinates.positionInWindow() }
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
                            onEnter("↵")
                        } else {
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("↵", maxLines = 1) }
        }
    }
}

@Composable
private fun RowScope.LetterKey(
    label: String,
    onClick: (String) -> Unit,
    onLongPressStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onRelease: () -> Unit
) {
    var keyWindowPos by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .weight(1f)
            .height(52.dp)
            .onGloballyPositioned { coordinates ->
                keyWindowPos = coordinates.positionInWindow()
            }
    ) {
        Button(
            onClick = {},
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(label) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress == null) {
                            onClick(label)
                        } else {
                            // 以被长按字母键的中心作为双环轮盘圆心。
                            val center = keyWindowPos + Offset(size.width / 2f, size.height / 2f)
                            onLongPressStart(center)

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
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable private fun RowScope.Key(label: String, onClick: (String) -> Unit, weight: Float = 1f) {
    if (label == "⌫") {
        var backspacePressed by remember { mutableStateOf(false) }

        LaunchedEffect(backspacePressed) {
            if (!backspacePressed) return@LaunchedEffect

            onClick(label)
            delay(350L)

            var repeatCount = 0
            while (backspacePressed) {
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

        Button(
            onClick = {},
            modifier = Modifier
                .weight(weight)
                .height(52.dp)
                .pointerInput(label) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        backspacePressed = true
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            backspacePressed = false
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
