package eu.kanade.tachiyomi.extension.zh.jinmantiantangapi

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen

/**
 * 禁漫天堂 API 版插件设置界面
 */
object JinmantiantangApiPreferences {

    fun setupPreferenceScreen(
        screen: PreferenceScreen,
        preferences: SharedPreferences,
        authManager: AuthManager,
    ) {
        val context = screen.context

        // ==================== 登录设置 ====================

        PreferenceCategory(context).apply {
            title = "登录设置"

            // 用户名
            EditTextPreference(context).apply {
                key = JmConstants.PREF_USERNAME
                title = "用户名"
                summary = "禁漫天堂账号用户名"
                setDefaultValue("")

                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit()
                        .putString(JmConstants.PREF_USERNAME, newValue as String)
                        .apply()
                    true
                }
            }.let(::addPreference)

            // 密码
            EditTextPreference(context).apply {
                key = JmConstants.PREF_PASSWORD
                title = "密码"
                summary = "禁漫天堂账号密码"
                setDefaultValue("")

                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit()
                        .putString(JmConstants.PREF_PASSWORD, newValue as String)
                        .apply()
                    true
                }
            }.let(::addPreference)

            // 登录状态显示
            Preference(context).apply {
                title = "登录状态"
                summary = if (authManager.isLoggedIn()) {
                    val username = authManager.getUsername()
                    if (username.isNotEmpty()) {
                        "已登录: $username"
                    } else {
                        "已登录"
                    }
                } else {
                    "未登录"
                }
                isEnabled = false
            }.let(::addPreference)

            // 测试登录按钮
            Preference(context).apply {
                title = "测试登录"
                summary = "点击测试登录是否成功"

                setOnPreferenceClickListener {
                    try {
                        val username = preferences.getString(JmConstants.PREF_USERNAME, "") ?: ""
                        val password = preferences.getString(JmConstants.PREF_PASSWORD, "") ?: ""

                        if (username.isEmpty() || password.isEmpty()) {
                            Toast.makeText(
                                context,
                                "请先输入用户名和密码",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val result = authManager.login(username, password)
                            Toast.makeText(context, result, Toast.LENGTH_SHORT).show()

                            // 刷新登录状态显示
                            screen.removeAll()
                            setupPreferenceScreen(screen, preferences, authManager)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "登录失败: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    true
                }
            }.let(::addPreference)

            // 登出按钮
            Preference(context).apply {
                title = "登出"
                summary = "清除登录信息"

                setOnPreferenceClickListener {
                    authManager.logout()
                    Toast.makeText(context, "已登出", Toast.LENGTH_SHORT).show()

                    // 刷新登录状态显示
                    screen.removeAll()
                    setupPreferenceScreen(screen, preferences, authManager)
                    true
                }
            }.let(::addPreference)
        }.let(screen::addPreference)

        // ==================== API 域名设置 ====================

        PreferenceCategory(context).apply {
            title = "API 域名设置"

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

                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit()
                        .putInt(JmConstants.PREF_API_DOMAIN_INDEX, (newValue as String).toInt())
                        .apply()
                    Toast.makeText(
                        context,
                        "域名已切换，请重启应用",
                        Toast.LENGTH_LONG,
                    ).show()
                    true
                }
            }.let(::addPreference)
        }.let(screen::addPreference)

        // ==================== 限流设置 ====================

        PreferenceCategory(context).apply {
            title = "限流设置"

            // 请求数量
            ListPreference(context).apply {
                key = JmConstants.PREF_RATE_LIMIT
                title = "请求数量限制"
                entries = Array(10) { "${it + 1}" }
                entryValues = Array(10) { "${it + 1}" }
                summary = "在限制时间内允许的请求数量\n当前值: %s\n需要重启应用生效"
                setDefaultValue(JmConstants.PREF_RATE_LIMIT_DEFAULT)
            }.let(::addPreference)

            // 限制周期
            ListPreference(context).apply {
                key = JmConstants.PREF_RATE_PERIOD
                title = "限制周期（秒）"
                entries = Array(60) { "${it + 1}" }
                entryValues = Array(60) { "${it + 1}" }
                summary = "限制持续时间\n当前值: %s 秒\n需要重启应用生效"
                setDefaultValue(JmConstants.PREF_RATE_PERIOD_DEFAULT)
            }.let(::addPreference)
        }.let(screen::addPreference)

        // ==================== 过滤设置 ====================

        PreferenceCategory(context).apply {
            title = "内容过滤"

            // 屏蔽词列表
            EditTextPreference(context).apply {
                key = JmConstants.PREF_BLOCK_LIST
                title = "屏蔽词列表"
                summary = "用空格分隔多个关键词，大小写不敏感"
                dialogTitle = "屏蔽词列表"
                dialogMessage = "例如: YAOI 扶他 獵奇\n关键词之间用空格分离"
                setDefaultValue("")
            }.let(::addPreference)
        }.let(screen::addPreference)
    }
}
