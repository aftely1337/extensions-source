package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class Jinmantiantang :
    HttpSource(),
    ConfigurableSource {

    companion object {
        private const val LEGACY_PREF_USERNAME = "username"
        private const val LEGACY_PREF_PASSWORD = "password"
        private const val PREFIX_ID_SEARCH_NO_COLON = "JM"
        private const val ADVANCED_SEARCH_PAGE_SIZE = 20
        private const val MAX_ADVANCED_SEARCH_REMOTE_PAGES = 12
        private const val BLOCK_WORD_SEARCH_SCOPE = "0"
        private const val DEFAULT_BLOCKED_WORD_DETAIL_CONCURRENCY = 8
        const val PREFIX_ID_SEARCH = "$PREFIX_ID_SEARCH_NO_COLON:"
    }

    override val lang: String = "zh"
    override val name: String = "禁漫天堂"
    override val supportsLatest: Boolean = true

    private val preferences = getPreferences { preferenceMigration() }

    private val signatureInterceptor = ApiSignatureInterceptor()
    private val responseInterceptor = ApiResponseInterceptor()
    private val blockedWordDetailCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, SManga>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SManga>?): Boolean = size > 256
        },
    )

    override val client: OkHttpClient = network.cloudflareClient
        .newBuilder()
        .addInterceptor(signatureInterceptor)
        .addInterceptor(responseInterceptor)
        .addInterceptor(ScrambledImageInterceptor)
        .build()

    private val domainManager = DomainManager(client, preferences)
    private val apiClient = JmApiClient(client, preferences)

    init {
        try {
            domainManager.tryUpdateDomains()
        } catch (_: Exception) {
        }

        preferences.edit()
            .remove(LEGACY_PREF_USERNAME)
            .remove(LEGACY_PREF_PASSWORD)
            .apply()
    }

    override val baseUrl: String
        get() {
            val domainList = preferences.getString(
                JmConstants.PREF_API_DOMAIN_LIST,
                JmConstants.API_DOMAIN_LIST.joinToString(","),
            )?.split(",") ?: JmConstants.API_DOMAIN_LIST.toList()

            val index = preferences.getApiDomainIndex()
            val domain = domainList.getOrNull(index) ?: domainList.first()
            return "https://$domain"
        }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl${JmConstants.ENDPOINT_CATEGORIES_FILTER}?page=$page&o=mv", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return apiClient.getCategoryFilter(page = page, sortBy = "mv").filterBlockedManga()
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl${JmConstants.ENDPOINT_CATEGORIES_FILTER}?page=$page&o=mr", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return apiClient.getCategoryFilter(page = page, sortBy = "mr").filterBlockedManga()
    }

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl${JmConstants.ENDPOINT_ALBUM}?id=$id", headers)

    private fun searchMangaByIdParse(id: String): MangasPage {
        val manga = apiClient.getAlbumDetail(id)
        manga.url = "/album/$id/"
        return MangasPage(listOf(manga), false).filterBlockedManga()
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(PREFIX_ID_SEARCH_NO_COLON, true) || query.toIntOrNull() != null) {
            val id = query.removePrefix(PREFIX_ID_SEARCH_NO_COLON).removePrefix(":")
            return client.newCall(searchMangaByIdRequest(id))
                .asObservableSuccess()
                .map { searchMangaByIdParse(id) }
        }

        val parsedFilters = filters.toApiSearchFilters()
        val resolvedQuery = resolveSearchQuery(
            query = query,
            allowCategoryRedirect = parsedFilters.categoryId.isBlank() && parsedFilters.categoryKeyword.isBlank(),
        )
        val searchPlan = buildServerSearchPlan(query, resolvedQuery, parsedFilters)

        return if (searchPlan?.requiresServerSetSearch() == true) {
            Observable.fromCallable {
                fetchAdvancedSearchManga(
                    page = page,
                    searchPlan = searchPlan,
                    parsedFilters = parsedFilters,
                )
            }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val parsedFilters = filters.toApiSearchFilters()
        val resolvedQuery = resolveSearchQuery(
            query = query,
            allowCategoryRedirect = parsedFilters.categoryId.isBlank() && parsedFilters.categoryKeyword.isBlank(),
        )
        val textQuery = resolvedQuery.textQuery
        val rawTextQuery = resolvedQuery.rawTextQuery
        val filterKeyword = parsedFilters.categoryKeyword.trim()
        val effectiveCategoryId = parsedFilters.categoryId.ifBlank { resolvedQuery.categoryId }
        val mergedQuery = when {
            textQuery.isNotBlank() && filterKeyword.isNotBlank() -> "$textQuery +$filterKeyword"
            textQuery.isNotBlank() -> textQuery
            filterKeyword.isNotBlank() -> filterKeyword
            else -> ""
        }
        val rawMergedQuery = when {
            rawTextQuery.isNotBlank() && filterKeyword.isNotBlank() -> "$rawTextQuery +$filterKeyword"
            rawTextQuery.isNotBlank() -> rawTextQuery
            filterKeyword.isNotBlank() -> filterKeyword
            else -> ""
        }

        val isCategoryListingMode = mergedQuery.isBlank()

        val url = if (isCategoryListingMode) {
            "$baseUrl${JmConstants.ENDPOINT_CATEGORIES_FILTER}".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("o", parsedFilters.sortBy)
                .apply {
                    addQueryParameter("c", effectiveCategoryId)
                    parsedFilters.time.takeIf { it.isNotBlank() }?.let { addQueryParameter("t", it) }
                }
                .build()
                .toString()
        } else {
            "$baseUrl${JmConstants.ENDPOINT_SEARCH}".toHttpUrl().newBuilder()
                .addQueryParameter("search_query", mergedQuery)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("main_tag", parsedFilters.mainTag)
                .addQueryParameter("o", parsedFilters.sortBy)
                .apply {
                    rawMergedQuery.takeIf { it.isNotBlank() && it != mergedQuery }
                        ?.let { addQueryParameter("raw_search_query", it) }
                    parsedFilters.time.takeIf { it.isNotBlank() }?.let { addQueryParameter("t", it) }
                }
                .build()
                .toString()
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url
        val query = url.queryParameter("search_query") ?: ""
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val sortBy = url.queryParameter("o") ?: "mr"
        val time = url.queryParameter("t") ?: ""

        return if (url.encodedPath.endsWith(JmConstants.ENDPOINT_CATEGORIES_FILTER)) {
            apiClient.getCategoryFilter(
                categoryId = url.queryParameter("c") ?: "",
                page = page,
                sortBy = sortBy,
                time = time,
            ).filterBlockedManga()
        } else {
            apiClient.search(
                query = query,
                page = page,
                mainTag = url.queryParameter("main_tag") ?: "0",
                sortBy = sortBy,
                time = time,
            ).filterSearchQuery(
                query = query,
                rawQuery = url.queryParameter("raw_search_query") ?: query,
            )
                .filterBlockedManga()
        }
    }

    private fun MangasPage.filterSearchQuery(query: String, rawQuery: String = query): MangasPage {
        val termVariants = buildList {
            parseSearchTerms(query.trim()).takeIf { it.hasTerms() }?.let(::add)
            parseSearchTerms(rawQuery.trim()).takeIf { it.hasTerms() && rawQuery.trim() != query.trim() }?.let(::add)
        }
        if (termVariants.isEmpty()) return this

        val shouldApplyFilter = termVariants.any(SearchTerms::requiresLocalFiltering)
        if (!shouldApplyFilter) return this

        val filteredMangas = mangas.filter { manga ->
            termVariants.any { terms -> manga.matchesSearchTerms(terms) }
        }
        return MangasPage(filteredMangas, hasNextPage)
    }

    private data class SearchTerms(
        val required: List<String>,
        val excluded: List<String>,
        val optional: List<String>,
    ) {
        fun hasTerms(): Boolean = required.isNotEmpty() || excluded.isNotEmpty() || optional.isNotEmpty()

        fun requiresLocalFiltering(): Boolean = required.isNotEmpty() || excluded.isNotEmpty() || optional.size > 1
    }

    private sealed class SearchCriterion {
        abstract val stableKey: String

        data class Keyword(
            val query: String,
            val mainTagOverride: String? = null,
        ) : SearchCriterion() {
            override val stableKey: String = "keyword:${mainTagOverride ?: "default"}:$query"
        }

        data class Category(
            val categoryId: String,
        ) : SearchCriterion() {
            override val stableKey: String = "category:$categoryId"
        }
    }

    private data class ServerSearchPlan(
        val required: List<SearchCriterion>,
        val optional: List<SearchCriterion>,
        val exclude: List<SearchCriterion>,
    ) {
        fun requiresServerSetSearch(): Boolean = required.isNotEmpty() || optional.size > 1 || exclude.isNotEmpty()

        fun allCriteria(): List<SearchCriterion> = (required + optional + exclude)
            .distinctBy(SearchCriterion::stableKey)
    }

    private data class SearchCriterionAccumulator(
        val mangas: MutableList<SManga> = mutableListOf(),
        val ids: MutableSet<String> = linkedSetOf(),
        var nextPage: Int = 1,
        var hasMore: Boolean = true,
    )

    private data class ResolvedSearchQuery(
        val textQuery: String = "",
        val rawTextQuery: String = "",
        val categoryId: String = "",
    )

    private fun buildServerSearchPlan(
        rawQuery: String,
        resolvedQuery: ResolvedSearchQuery,
        parsedFilters: ApiSearchFilters,
    ): ServerSearchPlan? {
        val required = linkedMapOf<String, SearchCriterion>()
        val optional = linkedMapOf<String, SearchCriterion>()
        val exclude = linkedMapOf<String, SearchCriterion>()

        fun addCriterion(target: MutableMap<String, SearchCriterion>, criterion: SearchCriterion?) {
            if (criterion == null) return
            target.putIfAbsent(criterion.stableKey, criterion)
        }

        rawQuery.split(Regex("\\s+")).forEach { rawToken ->
            if (rawToken.isBlank()) return@forEach

            val isExcluded = rawToken.startsWith("-") && rawToken.length > 1
            val token = when {
                rawToken.startsWith("+") && rawToken.length > 1 -> rawToken.drop(1)
                rawToken.startsWith("-") && rawToken.length > 1 -> rawToken.drop(1)
                else -> rawToken
            }

            val criterion = buildSearchCriterion(token)
            if (isExcluded) {
                addCriterion(exclude, criterion)
            } else if (rawToken.startsWith("+") && rawToken.length > 1) {
                addCriterion(required, criterion)
            } else {
                addCriterion(optional, criterion)
            }
        }

        addCriterion(
            required,
            parsedFilters.categoryId.takeIf { it.isNotBlank() }
                ?.let(SearchCriterion::Category),
        )
        addCriterion(
            required,
            parsedFilters.categoryKeyword.takeIf { it.isNotBlank() }
                ?.let { SearchCriterion.Keyword(it) },
        )

        if (required.isEmpty() && optional.isEmpty() && resolvedQuery.categoryId.isNotBlank()) {
            addCriterion(required, SearchCriterion.Category(resolvedQuery.categoryId))
        }

        getBlockedWords().forEach { blockedWord ->
            addCriterion(
                exclude,
                buildSearchCriterion(
                    token = blockedWord,
                    mainTagOverride = BLOCK_WORD_SEARCH_SCOPE,
                ),
            )
        }

        if (required.isEmpty() && optional.isEmpty()) return null
        return ServerSearchPlan(
            required = required.values.toList(),
            optional = optional.values.toList(),
            exclude = exclude.values.toList(),
        )
    }

    private fun buildSearchCriterion(token: String, mainTagOverride: String? = null): SearchCriterion? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null

        val matchedOption = findCategoryOptionBySearchToken(trimmed)
        return when {
            matchedOption?.categoryId?.isNotBlank() == true -> SearchCriterion.Category(matchedOption.categoryId)
            else -> SearchCriterion.Keyword(matchedOption?.keyword?.takeIf { it.isNotBlank() } ?: trimmed, mainTagOverride)
        }
    }

    private fun fetchAdvancedSearchManga(
        page: Int,
        searchPlan: ServerSearchPlan,
        parsedFilters: ApiSearchFilters,
    ): MangasPage {
        val accumulators = searchPlan.allCriteria()
            .associateWith { SearchCriterionAccumulator() }
            .toMutableMap()
        val targetResultCount = page * ADVANCED_SEARCH_PAGE_SIZE + 1

        var fetchIteration = 0
        while (fetchIteration < MAX_ADVANCED_SEARCH_REMOTE_PAGES) {
            var fetchedAny = false

            searchPlan.allCriteria().forEach { criterion ->
                val accumulator = accumulators.getValue(criterion)
                if (!accumulator.hasMore) return@forEach

                val result = fetchSearchCriterionPage(
                    criterion = criterion,
                    page = accumulator.nextPage,
                    parsedFilters = parsedFilters,
                )
                accumulator.nextPage += 1
                accumulator.hasMore = result.hasNextPage && result.mangas.isNotEmpty()
                result.mangas.forEach { manga ->
                    val albumId = extractAlbumId(manga.url)
                    if (albumId.isNotBlank() && accumulator.ids.add(albumId)) {
                        accumulator.mangas += manga
                    }
                }
                fetchedAny = true
            }

            val combinedResults = combineServerSearchResults(searchPlan, accumulators)
            if (combinedResults.size >= targetResultCount || !fetchedAny || accumulators.values.all { !it.hasMore }) {
                break
            }

            fetchIteration += 1
        }

        val finalResults = combineServerSearchResults(searchPlan, accumulators)
            .let { MangasPage(it, false) }
            .filterBlockedManga()
            .mangas
        val fromIndex = ((page - 1) * ADVANCED_SEARCH_PAGE_SIZE).coerceAtMost(finalResults.size)
        val toIndex = minOf(fromIndex + ADVANCED_SEARCH_PAGE_SIZE, finalResults.size)

        return MangasPage(
            finalResults.subList(fromIndex, toIndex),
            finalResults.size > toIndex || accumulators.values.any { it.hasMore },
        )
    }

    private fun fetchSearchCriterionPage(
        criterion: SearchCriterion,
        page: Int,
        parsedFilters: ApiSearchFilters,
    ): MangasPage = when (criterion) {
        is SearchCriterion.Category -> apiClient.getCategoryFilter(
            categoryId = criterion.categoryId,
            page = page,
            sortBy = parsedFilters.sortBy,
            time = parsedFilters.time,
        )
        is SearchCriterion.Keyword -> apiClient.search(
            query = criterion.query,
            page = page,
            mainTag = criterion.mainTagOverride ?: parsedFilters.mainTag,
            sortBy = parsedFilters.sortBy,
            time = parsedFilters.time,
        )
    }

    private fun combineServerSearchResults(
        searchPlan: ServerSearchPlan,
        accumulators: Map<SearchCriterion, SearchCriterionAccumulator>,
    ): List<SManga> {
        val baseResults = when {
            searchPlan.required.isNotEmpty() -> accumulators.getValue(searchPlan.required.first()).mangas
            searchPlan.optional.isNotEmpty() ->
                searchPlan.optional
                    .flatMap { accumulators.getValue(it).mangas }
                    .distinctBy { extractAlbumId(it.url) }
            else -> emptyList()
        }

        return baseResults.filter { manga ->
            val albumId = extractAlbumId(manga.url)
            albumId.isNotBlank() &&
                searchPlan.required.all { albumId in accumulators.getValue(it).ids } &&
                (searchPlan.optional.isEmpty() || searchPlan.optional.any { albumId in accumulators.getValue(it).ids }) &&
                searchPlan.exclude.none { albumId in accumulators.getValue(it).ids }
        }
    }

    private fun resolveSearchQuery(query: String, allowCategoryRedirect: Boolean): ResolvedSearchQuery {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ResolvedSearchQuery()

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (allowCategoryRedirect && tokens.size == 1) {
            val singleToken = tokens.single()
            val singleOption = findCategoryOptionBySearchToken(singleToken)
            if (
                singleOption != null &&
                singleOption.categoryId.isNotBlank() &&
                !singleToken.startsWith("+") &&
                !singleToken.startsWith("-")
            ) {
                return ResolvedSearchQuery(categoryId = singleOption.categoryId)
            }
        }

        return ResolvedSearchQuery(
            textQuery = tokens.joinToString(" ") { canonicalizeSearchToken(it) },
            rawTextQuery = tokens.joinToString(" "),
        )
    }

    private fun parseSearchTerms(query: String): SearchTerms {
        val required = mutableListOf<String>()
        val excluded = mutableListOf<String>()
        val optional = mutableListOf<String>()

        query.split(Regex("\\s+")).forEach { rawToken ->
            if (rawToken.isBlank()) return@forEach

            when {
                rawToken.startsWith("+") && rawToken.length > 1 -> required += rawToken.drop(1)
                rawToken.startsWith("-") && rawToken.length > 1 -> excluded += rawToken.drop(1)
                else -> optional += rawToken
            }
        }

        return SearchTerms(
            required = required.map { it.normalizeFilterText() }.filter { it.isNotEmpty() },
            excluded = excluded.map { it.normalizeFilterText() }.filter { it.isNotEmpty() },
            optional = optional.map { it.normalizeFilterText() }.filter { it.isNotEmpty() },
        )
    }

    private fun SManga.matchesSearchTerms(terms: SearchTerms): Boolean {
        val haystacks = listOf(title, genre, author, description)
            .map { it.orEmpty().normalizeFilterText() }
            .filter { it.isNotBlank() }

        if (terms.required.isNotEmpty() && terms.required.any { required -> haystacks.none { it.contains(required) } }) {
            return false
        }

        if (terms.excluded.any { excluded -> haystacks.any { it.contains(excluded) } }) {
            return false
        }

        if (terms.optional.isEmpty()) return true
        return terms.optional.any { optional -> haystacks.any { it.contains(optional) } }
    }

    private fun MangasPage.filterBlockedManga(): MangasPage {
        val blockedWords = getBlockedWords()
        if (blockedWords.isEmpty() || mangas.isEmpty()) return this

        val configuredConcurrency = preferences.getBlockedWordDetailConcurrency()
            .takeIf { it > 0 }
            ?: DEFAULT_BLOCKED_WORD_DETAIL_CONCURRENCY
        val executor = Executors.newFixedThreadPool(minOf(configuredConcurrency, mangas.size))
        val filteredMangas = try {
            val tasks = mangas.map { manga ->
                Callable { manga.matchesBlockedWordsWithDetails(blockedWords) }
            }
            val futures = executor.invokeAll(tasks)

            mangas.zip(futures)
                .filterNot { (_, future) -> runCatching { future.get() }.getOrElse { true } }
                .map { (manga, _) -> manga }
        } finally {
            executor.shutdown()
        }

        return MangasPage(filteredMangas, hasNextPage)
    }

    private fun SManga.matchesBlockedWordsWithDetails(blockedWords: List<String>): Boolean {
        if (matchesBlockedWords(blockedWords)) return true

        val albumId = extractAlbumId(url)
        if (albumId.isBlank()) return false

        val detailedManga = blockedWordDetailCache[albumId] ?: runCatching { apiClient.getAlbumDetail(albumId) }
            .onSuccess { blockedWordDetailCache[albumId] = it }
            .getOrElse { return true }

        return detailedManga.matchesBlockedWords(blockedWords)
    }

    private fun SManga.matchesBlockedWords(blockedWords: List<String>): Boolean {
        val haystacks = listOf(title, genre, author, description)
            .map { it.orEmpty().normalizeFilterText() }
            .filter { it.isNotBlank() }

        return blockedWords.any { blockedWord ->
            haystacks.any { text -> text.contains(blockedWord) }
        }
    }

    private fun getBlockedWords(): List<String> = preferences.getString(JmConstants.PREF_BLOCK_WORDS, null)
        .orEmpty()
        .substringBefore("//")
        .split(',', ' ', '\n', '\r', '\t')
        .flatMap(::expandBlockedWordVariants)
        .distinct()

    private fun expandBlockedWordVariants(rawWord: String): List<String> {
        val trimmed = rawWord.trim()
        val normalizedWord = trimmed.normalizeFilterText()
        if (normalizedWord.isEmpty()) return emptyList()

        val variants = linkedSetOf(normalizedWord)
        val matchedOption = findCategoryOptionBySearchToken(trimmed)

        matchedOption?.let { option ->
            variants += option.label.normalizeFilterText()
            option.keyword.takeIf { it.isNotBlank() }?.let { variants += it.normalizeFilterText() }
        }

        when (normalizedWord) {
            "扶她" -> variants += "扶他"
            "扶他" -> variants += "扶她"
        }

        return variants.filter { it.isNotEmpty() }
    }

    private fun canonicalizeSearchToken(rawToken: String): String {
        if (rawToken.isBlank()) return rawToken

        val prefix = rawToken.takeIf { it.startsWith("+") || it.startsWith("-") }
            ?.take(1)
            .orEmpty()
        val token = rawToken.removePrefix(prefix)
        val option = findCategoryOptionBySearchToken(token) ?: return rawToken
        val canonicalToken = option.keyword.ifBlank { token }

        return prefix + canonicalToken
    }

    private fun findCategoryOptionBySearchToken(token: String): CategoryOption? {
        val normalizedToken = token.normalizeFilterText()
        if (normalizedToken.isEmpty()) return null

        return ApiCategoryFilter.OPTIONS.firstOrNull { option ->
            option.matchesSearchToken(normalizedToken)
        }
    }

    private fun CategoryOption.matchesSearchToken(normalizedToken: String): Boolean = label.normalizeFilterText() == normalizedToken ||
        keyword.takeIf { it.isNotBlank() }?.normalizeFilterText() == normalizedToken ||
        categoryId.takeIf { it.isNotBlank() }?.normalizeFilterText() == normalizedToken

    private fun String.normalizeFilterText(): String = lowercase()
        .replace(" ", "")
        .replace("　", "")
        .replace(",", "")
        .replace("，", "")
        .replace(".", "")
        .replace("。", "")
        .replace("-", "")
        .replace("_", "")

    override fun mangaDetailsRequest(manga: SManga): Request {
        val albumId = extractAlbumId(manga.url)
        return GET("$baseUrl${JmConstants.ENDPOINT_ALBUM}?id=$albumId", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val albumId = response.request.url.queryParameter("id") ?: response.request.url.pathSegments.last()
        return apiClient.getAlbumDetail(albumId)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val albumId = extractAlbumId(manga.url)
        return GET("$baseUrl${JmConstants.ENDPOINT_ALBUM}?id=$albumId", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val albumId = response.request.url.queryParameter("id") ?: response.request.url.pathSegments.last()
        return apiClient.getChapterList(albumId)
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = extractChapterId(chapter.url)
        return GET("$baseUrl${JmConstants.ENDPOINT_CHAPTER}?id=$chapterId&mode=vertical", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.queryParameter("id") ?: response.request.url.pathSegments.last()
        return apiClient.getChapterPages(chapterId)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        getPreferenceList(screen.context, preferences).forEach(screen::addPreference)
    }

    override fun getFilterList() = FilterList(
        ApiCategoryFilter(),
        ApiSortFilter(),
        ApiTimeFilter(),
        ApiTypeFilter(),
    )

    private data class ApiSearchFilters(
        val categoryId: String = "",
        val categoryKeyword: String = "",
        val sortBy: String = "mr",
        val time: String = "",
        val mainTag: String = "0",
    )

    private fun FilterList.toApiSearchFilters(): ApiSearchFilters {
        val category = filterOrNull<ApiCategoryFilter>()?.selected ?: CategoryOption.ALL
        val sort = filterOrNull<ApiSortFilter>()?.selectedValue ?: "mr"
        val time = filterOrNull<ApiTimeFilter>()?.selectedValue ?: ""
        val mainTag = filterOrNull<ApiTypeFilter>()?.selectedValue ?: "0"
        return ApiSearchFilters(
            categoryId = category.categoryId,
            categoryKeyword = category.keyword,
            sortBy = sort,
            time = time,
            mainTag = mainTag,
        )
    }

    private inline fun <reified T> FilterList.filterOrNull(): T? = firstOrNull { it is T } as? T

    private data class CategoryOption(
        val label: String,
        val categoryId: String = "",
        val keyword: String = "",
    ) {
        companion object {
            val ALL = CategoryOption("全部")
        }
    }

    private class ApiCategoryFilter :
        Filter.Select<String>(
            "按类型",
            OPTIONS.map { it.label }.toTypedArray(),
        ) {
        val selected: CategoryOption get() = OPTIONS.getOrElse(state) { CategoryOption.ALL }

        companion object {
            val OPTIONS = listOf(
                CategoryOption.ALL,
                CategoryOption("其他", categoryId = "another"),
                CategoryOption("同人", categoryId = "doujin"),
                CategoryOption("韩漫", categoryId = "hanman"),
                CategoryOption("美漫", categoryId = "meiman"),
                CategoryOption("短篇", categoryId = "short"),
                CategoryOption("单本", categoryId = "single"),
                CategoryOption("Cosplay", keyword = "Cosplay"),
                CategoryOption("CG", keyword = "CG"),
                CategoryOption("P站", keyword = "PIXIV"),
                CategoryOption("3D", keyword = "3D"),
                CategoryOption("剧情", keyword = "劇情"),
                CategoryOption("校园", keyword = "校園"),
                CategoryOption("纯爱", keyword = "純愛"),
                CategoryOption("人妻", keyword = "人妻"),
                CategoryOption("师生", keyword = "師生"),
                CategoryOption("乱伦", keyword = "亂倫"),
                CategoryOption("近亲", keyword = "近親"),
                CategoryOption("百合", keyword = "百合"),
                CategoryOption("男同", keyword = "YAOI"),
                CategoryOption("性转", keyword = "性轉"),
                CategoryOption("NTR", keyword = "NTR"),
                CategoryOption("伪娘", keyword = "偽娘"),
                CategoryOption("痴女", keyword = "癡女"),
                CategoryOption("全彩", keyword = "全彩"),
                CategoryOption("女性向", keyword = "女性向"),
                CategoryOption("萝莉", keyword = "蘿莉"),
                CategoryOption("御姐", keyword = "御姐"),
                CategoryOption("熟女", keyword = "熟女"),
                CategoryOption("正太", keyword = "正太"),
                CategoryOption("巨乳", keyword = "巨乳"),
                CategoryOption("贫乳", keyword = "貧乳"),
                CategoryOption("女王", keyword = "女王"),
                CategoryOption("教师", keyword = "教師"),
                CategoryOption("女仆", keyword = "女僕"),
                CategoryOption("护士", keyword = "護士"),
                CategoryOption("泳裝", keyword = "泳裝"),
                CategoryOption("眼镜", keyword = "眼鏡"),
                CategoryOption("丝袜", keyword = "絲襪"),
                CategoryOption("连裤袜", keyword = "連褲襪"),
                CategoryOption("制服", keyword = "制服"),
                CategoryOption("兔女郎", keyword = "兔女郎"),
                CategoryOption("群交", keyword = "群交"),
                CategoryOption("足交", keyword = "足交"),
                CategoryOption("SM", keyword = "SM"),
                CategoryOption("肛交", keyword = "肛交"),
                CategoryOption("阿黑颜", keyword = "阿黑顏"),
                CategoryOption("药物", keyword = "藥物"),
                CategoryOption("扶他", keyword = "扶他"),
                CategoryOption("调教", keyword = "調教"),
                CategoryOption("野外", keyword = "野外"),
                CategoryOption("露出", keyword = "露出"),
                CategoryOption("催眠", keyword = "催眠"),
                CategoryOption("自慰", keyword = "自慰"),
                CategoryOption("触手", keyword = "觸手"),
                CategoryOption("兽交", keyword = "獸交"),
                CategoryOption("亚人", keyword = "亞人"),
                CategoryOption("魔物", keyword = "魔物"),
                CategoryOption("CG 全彩", keyword = "CG 全彩"),
                CategoryOption("重口", keyword = "重口"),
                CategoryOption("猎奇", keyword = "獵奇"),
                CategoryOption("非H", keyword = "非H"),
                CategoryOption("血腥", keyword = "血腥"),
                CategoryOption("暴力", keyword = "暴力"),
                CategoryOption("血腥暴力", keyword = "血腥暴力"),
            )
        }
    }

    private abstract class ApiValueFilter(
        name: String,
        private val items: Array<Pair<String, String>>,
    ) : Filter.Select<String>(name, items.map { it.first }.toTypedArray()) {
        val selectedValue: String get() = items.getOrElse(state) { items.first() }.second
    }

    private class ApiSortFilter :
        ApiValueFilter(
            "排序",
            arrayOf(
                "最新" to "mr",
                "最多浏览" to "mv",
                "最多爱心" to "tf",
                "最多图片" to "mp",
            ),
        )

    private class ApiTimeFilter :
        ApiValueFilter(
            "时间",
            arrayOf(
                "全部" to "",
                "今天" to "t",
                "这周" to "w",
                "本月" to "m",
            ),
        )

    private class ApiTypeFilter :
        ApiValueFilter(
            "搜索范围",
            arrayOf(
                "站内搜索" to "0",
                "作品" to "1",
                "作者" to "2",
                "标签" to "3",
                "登场人物" to "4",
            ),
        )

    private fun extractAlbumId(url: String): String = url.substringAfter("/album/").substringBefore("/").ifBlank { url.trim('/').substringAfterLast('/') }

    private fun extractChapterId(url: String): String {
        val photoId = url.substringAfter("/photo/").substringBefore("/")
        if (photoId.isNotBlank()) return photoId

        val chapterId = url.substringAfter("/chapter/").substringBefore("/")
        if (chapterId.isNotBlank()) return chapterId

        return url.trim('/').substringAfterLast('/')
    }
}
