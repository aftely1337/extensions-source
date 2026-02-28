package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request

class DomainManager(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {
    data class DomainOption(
        val domain: String,
        val label: String? = null,
    )

    fun fetchLatestDomainOptions(): List<DomainOption>? {
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

                    val domains = JmCryptoTool.decryptDomainOptions(encryptedData)
                    if (domains.isNotEmpty()) {
                        return domains.map { DomainOption(it.domain, it.label) }
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }

        return null
    }

    fun updateDomainOptions(domains: List<DomainOption>) {
        val labels = domains.map { it.label?.trim().orEmpty() }
        val hasAnyLabel = labels.any { it.isNotEmpty() }
        val labelJson = if (hasAnyLabel) {
            org.json.JSONArray().apply {
                labels.forEach { put(it) }
            }.toString()
        } else {
            null
        }

        preferences.edit()
            .putString(JmConstants.PREF_API_DOMAIN_LIST, domains.joinToString(",") { it.domain })
            .apply {
                if (labelJson != null) {
                    putString(JmConstants.PREF_API_DOMAIN_LABEL_LIST, labelJson)
                } else {
                    remove(JmConstants.PREF_API_DOMAIN_LABEL_LIST)
                }
            }
            .apply()
    }

    fun tryUpdateDomains() {
        try {
            val latestDomains = fetchLatestDomainOptions()
            if (latestDomains != null && latestDomains.isNotEmpty()) {
                updateDomainOptions(latestDomains)
            }
        } catch (_: Exception) {
        }
    }
}
