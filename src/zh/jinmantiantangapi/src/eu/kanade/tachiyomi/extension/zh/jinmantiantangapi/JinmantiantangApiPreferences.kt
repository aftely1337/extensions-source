package eu.kanade.tachiyomi.extension.zh.jinmantiantangapi

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference

/**
 * 禁漫天堂 API 版插件设置界面
 */
internal fun getPreferenceList(
    context: Context,
    preferences: SharedPreferences,
    authManager: AuthManager,
) = arrayOf(
    // 用户名
    EditTextPreference(context).apply {
        key = JmConstants.PREF_USERNAME
        title = "用户名"
        summary = "禁漫天堂账号用户名"
        setDefaultValue("")
    },

    // 密码
    EditTextPreference(context).apply {
        key = JmConstants.PREF_PASSWORD
        title = "密码"
        summary = "禁漫天堂账号密码（警告：明文存储）"
        setDefaultValue("")
    },

    // 登录状态显示
    Preference(context).apply {
        title = "登录状态"
        summary = if (authManager.isLoggedIn()) {
            val username = authManager.getUsername()
            if (username.isNotEmpty()) "已登录: $username" else "已登录"
        } else {
            "未登录"
        }
        isEnabled = false
    },

    // 测试登录按钮
    Preference(context).apply {
        title = "测试登录"
        summary = "点击测试登录是否成功"

        setOnPreferenceClickListener {
            try {
                val username = preferences.getString(JmConstants.PREF_USERNAME, "") ?: ""
                val password = preferences.getString(JmConstants.PREF_PASSWORD, "") ?: ""

                if (username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(context, "请先输入用户名和密码", Toast.LENGTH_SHORT).show()
                } else {
                    val result = authManager.login(username, password)
                    Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "登录失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            true
        }
    },

    // 登出按钮
    Preference(context).apply {
        title = "登出"
        summary = "清除登录信息"

        setOnPreferenceClickListener {
            authManager.logout()
            Toast.makeText(context, "已登出", Toast.LENGTH_SHORT).show()
            true
        }
    },

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

        setOnPreferenceChangeListener { _, _ ->
            Toast.makeText(context, "域名已切换，请重启应用", Toast.LENGTH_LONG).show()
            true
        }
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
