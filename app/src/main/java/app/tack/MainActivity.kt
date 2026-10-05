package app.tack

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ConsoleMessage
import android.widget.TextView
import android.widget.Toast
import android.content.ContentValues
import android.provider.MediaStore
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.net.URL
import kotlin.concurrent.thread
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

private const val TAG = "Tack"
private const val HOME = "https://www.pinterest.com/"
private val PIN_PAGE = Regex("""^/pin/[^/]+/?$""")

// /videos/<kind>/hls/[v2/]aa/bb/cc/<hash>… — playlists and media segments alike.
private val VIDEO_STREAM = Regex("""^/videos/(\w+)/hls/(v2/)?(\w\w/\w\w/\w\w)/([0-9a-f]{32})""")

/**
 * A browser window that only shows Pinterest. Pinterest's own site does all the
 * work and makes every request; this class adds nothing to that traffic.
 */
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    /** A window the page opened (sign-in popups). Drawn over the main page. */
    private var popup: WebView? = null

    private lateinit var pageScript: String
    private var scriptAtStart = false
    private lateinit var videoButton: TextView

    /** Where a pin's video lives on Pinterest's video CDN, read from the stream the player requests. */
    private class Video(val kind: String, val v2: Boolean, val path: String, val hash: String) {
        // The CDN also keeps a single MP4 with sound beside each stream. Which
        // of the two layouts applies depends on the stream's generation.
        fun files(): List<String> {
            val base = "https://v1.pinimg.com/videos"
            val current = "$base/mc/720p/$path/$hash.mp4"
            val legacy = "$base/$kind/expMp4/$path/${hash}_720w.mp4"
            return if (v2) listOf(current, legacy) else listOf(legacy, current)
        }
    }

    // Written from WebView's network thread, read on the main thread.
    @Volatile private var onPinPage = false
    @Volatile private var pinVideo: Video? = null

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        pageScript = assets.open("page.js").bufferedReader().use { it.readText() }
            .replace("__DEBUG__", BuildConfig.DEBUG.toString())
        configure(web, isPopup = false)
        // Before the site's own scripts: the rule that collapses sponsored tiles
        // has to exist before the grid first measures them. The script itself
        // does nothing outside Pinterest's own top-level page.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, pageScript, setOf("*"))
            scriptAtStart = true
        }
        Log.i(TAG, "page script at document start: $scriptAtStart")

        val dp = resources.displayMetrics.density
        videoButton = TextView(this).apply {
            text = "Save video"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding((18 * dp).toInt(), (11 * dp).toInt(), (18 * dp).toInt(), (11 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(0xE6111111.toInt())
            }
            elevation = 6 * dp
            visibility = View.GONE
            setOnClickListener { saveVideo() }
        }
        root.addView(
            videoButton,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                // Top corner: the bottom of a pin page holds the player's own
                // controls and the site's Save button.
                setMargins(0, (14 * dp).toInt(), (14 * dp).toInt(), 0)
            }
        )

        onBackPressedDispatcher.addCallback(this) {
            val popup = popup
            when {
                fullscreenView != null -> web.webChromeClient?.onHideCustomView()
                popup != null -> if (popup.canGoBack()) popup.goBack() else closePopup()
                web.canGoBack() -> web.goBack()
                else -> moveTaskToBack(true)
            }
        }

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(linkFrom(intent) ?: HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let(web::loadUrl)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        popup?.let { root.removeView(it); it.destroy() }
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView, isPopup: Boolean) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Real popup windows: "Continue with Google" opens one and hands the
            // result back to the page that opened it, which only works if the
            // popup is a separate window.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // The site has no dark theme of its own for the mobile web, so the
            // WebView darkens it (photos and videos are left as they are).
            if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = true
        }
        view.setBackgroundColor(0xFF0E0E0F.toInt())
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = Client(isPopup)
        view.webChromeClient = Chrome()
        view.setDownloadListener { url, userAgent, disposition, mime, _ -> download(url, userAgent, disposition, mime) }
    }

    /**
     * Downloads the open pin's video. The file comes from Pinterest's public
     * video CDN, which takes no cookies, so the request is not tied to the account.
     */
    private fun saveVideo() {
        val video = pinVideo ?: return
        val userAgent = web.settings.userAgentString
        Toast.makeText(this, "Saving video…", Toast.LENGTH_SHORT).show()
        thread {
            val saved = video.files().any { url ->
                runCatching { saveToGallery(url, userAgent, "${video.hash.take(12)}.mp4", isVideo = true) }
                    .onFailure { Log.w(TAG, "video not saved from $url: $it") }
                    .getOrDefault(false)
            }
            runOnUiThread {
                Toast.makeText(this, if (saved) "Video saved to gallery" else "This video can't be saved", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveOpenPinImage() {
        val userAgent = web.settings.userAgentString
        val findImage = """(function(){var i=document.querySelector('img[elementtiming^="closeup-image-main"]');return i?(i.currentSrc||i.src):''})()"""
        web.evaluateJavascript(findImage) { result ->
            val shown = result.orEmpty().trim('"')
            if (!shown.startsWith("https://i.pinimg.com/")) {
                Log.w(TAG, "no pin image to save: $shown")
                Toast.makeText(this, "This image can't be saved", Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            // Full-size upload first. It keeps the format it was uploaded in, which
            // can differ from the resized copy on screen, so each format is tried
            // before settling for the on-screen size.
            val original = shown.replace(Regex("""pinimg\.com/[^/]+/"""), "pinimg.com/originals/").substringBeforeLast('.')
            val formats = listOf(shown.substringAfterLast('.'), "png", "jpg", "gif", "webp").distinct()
            thread {
                val saved = (formats.map { "$original.$it" } + shown).any { url ->
                    runCatching { saveToGallery(url, userAgent, url.substringAfterLast('/'), isVideo = false) }
                        .onFailure { Log.w(TAG, "image not saved from $url: $it") }
                        .getOrDefault(false)
                }
                runOnUiThread {
                    Toast.makeText(this, if (saved) "Image saved to gallery" else "This image can't be saved", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Streams a file from Pinterest's public CDN straight into the gallery.
     * Returns false if the CDN doesn't have it. Must not run on the main thread.
     */
    private fun saveToGallery(url: String, userAgent: String, name: String, isVideo: Boolean): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", userAgent)
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) return false
            val collection =
                if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val item = contentResolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, connection.contentType?.substringBefore(';') ?: if (isVideo) "video/mp4" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/DisPinterest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: return false
            try {
                contentResolver.openOutputStream(item)!!.use { out -> connection.inputStream.use { it.copyTo(out) } }
                if (isVideo) {
                    contentResolver.openFileDescriptor(item, "rw")!!.use { fd ->
                        // One read-write descriptor, but Java only hands out
                        // one-directional channels over it.
                        stampRecordedNow(FileInputStream(fd.fileDescriptor).channel, FileOutputStream(fd.fileDescriptor).channel)
                    }
                }
                contentResolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                contentResolver.delete(item, null, null)
                throw e
            }
            Log.i(TAG, "saved to gallery: $url")
            return true
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Galleries sort videos by the recording date stored inside the file, which
     * for a Pinterest video is whenever it was uploaded, so a fresh download
     * would be filed months back. Rewrites that date (the MP4 movie header's
     * creation and modification times) to now.
     */
    private fun stampRecordedNow(file: FileChannel, output: FileChannel) {
        // Finds a box of [type] among the boxes laid end to end in [from, until).
        fun find(type: String, from: Long, until: Long): LongRange? {
            val header = ByteBuffer.allocate(16)
            var at = from
            while (at + 8 <= until) {
                header.clear()
                if (file.read(header, at) < 8) return null
                var size = header.getInt(0).toLong() and 0xFFFFFFFFL
                var headerSize = 8
                if (size == 1L) { size = header.getLong(8); headerSize = 16 } else if (size == 0L) size = until - at
                if (size < headerSize) return null
                val name = String(header.array(), 4, 4, Charsets.ISO_8859_1)
                if (name == type) return (at + headerSize) until (at + size)
                at += size
            }
            return null
        }

        val movie = find("moov", 0, file.size()) ?: return
        val header = find("mvhd", movie.first, movie.last + 1) ?: return
        val version = ByteBuffer.allocate(1).also { file.read(it, header.first) }.get(0).toInt()
        // MP4 counts seconds from 1904, not 1970.
        val now = System.currentTimeMillis() / 1000 + 2_082_844_800L
        val times = if (version == 1) ByteBuffer.allocate(16).putLong(now).putLong(now)
        else ByteBuffer.allocate(8).putInt(now.toInt()).putInt(now.toInt())
        times.flip()
        output.write(times, header.first + 4)
    }

    private fun closePopup() {
        val view = popup ?: return
        popup = null
        // Posted: this is reached from the popup's own callbacks, and a WebView
        // must not be destroyed while it is still inside one.
        root.post {
            root.removeView(view)
            view.destroy()
        }
    }

    private fun linkFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
            ?.takeIf { it.scheme == "https" && Hosts.isPinterest(it.host.orEmpty()) }
            ?.toString()

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "nothing can open $uri")
        }
    }

    private fun download(url: String, userAgent: String?, disposition: String?, mime: String?) {
        // The site's "Download image" hands over a blob: address that only the
        // page itself can read. The picture it holds is the open pin's.
        if (url.startsWith("blob:")) return saveOpenPinImage()
        if (!URLUtil.isNetworkUrl(url)) return
        val name = URLUtil.guessFileName(url, disposition, mime)
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mime)
            .addRequestHeader("User-Agent", userAgent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        getSystemService(DownloadManager::class.java).enqueue(request)
    }

    private inner class Client(private val isPopup: Boolean) : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            Log.i(TAG, "page loaded ${SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()} ms after process start: $url")
            // Only where the WebView can't run it at document start. The script
            // guards against running twice and survives in-page navigation.
            if (!scriptAtStart && !isPopup && Hosts.isPinterest(Uri.parse(url.orEmpty()).host.orEmpty())) {
                view.evaluateJavascript(pageScript, null)
            }
        }

        // Fires for the site's in-page navigation as well as real page loads.
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (isPopup) return
            if (BuildConfig.DEBUG) Log.d(TAG, "at $url")
            pinVideo = null
            onPinPage = PIN_PAGE.containsMatchIn(Uri.parse(url.orEmpty()).path.orEmpty())
            videoButton.visibility = View.GONE
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            // intent:, market:, pinterest: — "open in the app" hand-offs. Stay put.
            if (uri.scheme != "https" && uri.scheme != "http") return true
            if (Hosts.staysInApp(uri.host.orEmpty())) return false
            Log.i(TAG, "opening in browser: ${uri.host}")
            openExternally(uri)
            // A popup that was only opened to follow an outbound link has nothing left to show.
            if (isPopup && !view.canGoBack()) closePopup()
            return true
        }

        // Called off the main thread for every request, so this stays a cheap
        // suffix check. Blocked requests complete as an empty 200 rather than
        // failing, which is what the page handles most gracefully.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host ?: return null
            // The first stream requested after a pin opens is that pin's video.
            if (onPinPage && pinVideo == null && host.endsWith(".pinimg.com")) {
                VIDEO_STREAM.find(request.url.path.orEmpty())?.let { m ->
                    pinVideo = Video(m.groupValues[1], m.groupValues[2].isNotEmpty(), m.groupValues[3], m.groupValues[4])
                    root.post { if (onPinPage && pinVideo != null) videoButton.visibility = View.VISIBLE }
                }
            }
            if (!Hosts.isBlocked(host)) return null
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            Log.w(TAG, "load failed: ${error.errorCode} ${error.description}")
            val retry = request.url.toString().replace("\"", "%22")
            view.loadDataWithBaseURL(
                null,
                """<html><head><meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="color-scheme" content="light dark"></head>
                <body style="font-family:sans-serif;text-align:center;padding-top:35vh">
                <p>Can't reach Pinterest.</p><p><a href="$retry">Try again</a></p></body></html>""",
                "text/html", "utf-8", null,
            )
        }

        // The renderer was killed (usually memory pressure). Without this the
        // whole app would die with it; rebuilding the activity restores the page.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "renderer gone, crashed=${detail.didCrash()}")
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        // Debug builds only: the page script reports what it hid.
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG && message.message().startsWith("DPX ")) Log.d(TAG, message.message())
            return true
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            closePopup()
            val window = WebView(this@MainActivity)
            configure(window, isPopup = true)
            popup = window
            root.addView(window, MATCH_PARENT, MATCH_PARENT)
            (resultMsg.obj as WebView.WebViewTransport).webView = window
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window === popup) closePopup()
        }

        // Photo and video pickers for creating pins and changing profile pictures.
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                filePicker.launch(params.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) return callback.onCustomViewHidden()
            fullscreenView = view
            fullscreenCallback = callback
            root.addView(view, MATCH_PARENT, MATCH_PARENT)
            web.visibility = View.GONE
        }

        override fun onHideCustomView() {
            val view = fullscreenView ?: return
            root.removeView(view)
            web.visibility = View.VISIBLE
            fullscreenCallback?.onCustomViewHidden()
            fullscreenView = null
            fullscreenCallback = null
        }
    }
}
