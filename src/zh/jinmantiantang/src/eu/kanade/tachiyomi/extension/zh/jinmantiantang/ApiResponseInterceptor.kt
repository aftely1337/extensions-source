package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

class ApiResponseInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!response.isSuccessful) return response

        val contentType = response.body?.contentType()
        if (contentType?.toString()?.contains("json") != true) return response

        val responseBody = response.body?.string() ?: return response

        try {
            val json = JSONObject(responseBody)

            if (!json.has("data") || json.isNull("data")) {
                return response.newBuilder()
                    .body(responseBody.toResponseBody(contentType))
                    .build()
            }

            val dataField = json.get("data")
            if (dataField !is String) {
                return response.newBuilder()
                    .body(responseBody.toResponseBody(contentType))
                    .build()
            }

            val timestamp = request.header("X-Request-Timestamp")?.toLongOrNull()
                ?: response.request.header("X-Request-Timestamp")?.toLongOrNull()
                ?: response.header("X-Request-Timestamp")?.toLongOrNull()

            if (timestamp == null) {
                return response.newBuilder()
                    .body(responseBody.toResponseBody(contentType))
                    .build()
            }

            val decryptedData = try {
                JmCryptoTool.decryptResponse(dataField, timestamp)
            } catch (_: Exception) {
                try {
                    JSONObject(dataField)
                    dataField
                } catch (_: Exception) {
                    return response.newBuilder()
                        .body(responseBody.toResponseBody(contentType))
                        .build()
                }
            }

            json.put("data", JSONObject(decryptedData))
            val newResponseBody = json.toString()
            return response.newBuilder()
                .body(newResponseBody.toResponseBody(contentType))
                .build()
        } catch (_: Exception) {
            return response.newBuilder()
                .body(responseBody.toResponseBody(contentType))
                .build()
        }
    }
}
