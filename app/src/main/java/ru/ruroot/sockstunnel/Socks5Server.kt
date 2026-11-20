package ru.ruroot.sockstunnel

import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class Socks5Server(
    private val listenPort: Int,
    private val serverHost: String,
    private val serverPort: Int,
    private val proxyUser: String?,
    private val proxyPass: String?,
    private val localAuthRequired: Boolean
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort))
            serverSocket = ss
            acceptThread = Thread { acceptLoop() }.apply {
                name = "socks5-accept"
                start()
            }
            Log.i(TAG, "SOCKS5 listening on 127.0.0.1:$listenPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        for (s in clients.toList()) closeQuietly(s)
        clients.clear()
        val ss = serverSocket
        serverSocket = null
        closeQuietly(ss)
        acceptThread = null
    }

    private fun acceptLoop() {
        while (running.get()) {
            val ss = serverSocket ?: break
            val client: Socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept: ${e.message}")
                break
            }
            pool.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        clients.add(client)
        var remote: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30000
            val dis = DataInputStream(client.getInputStream())
            val dos = DataOutputStream(client.getOutputStream())

            if (!handshakeLocal(dis, dos)) {
                closeQuietly(client)
                return
            }

            val target = readClientRequest(dis)
            if (target == null) {
                writeReply(dos, REPLY_GENERAL)
                closeQuietly(client)
                return
            }

            remote = connectWithRetry(target)
            if (remote == null) {
                writeReply(dos, REPLY_FAILURE)
                closeQuietly(client)
                return
            }

            client.soTimeout = 0
            remote.tcpNoDelay = true
            remote.soTimeout = 0
            writeReply(dos, REPLY_OK)
            pump(client, remote)
        } catch (e: Exception) {
            Log.d(TAG, "client: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            clients.remove(client)
            closeQuietly(remote)
            closeQuietly(client)
        }
    }

    private fun handshakeLocal(dis: DataInputStream, dos: DataOutputStream): Boolean {
        if (dis.readUnsignedByte() != 5) return false
        val nmethods = dis.readUnsignedByte()
        if (nmethods < 1) return false
        val methods = ByteArray(nmethods)
        dis.readFully(methods)
        val supportsAuth = methods.contains(0x02.toByte())
        val supportsNoAuth = methods.contains(0x00.toByte())
        val chosen: Int = if (localAuthRequired) {
            if (!supportsAuth) return false
            0x02
        } else {
            if (!supportsNoAuth) return false
            0x00
        }
        dos.write(byteArrayOf(5, chosen.toByte()))
        dos.flush()
        if (chosen == 0x02) {
            if (dis.readUnsignedByte() != 1) return false
            val ulen = dis.readUnsignedByte()
            val user = ByteArray(ulen)
            dis.readFully(user)
            val plen = dis.readUnsignedByte()
            val pass = ByteArray(plen)
            dis.readFully(pass)
            val ok = String(user, Charsets.UTF_8) == (proxyUser ?: "") &&
                    String(pass, Charsets.UTF_8) == (proxyPass ?: "")
            dos.write(byteArrayOf(1, if (ok) 0 else 1))
            dos.flush()
            if (!ok) return false
        }
        return true
    }

    private fun readClientRequest(dis: DataInputStream): InetSocketAddress? {
        if (dis.readUnsignedByte() != 5) return null
        if (dis.readUnsignedByte() != 1) return null // поддерживаем только CONNECT
        dis.readUnsignedByte() // RSV
        val atyp = dis.readUnsignedByte()
        val host: String = when (atyp) {
            1 -> {
                val b = ByteArray(4)
                dis.readFully(b)
                InetAddress.getByAddress(b).hostAddress ?: return null
            }
            3 -> {
                val len = dis.readUnsignedByte()
                val b = ByteArray(len)
                dis.readFully(b)
                String(b, Charsets.ISO_8859_1)
            }
            4 -> {
                val b = ByteArray(16)
                dis.readFully(b)
                InetAddress.getByAddress(b).hostAddress ?: return null
            }
            else -> return null
        }
        val port = dis.readUnsignedShort()
        return InetSocketAddress.createUnresolved(host, port)
    }

    private fun writeReply(dos: DataOutputStream, code: Int) {
        dos.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        dos.flush()
    }

    private fun connectWithRetry(target: InetSocketAddress): Socket? {
        var delayMs = 1000L
        var attempt = 1
        while (attempt <= RETRY_ATTEMPTS) {
            if (!running.get()) return null
            try {
                return openTunnel(target)
            } catch (e: Exception) {
                Log.w(TAG, "attempt $attempt: ${e.message}")
                if (attempt == RETRY_ATTEMPTS) return null
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return null
                }
                delayMs *= 2
                attempt++
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun openTunnel(target: InetSocketAddress): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket: SSLSocket = factory.createSocket() as? SSLSocket
            ?: throw IOException("cannot create TLS socket")
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(serverHost, serverPort), CONNECT_TIMEOUT_MS)
        socket.soTimeout = CONNECT_TIMEOUT_MS
        socket.startHandshake()

        val dis = DataInputStream(socket.getInputStream())
        val dos = DataOutputStream(socket.getOutputStream())

        dos.write(byteArrayOf(5, 2))
        dos.flush()
        if (dis.readUnsignedByte() != 5) throw IOException("bad server version")
        if (dis.readUnsignedByte() != 2) throw IOException("server refused auth")

        val user = proxyUser?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val pass = proxyPass?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        if (user.size > 255 || pass.size > 255) throw IOException("credentials too long")
        dos.writeByte(1)
        dos.writeByte(user.size)
        dos.write(user)
        dos.writeByte(pass.size)
        dos.write(pass)
        dos.flush()
        if (dis.readUnsignedByte() != 1) throw IOException("bad auth response")
        if (dis.readUnsignedByte() != 0) throw IOException("auth rejected")

        val hostBytes: ByteArray
        val atyp: Int
        val addr: InetAddress? = try {
            InetAddress.getByName(target.hostString)
        } catch (_: UnknownHostException) {
            null
        }
        when {
            addr is Inet4Address -> { atyp = 1; hostBytes = addr.address }
            addr is Inet6Address -> { atyp = 4; hostBytes = addr.address }
            else -> { atyp = 3; hostBytes = target.hostString.toByteArray(Charsets.ISO_8859_1) }
        }
        dos.writeByte(5)
        dos.writeByte(1)
        dos.writeByte(0)
        dos.writeByte(atyp)
        if (atyp == 3) {
            dos.writeByte(hostBytes.size)
        }
        dos.write(hostBytes)
        dos.writeShort(target.port)
        dos.flush()

        if (dis.readUnsignedByte() != 5) throw IOException("bad server reply")
        val rep = dis.readUnsignedByte()
        if (rep != 0) throw IOException("server error code $rep")
        dis.readUnsignedByte() // RSV
        val batyp = dis.readUnsignedByte()
        when (batyp) {
            1 -> { val b = ByteArray(4); dis.readFully(b) }
            4 -> { val b = ByteArray(16); dis.readFully(b) }
            3 -> {
                val n = dis.readUnsignedByte()
                val b = ByteArray(n)
                dis.readFully(b)
            }
        }
        dis.readUnsignedShort() // bnd port
        return socket
    }

    private fun pump(a: Socket, b: Socket) {
        val t1 = Thread { copy(a, b) }.apply { name = "pump-ab" }
        val t2 = Thread { copy(b, a) }.apply { name = "pump-ba" }
        t1.start()
        t2.start()
        try {
            t1.join()
        } catch (_: InterruptedException) {
        }
        closeQuietly(a)
        closeQuietly(b)
        try {
            t2.join(3000)
        } catch (_: InterruptedException) {
        }
    }

    private fun copy(from: Socket, to: Socket) {
        val buf = ByteArray(16384)
        try {
            val src = from.getInputStream()
            val dst = to.getOutputStream()
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {
        } finally {
            closeQuietly(from)
            closeQuietly(to)
        }
    }

    private fun closeQuietly(c: Closeable?) {
        if (c == null) return
        try {
            c.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "Socks5Server"
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val RETRY_ATTEMPTS = 3
        private const val REPLY_OK = 0x00
        private const val REPLY_GENERAL = 0x01
        private const val REPLY_FAILURE = 0x01
    }
}
