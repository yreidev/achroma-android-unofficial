package dev.yrei.achroma

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import androidx.webkit.WebViewAssetLoader.PathHandler
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "GameFiles"
private const val ASSET_DIR = "ach/"
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

// 网页版每次更新都会改脚本链接上的 ?v= 来刷新浏览器缓存，拿它当版本号
private val VERSION = Regex("""\?v=(\d+)""")
// index.html 引用的本站文件（脚本、图标等）；带协议的外链和锚点不算
private val LOCAL_REF = Regex("""(?:src|href)="([^"#:]+)"""")
// 注释掉的引用不算，那些文件线上可能已经删了；没闭合的注释一直算到文件末尾
private val COMMENT = Regex("""<!--.*?(?:-->|$)""", RegexOption.DOT_MATCHES_ALL)

private fun versionOf(html: String) =
    VERSION.findAll(html.replace(COMMENT, "")).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()

private fun refsOf(html: String) = LOCAL_REF.findAll(html.replace(COMMENT, "")).map { it.groupValues[1] }
    .filter { !it.startsWith("/") && !it.startsWith("api/") }
    .distinct()
    .toList()

/**
 * 游戏文件从哪里读：安装包自带的 assets/ach/，或玩家同意更新后下载到 files/game/v<版本>/ 的新版本，取版本号大的。
 * 后台只检查版本号，发现新版本记到 [offer]，由界面问玩家；同意了才下载，整套下到临时目录、全部成功才改名生效。
 * 下载来的版本第一次启动时由界面检查，起不来就退回原来的版本，并记下这个版本不再提示。
 */
class GameFiles private constructor(private val context: Context) {
    private class Source(val version: Int, val handler: PathHandler, size: () -> Long) {
        /** 这一版文件的总大小，估新版本的下载量用；第一次用到时才算 */
        val bytes by lazy { runCatching(size).getOrDefault(0L) }
    }

    /** 线上有比当前更新的版本；bytes 按当前版本的大小估算 */
    class Offer(val version: Int, val bytes: Long)

    private val root = File(context.filesDir, "game")
    private val prefs = context.getSharedPreferences("game", Context.MODE_PRIVATE)
    private val userAgent = "${WebSettings.getDefaultUserAgent(context)} AchromaApp/${BuildConfig.VERSION_NAME}"
    private val checking = AtomicBoolean(false)
    // 同一时间只跑一个下载线程：取消后的线程要等当前文件下完才退出，新的下载得等它
    private val downloadLock = Any()
    @Volatile private var checkedAt = 0L
    // 启动失败退回过的版本、确认能启动的下载版本
    @Volatile private var broken = prefs.getInt("broken", 0)
    @Volatile private var verified = prefs.getInt("verified", 0)
    private val bundled = bundledSource()
    // 启动时在后台删旧版本的线程，下载前等它删完
    private var cleanup: Thread? = null
    @Volatile private var active = pick()
    @Volatile private var previous: Source? = null
    @Volatile private var ready: Source? = null

    @Volatile var offer: Offer? = null
        private set

    val version get() = active.version

    /** 已经下好、还没换上的新版本 */
    val readyVersion get() = ready?.version

    /** 当前用的是下载来的版本，还没确认它能正常启动 */
    val needsBootCheck get() = active !== bundled && active.version != verified

    /** 挂在游戏路径下：api/ 返回 null 交给网络，其余从当前来源读 */
    val pathHandler = PathHandler { path ->
        if (path.startsWith("api/")) null else active.handler.handle(path.ifEmpty { "index.html" })
    }

    /**
     * 距上次成功检查超过 6 小时，就在后台线程看一次网页版的版本号；都失败了下次回到前台再试。
     * 检查完在后台线程调 checked，有新版本时在 [offer] 里，没有时 [offer] 为空。
     */
    fun checkForUpdate(checked: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (checkedAt != 0L && now - checkedAt < CHECK_INTERVAL_MS) return
        if (!checking.compareAndSet(false, true)) return
        thread(name = "game-check", isDaemon = true) {
            try {
                // 刚冷启动时有时还解析不了域名（一加上实测过），隔 10 秒再试一次
                for (attempt in 1..2) {
                    try {
                        check()
                        checkedAt = now
                        checked()
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "update check failed ($attempt): $e")
                        if (attempt == 1) SystemClock.sleep(10_000)
                    }
                }
            } finally {
                checking.set(false)
            }
        }
    }

    /** 重新打开应用时调：下一次 [checkForUpdate] 不受 6 小时间隔限制，选过“稍后”的版本会再问 */
    fun resetCheckTimer() {
        checkedAt = 0L
    }

    /** 玩家选了“稍后”或取消了下载：这次不再问，等下一次检查（重新打开应用或 6 小时后）再问 */
    fun decline() {
        offer = null
    }

    /**
     * 下载线上最新版本，成功后在 [readyVersion] 里；线上已经没有更新的版本时也算成功，[readyVersion] 为空。
     * progress(已完成, 总数) 和 done(错误，取消时是 CancellationException) 都在后台线程回调。返回取消函数。
     */
    fun download(progress: (Int, Int) -> Unit, done: (Exception?) -> Unit): () -> Unit {
        val cancelled = AtomicBoolean(false)
        thread(name = "game-download", isDaemon = true) {
            val error = try {
                synchronized(downloadLock) { fetchAll(progress) { cancelled.get() } }
                null
            } catch (e: Exception) {
                // 取消之后正在下的文件报错（超时、断网）也算取消，不当成下载失败
                if (cancelled.get()) CancellationException() else e.also { Log.w(TAG, "download failed: $it") }
            }
            done(error)
        }
        return { cancelled.set(true) }
    }

    /** 换到刚下载好的版本，返回新版本号；调用方随后重新加载页面 */
    fun applyUpdate(): Int? {
        val next = ready ?: return null
        previous = active
        active = next
        ready = null
        Log.i(TAG, "switched to v${next.version}")
        return next.version
    }

    /** 当前版本启动检查通过 */
    fun booted() {
        if (!needsBootCheck) return
        verified = active.version
        prefs.edit { putInt("verified", verified) }
    }

    /** 当前版本启动失败：退回换版本前的那份（冷启动时是安装包自带的），删掉它并不再提示。返回退回后的版本号 */
    fun rollback(): Int? {
        val bad = active.takeIf { it !== bundled } ?: return null
        active = previous ?: bundled
        previous = null
        broken = bad.version
        prefs.edit { putInt("broken", broken) }
        thread(name = "game-cleanup", isDaemon = true) { File(root, "v${bad.version}").deleteRecursively() }
        Log.w(TAG, "v${bad.version} failed to start, back to v${active.version}")
        return active.version
    }

    private fun bundledSource(): Source {
        val version = context.assets.open(ASSET_DIR + "index.html").bufferedReader().use { versionOf(it.readText()) } ?: 0
        val assets = AssetsPathHandler(context)
        return Source(version, PathHandler { assets.handle(ASSET_DIR + it) }) { assetSize(ASSET_DIR.removeSuffix("/")) }
    }

    private fun pick(): Source {
        val downloaded = root.listFiles().orEmpty()
            .mapNotNull { dir -> dir.name.removePrefix("v").toIntOrNull()?.let { it to dir } }
            .filter { (version, _) -> version > bundled.version && version != broken }
            .maxByOrNull { it.first }
        // 只留要用的那份；旧版本、下载到一半的临时目录、不比安装包新的版本（刚装了新 APK）、启动失败的版本都在后台删掉
        val stale = root.listFiles().orEmpty().filter { it != downloaded?.second }
        if (stale.isNotEmpty()) cleanup = thread(name = "game-cleanup", isDaemon = true) { stale.forEach { it.deleteRecursively() } }
        if (downloaded != null) {
            Log.i(TAG, "using downloaded v${downloaded.first} (bundled v${bundled.version})")
            return downloadedSource(downloaded.first, downloaded.second)
        }
        Log.i(TAG, "using bundled v${bundled.version}")
        return bundled
    }

    private fun downloadedSource(version: Int, dir: File) =
        Source(version, InternalStoragePathHandler(context, dir)) { dir.walk().filter { it.isFile }.sumOf { it.length() } }

    // assets 里压缩过的文件拿不到大小，只能读一遍数字节
    private fun assetSize(path: String): Long {
        val children = context.assets.list(path).orEmpty()
        if (children.isNotEmpty()) return children.sumOf { assetSize("$path/$it") }
        return context.assets.open(path).use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            total
        }
    }

    @WorkerThread
    private fun check() {
        val html = fetch(GAME_URL).decodeToString()
        val version = versionOf(html)
        if (version == null || version <= (ready ?: active).version || version == broken) {
            offer = null
            return
        }
        // 新版本的大小没法提前知道，按当前版本的大小估
        offer = Offer(version, active.bytes)
        Log.i(TAG, "v$version available")
    }

    @WorkerThread
    private fun fetchAll(progress: (Int, Int) -> Unit, cancelled: () -> Boolean) {
        cleanup?.join()
        val index = fetch(GAME_URL)
        val html = index.decodeToString()
        val version = versionOf(html) ?: throw IOException("no version in index.html")
        // 线上撤回了新版本，或者已经下过：没有可更新的
        if (version <= (ready ?: active).version || version == broken) {
            offer = null
            Log.i(TAG, "v$version is not newer, nothing to download")
            return
        }
        val refs = refsOf(html)
        // 一个本站文件都没引用，多半是网页结构变了，换上去只会白屏
        if (refs.isEmpty()) throw IOException("index.html references no local files")
        val total = refs.size + 1
        val tmp = File(root, "tmp-v$version")
        tmp.deleteRecursively()
        try {
            save(tmp, "index.html", index)
            progress(1, total)
            refs.forEachIndexed { i, ref ->
                if (cancelled()) throw CancellationException()
                save(tmp, ref.substringBefore('?'), fetch(GAME_URL + ref))
                progress(i + 2, total)
            }
            // 最后一个文件下载时点了取消，也不换上
            if (cancelled()) throw CancellationException()
            val dir = File(root, "v$version")
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IOException("rename failed: $tmp")
            ready = downloadedSource(version, dir)
            offer = null
            Log.i(TAG, "v$version downloaded")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun save(dir: File, name: String, bytes: ByteArray) {
        val file = File(dir, name).canonicalFile
        // 引用路径带 ../ 跑出目录的，整次更新作废
        if (!file.path.startsWith(dir.canonicalPath + File.separator)) throw IOException("bad path: $name")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun fetch(url: String): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}: $url")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) throw IOException("empty: $url")
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        @Volatile private var instance: GameFiles? = null

        fun get(context: Context): GameFiles =
            instance ?: synchronized(this) { instance ?: GameFiles(context.applicationContext).also { instance = it } }
    }
}
