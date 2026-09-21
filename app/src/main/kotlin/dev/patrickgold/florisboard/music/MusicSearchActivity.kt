package dev.patrickgold.florisboard.music

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MusicSearchActivity : ComponentActivity() {
    private val api = MusicApi()
    private var pendingSong: Song? = null
    private var statusMessage by mutableStateOf<String?>(null)
    private var isDownloading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MusicSearchScreen(
                    status = statusMessage,
                    downloading = isDownloading,
                    onBack = { finish() },
                    onSearch = { query, callback ->
                        lifecycleScope.launch {
                            callback(runCatching { api.search(query) })
                        }
                    },
                    onDownload = { song, openTree ->
                        pendingSong = song
                        openTree.launch(null)
                    },
                )
            }
        }
    }

    private fun downloadTo(song: Song, folder: Uri) {
        lifecycleScope.launch {
            isDownloading = true
            statusMessage = "${song.title} hazırlanıyor…"
            runCatching {
                contentResolver.takePersistableUriPermission(
                    folder,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                val ready = api.prepare(song.url) { percent ->
                    withContext(Dispatchers.Main) { statusMessage = "MP3 hazırlanıyor: %$percent" }
                }
                statusMessage = "Dosya kaydediliyor…"
                val safeName = ready.fileName.replace(Regex("[\\/:*?\"<>|]"), "_")
                val document = android.provider.DocumentsContract.createDocument(
                    contentResolver,
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        folder, android.provider.DocumentsContract.getTreeDocumentId(folder),
                    ),
                    "audio/mpeg", safeName,
                ) ?: error("Seçilen klasörde dosya oluşturulamadı")
                contentResolver.openOutputStream(document)?.use { output ->
                    api.download(ready.fileUrl).use { input -> input.copyTo(output) }
                } ?: error("Dosya açılamadı")
                statusMessage = "İndirildi: $safeName"
            }.onFailure {
                statusMessage = "Hata: ${it.message ?: "İndirme tamamlanamadı"}"
            }
            isDownloading = false
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MusicSearchScreen(
        status: String?,
        downloading: Boolean,
        onBack: () -> Unit,
        onSearch: (String, (Result<List<Song>>) -> Unit) -> Unit,
        onDownload: (Song, androidx.activity.result.ActivityResultLauncher<Uri?>) -> Unit,
    ) {
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf(emptyList<Song>()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val song = pendingSong
            if (uri != null && song != null) downloadTo(song, uri)
            pendingSong = null
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Müzik indir") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Geri") } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Şarkı veya sanatçı") },
                        leadingIcon = { Icon(Icons.Default.MusicNote, null) },
                    )
                    IconButton(
                        enabled = query.isNotBlank() && !loading,
                        onClick = {
                            loading = true; error = null
                            onSearch(query) { result ->
                                loading = false
                                result.onSuccess { results = it }.onFailure { error = it.message }
                            }
                        },
                    ) { Icon(Icons.Default.Search, "Ara") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary) }
                error?.let { Text("Hata: $it", color = MaterialTheme.colorScheme.error) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(results, key = { it.url }) { song ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MusicNote, null, Modifier.size(42.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${song.channel} • ${formatDuration(song.duration)}", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(enabled = !downloading, onClick = { onDownload(song, folderPicker) }) {
                                    Icon(Icons.Default.Download, "İndir")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatDuration(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
}

@Serializable
data class Song(
    @SerialName("baslik") val title: String,
    @SerialName("kanal") val channel: String = "",
    @SerialName("sure") val duration: Int = 0,
    val url: String,
)

@Serializable private data class SearchResponse(@SerialName("sonuclar") val results: List<Song> = emptyList(), val ok: Boolean = false)
@Serializable private data class InstantResponse(@SerialName("job_id") val jobId: String, val ok: Boolean = false)
@Serializable private data class StatusResponse(
    @SerialName("durum") val state: String,
    @SerialName("yuzde") val percent: Int = 0,
    @SerialName("dosya") val fileName: String? = null,
    @SerialName("dosya_url") val fileUrl: String? = null,
    @SerialName("hata") val error: String? = null,
)
private data class ReadyFile(val fileName: String, val fileUrl: String)

private class MusicApi {
    private val base = "https://mp3-apisi.onrender.com"
    private val json = Json { ignoreUnknownKeys = true }
    private val key: String get() = BuildConfig.MP3_API_KEY.also { require(it.isNotBlank()) { "MP3 API anahtarı yapılandırılmamış" } }

    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        val body = get("/api/v1/search?q=${enc(query)}&key=${enc(key)}")
        val response = json.decodeFromString<SearchResponse>(body)
        check(response.ok) { "Arama servisi isteği reddetti" }
        response.results
    }

    suspend fun prepare(sourceUrl: String, progress: suspend (Int) -> Unit): ReadyFile = withContext(Dispatchers.IO) {
        val instant = json.decodeFromString<InstantResponse>(get("/api/v1/instant?q=${enc(sourceUrl)}&key=${enc(key)}"))
        check(instant.ok) { "Dönüştürme başlatılamadı" }
        repeat(120) {
            delay(1500)
            val status = json.decodeFromString<StatusResponse>(get("/api/v1/status/${instant.jobId}?key=${enc(key)}"))
            progress(status.percent)
            if (status.state == "bitti" && status.fileName != null && status.fileUrl != null) {
                return@withContext ReadyFile(status.fileName, status.fileUrl)
            }
            if (status.error != null || status.state in setOf("hata", "failed")) error(status.error ?: "Dönüştürme başarısız")
        }
        error("Dönüştürme zaman aşımına uğradı")
    }

    fun download(path: String) = open("$path${if (path.contains('?')) '&' else '?'}key=${enc(key)}").inputStream
    private fun get(path: String): String = open(path).run {
        inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
    }
    private fun open(path: String): HttpURLConnection = (URL(if (path.startsWith("http")) path else "$base$path").openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000; readTimeout = 120_000
        if (responseCode !in 200..299) error("Sunucu hatası: $responseCode")
    }
    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
