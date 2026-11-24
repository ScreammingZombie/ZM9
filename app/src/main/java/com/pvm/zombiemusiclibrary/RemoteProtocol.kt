package com.pvm.zombiemusiclibrary

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Talks to the PC program ("Recibir Música"). Same protocol as the PC side:
 * every message is [type:1][length:4 little endian][payload].
 */
object RemoteProto {
    const val VERSION = 1
    const val PORT = 48500
    const val HELLO = 1
    const val TRACK_BEGIN = 2
    const val TRACK_DATA = 3
    const val STATE = 4
    const val BYE = 5
    const val HELLO_OK = 10
    const val REJECT = 12
    const val DISCOVER = "ZML-DISCOVER1"
    const val HERE = "ZML-HERE1"
}

private class Payload {
    val out = ByteArrayOutputStream()

    fun int(v: Int): Payload {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        return this
    }

    fun long(v: Long): Payload {
        for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xFF).toInt())
        return this
    }

    fun byte(v: Int): Payload { out.write(v and 0xFF); return this }

    fun string(s: String): Payload {
        val b = s.toByteArray(Charsets.UTF_8)
        int(b.size)
        out.write(b)
        return this
    }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size): Payload { out.write(b, off, len); return this }
    fun toBytes(): ByteArray = out.toByteArray()
}

/** Writes messages to the PC. Safe to use from several threads (each message is written whole). */
class RemoteLink(private val out: OutputStream) {
    private val lock = Any()

    private fun send(type: Int, payload: ByteArray) {
        synchronized(lock) {
            val n = payload.size
            out.write(type)
            out.write(n and 0xFF); out.write((n shr 8) and 0xFF); out.write((n shr 16) and 0xFF); out.write((n shr 24) and 0xFF)
            out.write(payload)
            out.flush()
        }
    }

    fun hello(deviceName: String) = send(RemoteProto.HELLO, Payload().int(RemoteProto.VERSION).string(deviceName).toBytes())

    fun trackBegin(id: Int, size: Long, durationMs: Long, title: String, artist: String, album: String, year: String, cover: ByteArray?) {
        val p = Payload().int(id).long(size).long(durationMs).string(title).string(artist).string(album).string(year)
        if (cover != null && cover.isNotEmpty()) p.int(cover.size).bytes(cover) else p.int(0)
        send(RemoteProto.TRACK_BEGIN, p.toBytes())
    }

    fun trackData(id: Int, buf: ByteArray, off: Int, len: Int) =
        send(RemoteProto.TRACK_DATA, Payload().int(id).bytes(buf, off, len).toBytes())

    /** id = -1 means nothing is playing */
    fun state(id: Int, playing: Boolean, positionMs: Long) =
        send(RemoteProto.STATE, Payload().int(id).byte(if (playing) 1 else 0).long(positionMs).toBytes())

    fun bye() = send(RemoteProto.BYE, ByteArray(0))
}

object RemoteIo {
    class Message(val type: Int, val payload: ByteArray)

    /** Reads one message (blocks); null when the connection is closed. */
    fun read(input: InputStream): Message? {
        val din = DataInputStream(input)
        val type = input.read()
        if (type < 0) return null
        val b = ByteArray(4)
        din.readFully(b)
        val n = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
        if (n < 0 || n > 1_000_000) return null
        val payload = ByteArray(n)
        din.readFully(payload)
        return Message(type, payload)
    }

    /** A text field inside a payload: int length + UTF-8 bytes, starting at [pos] (skipping [skip] bytes first). */
    fun textAt(payload: ByteArray, pos: Int): String {
        if (payload.size < pos + 4) return ""
        val n = (payload[pos].toInt() and 0xFF) or ((payload[pos + 1].toInt() and 0xFF) shl 8) or
            ((payload[pos + 2].toInt() and 0xFF) shl 16) or ((payload[pos + 3].toInt() and 0xFF) shl 24)
        if (n < 0 || pos + 4 + n > payload.size) return ""
        return String(payload, pos + 4, n, Charsets.UTF_8)
    }
}

object RemoteDiscovery {
    class Found(val host: String, val name: String, val port: Int)

    /** Every address a "who is there?" broadcast should go to (all the local networks). */
    fun broadcastTargets(): List<InetAddress> {
        val list = ArrayList<InetAddress>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs != null && ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { if (!list.contains(it)) list.add(it) }
            }
        } catch (e: Exception) { }
        try { list.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) { }
        return list
    }

    /** Asks the networks and waits for the first PC that answers. */
    fun find(targets: List<InetAddress>, timeoutMs: Int, port: Int = RemoteProto.PORT): Found? {
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.soTimeout = 400
            val msg = RemoteProto.DISCOVER.toByteArray(Charsets.US_ASCII)
            val end = System.currentTimeMillis() + timeoutMs
            var lastSend = 0L
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() - lastSend > 700) {          // ask again every so often, packets can get lost
                    for (t in targets) try { socket.send(DatagramPacket(msg, msg.size, t, port)) } catch (e: Exception) { }
                    lastSend = System.currentTimeMillis()
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val text = String(p.data, 0, p.length, Charsets.UTF_8)
                    val parts = text.split('|')
                    if (parts.size >= 3 && parts[0] == RemoteProto.HERE) {
                        return Found(p.address.hostAddress ?: continue, parts[1], parts[2].toIntOrNull() ?: port)
                    }
                } catch (e: SocketTimeoutException) { }
            }
        } finally { socket.close() }
        return null
    }
}
