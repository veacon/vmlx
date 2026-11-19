package dev.amatv.veilsource

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 匿名 developer token（Bearer JWT）抓取器。
 *
 * 移植自 veil.music `data/AppleDisplayAuth.kt` 的第 1 步（令牌获取），裁掉全部
 * 登录/个性化展示段（MUT、storefront、账号名、资料库歌单）——am_atv 的 P1
 * 手机令牌登录改造延后，本次音源接入只需要「未登录也能访问 amp-api 匿名
 * catalog」这一件事。
 *
 * 抓取链路（与 veil 完全一致）：
 *  1. GET `https://music.apple.com/` 首页；
 *  2. 从 HTML 里找 `crossorigin src="(/assets/index*.js)"`；
 *  3. 下载该 JS，正则提取 `eyJ…` JWT 作为 Bearer token；
 *  4. 内存 + SharedPreferences 双缓存 12 小时。
 *
 * 该 token 只用于 `amp-api.music.apple.com` 的匿名 catalog 请求（浏览/榜单/
 * 搜索等公开目录数据），不携带任何用户身份，也不访问任何 `/v1/me/` 端点。
 * 网络出口全部经 [anonymousRequest] 构造：只允许 GET，且目标 host 在白名单内。
 */
class AppleDisplayAuth private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val refreshMutex = Mutex()

    /** 抓取专用客户端：独立连接池，不与播放/解析共享。 */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Volatile private var bearerCache: String = ""
    @Volatile private var bearerCacheAt: Long = 0L

    private fun storedBearer(): String = prefs.getString("bearer", "") ?: ""
    private fun storedBearerAt(): Long = prefs.getLong("bearer_at", 0L)

    /**
     * 取一个可用的 Bearer JWT。缓存 12 小时；缓存过期或缺失时重新抓取。
     * [forceRefresh] 供 401 重试使用：丢弃缓存重新走一遍抓取链路。
     * 抓取失败时返回缓存的旧 token（可能仍然有效），完全没有才返回 null。
     */
    suspend fun bearer(forceRefresh: Boolean = false): String? = refreshMutex.withLock {
        val cached = bearerCache.ifBlank { storedBearer() }
        val at = if (bearerCache.isNotBlank()) bearerCacheAt else storedBearerAt()
        val fresh = cached.isNotBlank() && System.currentTimeMillis() - at < CACHE_TTL_MS
        if (!forceRefresh && fresh) return@withLock cached
        val scraped = runCatching { scrapeBearer() }.getOrNull()
        if (scraped.isNullOrBlank()) {
            return@withLock cached.takeIf { it.isNotBlank() && !forceRefresh }
        }
        bearerCache = scraped
        bearerCacheAt = System.currentTimeMillis()
        prefs.edit { putString("bearer", scraped); putLong("bearer_at", bearerCacheAt) }
        scraped
    }

    /** veil 同款抓取实现：首页 → /assets/index*.js → eyJ 正则。 */
    private fun scrapeBearer(): String? {
        val home = anonymousRequest("https://music.apple.com/") ?: return null
        val html = runCatching {
            http.newCall(home).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return null
        val script = Regex("""crossorigin src="(/assets/index[^"]+\.js)"""")
            .find(html)?.groupValues?.get(1) ?: return null
        val asset = anonymousRequest("https://music.apple.com$script") ?: return null
        val js = runCatching {
            http.newCall(asset).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return null
        return Regex("""(eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*)""").find(js)?.value
    }

    /**
     * 网络出口守卫：只允许 GET music.apple.com（页面与 JS 资源）。
     * amp-api 请求由 VeilBrowseSource 自己构造（带 Authorization 头），不经这里。
     */
    private fun anonymousRequest(url: String): Request? {
        val lower = url.lowercase()
        val ok = lower == "https://music.apple.com/" ||
            (lower.startsWith("https://music.apple.com/") && lower.substringBefore('?').contains("/assets/index"))
        if (!ok) return null
        return Request.Builder().url(url).header("User-Agent", UA).get().build()
    }

    /** 供 VeilBrowseSource 构造 catalog 请求使用：Bearer + 常规请求头。 */
    fun catalogRequest(url: String, bearer: String): Request? {
        if (!isCatalogUrl(url)) return null
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .header("Origin", "https://music.apple.com")
            .header("User-Agent", UA)
            .get()
            .build()
    }

    companion object {
        private const val PREFS_NAME = "apple_display_prefs"
        private const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L
        private const val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

        /** 匿名 catalog 只访问 amp-api 的公开目录端点（/v1/catalog、/v1/editorial）。 */
        private fun isCatalogUrl(url: String): Boolean {
            val lower = url.lowercase()
            if (!lower.startsWith("https://amp-api.music.apple.com/") &&
                !lower.startsWith("https://amp-api-edge.music.apple.com/")
            ) return false
            val path = lower.substringBefore('?').removePrefix("https://amp-api.music.apple.com")
                .removePrefix("https://amp-api-edge.music.apple.com")
            return path.startsWith("/v1/catalog/") || path.startsWith("/v1/editorial/")
        }

        @Volatile private var instance: AppleDisplayAuth? = null

        @Synchronized fun get(context: Context): AppleDisplayAuth {
            val app = context.applicationContext
            val current = instance
            return if (current == null || current.appContext !== app) {
                AppleDisplayAuth(app).also { instance = it }
            } else current
        }
    }
}
