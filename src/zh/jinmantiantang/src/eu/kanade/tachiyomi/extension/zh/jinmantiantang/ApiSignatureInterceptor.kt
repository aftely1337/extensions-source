package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import okhttp3.Interceptor
import okhttp3.Response

class ApiSignatureInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        val timestamp = System.currentTimeMillis() / 1000
        val useSpecialSecret = originalUrl.toString().contains(JmConstants.ENDPOINT_CHAPTER_VIEW_TEMPLATE)
        val useEmptyVersion = originalRequest.method.equals("GET", ignoreCase = true)

        val (token, tokenparam) = if (useSpecialSecret) {
            if (useEmptyVersion) {
                JmCryptoTool.generateSpecialToken(timestamp).let { (specialToken, _) ->
                    specialToken to "$timestamp,"
                }
            } else {
                JmCryptoTool.generateSpecialToken(timestamp)
            }
        } else {
            if (useEmptyVersion) {
                JmCryptoTool.generateToken(timestamp, version = "")
            } else {
                JmCryptoTool.generateToken(timestamp)
            }
        }

        val newRequest = originalRequest.newBuilder()
            .header("token", token)
            .header("tokenparam", tokenparam)
            .header("X-Request-Timestamp", timestamp.toString())
            .header("User-Agent", JmConstants.USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate")
            .header("X-Requested-With", JmConstants.IMAGE_X_REQUESTED_WITH)
            .build()

        val response = chain.proceed(newRequest)
        return response.newBuilder()
            .addHeader("X-Request-Timestamp", timestamp.toString())
            .build()
    }
}
