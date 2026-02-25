package eu.kanade.tachiyomi.extension.zh.jinmantiantangapi

import android.content.SharedPreferences
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 登录管理器
 *
 * 负责用户登录、会话管理、登录状态检测
 */
class AuthManager(
    private val preferences: SharedPreferences,
    private val client: OkHttpClient,
    private val cookieJar: JmCookieJar,
) {

    /**
     * 获取当前 API 域名
     */
    private fun getApiDomain(): String {
        val domainList = preferences.getString(
            JmConstants.PREF_API_DOMAIN_LIST,
            JmConstants.API_DOMAIN_LIST.joinToString(","),
        )?.split(",") ?: JmConstants.API_DOMAIN_LIST.toList()

        val index = preferences.getInt(JmConstants.PREF_API_DOMAIN_INDEX, 0)
        return domainList.getOrNull(index) ?: domainList.first()
    }

    /**
     * 获取 API 基础 URL
     */
    private fun getApiBaseUrl(): String = "https://${getApiDomain()}"

    /**
     * 执行登录
     *
     * @param username 用户名
     * @param password 密码
     * @return 登录结果消息
     * @throws Exception 登录失败时抛出异常
     */
    fun login(username: String, password: String): String {
        if (username.isEmpty() || password.isEmpty()) {
            throw Exception("用户名和密码不能为空")
        }

        val baseUrl = getApiBaseUrl()
        val loginUrl = "$baseUrl${JmConstants.ENDPOINT_LOGIN}"

        // 构建登录请求体
        val requestBody = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()

        // 构建请求（签名拦截器会自动添加 token 和 tokenparam）
        val request = Request.Builder()
            .url(loginUrl)
            .post(requestBody)
            .build()

        // 执行请求
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("登录请求失败: HTTP ${response.code}")
            }

            // 读取响应
            val responseBody = response.body?.string()
                ?: throw Exception("登录响应为空")

            // 解析响应（响应拦截器已经解密了 data 字段）
            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw Exception("解析登录响应失败: ${e.message}")
            }

            // 检查响应状态
            val code = json.optInt("code", -1)
            if (code != 200) {
                val message = json.optString("message", "未知错误")
                throw Exception("登录失败: $message (code: $code)")
            }

            // 提取 data 字段
            val data = json.optJSONObject("data")
                ?: throw Exception("登录响应缺少 data 字段")

            // 提取 AVS cookie（对应响应中的 's' 字段）
            val avsValue = data.optString("s", "")

            // 保存 Cookie
            val domain = getApiDomain()
            val cookies = mutableListOf<String>()

            // 添加响应头中的 Set-Cookie
            response.headers("Set-Cookie").forEach { setCookie ->
                val cookiePair = setCookie.substringBefore(";")
                if (cookiePair.isNotEmpty()) {
                    cookies.add(cookiePair)
                }
            }

            // 添加 AVS cookie
            if (avsValue.isNotEmpty()) {
                cookies.add("AVS=$avsValue")
            }

            // 保存到 CookieJar
            if (cookies.isNotEmpty()) {
                cookieJar.setCookies(domain, cookies.joinToString("; "))
            }

            // 保存用户名和密码（用于自动重新登录）
            // 警告：密码以明文存储在 SharedPreferences 中
            // 安全改进：应使用 EncryptedSharedPreferences 或只存储 Cookie
            preferences.edit()
                .putString(JmConstants.PREF_USERNAME, username)
                .putString(JmConstants.PREF_PASSWORD, password)
                .apply()
            preferences.edit()
                .putString(JmConstants.PREF_USERNAME, username)
                .putString(JmConstants.PREF_PASSWORD, password)
                .apply()

            "登录成功"
        }
    }

    /**
     * 使用保存的凭证自动登录
     */
    fun autoLogin(): String {
        val username = preferences.getString(JmConstants.PREF_USERNAME, "") ?: ""
        val password = preferences.getString(JmConstants.PREF_PASSWORD, "") ?: ""

        if (username.isEmpty() || password.isEmpty()) {
            throw Exception("未保存登录凭证")
        }

        return login(username, password)
    }

    /**
     * 检查是否已登录
     *
     * @return true 如果有保存的 Cookie
     */
    fun isLoggedIn(): Boolean {
        val domain = getApiDomain()
        val cookies = cookieJar.getCookies(domain)
        return cookies.isNotEmpty()
    }

    /**
     * 获取保存的用户名
     */
    fun getUsername(): String = preferences.getString(JmConstants.PREF_USERNAME, "") ?: ""

    /**
     * 登出
     * 清除所有登录信息
     */
    fun logout() {
        cookieJar.clearAll()
        preferences.edit()
            .remove(JmConstants.PREF_USERNAME)
            .remove(JmConstants.PREF_PASSWORD)
            .apply()
    }

    /**
     * 测试登录状态
     * 通过请求需要登录的接口来验证会话是否有效
     */
    fun testLoginStatus(): Boolean {
        if (!isLoggedIn()) {
            return false
        }

        return try {
            val baseUrl = getApiBaseUrl()
            val testUrl = "$baseUrl${JmConstants.ENDPOINT_FAVORITE}?page=1"

            val request = Request.Builder()
                .url(testUrl)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()

            success
        } catch (e: Exception) {
            false
        }
    }
}
