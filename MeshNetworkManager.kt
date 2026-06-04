package com.fmcall.serval.mesh

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pConfig
import com.fmcall.serval.data.model.*
import com.fmcall.serval.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.*
import java.io.*
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Core Mesh Network Manager
 * Handles: Wi-Fi Direct P2P, multi-hop routing, packet forwarding,
 * node discovery, and Bluetooth mesh fallback.
 */
@Singleton
class MeshNetworkManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoManager,
    private val packetRouter: PacketRouter
) {
    companion object {
        private const val TAG = "MeshNetwork"
        const val MESH_PORT         = 8765
        const val DISCOVERY_PORT    = 8766
        const val BEACON_INTERVAL   = 5_000L   // ms
        const val NODE_TIMEOUT      = 30_000L  // ms - prune stale nodes
        const val MAX_HOPS          = 8
        const val BUFFER_SIZE       = 65536
    }

    // ── State ────────────────────────────────────────────────────────────────

    private val _connectedNodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val connectedNodes: StateFlow<List<MeshNode>> = _connectedNodes.asStateFlow()

    private val _meshStatus = MutableStateFlow(MeshStatus.OFFLINE)
    val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<MeshPacket>(
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val incomingPackets: SharedFlow<MeshPacket> = _incomingPackets.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nodeTable = mutableMapOf<String, MeshNode>()   // meshId -> node
    private val sessionKeys = mutableMapOf<String, ByteArray>() // meshId -> session key
    private val seenPackets = mutableSetOf<String>()            // dedup by packetId

    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    lateinit var localProfile: com.fmcall.serval.data.model.UserProfile

    // Wi-Fi Direct
    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var wifiP2pChannel: WifiP2pManager.Channel? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun start(profile: com.fmcall.serval.data.model.UserProfile) {
        if (isRunning) return
        localProfile = profile
        isRunning = true
        _meshStatus.value = MeshStatus.STARTING

        scope.launch {
            try {
                startTcpServer()
                startDiscoveryBeacon()
                startNodePruner()
                startWifiDirect()
                _meshStatus.value = MeshStatus.ONLINE
                Log.i(TAG, "Mesh started: ${profile.meshId}")
            } catch (e: Exception) {
                Log.e(TAG, "Mesh start failed", e)
                _meshStatus.value = MeshStatus.ERROR
            }
        }
    }

    fun stop() {
        isRunning = false
        _meshStatus.value = MeshStatus.OFFLINE
        scope.cancel()
        serverSocket?.close()
    }

    // ── TCP Server (receives packets from other nodes) ──────────────────────

    private fun startTcpServer() {
        scope.launch(Dispatchers.IO) {
            serverSocket = ServerSocket(MESH_PORT)
            Log.d(TAG, "TCP server listening on $MESH_PORT")
            while (isRunning) {
                try {
                    val client = serverSocket!!.accept()
                    launch { handleIncomingConnection(client) }
                } catch (e: SocketException) {
                    if (isRunning) Log.e(TAG, "Socket error", e)
                }
            }
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val input = DataInputStream(BufferedInputStream(socket.inputStream))
                val length = input.readInt()
                val data = ByteArray(length)
                input.readFully(data)
                val packet = deserializePacket(data)
                processIncomingPacket(packet, socket.inetAddress.hostAddress)
            } catch (e: Exception) {
                Log.e(TAG, "Connection handling error", e)
            } finally {
                socket.close()
            }
        }
    }

    // ── Packet Processing & Routing ──────────────────────────────────────────

    private suspend fun processIncomingPacket(packet: MeshPacket, fromIp: String) {
        // Deduplication
        if (packet.packetId in seenPackets) return
        seenPackets.add(packet.packetId)
        if (seenPackets.size > 10_000) seenPackets.clear()

        // Verify signature
        val senderNode = nodeTable[packet.sourceId]
        if (senderNode != null) {
            val payloadWithMeta = packet.packetId.toByteArray() + packet.payload
            if (!crypto.verifySignature(payloadWithMeta, packet.signature,
                    android.util.Base64.decode(senderNode.publicKey, android.util.Base64.NO_WRAP))) {
                Log.w(TAG, "Invalid signature from ${packet.sourceId}")
                return
            }
        }

        // Update node last seen
        senderNode?.let {
            nodeTable[packet.sourceId] = it.copy(
                lastSeen = System.currentTimeMillis(),
                ipAddress = fromIp
            )
        } ?: run {
            // Auto-discover node from beacon
            if (packet.type == PacketType.DISCOVERY_BEACON) {
                handleDiscoveryBeacon(packet, fromIp)
            }
        }

        // Route or consume
        if (packet.destinationId == localProfile.meshId) {
            // For us
            _incomingPackets.emit(packet)
        } else if (packet.ttl > 0) {
            // Forward (multi-hop relay)
            forwardPacket(packet)
        }
    }

    private suspend fun forwardPacket(packet: MeshPacket) {
        val decremented = packet.copy(
            ttl = packet.ttl - 1,
            hopList = (packet.hopList + localProfile.meshId).toMutableList()
        )
        // Find best next hop toward destination
        val nextHop = packetRouter.findNextHop(decremented.destinationId, nodeTable)
        nextHop?.let { sendPacketToNode(decremented, it) }
    }

    // ── Send Packet ──────────────────────────────────────────────────────────

    suspend fun sendPacket(packet: MeshPacket): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val target = nodeTable[packet.destinationId]
                if (target != null) {
                    sendPacketToNode(packet, target)
                } else {
                    // Broadcast to all known nodes (flood for discovery)
                    nodeTable.values.forEach { node ->
                        launch { sendPacketToNode(packet, node) }
                    }
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
                false
            }
        }
    }

    private fun sendPacketToNode(packet: MeshPacket, node: MeshNode): Boolean {
        val ip = node.ipAddress ?: return false
        return try {
            Socket().use { socket ->
                socket.soTimeout = 5000
                socket.connect(InetSocketAddress(ip, node.port), 3000)
                val data = serializePacket(packet)
                val output = DataOutputStream(socket.outputStream)
                output.writeInt(data.size)
                output.write(data)
                output.flush()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send to ${node.meshId}@$ip", e)
            false
        }
    }

    // ── Discovery Beacon ─────────────────────────────────────────────────────

    private fun startDiscoveryBeacon() {
        scope.launch(Dispatchers.IO) {
            val udpSocket = DatagramSocket(DISCOVERY_PORT)
            udpSocket.broadcast = true

            // Receive beacons
            launch {
                val buf = ByteArray(BUFFER_SIZE)
                while (isRunning) {
                    try {
                        val dp = DatagramPacket(buf, buf.size)
                        udpSocket.receive(dp)
                        val data = dp.data.copyOfRange(0, dp.length)
                        val packet = deserializePacket(data)
                        if (packet.type == PacketType.DISCOVERY_BEACON) {
                            handleDiscoveryBeacon(packet, dp.address.hostAddress)
                        }
                    } catch (e: Exception) { /* ignore */ }
                }
            }

            // Send beacons
            while (isRunning) {
                try {
                    val beacon = buildBeaconPacket()
                    val data = serializePacket(beacon)
                    // Broadcast on local subnet
                    val broadcast = InetAddress.getByName("255.255.255.255")
                    udpSocket.send(DatagramPacket(data, data.size, broadcast, DISCOVERY_PORT))
                } catch (e: Exception) {
                    Log.e(TAG, "Beacon error", e)
                }
                delay(BEACON_INTERVAL)
            }
        }
    }

    private suspend fun handleDiscoveryBeacon(packet: MeshPacket, fromIp: String) {
        val payload = String(packet.payload)
        // payload format: "name|publicKey|port"
        val parts = payload.split("|")
        if (parts.size < 3) return

        val node = MeshNode(
            meshId = packet.sourceId,
            displayName = parts[0],
            publicKey = parts[1],
            lastSeen = System.currentTimeMillis(),
            ipAddress = fromIp,
            port = parts[2].toIntOrNull() ?: MESH_PORT,
            hopCount = 1,
            transportType = TransportType.WIFI_DIRECT
        )

        nodeTable[packet.sourceId] = node
        _connectedNodes.value = nodeTable.values.toList()

        // Send key exchange
        if (!sessionKeys.containsKey(packet.sourceId)) {
            initiateKeyExchange(node)
        }
    }

    private fun buildBeaconPacket(): MeshPacket {
        val payload = "${localProfile.displayName}|${localProfile.publicKey}|$MESH_PORT"
        val data = payload.toByteArray()
        val sig = crypto.signData(
            localProfile.meshId.toByteArray() + data,
            android.util.Base64.decode(localProfile.privateKey, android.util.Base64.NO_WRAP)
        )
        return MeshPacket(
            type = PacketType.DISCOVERY_BEACON,
            sourceId = localProfile.meshId,
            destinationId = "broadcast",
            payload = data,
            signature = sig,
            ttl = 1
        )
    }

    // ── Key Exchange ─────────────────────────────────────────────────────────

    private suspend fun initiateKeyExchange(node: MeshNode) {
        val sessionKey = crypto.generateSessionKey()
        val theirPubKey = android.util.Base64.decode(node.publicKey, android.util.Base64.NO_WRAP)
        val myPrivKey = android.util.Base64.decode(localProfile.privateKey, android.util.Base64.NO_WRAP)
        val sharedSecret = crypto.deriveSharedSecret(myPrivKey, theirPubKey)
        sessionKeys[node.meshId] = sharedSecret

        val keyPayload = crypto.encryptMessage(
            android.util.Base64.encodeToString(sessionKey, android.util.Base64.NO_WRAP),
            sharedSecret
        )
        val packet = MeshPacket(
            type = PacketType.KEY_EXCHANGE,
            sourceId = localProfile.meshId,
            destinationId = node.meshId,
            payload = keyPayload.toByteArray(),
            signature = crypto.signData(keyPayload.toByteArray(), myPrivKey)
        )
        sendPacket(packet)
    }

    fun getSessionKey(meshId: String): ByteArray? = sessionKeys[meshId]

    // ── Node Pruner ──────────────────────────────────────────────────────────

    private fun startNodePruner() {
        scope.launch {
            while (isRunning) {
                delay(NODE_TIMEOUT)
                val cutoff = System.currentTimeMillis() - NODE_TIMEOUT
                nodeTable.entries.removeAll { it.value.lastSeen < cutoff }
                _connectedNodes.value = nodeTable.values.toList()
            }
        }
    }

    // ── Wi-Fi Direct ─────────────────────────────────────────────────────────

    private fun startWifiDirect() {
        wifiP2pChannel = wifiP2pManager?.initialize(context, context.mainLooper, null)
        discoverWifiDirectPeers()
    }

    private fun discoverWifiDirectPeers() {
        wifiP2pChannel?.let { ch ->
            wifiP2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "Wi-Fi Direct discovery started") }
                override fun onFailure(reason: Int) { Log.w(TAG, "Wi-Fi Direct discovery failed: $reason") }
            })
        }
    }

    fun onWifiP2pPeersChanged(devices: WifiP2pDeviceList) {
        devices.deviceList.forEach { device ->
            connectToWifiDirectPeer(device)
        }
    }

    private fun connectToWifiDirectPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        wifiP2pChannel?.let { ch ->
            wifiP2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "Connecting to ${device.deviceName}") }
                override fun onFailure(r: Int) { Log.w(TAG, "Connect failed: $r") }
            })
        }
    }

    // ── Serialization ────────────────────────────────────────────────────────

    private fun serializePacket(packet: MeshPacket): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeUTF(packet.packetId)
        dos.writeUTF(packet.type.name)
        dos.writeUTF(packet.sourceId)
        dos.writeUTF(packet.destinationId)
        dos.writeInt(packet.ttl)
        dos.writeLong(packet.timestamp)
        dos.writeInt(packet.payload.size)
        dos.write(packet.payload)
        dos.writeInt(packet.signature.size)
        dos.write(packet.signature)
        dos.writeInt(packet.hopList.size)
        packet.hopList.forEach { dos.writeUTF(it) }
        return baos.toByteArray()
    }

    private fun deserializePacket(data: ByteArray): MeshPacket {
        val dis = DataInputStream(ByteArrayInputStream(data))
        val packetId = dis.readUTF()
        val type = PacketType.valueOf(dis.readUTF())
        val sourceId = dis.readUTF()
        val destId = dis.readUTF()
        val ttl = dis.readInt()
        val ts = dis.readLong()
        val payloadLen = dis.readInt()
        val payload = ByteArray(payloadLen).also { dis.readFully(it) }
        val sigLen = dis.readInt()
        val sig = ByteArray(sigLen).also { dis.readFully(it) }
        val hopCount = dis.readInt()
        val hops = (0 until hopCount).map { dis.readUTF() }.toMutableList()
        return MeshPacket(packetId, type, sourceId, destId, ttl, payload, sig, ts, hops)
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    fun getNodeCount() = nodeTable.size
    fun getNodeByMeshId(meshId: String) = nodeTable[meshId]
    fun getAllNodes() = nodeTable.values.toList()
}

// ── Packet Router ────────────────────────────────────────────────────────────

class PacketRouter @Inject constructor() {
    /**
     * Finds the best next hop node to route a packet toward the destination.
     * Uses a simple hop-count-based greedy routing approach.
     */
    fun findNextHop(destinationId: String, nodeTable: Map<String, MeshNode>): MeshNode? {
        // Direct route?
        nodeTable[destinationId]?.let { return it }
        // Find relay with lowest hop count that has seen the destination
        return nodeTable.values
            .filter { it.isRelay || it.hopCount <= 2 }
            .minByOrNull { it.hopCount }
    }
}

// ── Wi-Fi Direct Broadcast Receiver ─────────────────────────────────────────

class WifiDirectBroadcastReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        // Handled by MeshService
    }
}

enum class MeshStatus { OFFLINE, STARTING, ONLINE, ERROR }
