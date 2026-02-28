package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import android.content.SharedPreferences
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class JmApiClient(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {
    private fun cleanString(value: String?): String = value
        ?.trim()
        ?.takeUnless { it.equals("null", ignoreCase = true) }
        .orEmpty()

    private fun JSONArray.joinSafeStrings(): String = (0 until length())
        .mapNotNull { index ->
            val value = opt(index)
            val text = when (value) {
                null, JSONObject.NULL -> null
                is String -> cleanString(value)
                is JSONObject -> cleanString(value.optString("name", value.optString("title", value.toString())))
                else -> cleanString(value.toString())
            }
            text?.takeIf { it.isNotBlank() }
        }
        .joinToString(", ")

    @Volatile
    private var initialized = false

    @Volatile
    private var cachedImageHost: String = ""
    private val initLock = Any()

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            try {
                val json = executeGet(JmConstants.ENDPOINT_SETTING)
                checkResponse(json)
                val settingData = json.optJSONObject("data")
                cachedImageHost = cleanString(settingData?.optString("img_host", ""))
                initialized = true
            } catch (_: Exception) {
                initialized = false
            }
        }
    }

    private fun getApiDomain(): String {
        val domainList = preferences.getString(
            JmConstants.PREF_API_DOMAIN_LIST,
            JmConstants.API_DOMAIN_LIST.joinToString(","),
        )?.split(",") ?: JmConstants.API_DOMAIN_LIST.toList()

        val index = preferences.getApiDomainIndex()
        return domainList.getOrNull(index) ?: domainList.first()
    }

    private fun getBaseUrl(): String = "https://${getApiDomain()}"

    private fun getImageHost(): String {
        val host = cachedImageHost.trim().trimEnd('/')
        if (host.isEmpty()) return ""
        return if (host.startsWith("http")) host else "https://$host"
    }

    private fun executeGet(endpoint: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = buildUrl(endpoint, params)
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("API 请求失败: HTTP ${response.code}")
            }

            val body = response.body?.string() ?: throw Exception("响应体为空")
            JSONObject(body)
        }
    }

    private fun buildUrl(endpoint: String, params: Map<String, String>): String {
        if (params.isEmpty()) return "${getBaseUrl()}$endpoint"

        val url = StringBuilder("${getBaseUrl()}$endpoint?")
        params.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) url.append('&')
            url.append(key)
            url.append('=')
            url.append(if (key == "search_query") encodeSearchQuery(value) else value.toHttpUrlQuery())
        }
        return url.toString()
    }

    private fun encodeSearchQuery(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun String.toHttpUrlQuery(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun checkResponse(json: JSONObject) {
        val code = json.optInt("code", -1)
        if (code != 200) {
            val message = json.optString("errorMsg", json.optString("message", "未知错误"))
            throw Exception("API 错误: $message (code: $code)")
        }
    }

    private fun getData(json: JSONObject): JSONObject {
        checkResponse(json)
        return json.optJSONObject("data") ?: throw Exception("响应缺少 data 字段")
    }

    fun search(
        query: String,
        page: Int,
        mainTag: String = "0",
        sortBy: String = "mr",
        time: String = "",
    ): MangasPage {
        ensureInitialized()
        val params = mutableMapOf(
            "search_query" to query,
            "page" to page.toString(),
            "main_tag" to mainTag,
            "o" to sortBy,
        )
        if (time.isNotEmpty()) params["t"] = time
        val json = executeGet(JmConstants.ENDPOINT_SEARCH, params)
        val data = getData(json)
        return parseMangaList(data)
    }

    fun getCategoryFilter(
        categoryId: String = "",
        page: Int = 1,
        sortBy: String = "mr",
        time: String = "",
    ): MangasPage {
        ensureInitialized()
        val params = mutableMapOf(
            "page" to page.toString(),
            "o" to sortBy,
        )
        if (categoryId.isNotEmpty()) {
            params["c"] = categoryId
        }
        if (time.isNotEmpty()) {
            params["t"] = time
        }
        val json = executeGet(JmConstants.ENDPOINT_CATEGORIES_FILTER, params)
        val data = getData(json)
        return parseMangaList(data)
    }

    fun getAlbumDetail(albumId: String): SManga {
        ensureInitialized()
        val json = executeGet(JmConstants.ENDPOINT_ALBUM, mapOf("id" to albumId))
        val data = getData(json)
        return parseMangaDetail(data)
    }

    fun getChapterList(albumId: String): List<SChapter> {
        ensureInitialized()
        val json = executeGet(JmConstants.ENDPOINT_ALBUM, mapOf("id" to albumId))
        val data = getData(json)
        return parseChapterList(data, albumId)
    }

    fun getChapterPages(chapterId: String): List<Page> {
        ensureInitialized()
        val json = executeGet(JmConstants.ENDPOINT_CHAPTER, mapOf("id" to chapterId, "mode" to "vertical"))
        val data = getData(json)
        return parsePageList(data)
    }

    private fun parseMangaList(data: JSONObject): MangasPage {
        val content = data.optJSONArray("content") ?: JSONArray()
        val mangas = mutableListOf<SManga>()

        for (i in 0 until content.length()) {
            try {
                val item = content.getJSONObject(i)
                mangas.add(parseManga(item))
            } catch (_: Exception) {
                continue
            }
        }

        val hasNextPage = data.optInt("total", 0) > data.optInt("page", 1) * 20
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseManga(json: JSONObject): SManga = SManga.create().apply {
        val id = cleanString(json.optString("id", json.optString("aid", "")))
        url = "/album/$id/"
        title = cleanString(json.optString("name", json.optString("title", "")))

        val imageUrl = cleanString(json.optString("image", ""))
        thumbnail_url = if (imageUrl.isNotEmpty()) {
            imageUrl.substringBeforeLast('.') + "_3x4.jpg"
        } else if (id.isNotEmpty() && getImageHost().isNotEmpty()) {
            "${getImageHost()}/media/albums/${id}_3x4.jpg"
        } else {
            ""
        }

        val authorArray = json.optJSONArray("author")
        author = if (authorArray != null && authorArray.length() > 0) {
            authorArray.joinSafeStrings()
        } else if (cleanString(json.optString("author", "")).isNotEmpty()) {
            cleanString(json.optString("author", ""))
        } else {
            ""
        }

        val tagsArray = json.optJSONArray("tags")
        genre = if (tagsArray != null && tagsArray.length() > 0) {
            tagsArray.joinSafeStrings()
        } else {
            buildList {
                cleanString(json.optJSONObject("category")?.optString("title")).takeIf { it.isNotBlank() }?.let(::add)
                cleanString(json.optJSONObject("category_sub")?.optString("title")).takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString(", ")
        }
    }

    private fun parseMangaDetail(data: JSONObject): SManga = SManga.create().apply {
        val id = cleanString(data.optString("id", ""))
        url = "/album/$id/"
        title = cleanString(data.optString("name", data.optString("title", "")))

        val imageUrl = cleanString(data.optString("image", ""))
        thumbnail_url = if (imageUrl.isNotEmpty()) {
            imageUrl.substringBeforeLast('.') + "_3x4.jpg"
        } else if (id.isNotEmpty() && getImageHost().isNotEmpty()) {
            "${getImageHost()}/media/albums/${id}_3x4.jpg"
        } else {
            ""
        }

        val authorArray = data.optJSONArray("author")
        author = if (authorArray != null && authorArray.length() > 0) {
            authorArray.joinSafeStrings()
        } else {
            cleanString(data.optString("author", ""))
        }

        val tagsArray = data.optJSONArray("tags")
        genre = if (tagsArray != null && tagsArray.length() > 0) {
            tagsArray.joinSafeStrings()
        } else {
            buildList {
                cleanString(data.optJSONObject("category")?.optString("title")).takeIf { it.isNotBlank() }?.let(::add)
                cleanString(data.optJSONObject("category_sub")?.optString("title")).takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString(", ")
        }

        status = when (data.optString("status", "")) {
            "連載中" -> SManga.ONGOING
            "完結" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        description = cleanString(data.optString("description", ""))
    }

    private fun parseChapterList(data: JSONObject, albumId: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val episodeArray = data.optJSONArray("episode") ?: data.optJSONArray("series")

        if (episodeArray == null || episodeArray.length() == 0) {
            val chapter = SChapter.create().apply {
                val fallbackChapterId = cleanString(data.optString("id", ""))
                    .takeIf { it.isNotBlank() && it != "0" }
                    ?: cleanString(data.optString("series_id", "")).takeIf { it.isNotBlank() && it != "0" }
                    ?: albumId
                url = "/photo/$fallbackChapterId"
                name = "单章节"
                chapter_number = 1f
                date_upload = parseDate(data.optString("created_at", data.optString("addtime", "")))
            }
            chapters.add(chapter)
        } else {
            for (i in 0 until episodeArray.length()) {
                try {
                    val episode = episodeArray.getJSONObject(i)
                    val chapter = SChapter.create().apply {
                        val chapterId = cleanString(episode.optString("id", ""))
                        url = "/photo/$chapterId"
                        name = cleanString(episode.optString("name", episode.optString("title", "第${i + 1}话")))
                            .ifEmpty { "第${i + 1}话" }
                        chapter_number = (i + 1).toFloat()
                        date_upload = parseDate(episode.optString("created_at", episode.optString("addtime", "")))
                    }
                    chapters.add(chapter)
                } catch (_: Exception) {
                    continue
                }
            }
        }

        return chapters.reversed()
    }

    private fun parsePageList(data: JSONObject): List<Page> {
        val pages = mutableListOf<Page>()
        val imagesArray = data.optJSONArray("images")
            ?: throw Exception("章节数据缺少 images 字段")

        val chapterId = cleanString(data.optString("id", ""))
            .takeIf { it.isNotBlank() && it != "0" }
            ?: cleanString(data.optString("series_id", "")).takeIf { it.isNotBlank() && it != "0" }
            ?: throw Exception("章节数据缺少 id 字段")

        val imageHost = cleanString(data.optString("image_domain", data.optString("domain", "")))
            .ifEmpty { getImageHost() }
            .trimEnd('/')
        if (imageHost.isEmpty()) {
            throw Exception("章节数据缺少 image_domain 字段")
        }

        for (i in 0 until imagesArray.length()) {
            try {
                val imagePath = cleanString(imagesArray.getString(i))
                if (imagePath.isEmpty()) continue

                val normalizedPath = if (imagePath.startsWith("/")) {
                    imagePath
                } else {
                    "/media/photos/$chapterId/$imagePath"
                }
                val imageUrl = if (normalizedPath.startsWith("http")) normalizedPath else "$imageHost$normalizedPath"
                pages.add(Page(i, "", imageUrl))
            } catch (_: Exception) {
                continue
            }
        }

        return pages
    }

    private fun parseDate(dateString: String): Long {
        if (dateString.isEmpty()) return 0L

        dateString.toLongOrNull()?.let { value ->
            return if (value > 1_000_000_000_000L) value else value * 1000L
        }

        return try {
            val date = dateString.substringBefore(" ")
            val parts = date.split("-")
            if (parts.size != 3) return 0L

            val year = parts[0].toInt()
            val month = parts[1].toInt()
            val day = parts[2].toInt()

            val calendar = java.util.Calendar.getInstance()
            calendar.set(year, month - 1, day, 0, 0, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        } catch (_: Exception) {
            0L
        }
    }
}
