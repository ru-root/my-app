package ru.ruroot.sockstunnel

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

/**
 * Локальный SOCKS5-сервер (127.0.0.1).
 *
 * Схема работы:
 *   приложение клиента -> локальный SOCKS5 :5557
 *        -> TLS-соединение с 3proxy (ruroot.ru:9443, сертификат проверяется)
 *        -> SOCKS5-рукопожатие с 3proxy (логин/пароль сервера)
 *        -> двусторонняя пересылка данных.
 *
 * На каждый запрос клиента открывается свой TLS-туннель, поэтому при обрыве
 * следующий запрос автоматически переподключится (повтор до 3 раз).
 */
class Socks5Server(
    private val localPort: Int,
    private val requireLocalAuth: Boolean,
    private val localUser: String?,
    private val localPass: String?,
    private val tunnelHost: String,
    private val tunnelPort: Int,
    private val tunnelUser: String,
    private val tunnelPass: String,
    private val onStatus: (String) -> Unit
) : Thread("socks5-server") {

    @Volatile private var running = true
    private var server: ServerSocket? = null

    override fun run() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), localPort))
            server = ss
            onStatus("SOCKS5 слушает 127.0.0.1:$localPort")
            while (running) {
                val client = try {
                    ss.accept()
                } catch (e: SocketException) {
                    break
                }
                Thread({ handleClient(client) }, "socks-conn").start()
            }
        } catch (e: Exception) {
            if (running) onStatus("Ошибка локального сервера: ${e.message ?: e.javaClass.simpleName}")
        }
        onStatus("Локальный SOCKS5 остановлен")
    }

    fun stopServer() {
        running = false
        try { server?.close() } catch (_: Exception) {}
    }

    private fun handleClient(client: Socket) {
        var tunnel: SSLSocket? = null
        try {
            client.tcpNoDelay = true
            val inp = DataInputStream(BufferedInputStream(client.getInputStream()))
            val out = BufferedOutputStream(client.getOutputStream())

            // --- приветствие SOCKS5 (RFC 1928) ---
            if (inp.readUnsignedByte() != 5) { client.close(); return }
            val nMethods = inp.readUnsignedByte()
            val methods = ByteArray(nMethods)
            inp.readFully(methods)
            val hasNoAuth = methods.contains(0x00.toByte())
            val hasAuth = methods.contains(0x02.toByte())

            val chosen: Int = when {
                requireLocalAuth && hasAuth -> 0x02
                !requireLocalAuth && hasNoAuth -> 0x00
                else -> 0xFF // нет подходящего метода
            }
            out.write(byteArrayOf(0x05, chosen.toByte()))
            out.flush()
            if (chosen == 0xFF) { client.close(); return }

            // --- локальная аутентификация (RFC 1929), если включена ---
            if (chosen == 0x02) {
                if (inp.readUnsignedByte() != 1) { client.close(); return }
                val uLen = inp.readUnsignedByte()
                val uname = ByteArray(uLen); inp.readFully(uname)
                val pLen = inp.readUnsignedByte()
                val passwd = ByteArray(pLen); inp.readFully(passwd)
                val okU = localUser?.let { String(uname, Charsets.UTF_8) == it } ?: true
                val okP = localPass?.let { String(passwd, Charsets.UTF_8) == it } ?: true
                out.write(byteArrayOf(0x01, (if (okU && okP) 0 else 1).toByte()))
                out.flush()
                if (!(okU && okP)) { client.close(); return }
            }

            // --- запрос CONNECT ---
            val ver = inp.readUnsignedByte()
            val cmd = inp.readUnsignedByte()
            inp.readUnsignedByte() // reserved
            val atyp = inp.readUnsignedByte()
            val addr: ByteArray = when (atyp) {
                0x01 -> ByteArray(4).also { inp.readFully(it) }
                0x03 -> {
                    val l = inp.readUnsignedByte()
                    ByteArray(l).also { inp.readFully(it) }
                }
                0x04 -> ByteArray(16).also { inp.readFully(it) }
                else -> { reply(out, 0x08); client.close(); return }
            }
            val portHi = inp.readUnsignedByte()
            val portLo = inp.readUnsignedByte()
            val targetPort = (portHi shl 8) or portLo
            if (ver != 5 || cmd != 0x01) { reply(out, 0x07); client.close(); return }

            // --- туннель к 3proxy через TLS ---
            tunnel = try {
                openTunnel()
            } catch (e: Exception) {
                onStatus("Не удалось установить TLS: ${e.message ?: e.javaClass.simpleName}")
                reply(out, 0x01)
                client.close()
                return
            }

            val ok = try {
                socks5HandshakeRemote(tunnel, atyp, addr, targetPort)
            } catch (e: Exception) {
                onStatus("Ошибка SOCKS5-рукопожатия с сервером: ${e.message ?: e.javaClass.simpleName}")
                try { tunnel.close() } catch (_: Exception) {}
                reply(out, 0x05)
                client.close()
                return
            }
            if (!ok) {
                reply(out, 0x01)
                client.close()
                return
            }

            onStatus("Туннель открыт: ${addrString(atyp, addr)}:$targetPort")
            reply(out, 0x00)
            pipe(client, tunnel)
        } catch (e: Exception) {
            // любая ошибка просто закрывает соединение клиента
        } finally {
            try { client.close() } catch (_: Exception) {}
            tunnel?.let { try { it.close() } catch (_: Exception) {} }
        }
    }

    /** Подтверждение локальному клиенту (BIND-адрес не используется, ставим нули). */
    private fun reply(out: BufferedOutputStream, code: Int) {
        out.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    /** TLS-подключение к 3proxy с проверкой сертификата и повторами. */
    private fun openTunnel(): SSLSocket {
        var last: Exception? = null
        var delay = 2000L
        for (attempt in 1..3) {
            try {
                val plain = Socket()
                plain.connect(InetSocketAddress(tunnelHost, tunnelPort), 10000)
                plain.tcpNoDelay = true

                // системное хранилище сертификатов + проверка имени хоста
                val factory = HttpsURLConnection.getDefaultSSLSocketFactory()
                val ssl = factory.createSocket(plain, tunnelHost, tunnelPort, true) as SSLSocket
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
                ssl.soTimeout = 15000 // только на время рукопожатия
                ssl.startHandshake()
                ssl.soTimeout = 0 // дальше без таймаута: долгоживущий туннель
                onStatus(
                    if (attempt == 1) "TLS: $tunnelHost:$tunnelPort подключён"
                    else "TLS: подключён (попытка $attempt)"
                )
                return ssl
            } catch (e: Exception) {
                last = e
                if (attempt < 3) {
                    onStatus("TLS: не удалось (попытка $attempt), повтор через ${delay / 1000} с")
                    try { sleep(delay) } catch (_: InterruptedException) {}
                    delay *= 2
                }
            }
        }
        throw last ?: IOException("Не удалось подключиться")
    }

    /** SOCKS5-рукопожатие с 3proxy: авторизация + CONNECT на цель. */
    private fun socks5HandshakeRemote(ssl: Socket, atyp: Int, addr: ByteArray, port: Int) {
        val inp = DataInputStream(BufferedInputStream(ssl.getInputStream()))
        val out = BufferedOutputStream(ssl.getOutputStream())

        out.write(byteArrayOf(0x05, 0x01, 0x02))
        out.flush()
        if (inp.readUnsignedByte() != 5) throw IOException("сервер ответил не SOCKS5")
        val method = inp.readUnsignedByte()
        if (method == 0x02) {
            val ub = tunnelUser.toByteArray(Charsets.UTF_8)
            val pb = tunnelPass.toByteArray(Charsets.UTF_8)
            if (ub.size > 255 || pb.size > 255) throw IOException("слишком длинный логин/пароль")
            val auth = ByteArrayOutputStream()
            auth.write(0x01); auth.write(ub.size); auth.write(ub)
            auth.write(pb.size); auth.write(pb)
            out.write(auth.toByteArray()); out.flush()
            if (inp.readUnsignedByte() != 0x01) throw IOException("сервер: некорректный ответ авторизации")
            if (inp.readUnsignedByte() != 0x00) throw IOException("сервер отклонил логин/пароль")
        } else if (method != 0x00) {
            throw IOException("сервер выбрал неподдерживаемый метод ($method)")
        }

        val req = ByteArrayOutputStream()
        req.write(0x05); req.write(0x01); req.write(0x00); req.write(atyp)
        if (atyp == 0x03) req.write(addr.size)
        req.write(addr)
        req.write((port shr 8) and 0xFF)
        req.write(port and 0xFF)
        out.write(req.toByteArray()); out.flush()

        if (inp.readUnsignedByte() != 5) throw IOException("сервер: некорректный ответ CONNECT")
        val rep = inp.readUnsignedByte()
        inp.readUnsignedByte() // reserved
        val ratyp = inp.readUnsignedByte()
        val blen = when (ratyp) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> inp.readUnsignedByte()
            else -> 0
        }
        if (blen > 0) { val b = ByteArray(blen); inp.readFully(b) }
        inp.readUnsignedByte(); inp.readUnsignedByte() // bound port
        if (rep != 0) throw IOException("сервер отказал в CONNECT (код $rep)")
    }

    /** Двусторонняя пересылка: когда одно направление обрывается, закрываем оба сокета. */
    private fun pipe(a: Socket, b: Socket) {
        val t = Thread({ copyOneWay(a, b) }, "pipe-1")
        t.start()
        copyOneWay(b, a)
        try { t.join(2000) } catch (_: InterruptedException) {}
    }

    private fun copyOneWay(from: Socket, to: Socket) {
        val buf = ByteArray(16384)
        try {
            val inp = from.getInputStream()
            val out = to.getOutputStream()
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: IOException) {
        } finally {
            try { from.close() } catch (_: Exception) {}
            try { to.close() } catch (_: Exception) {}
        }
    }

    private fun addrString(atyp: Int, addr: ByteArray): String = when (atyp) {
        0x03 -> String(addr, Charsets.UTF_8)
        else -> addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }
}
