package id.or.oo.hr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF1A1A2E)
private val Gold = Color(0xFFE9B44C)

@Composable
fun HrTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Gold, onPrimary = Navy, background = Navy, surface = Navy)
    } else {
        lightColorScheme(primary = Navy, onPrimary = Color.White)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
