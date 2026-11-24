package com.vrhands.bridge

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Sends the head pose to relay.py on the PC (UDP).
 * Packet (40 bytes, little endian): "VRH1", int seq, 7 floats (x y z qx qy qz qw), int tracking(1/0)
 * Runs on its own thread, and drops old packets if the network is slow (never blocks the AR loop).
 */
class PoseSender(private val host: String, private val port: Int) {
    private val socket = DatagramSocket()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8), ThreadPoolExecutor.DiscardOldestPolicy()
    )
    private var address: InetAddress? = null
    private var seq = 0
    private val buf = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)

    fun send(x: Float, y: Float, z: Float, qx: Float, qy: Float, qz: Float, qw: Float, tracking: Boolean) {
        executor.execute {
            try {
                if (address == null) address = InetAddress.getByName(host)
                buf.clear()
                buf.put('V'.code.toByte()).put('R'.code.toByte()).put('H'.code.toByte()).put('1'.code.toByte())
                buf.putInt(seq++)
                buf.putFloat(x).putFloat(y).putFloat(z)
                buf.putFloat(qx).putFloat(qy).putFloat(qz).putFloat(qw)
                buf.putInt(if (tracking) 1 else 0)
                socket.send(DatagramPacket(buf.array(), 40, address, port))
            } catch (_: Exception) {
                // PC not reachable yet: ignore, the next packet tries again
            }
        }
    }

    fun close() {
        executor.shutdownNow()
        socket.close()
    }
}
