package eu.kanade.tachiyomi.extension.zh.jinmantiantangapi

import android.app.Application
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 禁漫天堂 API 版插件
 *
 * 基于移动端 API 实现，支持登录功能
 * 与网页版插件并存，专注于需要登录的功能
 */
class JinmantiantangApi :
    HttpSource(),
    ConfigurableSource {

    override val name = "禁漫天堂(API)"
    override val lang = "zh"
    override val supportsLatest = true

    // SharedPreferences
    private val preferences = Injekt.get<Application>()
        .getSharedPreferences("source_$id", 0x0000)

    // Cookie 管理器
    private val cookieJar = JmCookieJar(preferences)

    // API 签名拦截器
    private val signatureInterceptor = ApiSignatureInterceptor()

    // 响应解密拦截器
    private val responseInterceptor = ApiResponseInterceptor()

    // 自定义 OkHttpClient
    override val client: OkHttpClient = network.cloudflareClient
        .newBuilder()
        .cookieJar(cookieJar)
        .addInterceptor(signatureInterceptor)
        .addInterceptor(responseInterceptor)
        .addInterceptor(ScrambledImageInterceptor)
        .build()

    // 域名管理器
    private val domainManager = DomainManager(client, preferences)

    // 登录管理器
    private val authManager = AuthManager(preferences, client, cookieJar)

    // API 客户端
    private val apiClient = JmApiClient(client, preferences)

    init {
        // 尝试更新域名列表（后台执行，不阻塞初始化）
        try {
            domainManager.tryUpdateDomains()
        } catch (e: Exception) {
            // 更新失败不影响扩展使用
        }
    }

    // 基础 URL（动态获取）
    override val baseUrl: String
        get() {
            val domainList = preferences.getString(
                JmConstants.PREF_API_DOMAIN_LIST,
                JmConstants.API_DOMAIN_LIST.joinToString(","),
            )?.split(",") ?: JmConstants.API_DOMAIN_LIST.toList()

            val index = preferences.getInt(JmConstants.PREF_API_DOMAIN_INDEX, 0)
            val domain = domainList.getOrNull(index) ?: domainList.first()
            return "https://$domain"
        }

    // ==================== 热门漫画 ====================

    override fun popularMangaRequest(page: Int): Request {
        // 使用 API 的分类筛选接口，按浏览量排序
        return GET("$baseUrl${JmConstants.ENDPOINT_CATEGORIES_FILTER}?page=$page&o=mv", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        // 从 URL 中提取页码
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return apiClient.getCategoryFilter(page = page, sortBy = "mv")
    }

    // ==================== 最新更新 ====================

    override fun latestUpdatesRequest(page: Int): Request {
        // 使用 API 的分类筛选接口，按最新排序
        return GET("$baseUrl${JmConstants.ENDPOINT_CATEGORIES_FILTER}?page=$page&o=mr", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        // 从 URL 中提取页码
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return apiClient.getCategoryFilter(page = page, sortBy = "mr")
    }

    // ==================== 搜索 ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl${JmConstants.ENDPOINT_SEARCH}?search_query=$query&page=$page"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        // 从 URL 中提取搜索参数
        val url = response.request.url
        val query = url.queryParameter("search_query") ?: ""
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1

        return apiClient.search(query, page)
    }

    // ==================== 漫画详情 ====================

    override fun mangaDetailsRequest(manga: SManga): Request {
        // 从 URL 中提取漫画 ID
        val albumId = manga.url.substringAfter("/album/").substringBefore("/")
        return GET("$baseUrl${JmConstants.ENDPOINT_ALBUM}/$albumId", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        // 从 URL 中提取漫画 ID
        val albumId = response.request.url.pathSegments.last()
        return apiClient.getAlbumDetail(albumId)
    }

    // ==================== 章节列表 ====================

    override fun chapterListRequest(manga: SManga): Request {
        // 从 URL 中提取漫画 ID
        val albumId = manga.url.substringAfter("/album/").substringBefore("/")
        return GET("$baseUrl${JmConstants.ENDPOINT_ALBUM}/$albumId", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        // 从 URL 中提取漫画 ID
        val albumId = response.request.url.pathSegments.last()
        return apiClient.getChapterList(albumId)
    }

    // ==================== 图片列表 ====================

    override fun pageListRequest(chapter: SChapter): Request {
        // 从 URL 中提取章节 ID
        val chapterId = chapter.url.substringAfter("/chapter/").substringBefore("/")
        return GET("$baseUrl${JmConstants.ENDPOINT_CHAPTER}/$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        // 从 URL 中提取章节 ID
        val chapterId = response.request.url.pathSegments.last()
        return apiClient.getChapterPages(chapterId)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ==================== 设置界面 ====================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        getPreferenceList(screen.context, preferences, authManager).forEach(screen::addPreference)
    }

    // ==================== 过滤器 ====================

    override fun getFilterList() = FilterList()
}
