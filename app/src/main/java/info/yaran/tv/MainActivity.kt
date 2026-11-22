package info.yaran.tv

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {

    companion object {
        private const val FILE_CHOOSER_REQUEST = 4101
        private const val STORAGE_PERMISSION_REQUEST = 4102
        private const val STATE_LAST_URL = "last_main_frame_url"

        private val PURPLE_PRIMARY = Color.rgb(108, 67, 198)
        private val PURPLE_DARK = Color.rgb(55, 32, 111)
        private val PURPLE_DEEP = Color.rgb(36, 19, 79)
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorView: View

    private var lastMainFrameUrl: String = AppConfig.START_URL
    private var mainFrameFailed = false
    private var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var pendingDownload: DownloadInfo? = null

    private val storagePermissionLauncher by lazy(LazyThreadSafetyMode.NONE) {
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val info = pendingDownload
            pendingDownload = null
            if (granted && info != null) {
                enqueueDownload(info)
            } else if (info != null) {
                showToast("مجوز ذخیرهٔ فایل صادر نشد.")
            }
        }
    }

    private val regularTypeface: Typeface by lazy(LazyThreadSafetyMode.NONE) {
        loadTypeface("fonts/Vazirmatn-Regular.ttf", Typeface.create("sans-serif", Typeface.NORMAL))
    }
    private val boldTypeface: Typeface by lazy(LazyThreadSafetyMode.NONE) {
        Typeface.create(regularTypeface, Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        title = getString(R.string.app_name)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = PURPLE_DARK
        window.navigationBarColor = PURPLE_DEEP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        CookieManager.getInstance().setAcceptCookie(true)
        createInterface()
        configureBackHandling()

        if (savedInstanceState != null) {
            lastMainFrameUrl = savedInstanceState.getString(STATE_LAST_URL) ?: AppConfig.START_URL
            val restored = webView.restoreState(savedInstanceState)
            if (restored == null) {
                webView.loadUrl(lastMainFrameUrl)
            }
        } else {
            webView.loadUrl(AppConfig.START_URL)
        }
    }

    private fun createInterface() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(PURPLE_DARK)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(PURPLE_PRIMARY)
            setProgressBackgroundColorSchemeColor(Color.WHITE)
            setOnRefreshListener {
                if (errorView.visibility == View.VISIBLE) {
                    retryCurrentPage()
                } else {
                    webView.reload()
                }
            }
        }

        webView = createWebView()
        swipeRefresh.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            webView.canScrollVertically(-1)
        }
        rootLayout.addView(
            swipeRefresh,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        loadingIndicator = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(PURPLE_PRIMARY)
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.rgb(232, 224, 248))
            visibility = View.GONE
            isIndeterminate = false
        }
        rootLayout.addView(
            loadingIndicator,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3),
                Gravity.TOP
            )
        )

        errorView = createErrorView().apply {
            visibility = View.GONE
        }
        rootLayout.addView(
            errorView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                max(bars.bottom, ime.bottom)
            )
            insets
        }

        setContentView(rootLayout)
    }

    private fun createWebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(true)
                loadsImagesAutomatically = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                defaultTextEncodingName = "UTF-8"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return handleNavigation(url)
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return if (url.isNullOrBlank()) false else handleNavigation(url)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        lastMainFrameUrl = url
                    }
                    mainFrameFailed = false
                    hideError()
                    loadingIndicator.progress = 0
                    loadingIndicator.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!mainFrameFailed) {
                        hideError()
                        loadingIndicator.progress = 100
                        loadingIndicator.visibility = View.GONE
                    }
                    swipeRefresh.isRefreshing = false
                    CookieManager.getInstance().flush()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        lastMainFrameUrl = request.url.toString()
                        showError()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    if (request?.isForMainFrame == true &&
                        (errorResponse?.statusCode ?: 0) >= 500
                    ) {
                        lastMainFrameUrl = request.url.toString()
                        showError()
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.cancel()
                    if (error?.url == lastMainFrameUrl) {
                        showError()
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    if (view != null && view === webView) {
                        recoverWebViewAfterRendererExit(view)
                    }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadingIndicator.progress = newProgress
                    if (newProgress in 0..99 && !mainFrameFailed) {
                        loadingIndicator.visibility = View.VISIBLE
                    } else if (newProgress >= 100 && !mainFrameFailed) {
                        loadingIndicator.visibility = View.GONE
                    }
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback

                    return try {
                        val params = fileChooserParams
                            ?: throw IllegalArgumentException("Missing file chooser parameters")
                        val chooserIntent = params.createIntent()
                        if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            chooserIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        startActivityForResult(
                            Intent.createChooser(chooserIntent, "انتخاب فایل"),
                            FILE_CHOOSER_REQUEST
                        )
                        true
                    } catch (_: ActivityNotFoundException) {
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                        this@MainActivity.filePathCallback = null
                        showToast("انتخاب‌گر فایل در دسترس نیست.")
                        false
                    } catch (_: Exception) {
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                        this@MainActivity.filePathCallback = null
                        showToast("امکان انتخاب فایل وجود ندارد.")
                        false
                    }
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    if (!isUserGesture || resultMsg == null) return false

                    val popup = WebView(this@MainActivity)
                    popup.settings.javaScriptEnabled = true
                    popup.settings.domStorageEnabled = true
                    var forwarded = false

                    fun forwardToMainWindow(url: String) {
                        if (forwarded || url == "about:blank") return
                        val scheme = Uri.parse(url).scheme?.lowercase(Locale.ROOT)
                        if (scheme == "http" || scheme == "https") {
                            forwarded = true
                            lastMainFrameUrl = url
                            hideError()
                            webView.loadUrl(url)
                        }
                        popup.post {
                            popup.stopLoading()
                            popup.destroy()
                        }
                    }

                    popup.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            forwardToMainWindow(url)
                            return true
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            if (!url.isNullOrBlank()) {
                                forwardToMainWindow(url)
                            }
                        }
                    }

                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
                        popup.destroy()
                        return false
                    }
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }

            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                beginDownload(url, userAgent, contentDisposition, mimeType)
            })
        }
    }

    private fun handleNavigation(url: String): Boolean {
        val uri = Uri.parse(url)
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> {
                lastMainFrameUrl = url
                false
            }
            "tel" -> {
                launchExternalIntent(Intent(Intent.ACTION_DIAL, uri))
                true
            }
            "mailto" -> {
                launchExternalIntent(Intent(Intent.ACTION_SENDTO, uri))
                true
            }
            "about", "javascript", "data", "blob" -> false
            else -> true
        }
    }

    private fun launchExternalIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showToast("برنامه‌ای برای باز کردن این پیوند یافت نشد.")
        }
    }

    private fun createErrorView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(PURPLE_DEEP, PURPLE_DARK, PURPLE_PRIMARY)
            )
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_cloud_off)
            contentDescription = "عدم اتصال به اینترنت"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(
            icon,
            LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                bottomMargin = dp(28)
            }
        )

        val title = TextView(this).apply {
            text = "اتصال به اینترنت برقرار نیست"
            textSize = 20f
            typeface = boldTypeface
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        container.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(28)
            }
        )

        val retryButton = Button(this).apply {
            text = "تلاش مجدد"
            isAllCaps = false
            textSize = 16f
            typeface = boldTypeface
            setTextColor(PURPLE_PRIMARY)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
            }
            minHeight = dp(52)
            setPadding(dp(28), dp(8), dp(28), dp(8))
            setOnClickListener { retryCurrentPage() }
        }
        container.addView(
            retryButton,
            LinearLayout.LayoutParams(dp(184), dp(54))
        )

        container.contentDescription = "اتصال به اینترنت برقرار نیست"
        return container
    }

    private fun showError() {
        mainFrameFailed = true
        loadingIndicator.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun hideError() {
        if (!::errorView.isInitialized || !::webView.isInitialized) return
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun retryCurrentPage() {
        val target = lastMainFrameUrl.takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        } ?: AppConfig.START_URL
        mainFrameFailed = false
        hideError()
        loadingIndicator.progress = 0
        loadingIndicator.visibility = View.VISIBLE
        webView.loadUrl(target)
    }

    private fun recoverWebViewAfterRendererExit(deadWebView: WebView) {
        (deadWebView.parent as? ViewGroup)?.removeView(deadWebView)
        deadWebView.destroy()
        webView = createWebView()
        swipeRefresh.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        showError()
    }

    private fun beginDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val uri = Uri.parse(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            showToast("برای دریافت امن فایل، پیوند HTTPS لازم است.")
            return
        }

        val info = DownloadInfo(url, userAgent, contentDisposition, mimeType)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = info
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueueDownload(info)
    }

    private fun enqueueDownload(info: DownloadInfo) {
        try {
            val fileName = URLUtil.guessFileName(
                info.url,
                info.contentDisposition,
                info.mimeType
            ).substringAfterLast('/')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(120)
                .ifBlank { "YaranTV-download" }

            val request = DownloadManager.Request(Uri.parse(info.url)).apply {
                setTitle(fileName)
                setDescription("دریافت فایل")
                setMimeType(info.mimeType ?: "application/octet-stream")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                info.userAgent?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("User-Agent", it)
                }
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
            }

            val manager = getSystemService(DOWNLOAD_SERVICE) as? DownloadManager
                ?: throw IllegalStateException("DownloadManager unavailable")
            manager.enqueue(request)
            showToast("دانلود فایل آغاز شد.")
        } catch (_: Exception) {
            showToast("امکان آغاز دانلود وجود ندارد.")
        }
    }

    @Deprecated("Activity result callback used by WebView file chooser")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val results = if (resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun loadTypeface(assetPath: String, fallback: Typeface): Typeface {
        return try {
            Typeface.createFromAsset(assets, assetPath)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) {
            webView.saveState(outState)
        }
        outState.putString(STATE_LAST_URL, lastMainFrameUrl)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
            CookieManager.getInstance().flush()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
        }
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        CookieManager.getInstance().flush()
        super.onDestroy()
    }

    private data class DownloadInfo(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )
}
