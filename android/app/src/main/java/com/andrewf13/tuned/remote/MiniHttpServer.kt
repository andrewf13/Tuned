package com.andrewf13.tuned.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
    val remote: InetAddress,
)

data class HttpResponse(val status: Int, val body: String, val contentType: String = "application/json; charset=utf-8")

/**
 * A deliberately tiny HTTP/1.1 server (one request per connection) for the phone remote.
 * Only private / local network addresses are served; everything else is dropped.
 */
class MiniHttpServer(private val handler: (HttpRequest) -> HttpResponse) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    val port: Int get() = socket?.localPort ?: -1

    fun start(preferredPort: Int = 8642): Int {
        val s = runCatching { ServerSocket(preferredPort) }.getOrElse { ServerSocket(0) }
        socket = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: SocketException) { break }
                pool.execute { serve(c) }
            }
        }, "tuned-remote").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun isLocal(a: InetAddress) =
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
            // IPv6 unique-local (fc00::/7)
            (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)

    private fun serve(c: Socket): Unit = c.use {
        it.soTimeout = 10_000
        if (!isLocal(it.inetAddress)) return
        val response = try {
            val req = read(BufferedInputStream(it.getInputStream()), it.inetAddress) ?: return
            handler(req)
        } catch (e: Exception) {
            HttpResponse(500, """{"error":"${e.message?.replace("\"", "'") ?: "error"}"}""")
        }
        val body = response.body.toByteArray()
        val head = "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
            "Content-Type: ${response.contentType}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        val out = it.getOutputStream()
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return out.toString(Charsets.UTF_8.name()).trimEnd('\r')
            out.write(b)
            if (out.size() > 8192) return null
        }
    }

    private fun read(input: InputStream, remote: InetAddress): HttpRequest? {
        val start = readLine(input) ?: return null
        val parts = start.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 64 * 1024) ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return HttpRequest(parts[0].uppercase(), path, query, headers, String(body, 0, read, Charsets.UTF_8), remote)
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 409 -> "Conflict"; 429 -> "Too Many Requests"; else -> "Error"
    }
}
