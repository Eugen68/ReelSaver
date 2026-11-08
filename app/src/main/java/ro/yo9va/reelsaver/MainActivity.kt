package ro.yo9va.reelsaver

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** URL primit prin Share; Compose îl observă și îl încarcă. */
    private val sharedUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedUrl.value = urlFrom(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ReelSaverScreen(sharedUrl = sharedUrl.value, onExit = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        urlFrom(intent)?.let { sharedUrl.value = it }
    }

    private fun urlFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> VideoExtractor.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        Intent.ACTION_VIEW -> intent.dataString
        else -> null
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReelSaverScreen(sharedUrl: String?, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlField by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val candidates = remember { mutableStateMapOf<String, Candidate>() }
    var status by remember { mutableStateOf("Dă Share → ReelSaver din Facebook sau lipește un link.") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var pageToken by remember { mutableIntStateOf(0) }

    fun addCandidate(c: Candidate) {
        val old = candidates[c.url]
        if (old == null || c.rank < old.rank) candidates[c.url] = c
    }

    fun load(url: String) {
        val u = url.trim()
        if (!u.startsWith("http")) { status = "Link invalid"; return }
        urlField = u
        candidates.clear()
        progress = null
        status = "Se încarcă reel-ul…"
        pageToken++
        webView?.loadUrl(u)
    }

    // Link venit prin Share
    LaunchedEffect(sharedUrl, webView) {
        if (sharedUrl != null && webView != null) load(sharedUrl)
    }

    // Facebook încarcă reel-ul dinamic, așa că re-scanăm pagina periodic.
    LaunchedEffect(pageToken, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (pageToken == 0) return@LaunchedEffect
        repeat(30) {
            delay(2000)
            wv.evaluateJavascript(VideoExtractor.JS) { res ->
                VideoExtractor.parse(res).forEach(::addCandidate)
            }
        }
    }

    val best = candidates.values.minByOrNull { it.rank }

    LaunchedEffect(best?.url) {
        if (best != null && progress == null && status.startsWith("Se încarcă")) status = "Video găsit: ${best.label}"
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onExit()
    }

    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = urlField,
                    onValueChange = { urlField = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("https://www.facebook.com/reel/…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { load(urlField) }),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { load(urlField) }) { Text("Deschide") }
            }

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView, request: WebResourceRequest,
                            ): WebResourceResponse? {
                                // Rulează pe un fir de fundal → trecem pe UI thread.
                                VideoExtractor.fromIntercept(request.url.toString())?.let { c ->
                                    view.post { addCandidate(c) }
                                }
                                return null
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                urlField = url
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                CookieManager.getInstance().flush() // păstrăm login-ul
                                view.evaluateJavascript(VideoExtractor.JS) { res ->
                                    VideoExtractor.parse(res).forEach(::addCandidate)
                                }
                            }
                        }
                        webView = this
                    }
                },
            )

            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis)
                progress?.let {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = best != null && progress == null,
                    onClick = {
                        val c = best ?: return@Button
                        val ua = webView?.settings?.userAgentString ?: ""
                        progress = 0f
                        status = "Se descarcă (${c.label})…"
                        scope.launch {
                            try {
                                VideoSaver.save(context, c.url, ua) { p ->
                                    scope.launch { progress = p }
                                }
                                status = "Salvat în galerie: Movies/ReelSaver ✔"
                            } catch (e: Exception) {
                                status = "Eroare: ${e.message}"
                                candidates.remove(c.url) // la următoarea apăsare încearcă alt URL
                            } finally {
                                progress = null
                            }
                        }
                    },
                ) {
                    Text(if (best == null) "Caut videoclipul…" else "Salvează în galerie (${best.label})")
                }
            }
        }
    }
}
