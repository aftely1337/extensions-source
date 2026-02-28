package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference

internal fun getPreferenceList(
    context: Context,
    preferences: SharedPreferences,
) = arrayOf(
    ListPreference(context).apply {
        key = JmConstants.PREF_API_DOMAIN_INDEX
        title = "API 域名"

        val domainList = preferences.getString(
            JmConstants.PREF_API_DOMAIN_LIST,
            JmConstants.API_DOMAIN_LIST.joinToString(","),
        )?.split(",") ?: JmConstants.API_DOMAIN_LIST.toList()
        val domainLabels = parseDomainLabels(
            preferences.getString(JmConstants.PREF_API_DOMAIN_LABEL_LIST, null),
            domainList.size,
        )

        entries = Array(domainList.size) { index ->
            formatDomainEntry(domain = domainList[index], label = domainLabels.getOrNull(index).orEmpty())
        }
        entryValues = Array(domainList.size) { it.toString() }
        summary = "当前: %s\n线路会自动更新，切换后需要重启应用"
        setDefaultValue("0")
    },

    EditTextPreference(context).apply {
        key = JmConstants.PREF_BLOCK_WORDS
        title = "屏蔽词列表"
        dialogTitle = "屏蔽词列表"
        dialogMessage = "按标题和标签在本地隐藏漫画。支持空格、逗号或换行分隔；\"//\" 后面的内容会被忽略。\n例如：YAOI 扶他 獵奇 韓漫"
        summary = "按标题和标签在本地隐藏漫画；支持空格、逗号或换行分隔，\"//\" 后面的内容会被忽略"
        setDefaultValue("")
    },
)

fun SharedPreferences.preferenceMigration() {
    val defaultDomainList = JmConstants.API_DOMAIN_LIST.joinToString(",")
    val oldMirrorIndex = getString("useMirrorWebsitePreference", null)?.toIntOrNull()

    edit().apply {
        if (!contains(JmConstants.PREF_API_DOMAIN_LIST)) {
            putString(JmConstants.PREF_API_DOMAIN_LIST, defaultDomainList)
        }
        if (!contains(JmConstants.PREF_API_DOMAIN_INDEX)) {
            putString(JmConstants.PREF_API_DOMAIN_INDEX, (oldMirrorIndex ?: 0).toString())
        }
    }.apply()
}

private fun parseDomainLabels(labelJson: String?, size: Int): List<String> {
    if (labelJson.isNullOrBlank() || size <= 0) return emptyList()

    return runCatching {
        val array = org.json.JSONArray(labelJson)
        List(size) { index -> array.optString(index, "").trim() }
    }.getOrElse {
        emptyList()
    }
}

private fun formatDomainEntry(domain: String, label: String): String {
    val normalizedLabel = label.trim().removeSurrounding("\"")
    return when {
        normalizedLabel.isBlank() -> domain
        normalizedLabel == domain -> domain
        else -> "$normalizedLabel ($domain)"
    }
}
