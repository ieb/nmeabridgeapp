package com.example.nmeabridge.server

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
import java.util.concurrent.ConcurrentHashMap

class NmeaTcpServer(
    private val port: Int,
    private val nmeaFlow: SharedFlow<String>,
    private val scope: CoroutineScope,
    private val onClientCountChanged: (Int) -> Unit
) {
    private val clients = ConcurrentHashMap<String, Job>()
    private var serverSocket: ServerSocket? = null

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

                clients[clientId] = clientJob
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
        clients.values.forEach { it.cancel() }
        clients.clear()
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
    }

    fun getClientCount(): Int = clients.size
}
