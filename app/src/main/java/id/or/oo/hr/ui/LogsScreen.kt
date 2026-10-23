package id.or.oo.hr.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.or.oo.hr.HrApp

@Composable
fun LogsScreen(app: HrApp, modifier: Modifier) {
    val revision by app.gatewayLog.revision.collectAsStateWithLifecycle()
    val lines = remember(revision) { app.gatewayLog.snapshot() }
    val list = rememberLazyListState()
    LaunchedEffect(lines) {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        val following = info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        if (following && lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
    }
    if (lines.isEmpty()) {
        Text("The gateway has not written anything yet.", modifier = modifier.padding(12.dp))
        return
    }
    LazyColumn(state = list, modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        itemsIndexed(lines) { _, line ->
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
