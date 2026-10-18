package br.atlas.app.net

import br.atlas.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable data class ChatMessageDto(val role: String, val content: String)
@Serializable data class ChatRequestDto(val messages: List<ChatMessageDto>, val preferences: String? = null)
@Serializable data class ChatResponseDto(val reply: String, val model: String? = null)
@Serializable data class HealthDto(
    val status: String, val provider: String? = null, val model: String? = null, val aiKeyConfigured: Boolean = false,
)

class ApiException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { NOT_CONFIGURED, OFFLINE, UNAUTHORIZED, TIMEOUT, RATE_LIMIT, SERVER, UNKNOWN }
}

class ChatApi(private val settings: SettingsStore) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun chat(history: List<ChatMessageDto>): ChatResponseDto {
        val body = json.encodeToString(ChatRequestDto.serializer(),
            ChatRequestDto(history, settings.preferences.ifBlank { null }))
        val (_, text) = call("/chat") { post(body.toRequestBody("application/json".toMediaType())) }
        return runCatching { json.decodeFromString(ChatResponseDto.serializer(), text) }
            .getOrElse { throw ApiException("Resposta do backend em formato inesperado.", ApiException.Kind.SERVER) }
    }

    suspend fun health(): HealthDto {
        val (_, text) = call("/health") { get() }
        return runCatching { json.decodeFromString(HealthDto.serializer(), text) }
            .getOrElse { throw ApiException("Resposta do backend em formato inesperado.", ApiException.Kind.SERVER) }
    }

    private suspend fun call(path: String, configure: Request.Builder.() -> Request.Builder): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val base = settings.backendUrl.trimEnd('/')
            if (base.isBlank() || settings.token.isBlank())
                throw ApiException("Configure o endereço do backend e o token em Ajustes.", ApiException.Kind.NOT_CONFIGURED)
            val request = try {
                Request.Builder().url(base + path).header("Authorization", "Bearer ${settings.token}").configure().build()
            } catch (e: IllegalArgumentException) {
                throw ApiException("Endereço do backend inválido. Use o formato https://servidor.", ApiException.Kind.NOT_CONFIGURED)
            }
            val response = try {
                client.newCall(request).await()
            } catch (e: UnknownHostException) {
                throw ApiException("Sem conexão ou servidor não encontrado. Verifique a internet e o endereço.", ApiException.Kind.OFFLINE)
            } catch (e: ConnectException) {
                throw ApiException("Não foi possível conectar ao backend. Ele está em execução?", ApiException.Kind.OFFLINE)
            } catch (e: SocketTimeoutException) {
                throw ApiException("O servidor demorou demais para responder.", ApiException.Kind.TIMEOUT)
            } catch (e: IOException) {
                if (e.message == "Canceled") throw e
                throw ApiException("Falha de rede: ${e.message ?: "erro desconhecido"}", ApiException.Kind.OFFLINE)
            }
            response.use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) return@withContext r.code to text
                val serverMsg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw when (r.code) {
                    401 -> ApiException("Token inválido. Confira o token em Ajustes.", ApiException.Kind.UNAUTHORIZED)
                    429 -> ApiException(serverMsg ?: "Limite de uso atingido. Tente novamente em instantes.", ApiException.Kind.RATE_LIMIT)
                    else -> ApiException(serverMsg ?: "Erro do servidor (${r.code}).", ApiException.Kind.SERVER)
                }
            }
        }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}
