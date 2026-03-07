package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import android.content.SharedPreferences

internal fun SharedPreferences.getApiDomainIndex(): Int {
    val stringValue = runCatching {
        getString(JmConstants.PREF_API_DOMAIN_INDEX, null)
    }.getOrNull()
        ?.toIntOrNull()
    if (stringValue != null) return stringValue.coerceAtLeast(0)

    val intValue = runCatching {
        getInt(JmConstants.PREF_API_DOMAIN_INDEX, 0)
    }.getOrDefault(0)
    return intValue.coerceAtLeast(0)
}

internal fun SharedPreferences.getBlockedWordDetailConcurrency(): Int {
    val stringValue = runCatching {
        getString(JmConstants.PREF_BLOCKED_WORD_DETAIL_CONCURRENCY, null)
    }.getOrNull()
        ?.toIntOrNull()
    if (stringValue != null) return stringValue.coerceIn(1, 8)

    val intValue = runCatching {
        getInt(JmConstants.PREF_BLOCKED_WORD_DETAIL_CONCURRENCY, 4)
    }.getOrDefault(4)
    return intValue.coerceIn(1, 8)
}
