package sssemil.com.p2p.dht

import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import sssemil.com.p2p.dht.api.*
import sssemil.com.p2p.dht.api.model.*
import sssemil.com.p2p.dht.util.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.Thread.sleep
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class Server {

    internal val storage = Storage()

    private val run = AtomicBoolean(true)

    private var workersNumber = Runtime.getRuntime().availableProcessors()

    val peersStorage = PeerStorage()

    private var serverSocket: ServerSocket? = null
    var port: Int
        get() {
            serverSocket?.let {
                return it.localPort
            }

            throw RuntimeException("Start the server first!")
        }
        set(value) {
            serverSocket = ServerSocket(value)
        }

    val peerId: KeyPair
        get() = peersStorage.id

    fun start() {
        run.set(true)

        serverSocket = ServerSocket(0)

        Logger.i("Number of workers: $workersNumber")
        Logger.i("Port number: ${serverSocket?.localPort}")
        Logger.i("ID: $peerId")

        thread {
            while (run.get()) {
                serverSocket?.accept()?.let {
                    handleClient(it)
                }
            }
        }
    }

    fun stop() {
        run.set(false)
        sleep(150)
    }

    private fun handleClient(socket: Socket) = async {
        while (socket.isAlive) {
            val inFromClient = DataInputStream(socket.getInputStream().buffered())
            val outToClient = DataOutputStream(socket.getOutputStream())

            if (inFromClient.available() > 0) {
                val size = inFromClient.readShort()
                val message = inFromClient.readShort()

                when (message) {
                    DHT_PUT -> {
                        val dhtPut = DhtPut.parse(size, inFromClient)

                        Logger.i("[${socket.inetAddress}][DHT_PUT] DhtPut: $dhtPut")

                        val put = Put(dhtPut.ttl.toLong(), dhtPut.replicationsLeft, dhtPut.value)

                        handleObj(socket, outToClient, DhtObj(put))
                    }
                    DHT_GET -> {
                        val dhtGet = DhtGet.parse(inFromClient)

                        Logger.i("[${socket.inetAddress}][DHT_GET] DhtGet: $dhtGet")

                        val distance = IdKeyUtils.distance(dhtGet.key, peerId.publicKey)

                        if (distance < TOLERANCE) {
                            Logger.i("Looking for the key-value in local storage!")

                            storage.get(dhtGet.key)?.let {
                                sendSuccess(outToClient, dhtGet.key, it)
                                return@async
                            }

                            Logger.i("Not found.")
                        }

                        val sentTo = HashSet<String>()

                        peersStorage.findClosest(dhtGet.key, sentTo).forEach { it ->
                            val client = Client(it.peer.ip, it.peer.port, peerId)
                            if (client.connect()) {
                                val response = client.send(DhtObj(FindValue(dhtGet.key)), DEFAULT_DELAY)

                                if (response != null && response is DhtObj) {
                                    when (response.code) {
                                        OBJ_FOUND_VALUE -> {
                                            val foundValue = response.obj as FoundValue

                                            val value = foundValue.value
                                            val key = generateKey(value)

                                            if (dhtGet.key.contentEquals(key)) {
                                                Logger.i("Value found for ${key.toHexString()}!")

                                                sendSuccess(outToClient, key, value)
                                            }
                                        }
                                        OBJ_FOUND_PEERS -> {
                                            val foundPeers = response.obj as FoundPeers

                                            foundPeers.peers.forEach {
                                                val testClient = Client(it.ip, it.port, peerId)

                                                if (!testClient.connect()) {
                                                    Logger.e("Connection failed to $it!")
                                                } else {
                                                    val pong = testClient.ping(peerId.publicKey, socket.localPort).await() as DhtObj?
                                                    Logger.i("ping ${it.ip.hostAddress} ${it.port} : $pong")

                                                    pong?.let { pongObj ->
                                                        var valid = true
                                                        val factualPeerId = (pongObj.obj as Pong).peerId

                                                        val providedId = it.id
                                                        if (!providedId.contentEquals(factualPeerId)) {
                                                            Logger.e("Peer verification failed!")
                                                            valid = false
                                                        }

                                                        if (valid) {
                                                            Logger.i("Adding new peer: $it")
                                                            peersStorage.add(it, peerId.publicKey)
                                                        }
                                                    }
                                                }

                                                testClient.stop()
                                            }
                                        }
                                        else -> {
                                            Logger.w("Hmmm...")
                                        }
                                    }
                                }
                            }

                            client.stop()
                        }

                        sendFailure(outToClient, dhtGet.key)
                    }
                    DHT_OBJ -> {
                        val dhtObj = DhtObj.parse(inFromClient, peerId)

                        Logger.i("[${socket.inetAddress}][DHT_OBJ] DhtObj: $dhtObj")

                        handleObj(socket, outToClient, dhtObj)
                    }
                    else -> {
                        Logger.e("[${socket.inetAddress}] Invalid message code: $message!")
                    }
                }
            }

            delay(10)
        }
    }

    data class WeightedPeer(val distance: Int, val peer: Peer) : Comparable<WeightedPeer> {
        override fun compareTo(other: WeightedPeer) = distance.compareTo(other.distance)
    }

    private fun handleObj(socket: Socket, outToClient: DataOutputStream, dhtObj: DhtObj) = async {
        when (dhtObj.code) {
            OBJ_PING -> {
                val ping = dhtObj.obj as Ping

                Logger.i("[${socket.inetAddress}][OBJ_PING] $ping")

                val pong = Pong(peerId.publicKey, socket.localPort)
                pong.token = ping.token

                Logger.i("[OBJ_PONG] sending: pong: $pong")

                val reply = DhtObj(pong).generate(byteArrayOf())

                outToClient.write(reply)

                peersStorage.add(Peer(ping.peerId, socket.inetAddress, ping.port), peerId.publicKey)
            }
            OBJ_PUT -> {
                val put = dhtObj.obj as Put

                Logger.i("[${socket.inetAddress}][OBJ_PUT] $put")

                if (storage.contains(put.key)) {
                    Logger.i("Ignore key, we have it : ${put.key}")
                }

                val distance = IdKeyUtils.distance(put.key, peerId.publicKey)

                Logger.i("Distance to key: $distance")

                if (distance < TOLERANCE) {
                    Logger.i("Storing the key-value!")

                    storage.store(put.key, put.value, put.ttl)

                    put.replicationsLeft--
                }

                val sentTo = HashSet<String>()

                while (put.replicationsLeft > 0) {
                    val closest = peersStorage.findClosest(put.key, sentTo)

                    closest.forEach { it ->
                        if (put.replicationsLeft > 0) {
                            val client = Client(it.peer.ip, it.peer.port, peerId)

                            if (client.connect()) {
                                put.replicationsLeft--
                                client.send(DhtObj(put).generate(it.peer.id))
                                sentTo.add(it.peer.id.toHexString())
                            }

                            client.stop()
                        }
                    }

                    if (closest.size < GRAB_SIZE) {
                        break
                    }
                }
            }
            OBJ_FIND_VALUE -> {
                val findValue = dhtObj.obj as FindValue
                val distance = IdKeyUtils.distance(findValue.key, peerId.publicKey)

                if (distance < TOLERANCE) {
                    Logger.i("Looking for the key-value in local storage!")

                    storage.get(findValue.key)?.let {
                        outToClient.write(DhtObj(FoundValue(it)).generate(byteArrayOf()))
                        outToClient.flush()
                        return@async
                    }
                }

                val closestPeers = peersStorage.findClosest(findValue.key, hashSetOf()).map { it.peer }

                outToClient.write(DhtObj(FoundPeers(closestPeers.toTypedArray())).generate(byteArrayOf()))
                outToClient.flush()

                return@async
            }
        }
    }

    private fun sendFailure(outToClient: DataOutputStream, key: ByteArray) {
        val dhtFailure = DhtFailure(key)

        Logger.i("[DHT_FAILURE] sending: $dhtFailure")

        outToClient.write(dhtFailure.generate(byteArrayOf()))
        outToClient.flush()
    }

    private fun sendSuccess(outToClient: DataOutputStream, key: ByteArray, value: ByteArray) {
        val dhtSuccess = DhtSuccess(key, value)

        Logger.i("[DHT_SUCCESS] sending: $dhtSuccess")

        outToClient.write(dhtSuccess.generate(byteArrayOf()))
        outToClient.flush()
    }

    fun pair(peerIp: InetAddress, peerPort: Int, providedId: ByteArray?) = async {
        val client = Client(peerIp, peerPort, peerId)

        if (!client.connect()) {
            Logger.i("Connection failed!")
        } else {
            val pong = serverSocket?.localPort?.let { client.ping(peerId.publicKey, it).await() } as DhtObj?
            Logger.i("ping ${peerIp.hostAddress} $peerPort : $pong")

            pong?.let {
                var valid = true
                val factualPeerId = (it.obj as Pong).peerId

                if (providedId != null) {
                    if (!providedId.contentEquals(factualPeerId)) {
                        Logger.e("Peer verification failed! ${providedId.toHexString()} != ${factualPeerId.toHexString()}.")
                        valid = false
                    }
                }

                if (valid) {
                    val peer = Peer(factualPeerId, peerIp, peerPort)
                    Logger.i("Adding new peer: $peer")
                    peersStorage.add(peer, peerId.publicKey)
                }
            }
        }

        client.stop()
    }
}