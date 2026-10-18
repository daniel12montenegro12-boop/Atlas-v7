package br.atlas.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.atlas.app.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AtlasApp).container
        setContent {
            val dark by container.settings.darkTheme.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val factory = remember { VmFactory(container) }
                val chatVm: ChatViewModel = viewModel(factory = factory)
                val diagVm: DiagnosticsViewModel = viewModel(factory = factory)
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val titles = listOf("Chat", "Diagnóstico", "Ajustes")
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            titles.forEachIndexed { i, t ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) }, alwaysShowLabel = true)
                            }
                        }
                    },
                ) { pad ->
                    Surface(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> ChatScreen(chatVm)
                            1 -> DiagnosticsScreen(diagVm)
                            else -> SettingsScreen(container)
                        }
                    }
                }
            }
        }
    }
}
