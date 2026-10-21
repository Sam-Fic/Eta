package io.github.mangi.eta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.oauth.OAuthCallback
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred

@SuppressLint("SetJavaScriptEnabled")
class OAuthLoginActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            fail(IllegalStateException(getString(R.string.oauth_missing_login_url)))
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(36), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        val close = TextView(this).apply {
            text = "×"
            textSize = 24f
            setTextColor(Color.BLACK)
            setPadding(dp(8), dp(4), dp(16), dp(4))
            setOnClickListener { fail(IllegalStateException(getString(R.string.provider_oauth_cancelled))) }
        }
        val title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: getString(R.string.provider_oauth_login_title)
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        bar.addView(close)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val web = WebView(this)
        webView = web
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        // OAuth 供应商全部走 https；禁混合内容，避免登录页的 http 子资源请求被注入或窃听。
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        val defaultUa = WebSettings.getDefaultUserAgent(this)
        web.settings.userAgentString = defaultUa.replace("; wv", "").replace(" Version/4.0", "")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleUri(request.url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUri(Uri.parse(url))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                handleUri(Uri.parse(url))
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) handleUri(request.url)
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        // API 36+ 手势返回不再回调 onBackPressed()，改用 OnBackPressedDispatcher（保留原语义：
        // WebView 可回退则回退页面，否则按「用户取消 OAuth」失败收尾）。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view != null && view.canGoBack()) {
                    view.goBack()
                    return
                }
                fail(IllegalStateException(getString(R.string.provider_oauth_cancelled)))
            }
        })
        web.loadUrl(url)
    }

    private fun handleUri(uri: Uri): Boolean {
        if (!OAuthCallback.isRedirect(uri)) return false
        runCatching { OAuthCallback.parse(uri) }
            .onSuccess { (code, state) -> succeed(code, state) }
            .onFailure { fail(it) }
        return true
    }

    private fun succeed(code: String, state: String?) {
        if (finished) return
        finished = true
        pending?.complete(code to state)
        pending = null
        finish()
    }

    private fun fail(error: Throwable) {
        if (finished) return
        finished = true
        pending?.completeExceptionally(error)
        pending = null
        finish()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        if (!finished) {
            pending?.completeExceptionally(IllegalStateException(getString(R.string.provider_oauth_cancelled)))
            pending = null
        }
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URL = "oauth_url"
        private const val EXTRA_TITLE = "oauth_title"
        @Volatile private var pending: CompletableDeferred<Pair<String, String?>>? = null
        @Volatile private var current: WeakReference<OAuthLoginActivity>? = null

        fun start(
            context: Context,
            url: String,
            deferred: CompletableDeferred<Pair<String, String?>>,
            title: String? = null,
        ) {
            pending?.let { old ->
                if (old.isActive) old.completeExceptionally(IllegalStateException(context.getString(R.string.provider_oauth_cancelled)))
            }
            pending = deferred
            val intent = Intent(context, OAuthLoginActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                if (!title.isNullOrBlank()) putExtra(EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun finishIfOpen() {
            current?.get()?.let { activity ->
                activity.finished = true
                activity.finish()
            }
        }
    }
}
