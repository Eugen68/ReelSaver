package ro.yo9va.reelsaver

import org.json.JSONArray
import org.json.JSONTokener

/** Un URL video găsit în pagină. rank mai mic = mai bun. */
data class Candidate(val url: String, val label: String, val rank: Int)

object VideoExtractor {

    /** Cheile din JSON-ul paginii Facebook, în ordinea preferinței. */
    private val KEYS = listOf(
        "browser_native_hd_url" to "HD",
        "playable_url_quality_hd" to "HD",
        "hd_src" to "HD",
        "browser_native_sd_url" to "SD",
        "playable_url" to "SD",
        "sd_src" to "SD",
    )

    /**
     * JS rulat în WebView: adună src-ul elementelor <video> (dacă nu sunt blob:)
     * și URL-urile progresive (video+audio) din JSON-ul încorporat în pagină.
     */
    val JS: String = """
        (function(){
          var out=[];
          document.querySelectorAll('video').forEach(function(v){
            var s=v.currentSrc||v.src;
            if(s && s.indexOf('blob:')!==0) out.push(['video',s]);
          });
          var h=document.documentElement.innerHTML;
          var keys=${JSONArray(KEYS.map { it.first })};
          var q='(?:"|&quot;)';
          keys.forEach(function(k){
            var re=new RegExp(q+k+q+':'+q+'(.+?)'+q,'g'); var m;
            while((m=re.exec(h))!==null){ out.push([k,m[1]]); }
          });
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /** Interpretează rezultatul lui evaluateJavascript (un string JSON codat încă o dată). */
    fun parse(jsResult: String?): List<Candidate> {
        if (jsResult.isNullOrBlank() || jsResult == "null") return emptyList()
        return try {
            val inner = JSONTokener(jsResult).nextValue() as? String ?: return emptyList()
            val arr = JSONArray(inner)
            (0 until arr.length()).mapNotNull { i ->
                val pair = arr.getJSONArray(i)
                val key = pair.getString(0)
                val url = unescape(pair.getString(1))
                if (!url.startsWith("http")) return@mapNotNull null
                if (key == "video") {
                    Candidate(url, "Video din pagină", 10)
                } else {
                    val idx = KEYS.indexOfFirst { it.first == key }
                    Candidate(url, KEYS[idx].second, idx)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** URL-uri .mp4 de pe fbcdn prinse din traficul WebView-ului (ultimă variantă). */
    fun fromIntercept(url: String): Candidate? {
        if (!url.contains("fbcdn") || !url.contains(".mp4")) return null
        // Fragmentele DASH au bytestart/byteend; le scoatem ca să cerem fișierul întreg.
        val clean = url
            .replace(Regex("[&?]bytestart=\\d+"), "")
            .replace(Regex("[&?]byteend=\\d+"), "")
            .let { if (!it.contains('?') && it.contains('&')) it.replaceFirst('&', '?') else it }
        return Candidate(clean, "Flux interceptat (poate fi fără sunet)", 20)
    }

    private fun unescape(s: String): String {
        var r = s.replace("&amp;", "&").replace("\\/", "/")
        r = Regex("\\\\u([0-9a-fA-F]{4})").replace(r) { it.groupValues[1].toInt(16).toChar().toString() }
        return r.replace("\\\\", "\\")
    }

    /** Scoate primul URL dintr-un text partajat ("Uite reel-ul ăsta https://..."). */
    fun firstUrl(text: String?): String? =
        text?.let { Regex("https?://\\S+").find(it)?.value?.trimEnd('.', ',', ')') }
}
