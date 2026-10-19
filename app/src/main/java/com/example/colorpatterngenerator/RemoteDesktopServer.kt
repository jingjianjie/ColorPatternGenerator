package com.example.colorpatterngenerator

import android.content.Context
import android.media.projection.MediaProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket

object RemoteDesktopServer {
    
    private const val PORT = 8889
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isRunning = false
    private var captureManager: ScreenCaptureManager? = null
    private var hasScreenCapture = false
    
    fun init(context: Context) {
        captureManager = ScreenCaptureManager(context)
    }
    
    fun startServer() {
        if (isRunning) return
        isRunning = true
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(PORT)
                FileLogger.log("RD", "Server listening on $PORT")
                
                while (isRunning) {
                    val client = serverSocket?.accept()
                    if (client != null) {
                        FileLogger.log("RD", "Client connected: ${client.inetAddress}")
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                FileLogger.e("RD", "Server Error", e)
            }
        }
    }
    
    fun setMediaProjection(mp: MediaProjection) {
        try {
            captureManager?.start(mp)
            hasScreenCapture = true

            // Setup frame callback to send data
            captureManager?.onFrameReady = { jpegBytes ->
                sendFrame(jpegBytes)
            }
        } catch (e: Exception) {
            hasScreenCapture = false
            FileLogger.e("RD", "Screen capture start failed", e)
            try { mp.stop() } catch (_: Exception) {}
        }
    }
    
    private fun handleClient(socket: Socket) {
        clientSocket?.close() // Only 1 client allowed
        clientSocket = socket
        
        try {
            val input = socket.getInputStream()
            val buffer = ByteArray(1024)
            
            while (socket.isConnected && isRunning) {
                val read = input.read(buffer)
                if (read == -1) break
                val cmd = String(buffer, 0, read).trim()
                processCommand(cmd)
            }
        } catch (e: Exception) {
            FileLogger.e("RD", "Client Error", e)
        } finally {
            socket.close()
            if (clientSocket == socket) clientSocket = null
        }
    }
    
    private fun processCommand(cmd: String) {
        FileLogger.log("RD", "CMD: $cmd")
        val parts = cmd.split(":")
        val action = parts[0]
        
        when (action) {
            "rd-hello" -> sendText("rd-ok:hello\n")
            "rd-status" -> {
                val accessibility = RemoteAccessibilityService.instance != null
                val capture = hasScreenCapture
                sendText("rd-status:accessibility=$accessibility,screen_capture=$capture\n")
            }
            "c-tap" -> {
                if (parts.size >= 2) {
                    val coords = parts[1].split(",")
                    if (coords.size == 2) {
                        val service = RemoteAccessibilityService.instance
                        if (service != null) {
                            service.performTap(coords[0].toFloat(), coords[1].toFloat())
                            sendText("rd-ok:c-tap\n")
                        } else {
                            sendText("rd-error:accessibility_disabled\n")
                        }
                    } else {
                        sendText("rd-error:bad_c-tap\n")
                    }
                } else {
                    sendText("rd-error:bad_c-tap\n")
                }
            }
            "c-swipe" -> {
                if (parts.size >= 2) {
                    val args = parts[1].split(",")
                    if (args.size == 5) {
                        val service = RemoteAccessibilityService.instance
                        if (service != null) {
                            service.performSwipe(
                                args[0].toFloat(), args[1].toFloat(),
                                args[2].toFloat(), args[3].toFloat(),
                                args[4].toLong()
                            )
                            sendText("rd-ok:c-swipe\n")
                        } else {
                            sendText("rd-error:accessibility_disabled\n")
                        }
                    } else {
                        sendText("rd-error:bad_c-swipe\n")
                    }
                } else {
                    sendText("rd-error:bad_c-swipe\n")
                }
            }
            "c-key" -> {
                if (parts.size >= 2) {
                    val code = parts[1].toIntOrNull()
                    if (code != null) {
                        val globalAction = when(code) {
                            1 -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                            2 -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                            3 -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                            else -> 0
                        }
                        if (globalAction != 0) {
                            val service = RemoteAccessibilityService.instance
                            if (service != null) {
                                service.performGlobalKey(globalAction)
                                sendText("rd-ok:c-key\n")
                            } else {
                                sendText("rd-error:accessibility_disabled\n")
                            }
                        } else {
                            sendText("rd-error:bad_c-key\n")
                        }
                    } else {
                        sendText("rd-error:bad_c-key\n")
                    }
                } else {
                    sendText("rd-error:bad_c-key\n")
                }
            }
            else -> sendText("rd-error:unknown_command\n")
        }
    }
    
    private fun sendFrame(data: ByteArray) {
        val socket = clientSocket ?: return
        if (socket.isClosed) return
        
        try {
            val out = socket.getOutputStream()
            val header = "frame:${data.size}\n".toByteArray()
            
            synchronized(out) {
                out.write(header)
                out.write(data)
                out.flush()
            }
        } catch (e: Exception) {
            // Client likely disconnected
        }
    }

    private fun sendText(text: String) {
        val socket = clientSocket ?: return
        if (socket.isClosed) return

        try {
            val out = socket.getOutputStream()
            synchronized(out) {
                out.write(text.toByteArray())
                out.flush()
            }
        } catch (e: Exception) {
            FileLogger.e("RD", "Text response failed", e)
        }
    }
    
    fun stop() {
        isRunning = false
        hasScreenCapture = false
        captureManager?.stop()
        serverSocket?.close()
        clientSocket?.close()
    }
}
