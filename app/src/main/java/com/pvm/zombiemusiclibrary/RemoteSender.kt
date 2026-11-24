package com.pvm.zombiemusiclibrary

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * "Enviar Música": streams what plays in this app to the PC program. The phone keeps the real playback going
 * (silently); the PC receives the song bytes in memory, plays them in step and follows play / pause / seek / next.
 */
class RemoteSender(private val svc: PlayerService) {

    enum class State { OFF, SEARCHING, CONNECTED }

    @Volatile var state = State.OFF
        private set
    @Volatile var pcName = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val ctrl = Executors.newSingleThreadExecutor()      // ordered: BEGIN of a song always goes before its STATE
    private val data = Executors.newSingleThreadExecutor()      // the file bytes
    @Volatile private var socket: Socket? = null
    @Volatile private var link: RemoteLink? = null
    @Volatile private var session = 0                           // changes with every connection; old jobs stop themselves
    private val idByTrack = IdentityHashMap<Track, Int>()
    private val announced = HashSet<Int>()
    private var nextId = 1
    @Volatile private var currentId = -1
    @Volatile private var wanted: Set<Int> = emptySet()

    val connected: Boolean get() = state == State.CONNECTED

    fun toggle() {
        if (state == State.OFF) start() else stop()
    }

    // ------------------------------------------------------------------ connect / disconnect

    private fun start() {
        state = State.SEARCHING
        val my = ++session
        svc.remoteChanged()
        Thread {
            try { connect(my) } catch (e: Exception) { fail(my, "No se pudo conectar con la PC: " + (e.message ?: "error")) }
        }.start()
    }

    private fun connect(my: Int) {
        var host: String? = null
        var port = RemoteProto.PORT
        val found = RemoteDiscovery.find(RemoteDiscovery.broadcastTargets(), 3000)
        if (found != null) { host = found.host; port = found.port }
        else {
            val manual = Repo.pcIp.trim()
            if (manual.isNotEmpty()) host = manual
        }
        if (host == null) {
            fail(my, "No encontré ninguna PC con \"Recibir Música\" activo en esta red. Revisá que la PC lo tenga encendido y que estén en el mismo Wi-Fi.")
            return
        }
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 4000)
        s.tcpNoDelay = true
        s.soTimeout = 5000
        val l = RemoteLink(s.getOutputStream())
        l.hello(Build.MODEL ?: "Teléfono")
        val reply = RemoteIo.read(s.getInputStream())
        if (reply == null) { s.close(); fail(my, "La PC no respondió."); return }
        if (reply.type == RemoteProto.REJECT) { s.close(); fail(my, RemoteIo.textAt(reply.payload, 0).ifEmpty { "La PC no aceptó la conexión." }); return }
        if (reply.type != RemoteProto.HELLO_OK) { s.close(); fail(my, "Respuesta inesperada de la PC."); return }
        s.soTimeout = 0
        synchronized(this) {
            if (my != session) { try { s.close() } catch (e: Exception) { }; return }
            socket = s; link = l
            pcName = RemoteIo.textAt(reply.payload, 4)
            state = State.CONNECTED
        }
        main.post { svc.onRemoteConnected() }
        Thread {                                                 // notices when the PC goes away
            try { while (RemoteIo.read(s.getInputStream()) != null) { } } catch (e: Exception) { }
            lost(my)
        }.start()
    }

    private fun fail(my: Int, message: String) {
        if (my != session) return
        state = State.OFF
        svc.remoteMessage(message)
        svc.remoteChanged()
    }

    private fun lost(my: Int) {
        if (my != session || state != State.CONNECTED) return
        reset()
        svc.remoteMessage("Se perdió la conexión con la PC.")
        main.post { svc.onRemoteDisconnected() }
    }

    fun stop() {
        val l = link
        val s = socket
        reset()
        ctrl.execute {
            try { l?.bye() } catch (e: Exception) { }
            try { s?.close() } catch (e: Exception) { }
        }
        svc.remoteMessage("Dejé de enviar música a la PC.")
        main.post { svc.onRemoteDisconnected() }
    }

    private fun reset() {
        val s = socket
        session++
        state = State.OFF
        link = null
        socket = null
        main.post {
            idByTrack.clear(); announced.clear(); currentId = -1; wanted = emptySet()
        }
        // closing the socket also ends the reader thread
        try { s?.close() } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ what the player tells us

    private fun idFor(t: Track): Int {
        val have = idByTrack[t]
        if (have != null) return have
        val id = nextId++
        idByTrack[t] = id
        return id
    }

    /** A new song started in the app. */
    fun onTrackStart(t: Track) {
        if (!connected) return
        val id = idFor(t)
        currentId = id
        wanted = setOf(id)
        announce(t, id)
    }

    private fun announce(t: Track, id: Int) {
        if (!announced.add(id)) return
        val my = session
        ctrl.execute {
            if (my != session) return@execute
            val l = link ?: return@execute
            try {
                val size = sizeOf(t)
                if (size <= 0) { main.post { announced.remove(id); idByTrack.remove(t) }; return@execute }
                l.trackBegin(id, size, t.durationMs, t.title, t.artist, t.album, t.year, coverBytes(t))
                data.execute { stream(my, t, id) }
            } catch (e: Exception) { lost(my) }
        }
    }

    private fun sizeOf(t: Track): Long {
        if (t.size > 0) return t.size
        return try { svc.contentResolver.openAssetFileDescriptor(Repo.uriOf(t), "r")?.use { it.length } ?: 0L } catch (e: Exception) { 0L }
    }

    /** Small JPEG of the cover (or null), so the PC can show it. */
    private fun coverBytes(t: Track): ByteArray? {
        return try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(svc, Repo.uriOf(t))
                val raw = r.embeddedPicture ?: return null
                val bmp = ThumbLoader.decode(raw, 256) ?: return null
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                val b = out.toByteArray()
                if (b.size > 400_000) null else b
            } finally { r.release() }
        } catch (e: Throwable) { null }
    }

    /** Sends the MP3 in order, chunk by chunk (the PC starts playing before it has all of it). */
    private fun stream(my: Int, t: Track, id: Int) {
        try {
            svc.contentResolver.openInputStream(Repo.uriOf(t))?.use { ins ->
                val buf = ByteArray(32768)
                var finished = false
                while (my == session && wanted.contains(id)) {
                    val n = ins.read(buf)
                    if (n < 0) { finished = true; break }
                    val l = link ?: return
                    l.trackData(id, buf, 0, n)
                }
                if (!finished && my == session) main.post { announced.remove(id); idByTrack.remove(t) }   // cancelled halfway: send it again from zero next time
            }
        } catch (e: Exception) { lost(my) }
    }

    /** Tells the PC what the phone is doing now: song, playing or paused, position. */
    fun pushState() {
        if (!connected) return
        val id = if (svc.hasMedia) currentId else -1
        val playing = svc.playing
        val pos = svc.position.toLong()
        val my = session
        ctrl.execute {
            if (my != session) return@execute
            try { link?.state(id, playing, pos) } catch (e: Exception) { lost(my) }
        }
    }

    /** Every second while connected. */
    fun tick() {
        if (!connected) return
        if (svc.playing) pushState()
        // near the end of the song, start sending the next one so there is no gap
        val d = svc.duration
        if (svc.playing && d > 0 && d - svc.position < 25000) {
            val next = svc.queue.getOrNull(svc.index + 1)
            if (next != null && next.id >= 0) {
                val id = idFor(next)
                if (!announced.contains(id)) {
                    wanted = setOf(currentId, id)
                    announce(next, id)
                }
            }
        }
    }
}
