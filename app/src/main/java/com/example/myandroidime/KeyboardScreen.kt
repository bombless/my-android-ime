package com.example.myandroidime
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
@Composable fun KeyboardScreen(composing: String, candidates: List<String>, onKey: (String) -> Unit, onCandidate: (String) -> Unit) {
    LaunchedEffect(composing, candidates) { android.util.Log.d("MyAndroidIME", "KeyboardScreen composing=$composing candidates=${candidates.take(10)}") }
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Always reserve a dedicated candidate strip so suggestions have a
            // stable place above the keyboard instead of competing with keys.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val visibleCandidates = candidates.take(5)
                if (visibleCandidates.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxHeight())
                } else {
                    visibleCandidates.forEach { text ->
                        Button(
                            onClick = { onCandidate(text) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text, maxLines = 1)
                            }
                        }
                    }
                }
            }

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
                Key("空格",onKey,3f)
                Key("↵",onKey,1.5f)
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


