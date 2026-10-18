package br.atlas.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import br.atlas.app.data.AtlasDatabase
import br.atlas.app.data.SettingsStore
import br.atlas.app.net.ChatApi
import br.atlas.app.ui.ChatViewModel
import br.atlas.app.ui.DiagnosticsViewModel

class AppContainer(ctx: Context) {
    val appContext: Context = ctx.applicationContext
    val settings = SettingsStore(appContext)
    // ATENÇÃO: banco ainda NÃO criptografado (SQLCipher está pendente; ver README).
    val db: AtlasDatabase = Room.databaseBuilder(appContext, AtlasDatabase::class.java, "atlas.db").build()
    val api = ChatApi(settings)
}

class AtlasApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}

class VmFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(c) as T
        modelClass.isAssignableFrom(DiagnosticsViewModel::class.java) -> DiagnosticsViewModel(c) as T
        else -> throw IllegalArgumentException("ViewModel desconhecido: ${modelClass.name}")
    }
}
