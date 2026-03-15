package dev.hapax.watch.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme

@Composable
fun HapaxWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        content = content,
    )
}
