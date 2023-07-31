package studio.attect.demo.vpnservice

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.net.VpnService
import android.os.Build
import android.os.Message
import android.os.MessageQueue
import android.service.carrier.MessagePdu
import android.util.Base64
import android.util.Log
import studio.attect.demo.vpnservice.protocol.IpUtil
import studio.attect.demo.vpnservice.protocol.Packet
import studio.attect.demo.vpnservice.protocol.Packet.TCPHeader
import studio.attect.demo.vpnservice.protocol.TCBStatus
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.text.MessageFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.experimental.and
import kotlin.experimental.or

/**
 * UDP packet queue of devices sending network
 */
val deviceToNetworkUDPQueue = ArrayBlockingQueue<Packet>(1024)

/**
 * TCP packet queue of the device sending network
 */
val deviceToNetworkTCPQueue = ArrayBlockingQueue<Packet>(1024)

/**
 * network response device packet queue
 */
val networkToDeviceQueue = ArrayBlockingQueue<ByteBuffer>(1024)

/**
 * TCP Forwarding Network Selector
 */
val tcpNioSelector: Selector = Selector.open()

/**
 * UDP forwarding channel queue
 */
val udpTunnelQueue = ArrayBlockingQueue<UdpTunnel>(1024)

/**
 * UDP forwarding network selector
 */
val udpNioSelector: Selector = Selector.open()

/**
 * Exists udp socket Map<br>
 * key for target host address: target port: request port
 */
val udpSocketMap = HashMap<String, ManagedDatagramChannel>()

const val UDP_SOCKET_IDLE_TIMEOUT = 60


/**
 * processing device network packet processing thread
 */
object ToNetworkQueueWorker : Runnable {
    private const val TAG = "ToNetworkQueueWorker"

    /**
     * own thread
     */
    private lateinit var thread: Thread

    /**
     * read data channel from device
     */
    private lateinit var vpnInput: FileChannel

    /**
     * overall read data byte count
     */
    var totalInputCount = 0L


    fun start(vpnFileDescriptor: FileDescriptor) {
        if (this::thread.isInitialized && thread.isAlive) throw IllegalStateException("已经在运行")
        vpnInput = FileInputStream(vpnFileDescriptor).channel
        thread = Thread(this).apply {
            name = TAG
            start()
        }
    }

    fun stop() {
        if (this::thread.isInitialized) {
            thread.interrupt()
        }
    }

    override fun run() {
        val readBuffer = ByteBuffer.allocate(16384)
        while (!thread.isInterrupted) {
            var readCount = 0
            try {
                readCount = vpnInput.read(readBuffer)
            } catch (e: IOException) {
                e.printStackTrace()
                continue
            }
            if (readCount > 0) {
                readBuffer.flip()
                val byteArray = ByteArray(readCount)
                readBuffer.get(byteArray)

                val byteBuffer = ByteBuffer.wrap(byteArray)
                totalInputCount += readCount

                val packet = Packet(byteBuffer)
                if (packet.isUDP) {
                    deviceToNetworkUDPQueue.offer(packet)
                } else if (packet.isTCP) {
                    deviceToNetworkTCPQueue.offer(packet)
                } else {
                    Log.d(TAG, "未知的数据包协议类型${packet.ip4Header.protocolNum}")
                }
            } else if (readCount < 0) {
                break
            }
            readBuffer.clear()
        }
        Log.i(TAG, "ToNetworkQueueWorker运行结束")
    }

    fun checkWebUrl(packet: Packet) {
        val pktBuffer = packet.backingBuffer
        pktBuffer.mark()
        val tmpBytes = byteArrayOf()
        pktBuffer.get(tmpBytes)
        pktBuffer.reset()
    }
}

/**
 * processing network communication device packet processing thread
 */
object ToDeviceQueueWorker : Runnable {
    private const val TAG = "ToDeviceQueueWorker"

    /**
     * own thread
     */
    private lateinit var thread: Thread

    /**
     * overall write data byte count
     */
    var totalOutputCount = 0L


    /**
     * write from network data channel
     */
    private lateinit var vpnOutput: FileChannel

    fun start(vpnFileDescriptor: FileDescriptor) {
        if (this::thread.isInitialized && thread.isAlive) throw IllegalStateException("已经在运行")
        vpnOutput = FileOutputStream(vpnFileDescriptor).channel
        thread = Thread(this).apply {
            name = TAG
            start()
        }
    }

    fun stop() {
        if (this::thread.isInitialized) {
            thread.interrupt()
        }
    }

    override fun run() {
        try {
            while (!thread.isInterrupted) {
                val byteBuffer = networkToDeviceQueue.take()
                byteBuffer.flip()
                while (byteBuffer.hasRemaining()) {
                    val count = vpnOutput.write(byteBuffer)
                    if (count > 0) {
                        totalOutputCount += count
                    }
                }
            }
        } catch (_: InterruptedException) {

        } catch (_: ClosedByInterruptException) {

        }

    }
}

/**
 * UDP data transfer channel
 */
data class UdpTunnel(
    val id: String,
    val local: InetSocketAddress,
    val remote: InetSocketAddress,
    val channel: DatagramChannel
)

data class ManagedDatagramChannel(
    val id: String,
    val channel: DatagramChannel,
    var lastTime: Long = System.currentTimeMillis()
)

/**
 * UDP Packet sending worker thread
 */
@SuppressLint("StaticFieldLeak")
object UdpSendWorker : Runnable {
    private const val TAG = "UdpSendWorker"

    /**
     * own thread
     */
    private lateinit var thread: Thread

    private var vpnService: VpnService? = null

    fun start(vpnService: VpnService) {
        this.vpnService = vpnService
        udpTunnelQueue.clear()
        thread = Thread(this).apply {
            name = TAG
            start()
        }
    }

    fun stop() {
        if (this::thread.isInitialized) {
            thread.interrupt()
        }
        vpnService = null
    }

    override fun run() {
        while (!thread.isInterrupted) {
            val packet = deviceToNetworkUDPQueue.take()

            val destinationAddress = packet.ip4Header.destinationAddress
            val udpHeader = packet.udpHeader

            val destinationPort = udpHeader.destinationPort
            val sourcePort = udpHeader.sourcePort
            val ipAndPort = (destinationAddress.hostAddress?.plus(":")
                ?: "unknownHostAddress") + destinationPort + ":" + sourcePort
            Log.d("Destination", "${destinationAddress.hostAddress}")
            //create new socket
            val managedChannel = if (!udpSocketMap.containsKey(ipAndPort)) {
                val channel = DatagramChannel.open()
                var channelConnectSuccess = false
                channel.apply {
                    val socket = socket()
                    vpnService?.protect(socket)
                    try {
                        connect(InetSocketAddress(destinationAddress, destinationPort))
                        channelConnectSuccess = true
                    } catch (_: ConnectException) {
                    }
                    configureBlocking(false)
                }
                if (!channelConnectSuccess) {
                    continue
                }

                val tunnel = UdpTunnel(
                    ipAndPort,
                    InetSocketAddress(packet.ip4Header.sourceAddress, udpHeader.sourcePort),
                    InetSocketAddress(
                        packet.ip4Header.destinationAddress,
                        udpHeader.destinationPort
                    ),
                    channel
                )
                udpTunnelQueue.offer(tunnel)
                udpNioSelector.wakeup()

                val managedDatagramChannel = ManagedDatagramChannel(ipAndPort, channel)
                synchronized(udpSocketMap) {
                    udpSocketMap[ipAndPort] = managedDatagramChannel
                }
                managedDatagramChannel
            } else {
                synchronized(udpSocketMap) {
                    udpSocketMap[ipAndPort]
                        ?: throw IllegalStateException("udp:udpSocketMap[$ipAndPort]不应为null")
                }
            }
            managedChannel.lastTime = System.currentTimeMillis()
            val buffer = packet.backingBuffer
            kotlin.runCatching {
                while (!thread.isInterrupted && buffer.hasRemaining()) {
                    managedChannel.channel.write(buffer)
                }

            }.exceptionOrNull()?.let {
                Log.e(TAG, "发送udp数据包发生错误", it)
                managedChannel.channel.close()
                synchronized(udpSocketMap) {
                    udpSocketMap.remove(ipAndPort)
                }
            }
        }
    }
}

/**
 * UDP packet receiving thread
 */
@SuppressLint("StaticFieldLeak")
object UdpReceiveWorker : Runnable {

    private const val TAG = "UdpReceiveWorker"

    /**
     * own thread
     */
    private lateinit var thread: Thread

    private var vpnService: VpnService? = null

    private var ipId = AtomicInteger()

    private const val UDP_HEADER_FULL_SIZE = Packet.IP4_HEADER_SIZE + Packet.UDP_HEADER_SIZE

    fun start(vpnService: VpnService) {
        this.vpnService = vpnService
        thread = Thread(this).apply {
            name = TAG
            start()
        }
    }

    fun stop() {
        thread.interrupt()
    }

    private fun sendUdpPacket(tunnel: UdpTunnel, source: InetSocketAddress, data: ByteArray) {
        val packet = IpUtil.buildUdpPacket(tunnel.remote, tunnel.local, ipId.addAndGet(1))

        val byteBuffer = ByteBuffer.allocate(UDP_HEADER_FULL_SIZE + data.size)
        byteBuffer.apply {
            position(UDP_HEADER_FULL_SIZE)
            put(data)
        }
        packet.updateUDPBuffer(byteBuffer, data.size)
        byteBuffer.position(UDP_HEADER_FULL_SIZE + data.size)
        networkToDeviceQueue.offer(byteBuffer)
    }

    override fun run() {
        val receiveBuffer = ByteBuffer.allocate(16384)
        while (!thread.isInterrupted) {
            val readyChannels = udpNioSelector.select()
            while (!thread.isInterrupted) {
                val tunnel = udpTunnelQueue.poll() ?: break
                kotlin.runCatching {
                    val key = tunnel.channel.register(udpNioSelector, SelectionKey.OP_READ, tunnel)
                    key.interestOps(SelectionKey.OP_READ)
                }.exceptionOrNull()?.printStackTrace()
            }
            if (readyChannels == 0) {
                udpNioSelector.selectedKeys().clear()
                continue
            }
            val keys = udpNioSelector.selectedKeys()
            val iterator = keys.iterator()
            while (!thread.isInterrupted && iterator.hasNext()) {
                val key = iterator.next()
                iterator.remove()
                if (key.isValid && key.isReadable) {
                    val tunnel = key.attachment() as UdpTunnel
                    kotlin.runCatching {
                        val inputChannel = key.channel() as DatagramChannel
                        receiveBuffer.clear()
                        inputChannel.read(receiveBuffer)
                        receiveBuffer.flip()
                        val data = ByteArray(receiveBuffer.remaining())
                        receiveBuffer.get(data)
                        sendUdpPacket(
                            tunnel,
                            inputChannel.socket().localSocketAddress as InetSocketAddress,
                            data
                        ) //todo api 21->24
                    }.exceptionOrNull()?.let {
                        it.printStackTrace()
                        synchronized(udpSocketMap) {
                            udpSocketMap.remove(tunnel.id)
                        }
                    }
                }
            }
        }
    }

}

/**
 * Udp lost socket cleanup thread
 */
object UdpSocketCleanWorker : Runnable {

    private const val TAG = "UdpSocketCleanWorker"

    /**
     * own thread
     */
    private lateinit var thread: Thread

    /**
     * check interval, unit: second
     */
    private const val INTERVAL_TIME = 5L

    fun start() {
        thread = Thread(this).apply {
            name = TAG
            start()
        }
    }

    fun stop() {
        thread.interrupt()
    }

    override fun run() {
        while (!thread.isInterrupted) {
            synchronized(udpSocketMap) {
                val iterator = udpSocketMap.iterator()
                var removeCount = 0
                while (!thread.isInterrupted && iterator.hasNext()) {
                    val managedDatagramChannel = iterator.next()
                    if (System.currentTimeMillis() - managedDatagramChannel.value.lastTime > UDP_SOCKET_IDLE_TIMEOUT * 1000) {
                        kotlin.runCatching {
                            managedDatagramChannel.value.channel.close()
                        }.exceptionOrNull()?.printStackTrace()
                        iterator.remove()
                        removeCount++
                    }
                }
                if (removeCount > 0) {
                    Log.d(TAG, "remove ${removeCount} overtime inactive UDP，currently active${udpSocketMap.size}")
                }
            }
            Thread.sleep(INTERVAL_TIME * 1000)
        }
    }

}

class TcpPipe(val tunnelKey: String, packet: Packet) {
    var mySequenceNum: Long = 0
    var theirSequenceNum: Long = 0
    var myAcknowledgementNum: Long = 0
    var theirAcknowledgementNum: Long = 0
    val tunnelId = tunnelIds++

    val sourceAddress: InetSocketAddress =
        InetSocketAddress(packet.ip4Header.sourceAddress, packet.tcpHeader.sourcePort)
    val destinationAddress: InetSocketAddress =
        InetSocketAddress(packet.ip4Header.destinationAddress, packet.tcpHeader.destinationPort)
    val remoteSocketChannel: SocketChannel =
        SocketChannel.open().also { it.configureBlocking(false) }
    val remoteSocketChannelKey: SelectionKey =
        remoteSocketChannel.register(tcpNioSelector, SelectionKey.OP_CONNECT)
            .also { it.attach(this) }

    var tcbStatus: TCBStatus = TCBStatus.SYN_SENT
    var remoteOutBuffer: ByteBuffer? = null

    var upActive = true
    var downActive = true
    var packId = 1
    var timestamp = System.currentTimeMillis()
    var synCount = 0


    fun tryConnect(vpnService: VpnService): Result<Boolean> {
        val result = kotlin.runCatching {
            vpnService.protect(remoteSocketChannel.socket())
            remoteSocketChannel.connect(destinationAddress)
        }
        return result
    }


    companion object {
        const val TAG = "TcpPipe"
        var tunnelIds = 0
    }
}

/**
 * TCP packet processing thread<br>
 * NIO
 */
@SuppressLint("StaticFieldLeak")
object TcpWorker : Runnable {
    private const val TAG = "TcpSendWorker"

    private const val TCP_HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE

    private lateinit var thread: Thread

    private val pipeMap = HashMap<String, TcpPipe>()

    private var vpnService: VpnService? = null

    fun start(vpnService: VpnService) {
        this.vpnService = vpnService
        thread = Thread(this).apply {
            name = TAG
            start()
        }
    }

    fun stop() {
        thread.interrupt()
        vpnService = null
    }

    override fun run() {
        while (!thread.isInterrupted) {
            if (vpnService == null) {
                throw IllegalStateException("VpnService not responding null")
            }
            handleReadFromVpn()
            handleSockets()

            Thread.sleep(1)
        }
    }

    private fun handleReadFromVpn() {
        while (!thread.isInterrupted) {
            val vpnService = this.vpnService ?: return
            val packet = deviceToNetworkTCPQueue.poll() ?: return
            val destinationAddress = packet.ip4Header.destinationAddress
            val tcpHeader = packet.tcpHeader
            val destinationPort = tcpHeader.destinationPort
            val sourcePort = tcpHeader.sourcePort

            val ipAndPort = (destinationAddress.hostAddress?.plus(":")
                ?: "unknown-host-address") + destinationPort + ":" + sourcePort

            val tcpPipe = if (!pipeMap.containsKey(ipAndPort)) {
                val pipe = TcpPipe(ipAndPort, packet)
                pipe.tryConnect(vpnService)
                pipeMap[ipAndPort] = pipe
                pipe
            } else {
                pipeMap[ipAndPort]
                    ?: throw IllegalStateException("pipeMap not present in the center null key:$ipAndPort")
            }
            handlePacket(packet, tcpPipe)
        }
    }

    private fun handleSockets() {
        while (!thread.isInterrupted && tcpNioSelector.selectNow() > 0) {
            val keys = tcpNioSelector.selectedKeys()
            val iterator = keys.iterator()
            while (!thread.isInterrupted && iterator.hasNext()) {
                val key = iterator.next()
                iterator.remove()
                val tcpPipe: TcpPipe? = key?.attachment() as? TcpPipe
                if (key.isValid) {
                    kotlin.runCatching {
                        if (key.isAcceptable) {
                            throw RuntimeException("key.isAcceptable")
                        } else if (key.isReadable) {
                            tcpPipe?.doRead()
                        } else if (key.isConnectable) {
                            tcpPipe?.doConnect()
                        } else if (key.isWritable) {
                            tcpPipe?.doWrite()
                        } else {
                            tcpPipe?.closeRst()
                        }
                        null
                    }.exceptionOrNull()?.let {

                        Log.d(
                            TAG,
                            "communication with target error occurred:${
                                Base64.encodeToString(
                                    tcpPipe?.destinationAddress.toString().toByteArray(),
                                    Base64.DEFAULT
                                )
                            }"
                        )
                        it.printStackTrace()
                        tcpPipe?.closeRst()
                    }
                }

            }
        }
    }

    private fun handlePacket(packet: Packet, tcpPipe: TcpPipe) {
        val tcpHeader = packet.tcpHeader
        when {
            tcpHeader.isSYN -> {
                handleSyn(packet, tcpPipe)
            }

            tcpHeader.isRST -> {
                handleRst(tcpPipe)
            }

            tcpHeader.isFIN -> {
                handleFin(packet, tcpPipe)
            }

            tcpHeader.isACK -> {
                handleAck(packet, tcpPipe)
            }
        }
    }

    private fun handleSyn(packet: Packet, tcpPipe: TcpPipe) {
        if (tcpPipe.tcbStatus == TCBStatus.SYN_SENT) {
            tcpPipe.tcbStatus = TCBStatus.SYN_RECEIVED
        }
        val tcpHeader = packet.tcpHeader
        tcpPipe.apply {
            if (synCount == 0) {
                mySequenceNum = 1
                theirSequenceNum = tcpHeader.sequenceNumber
                myAcknowledgementNum = tcpHeader.sequenceNumber + 1
                theirAcknowledgementNum = tcpHeader.acknowledgementNumber
                sendTcpPack(this, TCPHeader.SYN.toByte() or TCPHeader.ACK.toByte())
            } else {
                myAcknowledgementNum = tcpHeader.sequenceNumber + 1
            }
            synCount++
        }
    }

    private fun handleRst(tcpPipe: TcpPipe) {
        tcpPipe.apply {
            upActive = false
            downActive = false
            clean()
            tcbStatus = TCBStatus.CLOSE_WAIT
        }
    }

    private fun handleFin(packet: Packet, tcpPipe: TcpPipe) {
        tcpPipe.myAcknowledgementNum = packet.tcpHeader.sequenceNumber + 1
        tcpPipe.theirAcknowledgementNum = packet.tcpHeader.acknowledgementNumber + 1
        sendTcpPack(tcpPipe, TCPHeader.ACK.toByte())
        tcpPipe.closeUpStream()
        tcpPipe.tcbStatus = TCBStatus.CLOSE_WAIT
    }

    private fun handleAck(packet: Packet, tcpPipe: TcpPipe) {
        if (tcpPipe.tcbStatus == TCBStatus.SYN_RECEIVED) {
            tcpPipe.tcbStatus = TCBStatus.ESTABLISHED
        }

        val tcpHeader = packet.tcpHeader
        val payloadSize = packet.backingBuffer.remaining()

        if (payloadSize == 0) {
            return
        }

        val newAck = tcpHeader.sequenceNumber + payloadSize
        if (newAck <= tcpPipe.myAcknowledgementNum) {
            return
        }

        tcpPipe.apply {
            myAcknowledgementNum = tcpHeader.sequenceNumber + payloadSize
            theirAcknowledgementNum = tcpHeader.acknowledgementNumber
            remoteOutBuffer = packet.backingBuffer
            tryFlushWrite(this)
            sendTcpPack(this, TCPHeader.ACK.toByte())
        }

    }

    /**
     * send tcp package
     */
    private fun sendTcpPack(tcpPipe: TcpPipe, flag: Byte, data: ByteArray? = null) {
        val dataSize = data?.size ?: 0
        Log.d(
            "SOURCE AND DESTINATION",
            "Source: ${tcpPipe.sourceAddress} & Destination: ${tcpPipe.destinationAddress}"
        )
        val packet = IpUtil.buildTcpPacket(
            tcpPipe.destinationAddress,
            tcpPipe.sourceAddress,
            flag,
            tcpPipe.myAcknowledgementNum,
            tcpPipe.mySequenceNum,
            tcpPipe.packId
        )
        tcpPipe.packId++

        val byteBuffer = ByteBuffer.allocate(TCP_HEADER_SIZE + dataSize)
        byteBuffer.position(TCP_HEADER_SIZE)

        data?.let {
            byteBuffer.put(it)
        }

        packet?.updateTCPBuffer(
            byteBuffer,
            flag,
            tcpPipe.mySequenceNum,
            tcpPipe.myAcknowledgementNum,
            dataSize
        )
        packet?.release()

        byteBuffer.position(TCP_HEADER_SIZE + dataSize)

        networkToDeviceQueue.offer(byteBuffer)

        if ((flag and TCPHeader.SYN.toByte()) != 0.toByte()) {
            tcpPipe.mySequenceNum++
        }
        if ((flag and TCPHeader.FIN.toByte()) != 0.toByte()) {
            tcpPipe.mySequenceNum++
        }
        if ((flag and TCPHeader.ACK.toByte()) != 0.toByte()) {
            tcpPipe.mySequenceNum += dataSize
        }

    }

    /**
     * overwrite data
     */
    private fun tryFlushWrite(tcpPipe: TcpPipe): Boolean {
        val channel: SocketChannel = tcpPipe.remoteSocketChannel
        val buffer = tcpPipe.remoteOutBuffer

        if (tcpPipe.remoteSocketChannel.socket().isOutputShutdown && buffer?.remaining() != 0) {
            sendTcpPack(tcpPipe, TCPHeader.FIN.toByte() or TCPHeader.ACK.toByte())
            buffer?.compact()
            return false
        }

        if (!channel.isConnected) {
//            Log.w(TAG, "connection not engaged")
            val key = tcpPipe.remoteSocketChannelKey
            val ops = key.interestOps() or SelectionKey.OP_WRITE
            key.interestOps(ops)
            buffer?.compact()
            return false
        }

        while (!thread.isInterrupted && buffer?.hasRemaining() == true) {
            val n = kotlin.runCatching {
                channel.write(buffer)
            }
            if (n.isFailure) return false
            if (n.getOrThrow() <= 0) {
                val key = tcpPipe.remoteSocketChannelKey
                val ops = key.interestOps() or SelectionKey.OP_WRITE
                key.interestOps(ops)
                buffer.compact()
                return false
            }
        }
        buffer?.clear()
        if (!tcpPipe.upActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tcpPipe.remoteSocketChannel.shutdownOutput()
            } else {
                //todo The following sentence will result in incorrect handling of socket，But there is no solution here, no problem？
//                tcpPipe.remoteSocketChannel.close()
            }
        }
        return true
    }

    private fun TcpPipe.closeRst() {
        Log.d(TAG, "closeRst $tunnelId")
        clean()
        sendTcpPack(this, TCPHeader.RST.toByte())
        upActive = false
        downActive = false
    }

    private fun TcpPipe.doRead() {
        val buffer = ByteBuffer.allocate(4096)
        var isQuitType = false

        while (!thread.isInterrupted) {
            buffer.clear()
            val length = remoteSocketChannel.read(buffer)
            if (length == -1) {
                isQuitType = true
                break
            } else if (length == 0) {
                break
            } else {
                if (tcbStatus != TCBStatus.CLOSE_WAIT) {
                    buffer.flip()
                    val dataByteArray = ByteArray(buffer.remaining())
                    buffer.get(dataByteArray)
                    sendTcpPack(this, TCPHeader.ACK.toByte(), dataByteArray)
                }
            }
        }

        if (isQuitType) {
            closeDownStream()
        }
    }

    private fun TcpPipe.doConnect() {
        val finishConnect = remoteSocketChannel.finishConnect()
        timestamp = System.currentTimeMillis()
        remoteOutBuffer?.flip()
        remoteSocketChannelKey.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
    }

    private fun TcpPipe.doWrite() {
        if (tryFlushWrite(this)) {
            remoteSocketChannelKey.interestOps(SelectionKey.OP_READ)
        }
    }

    private fun TcpPipe.clean() {
        kotlin.runCatching {
            if (remoteSocketChannel.isOpen) {
                remoteSocketChannel.close()
            }
            remoteOutBuffer = null
            pipeMap.remove(tunnelKey)
        }.exceptionOrNull()?.printStackTrace()
    }

    private fun TcpPipe.closeUpStream() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            kotlin.runCatching {
                if (remoteSocketChannel.isOpen && remoteSocketChannel.isConnected) {
                    remoteSocketChannel.shutdownOutput()
                }
            }.exceptionOrNull()?.printStackTrace()
            upActive = false

            if (!downActive) {
                clean()
            }
        } else {
            upActive = false
            downActive = false
            clean()
        }
    }

    private fun TcpPipe.closeDownStream() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            kotlin.runCatching {
                if (remoteSocketChannel.isConnected) {
                    remoteSocketChannel.shutdownInput()
                    val ops = remoteSocketChannelKey.interestOps() and SelectionKey.OP_READ.inv()
                    remoteSocketChannelKey.interestOps(ops)
                }
                sendTcpPack(this, (TCPHeader.FIN.toByte() or TCPHeader.ACK.toByte()))
                downActive = false
                if (!upActive) {
                    clean()
                }
            }
        } else {
            sendTcpPack(this, (TCPHeader.FIN.toByte() or TCPHeader.ACK.toByte()))
            upActive = false
            downActive = false
            clean()
        }
    }
}