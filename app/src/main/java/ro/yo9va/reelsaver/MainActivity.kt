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
    var status by remember { mutableStateOf("Dă Share → ReelSaver din Facebook/Instagram sau lipește un link.") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var pageToken by remember { mutableIntStateOf(0) }
    var seq by remember { mutableLongStateOf(0L) }

    fun addCandidate(c: Candidate) {
        val old = candidates[c.url]
        // DASH: re-adăugăm ca să actualizăm seq (cel mai recent clip redat câștigă).
        if (old == null || c.rank < old.rank || c.seq > old.seq) candidates[c.url] = c
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

    val best = VideoExtractor.bestPlan(candidates.values)

    LaunchedEffect(best?.label) {
        if (best != null && progress == null && (status.startsWith("Se încarcă") || status.startsWith("Video găsit")))
            status = "Video găsit: ${best.label}"
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
                    placeholder = { Text("Link reel Facebook / Instagram") },
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
                                val url = request.url.toString()
                                if (VideoExtractor.isMetaCdn(url) && url.contains(".mp4")) view.post {
                                    VideoExtractor.fromIntercept(url, ++seq)?.let { addCandidate(it) }
                                }
                                return null
                            }

                            // Site-ul mobil încearcă să deschidă clipurile în aplicația Facebook
                            // (fb://fullscreen_video/…, intent://…). Le ignorăm și rămânem în pagină.
                            override fun shouldOverrideUrlLoading(
                                view: WebView, request: WebResourceRequest,
                            ): Boolean {
                                val scheme = request.url.scheme ?: return true
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (url.startsWith("http")) urlField = url
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
                        val plan = best ?: return@Button
                        val ua = webView?.settings?.userAgentString ?: ""
                        progress = 0f
                        status = "Se descarcă (${plan.label})…"
                        scope.launch {
                            try {
                                VideoSaver.save(context, plan, ua) { p ->
                                    scope.launch { progress = p }
                                }
                                status = "Salvat în galerie: Movies/ReelSaver ✔"
                            } catch (e: Exception) {
                                status = "Eroare: ${e.message}"
                                if (plan.video.kind == Kind.PROGRESSIVE) candidates.remove(plan.video.url)
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
