package io.github.snowdrit.mamokeep.data.scanner

import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

interface ModelResponse : Closeable {
    val body: InputStream
    val contentLength: Long
}

fun interface ModelTransport {
    fun open(uri: URI): ModelResponse
}

/** GET only; never receives photos, account data, or credentials. */
class HttpsModelTransport(private val policy: ModelDownloadPolicy) : ModelTransport {
    override fun open(uri: URI): ModelResponse {
        var target = uri
        repeat(policy.maxRedirects + 1) { redirect ->
            validateTarget(target, policy)
            val connection = target.toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = policy.connectTimeoutMillis
            connection.readTimeout = policy.readTimeoutMillis
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")
                        ?: throw ModelInstallException(ScannerModelError.SOURCE)
                    if (redirect == policy.maxRedirects) throw ModelInstallException(ScannerModelError.SOURCE)
                    target = target.resolve(location)
                    connection.disconnect()
                } else {
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw ModelInstallException(
                            if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_FORBIDDEN)
                                ScannerModelError.SOURCE else ScannerModelError.NETWORK,
                        )
                    }
                    if (connection.contentEncoding?.let { !it.equals("identity", true) } == true) {
                        throw ModelInstallException(ScannerModelError.INTEGRITY)
                    }
                    val stream = connection.inputStream
                    return object : ModelResponse {
                        override val body = stream
                        override val contentLength = connection.contentLengthLong
                        override fun close() {
                            try { stream.close() } finally { connection.disconnect() }
                        }
                    }
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw ModelInstallException(ScannerModelError.SOURCE)
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        internal fun validateTarget(uri: URI, policy: ModelDownloadPolicy) {
            if (uri.scheme != "https" || uri.host !in policy.allowedRedirectHosts ||
                uri.userInfo != null || uri.fragment != null || uri.port !in listOf(-1, 443)
            ) throw ModelInstallException(ScannerModelError.SOURCE)
        }
    }
}
