package com.example.engine

import android.os.Looper
import com.example.core.matrix.NetMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class NetListenerRevocationTest {
    private fun port() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    private fun connect(port: Int): Socket {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            runCatching { Socket(InetAddress.getLoopbackAddress(), port) }.getOrNull()?.let { return it }
            Thread.sleep(10)
        }
        error("Test listener did not start")
    }
    private fun awaitQueued() {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            if (!shadowOf(Looper.getMainLooper()).isIdle) return
            Thread.sleep(10)
        }
        error("Test command was not queued")
    }
    private fun send(socket: Socket) { socket.getOutputStream().write("test-token-123 do volume direction=up\n".toByteArray()); socket.getOutputStream().flush() }

    @Test fun stopClosesAcceptedConnectionsAndDropsAlreadyQueuedCommand() {
        val received = mutableListOf<NetMessage>()
        val listener = NetListener({ received += it }, {})
        val port = port()
        try {
            listener.start(port, "test-token-123", true)
            connect(port).use { socket ->
                send(socket)
                awaitQueued()
                listener.stop()
                socket.soTimeout = 2000
                assertEquals(-1, socket.getInputStream().read())
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("Revoked command must not reach the engine", received.isEmpty())
            }
        } finally { listener.stop(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun restoringIdenticalSettingsCannotResurrectQueuedCommandsFromOldSession() {
        val received = mutableListOf<NetMessage>()
        val listener = NetListener({ received += it }, {})
        val port = port()
        try {
            listener.start(port, "test-token-123", true)
            connect(port).use { socket ->
                send(socket)
                awaitQueued()
                listener.stop()
                listener.start(port, "test-token-123", true)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("Session identity must differ despite equal configuration", received.isEmpty())
            }
        } finally { listener.stop(); shadowOf(Looper.getMainLooper()).idle() }
    }
}
