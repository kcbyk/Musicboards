package dev.patrickgold.florisboard.music

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.BuildConfig
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MusicPanelState {
    var visible by mutableStateOf(false)
    fun show() { visible = true }
    fun hide() { visible = false }
}

enum class MusicDestination(val title: String, val relativePath: String) {
    MUSIC("Müzik", Environment.DIRECTORY_MUSIC + "/Musicboards"),
    DOWNLOADS("İndirilenler", Environment.DIRECTORY_DOWNLOADS + "/Musicboards"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { MusicApi() }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Song>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectedSong by remember { mutableStateOf<Song?>(null) }
    var showMiniKeyboard by remember { mutableStateOf(true) }

    fun search() {
        if (query.isBlank() || loading) return
        loading = true; message = null; showMiniKeyboard = false
        scope.launch {
            runCatching { api.search(query) }
                .onSuccess { results = it }
                .onFailure { message = "Arama hatası: ${it.message}" }
            loading = false
        }
    }

    Surface(Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 560.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MusicNote, null)
                Text(" Müzik indir", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = MusicPanelState::hide) { Icon(Icons.Default.Close, "Kapat") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showMiniKeyboard = !showMiniKeyboard }, modifier = Modifier.weight(1f)) {
                    Text(if (query.isBlank()) "Şarkı veya sanatçı yaz" else query, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = ::search, enabled = query.isNotBlank() && !loading) { Text("Ara") }
            }
            if (showMiniKeyboard) {
                MiniSearchKeyboard(
                    onChar = { query += it },
                    onSpace = { if (query.isNotEmpty() && !query.endsWith(' ')) query += " " },
                    onDelete = { if (query.isNotEmpty()) query = query.dropLast(1) },
                    onClear = { query = "" },
                    onSearch = ::search,
                )
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(4.dp)) }
            if (!showMiniKeyboard) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
                    items(results, key = { it.url }) { song ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MusicNote, null)
                                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                    Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${song.channel} • ${formatDuration(song.duration)}", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(enabled = !loading, onClick = { selectedSong = song }) {
                                    Icon(Icons.Default.Download, "İndir")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedSong?.let { song ->
        AlertDialog(
            onDismissRequest = { selectedSong = null },
            title = { Text("Nereye kaydedilsin?") },
            text = {
                Column {
                    Text(song.title, maxLines = 2)
                    MusicDestination.entries.forEach { destination ->
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            selectedSong = null; loading = true; message = "MP3 hazırlanıyor…"
                            scope.launch {
                                runCatching {
                                    val ready = api.prepare(song.url) { message = "MP3 hazırlanıyor: %$it" }
                                    message = "Dosya kaydediliyor…"
                                    saveToMediaStore(context, destination, ready.fileName, api.download(ready.fileUrl))
                                }.onSuccess { message = "${destination.title} klasörüne indirildi" }
                                    .onFailure { message = "İndirme hatası: ${it.message}" }
                                loading = false
                            }
                        }) { Text("${destination.title}/Musicboards") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selectedSong = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun MiniSearchKeyboard(onChar: (String) -> Unit, onSpace: () -> Unit, onDelete: () -> Unit, onClear: () -> Unit, onSearch: () -> Unit) {
    val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM", "ĞÜŞİÖÇ")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                row.forEach { char -> TextButton(onClick = { onChar(char.toString().lowercase()) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f)) { Text(char.toString()) } }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onClear) { Text("Temizle") }
            Button(onClick = onSpace, modifier = Modifier.weight(1f)) { Text("Boşluk") }
            TextButton(onClick = onDelete) { Text("⌫") }
            TextButton(onClick = onSearch) { Text("Ara") }
        }
    }
}

private suspend fun saveToMediaStore(context: Context, destination: MusicDestination, rawName: String, input: java.io.InputStream) = withContext(Dispatchers.IO) {
    val fileName = rawName.replace(Regex("[\\/:*?\"<>|]"), "_").let { if (it.endsWith(".mp3", true)) it else "$it.mp3" }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, destination.relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("Dosya oluşturulamadı")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output -> input.use { it.copyTo(output) } } ?: error("Dosya açılamadı")
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Throwable) { context.contentResolver.delete(uri, null, null); throw e }
    } else {
        val base = context.getExternalFilesDir(if (destination == MusicDestination.MUSIC) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val dir = File(base, "Musicboards").apply { mkdirs() }
        File(dir, fileName).outputStream().use { output -> input.use { it.copyTo(output) } }
    }
}

private fun formatDuration(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

@Serializable data class Song(@SerialName("baslik") val title: String, @SerialName("kanal") val channel: String = "", @SerialName("sure") val duration: Int = 0, val url: String)
@Serializable private data class SearchResponse(@SerialName("sonuclar") val results: List<Song> = emptyList(), val ok: Boolean = false)
@Serializable private data class InstantResponse(@SerialName("job_id") val jobId: String, val ok: Boolean = false)
@Serializable private data class StatusResponse(@SerialName("durum") val state: String, @SerialName("yuzde") val percent: Int = 0, @SerialName("dosya") val fileName: String? = null, @SerialName("dosya_url") val fileUrl: String? = null, @SerialName("hata") val error: String? = null)
private data class ReadyFile(val fileName: String, val fileUrl: String)

private class MusicApi {
    private val base = "https://mp3-apisi.onrender.com"
    private val json = Json { ignoreUnknownKeys = true }
    private val key get() = BuildConfig.MP3_API_KEY.also { require(it.isNotBlank()) { "API anahtarı ayarlanmamış" } }
    suspend fun search(q: String) = withContext(Dispatchers.IO) { json.decodeFromString<SearchResponse>(get("/api/v1/search?q=${enc(q)}&key=${enc(key)}")).also { check(it.ok) }.results }
    suspend fun prepare(url: String, progress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
        val job = json.decodeFromString<InstantResponse>(get("/api/v1/instant?q=${enc(url)}&key=${enc(key)}")); check(job.ok)
        repeat(120) { delay(1500); val s = json.decodeFromString<StatusResponse>(get("/api/v1/status/${job.jobId}?key=${enc(key)}")); progress(s.percent); if (s.state == "bitti" && s.fileName != null && s.fileUrl != null) return@withContext ReadyFile(s.fileName, s.fileUrl); if (s.error != null) error(s.error) }
        error("Dönüştürme zaman aşımı")
    }
    fun download(path: String) = open("$path${if (path.contains('?')) '&' else '?'}key=${enc(key)}").inputStream
    private fun get(path: String) = open(path).run { inputStream.bufferedReader().use { it.readText() }.also { disconnect() } }
    private fun open(path: String) = (URL(if (path.startsWith("http")) path else "$base$path").openConnection() as HttpURLConnection).apply { connectTimeout = 30000; readTimeout = 120000; if (responseCode !in 200..299) error("Sunucu hatası: $responseCode") }
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
}
