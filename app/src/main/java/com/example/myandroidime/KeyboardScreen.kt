package com.example.myandroidime
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@Composable fun KeyboardScreen(onKey: (String) -> Unit) {
    MaterialTheme {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("QWERTYUIOP","ASDFGHJKL","ZXCVBNM").forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) { row.forEach { Key(it.toString(), onKey) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) { Key("⌫",onKey,1.5f); Key("空格",onKey,3f); Key("↵",onKey,1.5f) }
        }
    }
}
@Composable private fun RowScope.Key(label:String,onClick:(String)->Unit,weight:Float=1f) {
    Button(onClick={onClick(label)},modifier=Modifier.weight(weight).height(52.dp)){Text(label)}
}


