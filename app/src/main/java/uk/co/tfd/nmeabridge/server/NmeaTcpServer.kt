package uk.co.tfd.nmeabridge.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class NmeaTcpServer(
    private val port: Int,
    private val nmeaFlow: SharedFlow<String>,
    private val scope: CoroutineScope,
    private val onClientCountChanged: (Int) -> Unit
) {
    private val clients = ConcurrentHashMap<String, ClientEntry>()
    private var serverSocket: ServerSocket? = null

    private data class ClientEntry(val job: Job, val socket: Socket)

    suspend fun start() = withContext(Dispatchers.IO) {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = server

        scope.coroutineContext[Job]?.invokeOnCompletion {
            try { server.close() } catch (_: IOException) {}
        }

        while (isActive) {
            try {
                val clientSocket = server.accept()
                clientSocket.tcpNoDelay = true
                clientSocket.setSoLinger(true, 0) // RST on close for immediate notification

                val clientId = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"

                val clientJob = scope.launch(Dispatchers.IO) {
                    try {
                        val writer = BufferedOutputStream(clientSocket.getOutputStream())
                        nmeaFlow.collect { sentence ->
                            val bytes = "$sentence\r\n".toByteArray(Charsets.US_ASCII)
                            writer.write(bytes)
                            writer.flush()
                        }
                    } catch (_: IOException) {
                        // Client disconnected
                    } finally {
                        try { clientSocket.close() } catch (_: IOException) {}
                    }
                }

                clients[clientId] = ClientEntry(clientJob, clientSocket)
                onClientCountChanged(clients.size)

                clientJob.invokeOnCompletion {
                    clients.remove(clientId)
                    onClientCountChanged(clients.size)
                }
            } catch (_: IOException) {
                if (!isActive) break
            }
        }
    }

    fun stop() {
        // Close all client sockets first — this unblocks any pending writes
        // and sends RST to remote clients so they detect the disconnection
        clients.values.forEach { entry ->
            try { entry.socket.close() } catch (_: IOException) {}
            entry.job.cancel()
        }
        clients.clear()
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        onClientCountChanged(0)
    }

    fun getClientCount(): Int = clients.size
}
