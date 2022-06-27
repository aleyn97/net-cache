package com.aleyn.cache

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.internal.EMPTY_HEADERS
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.cache.CacheRequest
import okhttp3.internal.cache.DiskLruCache
import okhttp3.internal.closeQuietly
import okhttp3.internal.http.StatusLine
import okhttp3.internal.io.FileSystem
import okhttp3.internal.platform.Platform
import okhttp3.internal.toLongOrDefault
import okio.*
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.io.File
import java.io.Flushable
import java.io.IOException
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.util.*

/**
 * @author : Aleyn
 * @date : 2022/06/24 18:26
 */
class CacheManager(
    directory: File,
    maxSize: Long = 10 * 1024 * 1024 // 最大缓存大小, 默认 10M
) : Closeable, Flushable, ICache {

    val cache: DiskLruCache =
        OkHttpHelper.getDiskLruCache(FileSystem.SYSTEM, directory, 1, 2, maxSize)

    internal var writeSuccessCount = 0
    internal var writeAbortCount = 0

    val isClosed: Boolean
        get() = cache.isClosed()

    override fun getCache(cacheKey: String?, request: Request): Response? {
        cacheKey ?: return null
        val key = key(cacheKey)
        val snapshot: DiskLruCache.Snapshot = try {
            cache[key] ?: return null
        } catch (e: IOException) {
            return null
        }

        val entry: Entry = try {
            Entry(snapshot.getSource(ENTRY_METADATA))
        } catch (_: IOException) {
            snapshot.closeQuietly()
            return null
        }

        val response = entry.response(request, snapshot)
        if (!entry.matches(request, response)) {
            response.body?.closeQuietly()
            return null
        }
        return response
    }

    override fun putCache(cacheKey: String?, response: Response): CacheRequest? {
        if (response.hasVaryAll()) return null
        val entry = Entry(response)
        var editor: DiskLruCache.Editor? = null
        return try {
            val redKey = key(cacheKey ?: response.request.url.toString())
            editor = cache.edit(redKey) ?: return null
            entry.writeTo(editor)
            RealCacheRequest(editor)
        } catch (_: IOException) {
            abortQuietly(editor)
            null
        }
    }

    override fun remove(cacheKey: String?) {
        cacheKey?.let {
            cache.remove(key(it))
        }
    }

    override fun removeAll() {
        cache.evictAll()
    }


    private fun abortQuietly(editor: DiskLruCache.Editor?) {
        try {
            editor?.abort()
        } catch (_: IOException) {
        }
    }


    @Throws(IOException::class)
    fun initialize() {
        cache.initialize()
    }

    @Throws(IOException::class)
    fun delete() {
        cache.delete()
    }

    @Synchronized
    fun writeAbortCount(): Int = writeAbortCount

    @Synchronized
    fun writeSuccessCount(): Int = writeSuccessCount

    @Throws(IOException::class)
    override fun flush() {
        cache.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        cache.close()
    }

    private inner class RealCacheRequest(
        private val editor: DiskLruCache.Editor
    ) : CacheRequest {
        private val cacheOut: Sink = editor.newSink(ENTRY_BODY)
        private val body: Sink
        var done = false

        init {
            this.body = object : ForwardingSink(cacheOut) {
                @Throws(IOException::class)
                override fun close() {
                    synchronized(this@CacheManager) {
                        if (done) return
                        done = true
                        writeSuccessCount++
                    }
                    super.close()
                    editor.commit()
                }
            }
        }

        override fun abort() {
            synchronized(this@CacheManager) {
                if (done) return
                done = true
                writeAbortCount++
            }
            cacheOut.closeQuietly()
            try {
                editor.abort()
            } catch (_: IOException) {
            }
        }

        override fun body(): Sink = body
    }

    private class Entry {
        private val url: HttpUrl
        private val varyHeaders: Headers
        private val requestMethod: String
        private val protocol: Protocol
        private val code: Int
        private val message: String
        private val responseHeaders: Headers
        private val handshake: Handshake?
        private val sentRequestMillis: Long
        private val receivedResponseMillis: Long

        private val isHttps: Boolean get() = url.scheme == "https"

        @Throws(IOException::class)
        constructor(rawSource: Source) {
            try {
                val source = rawSource.buffer()
                val urlLine = source.readUtf8LineStrict()
                url = urlLine.toHttpUrlOrNull()
                    ?: throw IOException("Cache corruption for $urlLine")
                requestMethod = source.readUtf8LineStrict()
                val varyHeadersBuilder = Headers.Builder()
                val varyRequestHeaderLineCount = readInt(source)
                for (i in 0 until varyRequestHeaderLineCount) {
                    addHeaderLenient(varyHeadersBuilder, source.readUtf8LineStrict())
                }
                varyHeaders = varyHeadersBuilder.build()

                val statusLine = StatusLine.parse(source.readUtf8LineStrict())
                protocol = statusLine.protocol
                code = statusLine.code
                message = statusLine.message
                val responseHeadersBuilder = Headers.Builder()
                val responseHeaderLineCount = readInt(source)
                for (i in 0 until responseHeaderLineCount) {
                    addHeaderLenient(responseHeadersBuilder, source.readUtf8LineStrict())
                }
                val sendRequestMillisString = responseHeadersBuilder[SENT_MILLIS]
                val receivedResponseMillisString = responseHeadersBuilder[RECEIVED_MILLIS]
                responseHeadersBuilder.removeAll(SENT_MILLIS)
                responseHeadersBuilder.removeAll(RECEIVED_MILLIS)
                sentRequestMillis = sendRequestMillisString?.toLong() ?: 0L
                receivedResponseMillis = receivedResponseMillisString?.toLong() ?: 0L
                responseHeaders = responseHeadersBuilder.build()

                if (isHttps) {
                    val blank = source.readUtf8LineStrict()
                    if (blank.isNotEmpty()) {
                        throw IOException("expected \"\" but was \"$blank\"")
                    }
                    val cipherSuiteString = source.readUtf8LineStrict()
                    val cipherSuite = CipherSuite.forJavaName(cipherSuiteString)
                    val peerCertificates = readCertificateList(source)
                    val localCertificates = readCertificateList(source)
                    val tlsVersion = if (!source.exhausted()) {
                        TlsVersion.forJavaName(source.readUtf8LineStrict())
                    } else {
                        TlsVersion.SSL_3_0
                    }
                    handshake =
                        Handshake.get(tlsVersion, cipherSuite, peerCertificates, localCertificates)
                } else {
                    handshake = null
                }
            } finally {
                rawSource.close()
            }
        }

        constructor(response: Response) {
            this.url = response.request.url
            this.varyHeaders = response.varyHeaders()
            this.requestMethod = response.request.method
            this.protocol = response.protocol
            this.code = response.code
            this.message = response.message
            this.responseHeaders = response.headers
            this.handshake = response.handshake
            this.sentRequestMillis = response.sentRequestAtMillis
            this.receivedResponseMillis = response.receivedResponseAtMillis
        }

        @Throws(IOException::class)
        fun writeTo(editor: DiskLruCache.Editor) {
            editor.newSink(ENTRY_METADATA).buffer().use { sink ->
                sink.writeUtf8(url.toString()).writeByte('\n'.code)
                sink.writeUtf8(requestMethod).writeByte('\n'.code)
                sink.writeDecimalLong(varyHeaders.size.toLong()).writeByte('\n'.code)
                for (i in 0 until varyHeaders.size) {
                    sink.writeUtf8(varyHeaders.name(i))
                        .writeUtf8(": ")
                        .writeUtf8(varyHeaders.value(i))
                        .writeByte('\n'.code)
                }

                sink.writeUtf8(StatusLine(protocol, code, message).toString())
                    .writeByte('\n'.code)
                sink.writeDecimalLong((responseHeaders.size + 2).toLong()).writeByte('\n'.code)
                for (i in 0 until responseHeaders.size) {
                    sink.writeUtf8(responseHeaders.name(i))
                        .writeUtf8(": ")
                        .writeUtf8(responseHeaders.value(i))
                        .writeByte('\n'.code)
                }
                sink.writeUtf8(SENT_MILLIS)
                    .writeUtf8(": ")
                    .writeDecimalLong(sentRequestMillis)
                    .writeByte('\n'.code)
                sink.writeUtf8(RECEIVED_MILLIS)
                    .writeUtf8(": ")
                    .writeDecimalLong(receivedResponseMillis)
                    .writeByte('\n'.code)

                if (isHttps) {
                    sink.writeByte('\n'.code)
                    sink.writeUtf8(handshake!!.cipherSuite.javaName).writeByte('\n'.code)
                    writeCertList(sink, handshake.peerCertificates)
                    writeCertList(sink, handshake.localCertificates)
                    sink.writeUtf8(handshake.tlsVersion.javaName).writeByte('\n'.code)
                }
            }
        }

        @Throws(IOException::class)
        private fun readCertificateList(source: BufferedSource): List<Certificate> {
            val length = readInt(source)
            if (length == -1) return emptyList() // OkHttp v1.2 used -1 to indicate null.

            try {
                val certificateFactory = CertificateFactory.getInstance("X.509")
                val result = ArrayList<Certificate>(length)
                for (i in 0 until length) {
                    val line = source.readUtf8LineStrict()
                    val bytes = Buffer()
                    bytes.write(line.decodeBase64()!!)
                    result.add(certificateFactory.generateCertificate(bytes.inputStream()))
                }
                return result
            } catch (e: CertificateException) {
                throw IOException(e.message)
            }
        }

        @Throws(IOException::class)
        private fun writeCertList(sink: BufferedSink, certificates: List<Certificate>) {
            try {
                sink.writeDecimalLong(certificates.size.toLong()).writeByte('\n'.code)
                for (element in certificates) {
                    val bytes = element.encoded
                    val line = bytes.toByteString().base64()
                    sink.writeUtf8(line).writeByte('\n'.code)
                }
            } catch (e: CertificateEncodingException) {
                throw IOException(e.message)
            }
        }

        fun matches(request: Request, response: Response): Boolean {
            return url == request.url &&
                    requestMethod == request.method &&
                    varyMatches(response, varyHeaders, request)
        }

        fun response(request: Request, snapshot: DiskLruCache.Snapshot): Response {
            val contentType = responseHeaders["Content-Type"]
            val contentLength = responseHeaders["Content-Length"]
            return Response.Builder()
                .request(request)
                .protocol(protocol)
                .code(code)
                .message(message)
                .headers(responseHeaders)
                .body(CacheResponseBody(snapshot, contentType, contentLength))
                .handshake(handshake)
                .sentRequestAtMillis(sentRequestMillis)
                .receivedResponseAtMillis(receivedResponseMillis)
                .build()
        }

        companion object {
            private val SENT_MILLIS = "${Platform.get().getPrefix()}-Sent-Millis"
            private val RECEIVED_MILLIS = "${Platform.get().getPrefix()}-Received-Millis"
        }
    }

    private class CacheResponseBody(
        val snapshot: DiskLruCache.Snapshot,
        private val contentType: String?,
        private val contentLength: String?
    ) : ResponseBody() {
        private val bodySource: BufferedSource

        init {
            val source = snapshot.getSource(ENTRY_BODY)
            bodySource = object : ForwardingSource(source) {
                @Throws(IOException::class)
                override fun close() {
                    snapshot.close()
                    super.close()
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = contentType?.toMediaTypeOrNull()

        override fun contentLength(): Long = contentLength?.toLongOrDefault(-1L) ?: -1L

        override fun source(): BufferedSource = bodySource
    }

    companion object {
        private const val ENTRY_METADATA = 0
        private const val ENTRY_BODY = 1

        /**
         * 全局缓存模式
         */
        var cacheModel = CacheMode.ONLY_NETWORK
            private set

        /**
         * 全局缓存时长
         */
        var cacheTime = 10 * 1000L
            private set

        /**
         * 是否使用过期数据
         */
        var useExpiredData = false
            private set

        fun setCacheModel(@CacheMode.CacheModel cacheMode: String) = apply {
            this.cacheModel = cacheMode
        }

        fun setCacheTime(cacheTime: Long) = apply {
            this.cacheTime = cacheTime
        }

        /**
         * 仅对只读取缓存模式生效
         */
        fun useExpiredData(useExpiredData: Boolean) = apply {
            this.useExpiredData = useExpiredData
        }

        @JvmStatic
        internal fun key(url: String): String = url.encodeUtf8().md5().hex()

        @Throws(IOException::class)
        internal fun readInt(source: BufferedSource): Int {
            try {
                val result = source.readDecimalLong()
                val line = source.readUtf8LineStrict()
                if (result < 0L || result > Integer.MAX_VALUE || line.isNotEmpty()) {
                    throw IOException("expected an int but was \"$result$line\"")
                }
                return result.toInt()
            } catch (e: NumberFormatException) {
                throw IOException(e.message)
            }
        }

        private fun varyMatches(
            cachedResponse: Response,
            cachedRequest: Headers,
            newRequest: Request
        ): Boolean {
            return cachedResponse.headers.varyFields().none {
                cachedRequest.values(it) != newRequest.headers(it)
            }
        }

        private fun Response.hasVaryAll() = "*" in headers.varyFields()

        private fun Headers.varyFields(): Set<String> {
            var result: MutableSet<String>? = null
            for (i in 0 until size) {
                if (!"Vary".equals(name(i), ignoreCase = true)) {
                    continue
                }

                val value = value(i)
                if (result == null) {
                    result = TreeSet(String.CASE_INSENSITIVE_ORDER)
                }
                for (varyField in value.split(',')) {
                    result.add(varyField.trim())
                }
            }
            return result ?: emptySet()
        }

        fun Response.varyHeaders(): Headers {
            val requestHeaders = networkResponse!!.request.headers
            val responseHeaders = headers
            return varyHeaders(requestHeaders, responseHeaders)
        }

        private fun varyHeaders(requestHeaders: Headers, responseHeaders: Headers): Headers {
            val varyFields = responseHeaders.varyFields()
            if (varyFields.isEmpty()) return EMPTY_HEADERS

            val result = Headers.Builder()
            for (i in 0 until requestHeaders.size) {
                val fieldName = requestHeaders.name(i)
                if (fieldName in varyFields) {
                    result.add(fieldName, requestHeaders.value(i))
                }
            }
            return result.build()
        }
    }
}