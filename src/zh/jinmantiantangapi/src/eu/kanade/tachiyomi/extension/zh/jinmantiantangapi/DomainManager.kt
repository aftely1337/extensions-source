package eu.kanade.tachiyomi.extension.zh.jinmantiantangapi

import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 域名管理器
 * 负责从域名服务器获取和更新最新的 API 域名列表
 */
class DomainManager(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {

    /**
     * 从域名服务器获取最新域名列表
     *
     * @return 成功返回域名列表，失败返回 null
     */
    fun fetchLatestDomains(): List<String>? {
        for (serverUrl in JmConstants.API_DOMAIN_SERVER_LIST) {
            try {
                val request = Request.Builder()
                    .url(serverUrl)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) continue

                    val encryptedData = response.body?.string()?.trim() ?: continue
                    if (encryptedData.isEmpty()) continue

                    // 解密域名列表
                    val domains = JmCryptoTool.decryptDomainList(encryptedData)
                    if (domains.isNotEmpty()) {
                        return domains
                    }
                }
            } catch (e: Exception) {
                // 当前服务器失败，尝试下一个
                continue
            }
        }

        return null
    }

    /**
     * 更新域名列表到 SharedPreferences
     *
     * @param domains 新的域名列表
     */
    fun updateDomains(domains: List<String>) {
        preferences.edit()
            .putString(JmConstants.PREF_API_DOMAIN_LIST, domains.joinToString(","))
            .apply()
    }

    /**
     * 尝试更新域名列表
     * 如果获取失败，保持使用现有域名
     */
    fun tryUpdateDomains() {
        try {
            val latestDomains = fetchLatestDomains()
            if (latestDomains != null && latestDomains.isNotEmpty()) {
                updateDomains(latestDomains)
            }
        } catch (e: Exception) {
            // 更新失败，继续使用现有域名
        }
    }

    /**
     * 获取当前使用的域名
     */
    fun getCurrentDomain(): String {
        val domainList = preferences.getString(
            JmConstants.PREF_API_DOMAIN_LIST,
            JmConstants.API_DOMAIN_LIST.joinToString(","),
        )?.split(",") ?: JmConstants.API_DOMAIN_LIST.toList()

        val index = preferences.getInt(JmConstants.PREF_API_DOMAIN_INDEX, 0)
        return domainList.getOrNull(index) ?: domainList.first()
    }

    /**
     * 获取当前域名列表
     */
    fun getDomainList(): List<String> = preferences.getString(
        JmConstants.PREF_API_DOMAIN_LIST,
        JmConstants.API_DOMAIN_LIST.joinToString(","),
    )?.split(",") ?: JmConstants.API_DOMAIN_LIST.toList()
}
