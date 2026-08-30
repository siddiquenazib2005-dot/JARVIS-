package com.jarvis.ai.bridge

import android.content.Context
import android.os.Build
import android.os.Process
import com.jarvis.ai.intelligence.TaskRouter
import com.jarvis.ai.orchestrator.MasterOrchestrator
import com.jarvis.ai.provider.ProviderRouter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.toList
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

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
    
    private val allowedUids = setOf(
        context.applicationInfo.uid,
        2000,
        "com.termux".hashCode()
    )

    private fun isAuthorized(): Boolean {
        return try {
            val callingUid = Process.myUid()
            callingUid in allowedUids
        } catch (e: Exception) {
            false
        }
    }
    
    companion object {
        const val SOCKET_NAME = "jarvis.port"
        private const val MAX_COMMAND_LENGTH = 2000
        private const val MAX_REQUESTS_PER_SECOND = 10
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

            if (!isAuthorized()) {
                writer.println(createErrorResponse("", "unauthorized"))
                return@withContext
            }

            val request = parseRequest(line)
            if (request == null) {
                writer.println(createErrorResponse("", "invalid JSON"))
                return@withContext
            }
            
            val (requestId, cmd) = request
            
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
            append("\"requestId\": \"$requestId\",")
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
        
        writer.println("""{"requestId":"$requestId","status":"done","result":$historyJson}""")
    }

    private suspend fun handleProviders(writer: PrintWriter, requestId: String) {
        // Get provider stats
        val providersJson = "[]"
        
        writer.println("""{"requestId":"$requestId","status":"done","result":$providersJson}""")
    }

    private suspend fun handleMemory(writer: PrintWriter, requestId: String) {
        // Get memory context
        val context = ""
        val contextJson = """{"requestId":"$requestId","status":"done","result":"${escapeJson(context)}"}"""
        
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
            
            writer.println("""{"requestId":"$requestId","status":"done","result":"${escapeJson(responseText)}"}""")
            writer.flush()
        }
        
        job.join()
    }

    private fun parseRequest(line: String): Pair<String, String>? {
        return try {
            val json = org.json.JSONObject(line)
            val cmd = json.optString("cmd", "")
            val requestId = json.optString("requestId", "")
            
            if (cmd.isBlank()) null
            else Pair(requestId, cmd)
        } catch (e: Exception) {
            null
        }
    }

    internal fun createErrorResponse(requestId: String, error: String): String {
        return """{"requestId":"$requestId","status":"error","result":"$error"}"""
    }

    internal fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}