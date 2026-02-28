package eu.kanade.tachiyomi.extension.zh.jinmantiantang

object JmConstants {
    const val APP_TOKEN_SECRET = "18comicAPP"
    const val APP_TOKEN_SECRET_2 = "18comicAPPContent"
    const val APP_DATA_SECRET = "185Hcomic3PAPP7R"
    const val API_DOMAIN_SERVER_SECRET = "diosfjckwpqpdfjkvnqQjsik"
    const val APP_VERSION = "2.0.18"

    val API_DOMAIN_LIST = arrayOf(
        "www.cdnaspa.vip",
        "www.cdnaspa.club",
        "www.cdnplaystation6.vip",
        "www.cdnplaystation6.cc",
    )

    val API_DOMAIN_SERVER_LIST = arrayOf(
        "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
        "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
    )

    const val ENDPOINT_SETTING = "/setting"
    const val ENDPOINT_SEARCH = "/search"
    const val ENDPOINT_CATEGORIES_FILTER = "/categories/filter"
    const val ENDPOINT_ALBUM = "/album"
    const val ENDPOINT_CHAPTER = "/chapter"
    const val ENDPOINT_CHAPTER_VIEW_TEMPLATE = "/chapter_view_template"

    const val SCRAMBLE_ID = 220980
    const val SCRAMBLE_268850 = 268850
    const val SCRAMBLE_421926 = 421926

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36"
    const val IMAGE_X_REQUESTED_WITH = "com.JMComic3.app"

    const val PREF_API_DOMAIN_INDEX = "api_domain_index"
    const val PREF_API_DOMAIN_LIST = "api_domain_list"
    const val PREF_API_DOMAIN_LABEL_LIST = "api_domain_label_list"
}
