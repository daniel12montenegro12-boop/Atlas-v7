package br.atlas.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.atlas.app.AppContainer
import br.atlas.app.net.ApiException
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(s.messages.size) { if (s.messages.isNotEmpty()) listState.animateScrollToItem(s.messages.lastIndex) }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showHistory = true }) { Text("Histórico (${s.conversations.size})") }
            TextButton(onClick = { vm.newConversation() }, enabled = !s.sending) { Text("Nova conversa") }
        }
        if (s.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Nenhuma mensagem nesta conversa. Configure o backend em Ajustes e envie a primeira mensagem.")
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.messages, key = { it.id }) { m ->
                    val mine = m.role == "user"
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (mine) "Você" else "Atlas", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text(m.content)
                            if (!mine) TextButton(onClick = { clipboard.setText(AnnotatedString(m.content)) }) { Text("Copiar") }
                        }
                    }
                }
                if (s.sending) item { Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("Atlas está processando…")
                } }
            }
        }
        s.error?.let { e ->
            Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(e)
                    if (s.canRetry) TextButton(onClick = { vm.retry() }) { Text("Tentar novamente") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                label = { Text("Mensagem") }, maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { vm.send(input); input = "" } }),
            )
            Spacer(Modifier.width(8.dp))
            if (s.sending) Button(onClick = { vm.stop() }) { Text("Parar") }
            else Button(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) { Text("Enviar") }
        }
    }

    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false },
        confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Fechar") } },
        title = { Text("Conversas") },
        text = {
            if (s.conversations.isEmpty()) Text("Nenhuma conversa salva.")
            else LazyColumn {
                items(s.conversations, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.open(c.id); showHistory = false }, modifier = Modifier.weight(1f)) {
                            Text(c.title + if (c.id == s.currentId) "  (aberta)" else "", maxLines = 1)
                        }
                        TextButton(onClick = { vm.delete(c.id) }) { Text("Excluir") }
                    }
                }
            }
        },
    )
}

@Composable
fun DiagnosticsScreen(vm: DiagnosticsViewModel, modifier: Modifier = Modifier) {
    val items by vm.items.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Button(onClick = { vm.run() }) { Text("Executar diagnóstico novamente") }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items) { i ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(i.name, fontWeight = FontWeight.Bold)
                        Text("Estado: ${i.status.label}", style = MaterialTheme.typography.labelLarge)
                        Text(i.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(c: AppContainer, modifier: Modifier = Modifier) {
    val settings = c.settings
    val dark by settings.darkTheme.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(settings.backendUrl) }
    var token by rememberSaveable { mutableStateOf(settings.token) }
    var prefs by rememberSaveable { mutableStateOf(settings.preferences) }
    var msg by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!settings.secureStorageOk) Text("Armazenamento seguro indisponível: nada será salvo.", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Endereço do backend (https://…)") }, singleLine = true)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Token de acesso") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(prefs, { prefs = it }, Modifier.fillMaxWidth(), label = { Text("Preferências e referências religiosas (opcional)") },
            supportingText = { Text("Enviado ao serviço de IA junto com as mensagens.") }, maxLines = 6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                settings.backendUrl = url; settings.token = token; settings.preferences = prefs
                val saved = settings.backendUrl == url.trim() && settings.token == token.trim() && settings.preferences == prefs
                msg = if (saved) "Ajustes salvos e verificados no armazenamento seguro." else "Falha ao salvar: o armazenamento seguro não confirmou a gravação."
            }) { Text("Salvar") }
            OutlinedButton(enabled = !testing, onClick = {
                settings.backendUrl = url; settings.token = token; settings.preferences = prefs
                testing = true; msg = "Testando conexão…"
                scope.launch {
                    msg = try {
                        val h = c.api.health()
                        "Backend respondeu. Modelo: ${h.model ?: "?"}. Chave de IA no servidor: ${if (h.aiKeyConfigured) "sim" else "NÃO"}."
                    } catch (e: ApiException) { e.message } catch (e: Exception) { "Erro inesperado: ${e.message}" }
                    testing = false
                }
            }) { Text("Testar conexão") }
        }
        msg?.let { Text(it) }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tema escuro", Modifier.weight(1f))
            Switch(checked = dark, onCheckedChange = { settings.setDark(it) })
        }
        HorizontalDivider()
        Text("Sobre a IA", fontWeight = FontWeight.Bold)
        Text("Fornecedor: Anthropic (modelo definido pelo backend; padrão claude-sonnet-5-5). Funciona por API e exige internet.\n" +
            "Dados enviados: as últimas 30 mensagens da conversa e as preferências acima, via seu backend. A chave da API fica só no servidor.\n" +
            "Custos: cobrados na conta de API de quem opera o backend. Há limite de 30 requisições por minuto.\n" +
            "Se a API estiver indisponível, o chat mostra o erro e permite tentar novamente; não há modelo alternativo nem modo offline.\n" +
            "O Atlas ainda não tem ferramentas: não abre apps, não cria alarmes e não acessa e-mail.",
            style = MaterialTheme.typography.bodySmall)
    }
}
