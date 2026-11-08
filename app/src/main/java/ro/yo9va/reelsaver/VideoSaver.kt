package ro.yo9va.reelsaver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoSaver {

    /**
     * Descarcă videoclipul și îl scrie în Movies/ReelSaver prin MediaStore,
     * ca să apară imediat în galerie. Folosește cookie-urile și User-Agent-ul WebView-ului.
     */
    suspend fun save(
        context: Context,
        url: String,
        userAgent: String,
        onProgress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", "https://www.facebook.com/")
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Serverul a răspuns HTTP $code")
            val total = conn.contentLengthLong

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "reel_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ReelSaver")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Nu pot crea fișierul în galerie")

            try {
                resolver.openOutputStream(uri)!!.use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                        if (done < 1024) throw IOException("Fișier prea mic ($done octeți)")
                    }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } finally {
            conn.disconnect()
        }
    }
}
