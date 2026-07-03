package nl.vdzon.softwarefactory.youtrack.clients

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.YouTrackApiException
import nl.vdzon.softwarefactory.support.CallMetrics
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Alle HTTP-verkeer richting YouTrack: request-opbouw (JSON + multipart),
 * verzenden met metrics, en response-parsing. Kent niets van issues of
 * schema's — dat zit in [YouTrackClient] resp. [YouTrackSchemaBootstrapper].
 */
internal class YouTrackHttpTransport(
    factorySecrets: FactorySecrets,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient,
) {
    private val baseUrl = factorySecrets.youTrackBaseUrl.trimEnd('/')
    private val authorizationHeader = "Bearer ${factorySecrets.youTrackToken}"

    fun sendJson(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Any? = null,
        allowedStatuses: Set<Int> = successStatuses,
    ): JsonNode {
        val response = send(request(method, path, query, body))
        return parseJsonResponse(response, method, path, allowedStatuses)
    }

    fun sendJson(
        request: HttpRequest,
        allowedStatuses: Set<Int> = successStatuses,
    ): JsonNode {
        val response = send(request)
        return parseJsonResponse(response, request.method(), request.uri().path, allowedStatuses)
    }

    private fun parseJsonResponse(
        response: YouTrackResponse,
        method: String,
        path: String,
        allowedStatuses: Set<Int>,
    ): JsonNode {
        if (response.status !in allowedStatuses) {
            throw YouTrackApiException(
                "YouTrack request $method $path failed with status ${response.status}: ${response.body.take(500)}",
            )
        }
        return if (response.body.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(response.body)
    }

    fun request(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Any? = null,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path + query.toQueryString()))
            .header("Authorization", authorizationHeader)
            .header("Accept", "application/json")
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        }
        return builder.build()
    }

    fun multipartRequest(
        path: String,
        query: List<Pair<String, String>>,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): HttpRequest {
        val boundary = "----software-factory-${UUID.randomUUID()}"
        val body = buildMultipartBody(boundary, fieldName, fileName, mimeType, bytes)
        return HttpRequest.newBuilder(URI.create(baseUrl + path + query.toQueryString()))
            .header("Authorization", authorizationHeader)
            .header("Accept", "application/json")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
    }

    private fun buildMultipartBody(
        boundary: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ByteArray {
        // Bouw de part-header expliciet op met CRLF en een afsluitende lege regel
        // (`\r\n\r\n`) tussen de headers en de body. Een eerdere `trimIndent()`-variant
        // verwijderde die lege regel, waardoor de binaire data direct tegen de headers
        // plakte en YouTrack de upload afwees met 400 "Header section has more than 512
        // bytes (maybe it is not properly terminated)".
        val header = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"$fieldName\"; " +
                "filename=\"${fileName.replace("\"", "")}\"\r\n" +
                "Content-Type: $mimeType\r\n" +
                "\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        return header + bytes + footer
    }

    fun send(request: HttpRequest): YouTrackResponse =
        CallMetrics.measure("youtrack", "${request.method()} ${request.uri().path}") {
            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                YouTrackResponse(response.statusCode(), response.body() ?: "")
            } catch (exception: Exception) {
                throw YouTrackApiException(
                    "YouTrack request failed: ${request.method()} ${request.uri().path}",
                    exception,
                )
            }
        }

    /**
     * Downloadt ruwe bytes van [url]. Relatieve paden (zoals de ondertekende attachment-`url`s
     * van YouTrack) worden tegen de baseUrl geresolved; absolute URLs gebruiken we zoals ze
     * zijn. Het Bearer-token is voor beide veilig (zelfde host). Non-2xx → null.
     */
    fun downloadBytes(url: String): ByteArray? {
        val uri = if (url.startsWith("http://") || url.startsWith("https://")) {
            URI.create(url)
        } else {
            URI.create(baseUrl + url)
        }
        val request = HttpRequest.newBuilder(uri)
            .header("Authorization", authorizationHeader)
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return if (response.statusCode() in 200..299) response.body() else null
    }

    private fun List<Pair<String, String>>.toQueryString(): String =
        if (isEmpty()) {
            ""
        } else {
            joinToString(prefix = "?", separator = "&") { (key, value) ->
                "${key.urlEncoded()}=${value.urlEncoded()}"
            }
        }

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8)

    companion object {
        internal val successStatuses = (200..299).toSet()

        /**
         * HttpClient die zowel de publieke CA's (default cacerts) als de
         * cluster-ingress-CA vertrouwt. Nodig om YouTrack via de directe
         * OpenShift-route (*.apps.sno.lab.vdzon.com, lab-self-signed) te
         * bereiken i.p.v. via de Cloudflare-tunnel — zónder publieke trust
         * te verliezen (Anthropic/GitHub-calls blijven werken).
         *
         * Het CA-bestand staat op het classpath: /certs/cluster-ingress-ca.crt.
         * Ontbreekt het, dan vallen we terug op de standaard-truststore.
         *
         * NB: het cert is van de ingress-operator en roteert; bij rotatie
         * moet cluster-ingress-ca.crt opnieuw geëxporteerd worden
         * (oc get configmap default-ingress-cert -n openshift-config-managed).
         */
        internal fun defaultHttpClient(): HttpClient {
            val caStream = YouTrackHttpTransport::class.java.getResourceAsStream("/certs/cluster-ingress-ca.crt")
                ?: return HttpClient.newHttpClient()
            val caCerts = caStream.use { CertificateFactory.getInstance("X.509").generateCertificates(it) }

            val customTrustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                caCerts.forEachIndexed { index, cert -> setCertificateEntry("cluster-ingress-ca-$index", cert) }
            }
            val labTrustManager = trustManagerFrom(customTrustStore)
            val defaultTrustManager = trustManagerFrom(null) // null => JVM-default cacerts

            // Probeer eerst de publieke CA's; faalt dat, dan het lab-CA.
            val mergedTrustManager = object : X509TrustManager {
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        defaultTrustManager.checkServerTrusted(chain, authType)
                    } catch (ignored: CertificateException) {
                        labTrustManager.checkServerTrusted(chain, authType)
                    }
                }

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    defaultTrustManager.checkClientTrusted(chain, authType)

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    defaultTrustManager.acceptedIssuers + labTrustManager.acceptedIssuers
            }

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(mergedTrustManager), null)
            }
            return HttpClient.newBuilder().sslContext(sslContext).build()
        }

        private fun trustManagerFrom(keyStore: KeyStore?): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}

/** Status + body van een YouTrack-response, los van de java.net.http-typen. */
internal data class YouTrackResponse(
    val status: Int,
    val body: String,
)

/** Encodeert een path-segment (issue-key, comment-id, ...) — spaties als %20, niet als '+'. */
internal fun String.pathEncoded(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")
