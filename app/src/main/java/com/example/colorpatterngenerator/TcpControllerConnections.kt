package com.example.colorpatterngenerator

import java.io.Closeable
import java.net.Socket
import java.net.SocketAddress

/** Independent control sessions, identified by the controller's IP AND TCP port. */
internal class TcpControllerConnections : Closeable {
    private val sockets = mutableMapOf<SocketAddress, Socket>()
    private var closed = false

    val size: Int
        @Synchronized get() = sockets.size

    @Synchronized
    fun isEmpty(): Boolean = sockets.isEmpty()

    /** Publish the replacement before closing the old socket, avoiding a false last disconnect. */
    @Synchronized
    fun add(socket: Socket): Socket? {
        check(!closed) { "Controller connections are closed" }
        require(socket.isConnected && !socket.isClosed) { "Controller socket must be connected" }
        return sockets.put(socket.remoteSocketAddress, socket)
    }

    @Synchronized
    fun remove(socket: Socket): Boolean {
        val endpoint = socket.remoteSocketAddress
        // A replaced connection may finish later; it must not remove its successor.
        if (sockets[endpoint] !== socket) return false
        sockets.remove(endpoint)
        return true
    }

    override fun close() {
        val remaining = synchronized(this) {
            closed = true
            sockets.values.toList().also { sockets.clear() }
        }
        remaining.forEach { socket ->
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
