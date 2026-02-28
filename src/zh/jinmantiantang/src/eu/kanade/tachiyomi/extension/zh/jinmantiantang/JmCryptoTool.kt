package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object JmCryptoTool {
    data class DomainOption(
        val domain: String,
        val label: String? = null,
    )

    fun generateToken(
        timestamp: Long,
        version: String = JmConstants.APP_VERSION,
        secret: String = JmConstants.APP_TOKEN_SECRET,
    ): Pair<String, String> {
        val token = md5("$timestamp$secret")
        val tokenparam = "$timestamp,$version"
        return Pair(token, tokenparam)
    }

    fun generateSpecialToken(timestamp: Long): Pair<String, String> = generateToken(
        timestamp = timestamp,
        secret = JmConstants.APP_TOKEN_SECRET_2,
    )

    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun decryptResponse(
        encryptedData: String,
        timestamp: Long,
        secret: String = JmConstants.APP_DATA_SECRET,
    ): String {
        try {
            val keyString = md5("$timestamp$secret")
            val keyBytes = keyString.toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val encryptedBytes = Base64.decode(encryptedData, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            throw Exception("解密响应数据失败: ${e.message}", e)
        }
    }

    fun getScrambleNum(aid: Int, imgIndex: String): Int {
        if (aid < JmConstants.SCRAMBLE_ID) return 0

        val modulus = when {
            aid >= JmConstants.SCRAMBLE_421926 -> 8
            aid >= JmConstants.SCRAMBLE_268850 -> 10
            else -> return 10
        }
        val md5LastChar = md5LastCharCode("$aid$imgIndex")
        return 2 * (md5LastChar % modulus) + 2
    }

    private fun md5LastCharCode(input: String): Int = md5(input).last().code

    fun decryptDomainOptions(encryptedData: String): List<DomainOption> {
        try {
            val keyString = md5(JmConstants.API_DOMAIN_SERVER_SECRET)
            val keyBytes = keyString.toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val encryptedBytes = Base64.decode(encryptedData, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            val decryptedText = String(decryptedBytes, Charsets.UTF_8)
            return extractDomainOptionsFromJson(decryptedText)
        } catch (e: Exception) {
            throw Exception("解密域名列表失败: ${e.message}", e)
        }
    }

    private fun extractDomainOptionsFromJson(jsonText: String): List<DomainOption> {
        val domains = mutableListOf<DomainOption>()
        try {
            val json = org.json.JSONObject(jsonText)
            json.keys().forEach { key ->
                val value = json.get(key)
                extractDomainOptionsFromValue(value, domains)
            }
        } catch (_: Exception) {
            try {
                val jsonArray = org.json.JSONArray(jsonText)
                extractDomainOptionsFromValue(jsonArray, domains)
            } catch (_: Exception) {
                jsonText.lines()
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { isLikelyDomain(it) }
                    .forEach { domains.add(DomainOption(domain = it)) }
            }
        }
        return mergeDomainOptions(domains)
    }

    private fun extractDomainOptionsFromValue(
        value: Any,
        domains: MutableList<DomainOption>,
        inheritedLabel: String? = null,
    ) {
        when (value) {
            is org.json.JSONArray -> {
                for (i in 0 until value.length()) {
                    val item = value.get(i)
                    when (item) {
                        is String -> {
                            if (isLikelyDomain(item)) {
                                domains.add(DomainOption(domain = item, label = inheritedLabel))
                            }
                        }
                        is org.json.JSONArray -> {
                            if (item.length() > 0) {
                                val first = item.get(0)
                                if (first is String && isLikelyDomain(first)) {
                                    val label = buildList {
                                        val second = if (item.length() > 1) item.opt(1) else null
                                        if (second is String) add(second)
                                        if (!inheritedLabel.isNullOrBlank()) add(inheritedLabel)
                                    }.firstOrNull { !sanitizeLabel(it).isNullOrBlank() }
                                    domains.add(DomainOption(domain = first, label = sanitizeLabel(label)))
                                } else {
                                    extractDomainOptionsFromValue(item, domains, inheritedLabel)
                                }
                            }
                        }
                        is org.json.JSONObject -> extractDomainOptionsFromObject(item, domains, inheritedLabel)
                        else -> extractDomainOptionsFromValue(item, domains, inheritedLabel)
                    }
                }
            }
            is String -> {
                if (isLikelyDomain(value)) {
                    domains.add(DomainOption(domain = value, label = inheritedLabel))
                }
            }
            is org.json.JSONObject -> extractDomainOptionsFromObject(value, domains, inheritedLabel)
        }
    }

    private fun extractDomainOptionsFromObject(
        obj: org.json.JSONObject,
        domains: MutableList<DomainOption>,
        inheritedLabel: String? = null,
    ) {
        val directDomain = sequenceOf("domain", "host", "url", "api_domain")
            .mapNotNull { key -> obj.opt(key) as? String }
            .firstOrNull { isLikelyDomain(it) }
        val directLabel = sequenceOf("label", "name", "title", "desc", "line", "region")
            .mapNotNull { key -> obj.opt(key) as? String }
            .map { sanitizeLabel(it) }
            .firstOrNull { !it.isNullOrBlank() }
        if (directDomain != null) {
            domains.add(DomainOption(directDomain, directLabel ?: inheritedLabel))
            return
        }

        obj.keys().forEach { key ->
            val child = obj.get(key)
            val nextLabel = when {
                isLikelyDomain(key) -> inheritedLabel
                else -> sanitizeLabel(key) ?: inheritedLabel
            }
            extractDomainOptionsFromValue(child, domains, nextLabel)
        }
    }

    private fun mergeDomainOptions(items: List<DomainOption>): List<DomainOption> {
        val merged = LinkedHashMap<String, String?>()
        items.forEach { item ->
            val domain = item.domain.trim().removeSurrounding("\"")
            if (!isLikelyDomain(domain)) return@forEach

            val label = sanitizeLabel(item.label)
            val existing = merged[domain]
            if (existing.isNullOrBlank() || (!label.isNullOrBlank() && existing == domain)) {
                merged[domain] = label
            } else if (!merged.containsKey(domain)) {
                merged[domain] = label
            }
        }

        return merged.map { (domain, label) -> DomainOption(domain = domain, label = label) }
    }

    private fun sanitizeLabel(value: String?): String? {
        val normalized = value?.trim()?.removeSurrounding("\"")
        return normalized?.takeIf { it.isNotBlank() }
    }

    private fun isLikelyDomain(value: String): Boolean {
        val normalized = value.trim().removeSurrounding("\"")
        return normalized.contains('.') && !normalized.contains(':') && !normalized.contains('/')
    }
}
