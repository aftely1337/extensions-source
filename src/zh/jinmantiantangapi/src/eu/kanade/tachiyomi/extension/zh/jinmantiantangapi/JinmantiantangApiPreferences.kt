package eu.kanade.tachiyomi.extension.zh.jinmantiantangapi

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.ListPreference

/**
 * 禁漫天堂 API 版插件设置界面
 */
internal fun getPreferenceList(
    context: Context,
    preferences: SharedPreferences,
) = arrayOf(
    // API 域名选择
    ListPreference(context).apply {
        key = JmConstants.PREF_API_DOMAIN_INDEX
        title = "API 域名"

        val domainList = preferences.getString(
            JmConstants.PREF_API_DOMAIN_LIST,
            JmConstants.API_DOMAIN_LIST.joinToString(","),
        )?.split(",") ?: JmConstants.API_DOMAIN_LIST.toList()

        entries = domainList.toTypedArray()
        entryValues = Array(domainList.size) { it.toString() }
        summary = "当前: %s\n切换后需要重启应用"
        setDefaultValue("0")
    },
)
