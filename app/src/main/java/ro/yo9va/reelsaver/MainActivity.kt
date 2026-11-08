package ro.yo9va.reelsaver

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Găsește ferestrele de dialog ale paginii (ex. cookie-uri Instagram în UE), apasă butonul
 * "refuză opționale" / "nu acum" dacă există și raportează ce a găsit (pentru depanare).
 */
private val DIALOG_JS = """
    (function(){
      var d=[].slice.call(document.querySelectorAll('[role=dialog],[aria-modal=true]'));
      var re=/^(Refuz|Decline|Respinge|Only allow essential|Permite doar|Nu acum|Not now)/i;
      var clicked='';
      d.forEach(function(x){
        x.querySelectorAll('button,[role=button]').forEach(function(b){
          var t=(b.innerText||'').trim();
          if(!clicked && re.test(t)){ b.click(); clicked=t; }
        });
      });
      var info=d.map(function(x){
        var r=x.getBoundingClientRect();
        return (x.innerText||'').trim().replace(/\s+/g,' ').slice(0,60)+' ['+Math.round(r.top)+'/'+Math.round(r.height)+']';
      }).join(' | ');
      return JSON.stringify({n:d.length,clicked:clicked,info:info,h:window.innerHeight,el:document.getElementsByTagName('*').length,tx:(document.body?document.body.innerText.length:-1)});
    })()
""".trimIndent()

class MainActivity : ComponentActivity() {

    /** URL primit prin Share; Compose îl observă și îl încarcă. */
    private val sharedUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Iconițe albe în bara de stare (aplicația are temă închisă).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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
    var jsError by remember { mutableStateOf<String?>(null) }
    var dialogInfo by remember { mutableStateOf<String?>(null) }

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
        jsError = null
        dialogInfo = null
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
            wv.evaluateJavascript(DIALOG_JS) { res ->
                try {
                    val o = org.json.JSONObject(org.json.JSONTokener(res).nextValue() as String)
                    val clicked = o.optString("clicked")
                    dialogInfo = "Pagină: ${o.optInt("el")} elem., ${o.optInt("tx")} car. text, h=${o.optInt("h")}" +
                        (if (o.optInt("n") > 0) " | dialog ×${o.optInt("n")}: ${o.optString("info")}" else "") +
                        (if (clicked.isNotEmpty()) " | apăsat: $clicked" else "")
                } catch (_: Exception) { }
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

    Scaffold(
        // Bara de sus stă deasupra WebView-ului, nu poate fi acoperită de pagină.
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.statusBarsPadding().padding(bottom = 6.dp)) {
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
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { load("https://m.facebook.com/login/") }, modifier = Modifier.weight(1f)) {
                            Text("Facebook")
                        }
                        OutlinedButton(onClick = { load("https://www.instagram.com/accounts/login/") }, modifier = Modifier.weight(1f)) {
                            Text("Instagram")
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().fillMaxWidth().padding(12.dp)) {
                    Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    dialogInfo?.let {
                        Text(
                            it, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    jsError?.let {
                        Text(
                            "JS: $it", maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
        },
    ) { pad ->
        AndroidView(
            modifier = Modifier.padding(pad).fillMaxSize().clipToBounds(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // Fără WebChromeClient, dialogurile JS ale paginii (alert/confirm) nu se afișează.
                    // Reținem și ultima eroare JS, ca s-o vedem pe ecran la depanare.
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                jsError = msg.message().take(160) + " (linia " + msg.lineNumber() + ")"
                            }
                            return false
                        }
                    }
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
                            // Doar navigarea paginii principale spre scheme de aplicații; cadrele interne
                            // (about:blank, blob:, data:) trebuie lăsate în pace, altfel Instagram nu pornește.
                            if (!request.isForMainFrame) return false
                            val scheme = request.url.scheme?.lowercase() ?: return false
                            return scheme !in setOf("http", "https", "about", "data", "blob", "javascript")
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
    }
}
