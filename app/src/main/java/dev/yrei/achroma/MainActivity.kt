package dev.yrei.achroma

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URISyntaxException
import java.util.Locale
import java.util.concurrent.CancellationException

// 游戏文件在本地（见 GameFiles），但挂在线上的同一个地址下：页面的源还是 game.unsnow.online，
// 相对路径的 api/ 请求照常发到服务器，登录 cookie、成绩上传和排行榜都和网页版一样
private const val GAME_HOST = "game.unsnow.online"
private const val GAME_PATH = "/ach/"
internal const val GAME_URL = "https://$GAME_HOST$GAME_PATH"
private const val BG = 0xFF050506.toInt()
// 加载进度条用游戏里登录按钮高亮的 linux.do 黄
private const val LOADING_COLOR = 0xFFFFB003.toInt()
private const val TAG = "MainActivity"

// 应用本身已经全屏横屏，让游戏以为处在全屏状态：不再弹“全屏 + 横屏”询问，也不显示全屏按钮；
// 设置里的全屏开关和 F 键调到的进出全屏什么都不做
private const val FULLSCREEN_SHIM = """
(function () {
  var full = { configurable: true, get: function () { return document.documentElement; } };
  Object.defineProperty(Document.prototype, 'fullscreenElement', full);
  Object.defineProperty(Document.prototype, 'webkitFullscreenElement', full);
  var none = function () { return Promise.resolve(); };
  Document.prototype.exitFullscreen = Document.prototype.webkitExitFullscreen = none;
  Element.prototype.requestFullscreen = Element.prototype.webkitRequestFullscreen = none;
})();
"""

// 数页面加载时的脚本报错（文件加载失败、脚本抛异常），下载来的新版本第一次启动时据此判断起没起来
private const val ERROR_COUNTER = "window.ACH_ERRORS = 0; addEventListener('error', function () { ACH_ERRORS++; }, true);"
private const val BOOT_OK_JS = "typeof ACH_ERRORS !== 'number' || ACH_ERRORS === 0"

// 返回键等同于游戏里的 Esc（对局中暂停、菜单里回上一级、更新提示里选稍后）；停在主菜单时告诉外面可以退出了
private const val BACK_JS = """
(function () {
  var k = { key: 'Escape', code: 'Escape', bubbles: true };
  dispatchEvent(new KeyboardEvent('keydown', k));
  setTimeout(function () { dispatchEvent(new KeyboardEvent('keyup', k)); }, 100);
  try {
    if (!(G.state === 'menu' && MENU.page === 'main' && !MENU.stack.length)) return 'esc';
    // 更新提示开着、马上要弹（这一下 Esc 会被它吞掉），或者马上要换新版本重新载入：都不算要退出
    var u = window.ACH_UPD;
    return u && !u.dead && (u.open || u.want || u.apply) ? 'esc' : 'exit';
  } catch (e) { return 'exit'; }
})()
"""

// 停在主菜单首页时弹提示、换版本不丢东西（存档和设置都在 localStorage）。游戏改版认不出来时返回 null
private const val AT_MAIN_MENU_JS =
    "(function () { try { return G.state === 'menu' && MENU.page === 'main' && !MENU.stack.length; } catch (e) { return null; } })()"

// 切到后台：对局中自动暂停，并停掉音频；回来时恢复音频，暂停菜单留给玩家自己点继续
private const val PAUSE_JS = "try { if (G.state === 'play') openPause('play'); } catch (e) {} try { AU.ctx && AU.ctx.suspend(); } catch (e) {}"
private const val RESUME_JS = "try { AU.ctx && AU.ctx.resume(); } catch (e) {}"

// 新版本提示画在哪：游戏画面里（注入 assets/app/update.js 成功）或系统对话框（注入不上）；页面没加载完时先不提示
private enum class Prompt { NONE, GAME, DIALOG }

class MainActivity : ComponentActivity() {
    private lateinit var game: GameFiles
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var loadingBar: ProgressBar
    private var loading = false
    private var loadProgress = 0
    private val showLoading = Runnable { if (loading) loadingBar.visibility = View.VISIBLE }
    // 重定向期间真实进度一直停在 10%，像浏览器一样让进度条自己慢慢往前走（最多到 80%），看得出在加载
    private val loadingCreep = ValueAnimator.ofFloat(0f, 80f).apply {
        duration = 10_000
        interpolator = DecelerateInterpolator()
        addUpdateListener { updateLoadingBar() }
    }
    private var backAt = 0L

    private val updateJs by lazy { assets.open("app/update.js").bufferedReader().use { it.readText() } }
    private var prompt = Prompt.NONE
    // 下载中才有；玩家取消时马上清掉，后台线程收尾时的回调就不再理会
    private var cancelDownload: (() -> Unit)? = null
    private var downloading = 0
    private var progressDone = 0
    private var progressTotal = 0
    private var failed = false
    private var updatedTo = 0
    private var dialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    // 系统对话框不像游戏里的面板能自己等到回主菜单：有要弹的就每秒看一次
    private val menuPoll = Runnable { syncUpdate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        setContentView(root)
        // 画面铺满到挖孔下面：挖孔在横屏短边正中，默认布局里那里只有场景没有按钮，留黑边反而让画面偏到一侧。
        // 只在输入法弹起时（linux.do 登录页）把页面顶上去
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            WindowInsetsCompat.CONSUMED
        }
        hideSystemBars()
        // 跳去 linux.do 授权、授权完跳回游戏要经过好几次重定向，常常要好几秒；应用全屏没有地址栏，
        // 在顶边画一条进度条，免得点了登录像没反应
        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressDrawable = ClipDrawable(ColorDrawable(LOADING_COLOR), Gravity.START, ClipDrawable.HORIZONTAL)
            max = 1000
            visibility = View.GONE
        }
        root.addView(loadingBar, FrameLayout.LayoutParams(MATCH_PARENT, (3 * resources.displayMetrics.density).toInt(), Gravity.TOP))

        game = GameFiles.get(this)
        // 进程可能还活着（退出后马上又打开），重新打开应用总要检查一次
        game.resetCheckTimer()
        createWebView()
        web.loadUrl(GAME_URL)
        onBackPressedDispatcher.addCallback(this) { onBack() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(GAME_HOST)
            .addPathHandler(GAME_PATH, game.pathHandler)
            .build()

        web = WebView(this).apply {
            setBackgroundColor(BG)
            overScrollMode = View.OVER_SCROLL_NEVER
            isHapticFeedbackEnabled = false
            setOnLongClickListener { true } // 长按不弹文字选择
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true // 存档、设置、待上传的成绩都在 localStorage
                mediaPlaybackRequiresUserGesture = false
                textZoom = 100
                setSupportZoom(false)
                allowFileAccess = false
                allowContentAccess = false
                userAgentString = "$userAgentString AchromaApp/${BuildConfig.VERSION_NAME}"
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (view === web) onLoadProgress(newProgress)
                }
            }
            webViewClient = GameClient(loader)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, FULLSCREEN_SHIM + ERROR_COUNTER, setOf("https://$GAME_HOST"))
        }
        // 更新提示里玩家的选择从这里传回来；只有游戏域名的页面拿得到 AchromaBridge，linux.do 的页面没有
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "AchromaBridge", setOf("https://$GAME_HOST")) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) onBridgeMessage(message.data.orEmpty())
            }
        }
        root.addView(web, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) // 压在进度条下面
    }

    // 游戏本身从本地载入很快，超过 0.3 秒还没载完才显示进度条，免得每次打开都闪一下
    private fun onLoadProgress(progress: Int) {
        loadProgress = progress
        if (progress < 100) {
            if (!loading) {
                loading = true
                loadingCreep.start()
                root.postDelayed(showLoading, 300)
            }
            updateLoadingBar()
        } else {
            loading = false
            loadingCreep.cancel()
            root.removeCallbacks(showLoading)
            loadingBar.visibility = View.GONE
        }
    }

    private fun updateLoadingBar() {
        loadingBar.progress = (maxOf(loadProgress.toFloat(), loadingCreep.animatedValue as? Float ?: 0f) * 10).toInt()
    }

    private inner class GameClient(private val loader: WebViewAssetLoader) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            loader.shouldInterceptRequest(request.url)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            prompt = Prompt.NONE
            failed = false // 重新载入后还没回答的新版本会重新问，不用再提下载失败
        }

        // 游戏页面加载完：下载来的新版本先确认起来了，再挂上更新提示
        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== web || !isGamePage(url)) return
            if (!game.needsBootCheck) return attachUpdate(view)
            view.evaluateJavascript(BOOT_OK_JS) { ok ->
                if (view !== web) return@evaluateJavascript
                if (ok == "false") {
                    rollback()
                } else {
                    game.booted()
                    attachUpdate(view)
                }
            }
        }

        // 游戏里只会跳去 linux.do 授权，整个登录流程留在应用内；intent:、mailto: 之类交给系统
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme == "https" || url.scheme == "http") return false
            // 不是玩家点出来的子框架跳转（广告常见的手法）不放行
            if (!request.isForMainFrame && !request.hasGesture()) return true
            val intent = try {
                if (url.scheme == "intent") Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, url)
            } catch (_: URISyntaxException) {
                return true
            }
            // 和浏览器一样：只拉起声明了能从网页打开的界面，不许网页指定具体组件或带上文件读写授权
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            intent.flags = 0
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // 没装对应的应用：intent: 链接带了网页地址就打开网页
                intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("https://") }?.let { view.loadUrl(it) }
            }
            return true
        }

        // 渲染进程崩溃或被系统回收：换一个新的 WebView 重进游戏，存档在 localStorage 里不受影响；
        // 正在进行的下载不受影响，页面加载完会接着显示进度
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onLoadProgress(100)
            root.removeView(view)
            view.destroy()
            createWebView()
            web.loadUrl(GAME_URL)
            return true
        }
    }

    private fun isGamePage(url: String?) =
        url != null && url.startsWith(GAME_URL) && !url.startsWith(GAME_URL + "api/")

    private fun onBack() {
        // 在 linux.do 登录页里：按网页历史后退，退到头就回游戏
        if (!isGamePage(web.url)) {
            if (web.canGoBack()) web.goBack() else web.loadUrl(GAME_URL)
            return
        }
        web.evaluateJavascript(BACK_JS) { result ->
            if (result != "\"exit\"") return@evaluateJavascript
            val now = SystemClock.uptimeMillis()
            if (now - backAt < 2000) {
                finish()
            } else {
                backAt = now
                Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        web.evaluateJavascript(PAUSE_JS, null)
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.evaluateJavascript(RESUME_JS, null)
        game.checkForUpdate { runOnUiThread { if (!isDestroyed) syncUpdate() } }
        syncUpdate()
    }

    override fun onStop() {
        root.removeCallbacks(menuPoll)
        super.onStop()
    }

    override fun onDestroy() {
        root.removeCallbacks(menuPoll)
        root.removeCallbacks(showLoading)
        loadingCreep.cancel()
        cancelDownload?.invoke()
        dialog?.dismiss()
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ---- 新版本提示：问玩家 → 下载并显示进度 → 回到主菜单首页时换上新版本重新载入 ----

    private fun attachUpdate(view: WebView) {
        view.evaluateJavascript(updateJs) { ok ->
            if (view !== web) return@evaluateJavascript
            prompt = if (ok == "true") Prompt.GAME else Prompt.DIALOG
            if (prompt == Prompt.DIALOG) Log.w(TAG, "update panel unavailable, using dialog")
            if (updatedTo != 0) showUpdated(updatedTo)
            updatedTo = 0
            syncUpdate()
        }
    }

    /**
     * 把更新进行到哪一步交给当前的提示方式，优先级：下载中 → 已下好待换上 → 下载失败 → 有新版本。
     * 游戏里的面板自己等回到主菜单首页才弹、才换版本；系统对话框由这里每秒看一次，认不出游戏状态时直接弹
     */
    private fun syncUpdate() {
        root.removeCallbacks(menuPoll)
        val offer = game.offer
        when (prompt) {
            Prompt.GAME -> web.evaluateJavascript(
                when {
                    cancelDownload != null -> "ACH_UPD.busy($downloading, $progressDone, $progressTotal)"
                    game.readyVersion != null -> "ACH_UPD.ready()"
                    failed -> "ACH_UPD.failed()"
                    offer != null -> "ACH_UPD.offer(${game.version}, ${offer.version}, '${sizeOf(offer)}')"
                    else -> "ACH_UPD.idle()" // 线上撤回了新版本之类，开着的面板收起来
                },
                null,
            )
            Prompt.DIALOG -> {
                val pending = cancelDownload != null || game.readyVersion != null || failed || offer != null
                if (dialog != null || !pending || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                web.evaluateJavascript(AT_MAIN_MENU_JS) { atMain ->
                    if (prompt != Prompt.DIALOG || dialog != null || isDestroyed) return@evaluateJavascript
                    if (atMain == "false") {
                        root.postDelayed(menuPoll, 1000)
                        return@evaluateJavascript
                    }
                    val now = game.offer
                    when {
                        cancelDownload != null -> progressDialog()
                        game.readyVersion != null -> reloadWithUpdate()
                        failed -> askDialog(getString(R.string.update_failed), R.string.update_retry)
                        now != null -> askDialog(getString(R.string.update_ask, now.version, game.version, sizeOf(now)), R.string.update_now)
                    }
                }
            }
            Prompt.NONE -> {}
        }
    }

    private fun sizeOf(offer: GameFiles.Offer) = String.format(Locale.US, "%.1f", (offer.bytes / 1_048_576.0).coerceAtLeast(0.1))

    private fun onBridgeMessage(msg: String) {
        when {
            msg == "update" -> startDownload()
            msg == "later" -> {
                failed = false
                game.decline()
            }
            msg == "cancel" -> cancelUpdate()
            msg == "apply" -> reloadWithUpdate()
            // 游戏里的面板出错停用了：改用系统对话框，正在下载就接着显示进度
            msg.startsWith("broken") -> {
                Log.w(TAG, "update panel $msg")
                prompt = Prompt.DIALOG
                syncUpdate()
            }
        }
    }

    private fun startDownload() {
        if (cancelDownload != null) return
        failed = false
        // 问的时候还有、点下去时已经撤回了：收起提示
        val offer = game.offer ?: return syncUpdate()
        downloading = offer.version
        progressDone = 0
        progressTotal = 0
        lateinit var cancel: () -> Unit
        cancel = game.download(
            progress = { done, total -> runOnUiThread { if (cancelDownload === cancel) onProgress(done, total) } },
            done = { error -> runOnUiThread { if (cancelDownload === cancel) onDownloaded(error) } },
        )
        cancelDownload = cancel
        if (prompt == Prompt.DIALOG) progressDialog()
    }

    private fun cancelUpdate() {
        cancelDownload?.invoke()
        cancelDownload = null
        dismissDialog()
        game.decline()
    }

    private fun onProgress(done: Int, total: Int) {
        if (isDestroyed) return
        progressDone = done
        progressTotal = total
        if (prompt == Prompt.GAME) web.evaluateJavascript("ACH_UPD.progress($done, $total)", null)
        progressBar?.let {
            it.max = total
            it.progress = done
            dialog?.setMessage("${getString(R.string.update_downloading, downloading)}  $done / $total")
        }
    }

    // 下好了也不马上重新载入：等玩家回到主菜单首页（页面重新载入过、玩家已经开了一局时）
    private fun onDownloaded(error: Exception?) {
        cancelDownload = null
        dismissDialog()
        if (isDestroyed) return // 下好的版本下次打开时用上
        when (error) {
            null -> {}
            is CancellationException -> game.decline()
            else -> failed = true
        }
        syncUpdate()
    }

    private fun reloadWithUpdate() {
        updatedTo = game.applyUpdate() ?: return
        web.loadUrl(GAME_URL)
    }

    // 下载来的新版本起不来：退回原来的版本重新载入
    private fun rollback() {
        val bad = game.version
        val back = game.rollback() ?: return
        updatedTo = 0
        Toast.makeText(this, getString(R.string.update_rolled_back, bad, back), Toast.LENGTH_LONG).show()
        web.loadUrl(GAME_URL)
    }

    private fun showUpdated(version: Int) {
        if (prompt == Prompt.GAME) web.evaluateJavascript("ACH_UPD.updated($version)", null)
        else Toast.makeText(this, getString(R.string.update_done, version), Toast.LENGTH_SHORT).show()
    }

    private fun askDialog(message: String, ok: Int) = showDialog(
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(ok) { _, _ -> startDownload() }
            .setNegativeButton(R.string.update_later) { _, _ -> declineDialog() }
            .setOnCancelListener { declineDialog() }
    )

    private fun declineDialog() {
        failed = false
        game.decline()
    }

    private fun progressDialog() {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        val pad = (24 * resources.displayMetrics.density).toInt()
        showDialog(
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.update_downloading, downloading))
                .setView(FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(bar) })
                .setNegativeButton(R.string.update_cancel) { _, _ -> cancelUpdate() }
                .setCancelable(false)
        )
        progressBar = bar
        if (progressTotal > 0) onProgress(progressDone, progressTotal)
    }

    private fun showDialog(builder: AlertDialog.Builder) {
        dismissDialog()
        // 旧对话框的关闭回调是异步来的，只清掉自己
        dialog = builder.setOnDismissListener { if (dialog === it) { dialog = null; progressBar = null } }.show()
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
        progressBar = null
    }
}
