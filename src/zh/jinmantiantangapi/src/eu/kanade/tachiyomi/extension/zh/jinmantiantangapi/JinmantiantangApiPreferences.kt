package eu.kanade.tachiyomi.extension.zh.jinmantiantangapi

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
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

    // 请求数量限制
    ListPreference(context).apply {
        key = JmConstants.PREF_RATE_LIMIT
        title = "请求数量限制"
        entries = Array(10) { "${it + 1}" }
        entryValues = Array(10) { "${it + 1}" }
        summary = "在限制时间内允许的请求数量\n当前值: %s\n需要重启应用生效"
        setDefaultValue(JmConstants.PREF_RATE_LIMIT_DEFAULT)
    },

    // 限制周期
    ListPreference(context).apply {
        key = JmConstants.PREF_RATE_PERIOD
        title = "限制周期（秒）"
        entries = Array(60) { "${it + 1}" }
        entryValues = Array(60) { "${it + 1}" }
        summary = "限制持续时间\n当前值: %s 秒\n需要重启应用生效"
        setDefaultValue(JmConstants.PREF_RATE_PERIOD_DEFAULT)
    },

    // 屏蔽词列表
    EditTextPreference(context).apply {
        key = JmConstants.PREF_BLOCK_LIST
        title = "屏蔽词列表"
        summary = "用空格分隔多个关键词，大小写不敏感"
        dialogTitle = "屏蔽词列表"
        setDefaultValue("")
    },
)
