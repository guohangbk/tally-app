package io.github.guohangbk.tally.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.guohangbk.tally.TallyApplication

val LocalAppGraph = staticCompositionLocalOf<AppGraph?> { null }

@Composable
fun AppGraphProvider(graph: AppGraph, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppGraph provides graph, content = content)
}

/** ViewModel 与仓储都从这里取依赖图。 */
@Composable
fun rememberAppGraph(): AppGraph {
    val context = LocalContext.current
    return LocalAppGraph.current ?: remember { TallyApplication.from(context).graph }
}
