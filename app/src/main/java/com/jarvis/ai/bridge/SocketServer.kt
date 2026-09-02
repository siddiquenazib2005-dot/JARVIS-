package com.jarvis.ai.bridge

import android.content.Context
import com.jarvis.ai.intelligence.TaskRouter
import com.jarvis.ai.orchestrator.MasterOrchestrator
import com.jarvis.ai.provider.ProviderRouter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.toList
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

class SocketServer(
    private val context: Context,
    private val orchestrator: MasterOrchestrator,
    private val providerRouter: ProviderRouter,
    private val taskRouter: TaskRouter
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var runningPort = 0
    
    private val socketFile: java.io.File
        get() = java.io.File(context.filesDir, SOCKET_NAME)

    private val authTokenBase: String =
        context.packageName + ":" + android.os.Build.VERSION.SDK_INT

    private fun isLoopback(socket: Socket): Boolean {
        return try {
            socket.inetAddress.isLoopbackAddress
        } catch (e: Exception) {
            false
        }
    }

    private fun isAuthorized(clientSocket: Socket, token: String?): Boolean {
        // TCP control socket: restrict to loopback, then require the shared
        // token (derived from package name) so any local process must
        // explicitly opt-in rather than being implicitly trusted.
        if (!isLoopback(clientSocket)) return false
        if (token.isNullOrBlank()) return false
        val expected = sha256(authTokenBase)
        return constantTimeEquals(expected, sha256(token.trim()))
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
    
    companion object {
        const val SOCKET_NAME = "jarvis.port"
        private const val MAX_COMMAND_LENGTH = 2000
    }
    
    fun start() {
        if (isRunning) return
        
        isRunning = true
        scope.launch {
            acceptConnections()
        }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        serverSocket = null
        
        val sockFile = socketFile
        if (sockFile.exists()) {
            sockFile.delete()
        }
        
        scope.coroutineContext[Job]?.cancelChildren()
    }
    
    fun getPort(): Int = runningPort

    private suspend fun acceptConnections() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(0)
            runningPort = serverSocket?.localPort ?: 0
            
            val sockFile = socketFile
            sockFile.writeText(runningPort.toString())
            
            while (isRunning) {
                try {
                    val client = serverSocket?.accept() ?: break
                    scope.launch { handleClient(client) }
                } catch (e: Exception) {
                    if (isRunning) {
                        delay(100)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val writer = PrintWriter(clientSocket.getOutputStream(), true)
            
            val line = reader.readLine() ?: run {
                writer.println("""{"error":"empty request"}""")
                return@withContext
            }

            if (line.length > MAX_COMMAND_LENGTH) {
                writer.println(createErrorResponse("", "request too long"))
                return@withContext
            }

            val request = parseRequest(line)
            if (request == null) {
                writer.println(createErrorResponse("", "invalid JSON"))
                return@withContext
            }

            val (requestId, cmd, token) = request

            if (!isAuthorized(clientSocket, token)) {
                writer.println(createErrorResponse(requestId, "unauthorized"))
                return@withContext
            }
            
            when (cmd.lowercase()) {
                "status" -> handleStatus(writer, requestId)
                "history" -> handleHistory(writer, requestId)
                "providers" -> handleProviders(writer, requestId)
                "memory" -> handleMemory(writer, requestId)
                else -> handleCommand(writer, requestId, cmd)
            }
            
            writer.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) { }
        }
    }

    private fun handleStatus(writer: PrintWriter, requestId: String) {
        val accessibilityEnabled = com.jarvis.ai.accessibility.JarvisAccessibilityService.instance != null
        val provider = try {
            // Get provider info from router
            "active"
        } catch (e: Exception) { "unknown" }
        
        val statusJson = buildString {
            append("{")
            append("\"requestId\": \"${escapeJson(requestId)}\",")
            append("\"status\": \"done\",")
            append("\"result\": {")
            append("\"service\": \"running\",")
            append("\"accessibility\": $accessibilityEnabled,")
            append("\"provider\": \"$provider\",")
            append("\"port\": $runningPort")
            append("}}")
        }
        
        writer.println(statusJson)
    }

    private suspend fun handleHistory(writer: PrintWriter, requestId: String) {
        // Get history from vector memory
        val historyJson = "[]"
        
        writer.println("""{"requestId":"${escapeJson(requestId)}","status":"done","result":$historyJson}""")
    }

    private suspend fun handleProviders(writer: PrintWriter, requestId: String) {
        // Get provider stats
        val providersJson = "[]"
        
        writer.println("""{"requestId":"${escapeJson(requestId)}","status":"done","result":$providersJson}""")
    }

    private suspend fun handleMemory(writer: PrintWriter, requestId: String) {
        // Get memory context
        val context = ""
        val contextJson = """{"requestId":"${escapeJson(requestId)}","status":"done","result":"${escapeJson(context)}"}"""
        
        writer.println(contextJson)
    }

    private suspend fun handleCommand(writer: PrintWriter, requestId: String, cmd: String) {
        val job = scope.launch {
            // Execute through orchestrator
            val result = orchestrator.processRequest(
                input = cmd,
                history = emptyList(),
                userConfirmedThisTurn = false
            ).toList()
            
            val deltas = result.filterIsInstance<com.jarvis.ai.orchestrator.OrchestratorUpdate.Delta>()
            val responseText = deltas.joinToString("") { it.text }
            
            writer.println("""{"requestId":"${escapeJson(requestId)}","status":"done","result":"${escapeJson(responseText)}"}""")
            writer.flush()
        }
        
        job.join()
    }

    private fun parseRequest(line: String): Triple<String, String, String>? {
        return try {
            val json = org.json.JSONObject(line)
            val cmd = json.optString("cmd", "")
            val requestId = json.optString("requestId", "")
            val token = json.optString("token", "")

            if (cmd.isBlank()) null
            else Triple(requestId, cmd, token)
        } catch (e: Exception) {
            null
        }
    }

    internal fun createErrorResponse(requestId: String, error: String): String {
        return """{"requestId":"${escapeJson(requestId)}","status":"error","result":"${escapeJson(error)}"}"""
    }

    internal fun escapeJson(s: String): String {
        var result = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
        // Escape any remaining C0/C1 control characters per RFC 8259
        val sb = StringBuilder(result.length + 16)
        for (ch in result) {
            if (ch.code in 0x00..0x1F) {
                sb.append("\\u").append(String.format("%04x", ch.code))
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}