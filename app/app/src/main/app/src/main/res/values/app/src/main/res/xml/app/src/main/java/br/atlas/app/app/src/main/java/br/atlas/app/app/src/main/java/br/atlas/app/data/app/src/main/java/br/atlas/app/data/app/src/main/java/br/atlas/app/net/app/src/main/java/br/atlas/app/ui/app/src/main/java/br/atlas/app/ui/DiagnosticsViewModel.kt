package br.atlas.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.atlas.app.AppContainer
import br.atlas.app.data.ConversationEntity
import br.atlas.app.net.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class DiagStatus(val label: String) {
    OK("OK"), FAIL("FALHA"), WARN("ATENÇÃO"), RUNNING("VERIFICANDO…"), NOT_IMPLEMENTED("NÃO IMPLEMENTADO")
}

data class DiagItem(val name: String, val status: DiagStatus, val detail: String)

class DiagnosticsViewModel(private val c: AppContainer) : ViewModel() {
    private val _items = MutableStateFlow<List<DiagItem>>(emptyList())
    val items: StateFlow<List<DiagItem>> = _items

    private val pending = listOf(
        "Voz (reconhecimento e síntese)", "Palavra de ativação \"Ei, Atlas\"", "Serviço em segundo plano e janela flutuante",
        "Ferramentas Android (abrir apps, alarmes, lanterna)", "Rotinas e lembretes", "Memória da IA",
        "Aba Escolar", "Atlas Financeiro", "Imagens e câmera", "E-mail / WhatsApp / SMS",
        "Cofre de senhas", "Atlas Studio", "Autenticação por PIN/biometria", "Criptografia do banco (SQLCipher)",
    )

    init { run() }

    fun run() {
        viewModelScope.launch {
            _items.value = listOf(DiagItem("Verificação", DiagStatus.RUNNING, "Executando testes…"))
            val out = mutableListOf<DiagItem>()
            out += checkNetwork()
            out += checkDatabase()
            out += DiagItem(
                "Armazenamento seguro de ajustes",
                if (c.settings.secureStorageOk) DiagStatus.OK else DiagStatus.FAIL,
                if (c.settings.secureStorageOk) "EncryptedSharedPreferences ativo (Android Keystore)."
                else "Indisponível neste aparelho: URL e token não podem ser salvos.",
            )
            out += DiagItem(
                "Configuração do backend",
                if (c.settings.backendUrl.isNotBlank() && c.settings.token.isNotBlank()) DiagStatus.OK else DiagStatus.WARN,
                if (c.settings.backendUrl.isNotBlank() && c.settings.token.isNotBlank()) "Endereço e token preenchidos (validade testada abaixo)."
                else "Endereço e/ou token não preenchidos em Ajustes.",
            )
            out += checkBackend()
            pending.forEach { out += DiagItem(it, DiagStatus.NOT_IMPLEMENTED, "Ainda não existe no código.") }
            _items.value = out
        }
    }

    private fun checkNetwork(): DiagItem {
        val cm = c.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val ok = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return DiagItem("Conexão com a internet", if (ok) DiagStatus.OK else DiagStatus.FAIL,
            if (ok) "Rede ativa com acesso validado." else "Sem rede validada. O chat com IA não funcionará.")
    }

    private suspend fun checkDatabase(): DiagItem = try {
        val dao = c.db.dao()
        val now = System.currentTimeMillis()
        val id = dao.insertConversation(ConversationEntity(title = "__diagnostico__", createdAt = now, updatedAt = now))
        dao.deleteConversation(id)
        val n = dao.countConversations()
        DiagItem("Banco de dados (Room)", DiagStatus.OK, "Escrita e leitura de teste concluídas. Conversas salvas: $n. Banco sem criptografia (pendente).")
    } catch (e: Exception) {
        DiagItem("Banco de dados (Room)", DiagStatus.FAIL, "Falha: ${e.message}")
    }

    private suspend fun checkBackend(): List<DiagItem> = try {
        val h = c.api.health()
        listOf(
            DiagItem("Backend", DiagStatus.OK, "Respondeu e aceitou o token."),
            DiagItem("IA (${h.provider ?: "?"}, modelo ${h.model ?: "?"})",
                if (h.aiKeyConfigured) DiagStatus.OK else DiagStatus.FAIL,
                if (h.aiKeyConfigured) "Chave da API presente no servidor. Uma resposta real só é confirmada ao enviar uma mensagem no chat."
                else "O backend está sem ANTHROPIC_API_KEY."),
        )
    } catch (e: ApiException) {
        val st = if (e.kind == ApiException.Kind.NOT_CONFIGURED) DiagStatus.WARN else DiagStatus.FAIL
        listOf(DiagItem("Backend", st, e.message ?: "Erro"), DiagItem("IA", DiagStatus.WARN, "Não verificável sem o backend."))
    } catch (e: Exception) {
        listOf(DiagItem("Backend", DiagStatus.FAIL, "Erro inesperado: ${e.message}"), DiagItem("IA", DiagStatus.WARN, "Não verificável sem o backend."))
    }
}
