package br.atlas.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.atlas.app.AppContainer
import br.atlas.app.data.ConversationEntity
import br.atlas.app.data.MessageEntity
import br.atlas.app.net.ApiException
import br.atlas.app.net.ChatMessageDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val currentId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val canRetry: Boolean = false,
)

class ChatViewModel(c: AppContainer) : ViewModel() {
    private val dao = c.db.dao()
    private val api = c.api
    private val currentId = MutableStateFlow<Long?>(null)
    private val sending = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val messagesFlow = currentId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeMessages(id)
    }

    val state: StateFlow<ChatUiState> = combine(
        dao.observeConversations(), currentId, messagesFlow, sending, error,
    ) { convs, id, msgs, s, e ->
        ChatUiState(convs, id, msgs, s, e, canRetry = e != null && msgs.lastOrNull()?.role == "user")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    init { viewModelScope.launch { currentId.value = dao.latestConversationId() } }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || sending.value) return
        job = viewModelScope.launch {
            val now = System.currentTimeMillis()
            var id = currentId.value
            if (id == null) {
                id = dao.insertConversation(ConversationEntity(title = t.take(40), createdAt = now, updatedAt = now))
                currentId.value = id
            }
            dao.insertMessage(MessageEntity(conversationId = id, role = "user", content = t, createdAt = now))
            dao.touch(id, now)
            requestReply(id)
        }
    }

    fun retry() {
        val id = currentId.value ?: return
        if (sending.value) return
        job = viewModelScope.launch { requestReply(id) }
    }

    fun stop() {
        if (!sending.value) return
        job?.cancel()
        error.value = "Geração interrompida por você."
    }

    fun newConversation() { if (!sending.value) { currentId.value = null; error.value = null } }
    fun open(id: Long) { if (!sending.value) { currentId.value = id; error.value = null } }
    fun delete(id: Long) {
        viewModelScope.launch {
            dao.deleteConversation(id)
            if (currentId.value == id) currentId.value = dao.latestConversationId()
        }
    }

    private suspend fun requestReply(id: Long) {
        sending.value = true
        error.value = null
        try {
            val history = dao.messagesOnce(id).takeLast(30).map { ChatMessageDto(it.role, it.content) }
            val reply = api.chat(history)
            val now = System.currentTimeMillis()
            dao.insertMessage(MessageEntity(conversationId = id, role = "assistant", content = reply.reply, createdAt = now))
            dao.touch(id, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            error.value = e.message
        } catch (e: Exception) {
            error.value = "Erro inesperado: ${e.message ?: e::class.simpleName}"
        } finally {
            sending.value = false
        }
    }
}
