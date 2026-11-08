package ro.yo9va.reelsaver

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoSaver {

    suspend fun save(
        context: Context,
        plan: SavePlan,
        userAgent: String,
        onProgress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val uri = newGalleryEntry(context)
        try {
            if (plan.video.kind == Kind.PROGRESSIVE) {
                resolver.openOutputStream(uri)!!.use { out ->
                    download(plan.video.url, userAgent, out) { onProgress(it) }
                }
            } else {
                // DASH: descărcăm separat video și audio în cache, apoi le unim.
                val vFile = File(context.cacheDir, "v.mp4")
                val aFile = File(context.cacheDir, "a.mp4")
                try {
                    vFile.outputStream().use { download(plan.video.url, userAgent, it) { p -> onProgress(p * 0.8f) } }
                    plan.audio?.let { a ->
                        aFile.outputStream().use { download(a.url, userAgent, it) { p -> onProgress(0.8f + p * 0.15f) } }
                    }
                    resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                        mux(vFile, if (plan.audio != null) aFile else null, pfd.fileDescriptor)
                    }
                    onProgress(1f)
                } finally {
                    vFile.delete(); aFile.delete()
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun newGalleryEntry(context: Context): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "reel_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ReelSaver")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Nu pot crea fișierul în galerie")
    }

    private fun download(url: String, userAgent: String, out: OutputStream, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty(
                "Referer",
                if (url.contains("cdninstagram")) "https://www.instagram.com/" else "https://m.facebook.com/",
            )
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Serverul a răspuns HTTP $code")
            val total = conn.contentLengthLong
            var done = 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
            if (done < 1024) throw IOException("Fișier prea mic ($done octeți)")
        } finally {
            conn.disconnect()
        }
    }

    /** Copiază pista video (și audio, dacă există) într-un MP4 nou, fără re-encodare. */
    private fun mux(video: File, audio: File?, fd: FileDescriptor) {
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = mutableListOf<Pair<MediaExtractor, Int>>()

        fun addTrack(file: File, mimePrefix: String) {
            val ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true) {
                    ex.selectTrack(i)
                    sources += ex to muxer.addTrack(fmt)
                    return
                }
            }
            ex.release()
            if (mimePrefix == "video/") throw IOException("Fișierul descărcat nu conține video")
        }

        try {
            addTrack(video, "video/")
            audio?.let { addTrack(it, "audio/") }
            muxer.start()
            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((ex, track) in sources) {
                while (true) {
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    info.set(
                        0, size, ex.sampleTime,
                        if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                    muxer.writeSampleData(track, buf, info)
                    ex.advance()
                }
            }
            muxer.stop()
        } finally {
            sources.forEach { it.first.release() }
            muxer.release()
        }
    }
}
