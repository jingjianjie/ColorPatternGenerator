package com.example.colorpatterngenerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TcpControllerConnectionsTest {
    private class Controller(host: String = "127.0.0.1", port: Int = 0) : Closeable {
        val server = ServerSocket(port, 16, InetAddress.getByName(host)).apply { soTimeout = 2_000 }
        private val sessions = mutableListOf<Session>()

        fun connect(): Session {
            val app = Socket().apply {
                soTimeout = 2_000
                connect(InetSocketAddress(server.inetAddress, server.localPort), 2_000)
            }
            try {
                val controller = server.accept().apply { soTimeout = 2_000 }
                return Session(app, controller).also { sessions += it }
            } catch (e: Exception) {
                app.close()
                throw e
            }
        }

        override fun close() {
            sessions.forEach { it.app.close(); it.controller.close() }
            server.close()
        }
    }

    private class Session(val app: Socket, val controller: Socket) {
        fun assertRoundTrip(id: Int) {
            controller.getOutputStream().write("get_status\n".toByteArray())
            assertEquals("get_status", app.getInputStream().bufferedReader().readLine())
            app.getOutputStream().write("status:$id\n".toByteArray())
            assertEquals("status:$id", controller.getInputStream().bufferedReader().readLine())
        }
    }

    @Test
    fun threeHostsCanUseTheSamePortAndDisconnectIndependently() {
        TcpControllerConnections().use { connections ->
            Controller().use { first ->
                Controller("127.0.0.2", first.server.localPort).use { second ->
                    Controller("127.0.0.3", first.server.localPort).use { third ->
                        val sessions = listOf(first.connect(), second.connect(), third.connect())
                        sessions.forEach { assertNull(connections.add(it.app)) }
                        assertEquals(3, connections.size)
                        sessions.forEachIndexed { id, session -> session.assertRoundTrip(id) }

                        sessions[1].controller.close()
                        assertEquals(-1, sessions[1].app.getInputStream().read())
                        sessions[1].app.close()
                        assertTrue(connections.remove(sessions[1].app))
                        assertEquals(2, connections.size)
                        sessions[0].assertRoundTrip(0)
                        sessions[2].assertRoundTrip(2)
                    }
                }
            }
        }
    }

    @Test
    fun controllersOnOneHostCanUseDifferentPorts() {
        TcpControllerConnections().use { connections ->
            Controller().use { first ->
                Controller().use { second ->
                    Controller().use { third ->
                        val sessions = listOf(first.connect(), second.connect(), third.connect())
                        sessions.forEach { assertNull(connections.add(it.app)) }
                        assertEquals(3, connections.size)
                        sessions.forEachIndexed { id, session -> session.assertRoundTrip(id) }
                    }
                }
            }
        }
    }

    @Test
    fun reconnectReplacesOnlyTheMatchingEndpointAndLateCleanupKeepsItsSuccessor() {
        TcpControllerConnections().use { connections ->
            Controller().use { first ->
                Controller().use { second ->
                    val original = first.connect()
                    val other = second.connect()
                    connections.add(original.app)
                    connections.add(other.app)
                    val replacement = first.connect()

                    assertSame(original.app, connections.add(replacement.app))
                    original.app.close()
                    assertFalse(connections.remove(original.app))
                    assertEquals(2, connections.size)
                    assertFalse(connections.isEmpty())
                    replacement.assertRoundTrip(1)
                    other.assertRoundTrip(2)

                    assertTrue(connections.remove(replacement.app))
                    assertTrue(connections.remove(other.app))
                    assertTrue(connections.isEmpty())
                }
            }
        }
    }

    @Test
    fun simultaneousReconnectsLeaveOneLiveSession() {
        TcpControllerConnections().use { connections ->
            Controller().use { controller ->
                val sessions = List(8) { controller.connect() }
                val executor = Executors.newFixedThreadPool(sessions.size)
                val start = CountDownLatch(1)
                try {
                    val tasks = sessions.map { session ->
                        executor.submit {
                            check(start.await(2, TimeUnit.SECONDS))
                            connections.add(session.app)?.let { previous ->
                                previous.close()
                                assertFalse(connections.remove(previous))
                            }
                        }
                    }
                    start.countDown()
                    tasks.forEach { it.get(5, TimeUnit.SECONDS) }
                    assertEquals(1, connections.size)
                    sessions.single { !it.app.isClosed }.assertRoundTrip(1)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun closingConnectionsClosesAllSocketsAndRejectsLateConnections() {
        val connections = TcpControllerConnections()
        Controller().use { first ->
            Controller().use { second ->
                val firstSession = first.connect()
                val secondSession = second.connect()
                connections.add(firstSession.app)
                connections.add(secondSession.app)
                connections.close()
                connections.close()

                assertTrue(firstSession.app.isClosed)
                assertTrue(secondSession.app.isClosed)
                assertTrue(connections.isEmpty())
                assertFalse(connections.remove(firstSession.app))
                try {
                    connections.add(first.connect().app)
                    fail("Closed connection registry accepted a late connection")
                } catch (_: IllegalStateException) {
                    // MainActivity's finally block closes any connection finishing after destruction.
                }
            }
        }
    }
}
