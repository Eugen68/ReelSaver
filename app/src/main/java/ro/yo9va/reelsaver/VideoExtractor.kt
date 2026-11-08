package ro.yo9va.reelsaver

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class Kind { PROGRESSIVE, DASH_VIDEO, DASH_AUDIO }

/**
 * Un URL video găsit. Pentru PROGRESSIVE rank mai mic = mai bun.
 * Pentru DASH folosim videoId/height/bitrate, iar seq arată cât de recent a fost văzut.
 */
data class Candidate(
    val url: String,
    val label: String,
    val kind: Kind,
    val rank: Int = 0,
    val videoId: String? = null,
    val height: Int = 0,
    val bitrate: Long = 0,
    val seq: Long = 0,
)

/** Ce salvăm: fie un fișier progresiv, fie video DASH + (opțional) audio DASH. */
data class SavePlan(val video: Candidate, val audio: Candidate?, val label: String)

object VideoExtractor {

    private val KEYS = listOf(
        "browser_native_hd_url" to "HD",
        "playable_url_quality_hd" to "HD",
        "hd_src" to "HD",
        "browser_native_sd_url" to "SD",
        "playable_url" to "SD",
        "sd_src" to "SD",
    )

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
                    Candidate(url, "video din pagină", Kind.PROGRESSIVE, rank = 10)
                } else {
                    val idx = KEYS.indexOfFirst { it.first == key }
                    Candidate(url, KEYS[idx].second, Kind.PROGRESSIVE, rank = idx)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Cereri .mp4 de pe fbcdn prinse din traficul WebView-ului. Sunt fragmente DASH
     * (bytestart/byteend); le scoatem ca să cerem fișierul întreg al reprezentării.
     * Parametrul efg (base64 JSON) spune dacă e audio sau video, rezoluția și id-ul clipului.
     */
    fun fromIntercept(url: String, seq: Long): Candidate? {
        if (!url.contains("fbcdn") || !url.contains(".mp4")) return null
        val clean = url
            .replace(Regex("[&?]bytestart=\\d+"), "")
            .replace(Regex("[&?]byteend=\\d+"), "")
            .let { if (!it.contains('?') && it.contains('&')) it.replaceFirst('&', '?') else it }

        val efg = decodeEfg(clean)
        val tag = efg?.optString("vencode_tag").orEmpty()
        val videoId = efg?.opt("video_id")?.toString()
        val bitrate = efg?.optLong("bitrate") ?: 0L
        val isAudio = tag.contains("audio", ignoreCase = true)
        val height = Regex("(\\d{3,4})p").find(tag)?.groupValues?.get(1)?.toInt() ?: 0

        return if (isAudio) {
            Candidate(clean, "audio", Kind.DASH_AUDIO, videoId = videoId, bitrate = bitrate, seq = seq)
        } else {
            Candidate(
                clean, if (height > 0) "${height}p" else "video", Kind.DASH_VIDEO,
                videoId = videoId, height = height, bitrate = bitrate, seq = seq,
            )
        }
    }

    /** Alege cea mai bună variantă din ce s-a găsit până acum. */
    fun bestPlan(all: Collection<Candidate>): SavePlan? {
        all.filter { it.kind == Kind.PROGRESSIVE }.minByOrNull { it.rank }?.let {
            return SavePlan(it, null, "${it.label}, cu sunet")
        }
        val videos = all.filter { it.kind == Kind.DASH_VIDEO }
        if (videos.isEmpty()) return null
        // Clipul cel mai recent redat (contează dacă ai derulat prin mai multe reel-uri).
        val id = videos.maxBy { it.seq }.videoId
        val v = videos.filter { it.videoId == id }.maxBy { it.height * 100_000_000L + it.bitrate }
        val a = all.filter { it.kind == Kind.DASH_AUDIO && it.videoId == id }.maxByOrNull { it.bitrate }
        return SavePlan(v, a, v.label + if (a != null) " + sunet" else " (fără sunet încă)")
    }

    private fun decodeEfg(url: String): JSONObject? = try {
        val raw = Uri.parse(url).getQueryParameter("efg") ?: null
        raw?.let {
            val bytes = Base64.decode(it.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
            JSONObject(String(bytes))
        }
    } catch (e: Exception) {
        null
    }

    private fun unescape(s: String): String {
        var r = s.replace("&amp;", "&").replace("\\/", "/")
        r = Regex("\\\\u([0-9a-fA-F]{4})").replace(r) { it.groupValues[1].toInt(16).toChar().toString() }
        return r.replace("\\\\", "\\")
    }

    fun firstUrl(text: String?): String? =
        text?.let { Regex("https?://\\S+").find(it)?.value?.trimEnd('.', ',', ')') }
}
