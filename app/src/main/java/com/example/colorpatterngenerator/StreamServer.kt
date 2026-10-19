package com.example.colorpatterngenerator

import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.ImageFormat

object StreamServer {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isRunning = false

    fun start(): Int {
        // Force stop previous session to release port/resources
        stop()
        
        try {
            // Bind explicitly to 0.0.0.0 to ensure accessibility
            serverSocket = ServerSocket(0, 1, java.net.InetAddress.getByName("0.0.0.0"))
            serverSocket?.reuseAddress = true
            
            isRunning = true
            val port = serverSocket!!.localPort
            val sessionId = System.currentTimeMillis()
            
            FileLogger.log("StreamServer", "Starting Session $sessionId on port $port")
            
            // Start accepting in background
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    // This blocks until a client connects.
                    // If a new session starts (stop() called), this socket is closed, throwing exception.
                    val socket = serverSocket!!.accept()
                    
                    // Double check if we are still the valid running session
                    if (!isRunning) {
                        socket.close()
                        return@launch
                    }
                    
                    clientSocket = socket
                    FileLogger.log("StreamServer", "Client connected to Session $sessionId: ${socket.inetAddress}")
                } catch (e: Exception) {
                    if (isRunning) FileLogger.e("StreamServer", "Accept failed", e)
                }
            }
            
            return port
        } catch (e: Exception) {
            FileLogger.e("StreamServer", "Start failed", e)
            return -1
        }
    }

    fun stop() {
        isRunning = false
        try { 
            clientSocket?.close() 
        } catch(e: Exception){}
        
        try { 
            serverSocket?.close() 
        } catch(e: Exception){}
        
        clientSocket = null
        serverSocket = null
        FileLogger.log("StreamServer", "Server Stopped")
    }

    fun sendFrame(image: androidx.camera.core.ImageProxy) {
        val socket = clientSocket
        if (socket == null || socket.isClosed) return

        try {
            val nv21 = yuv420ToNv21(image)
            
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 30, out)
            val jpegBytes = out.toByteArray()
            
            val header = "frame:${jpegBytes.size}\n"
            
            synchronized(socket) {
                val output = socket.getOutputStream()
                output.write(header.toByteArray())
                output.write(jpegBytes)
                output.flush()
            }
        } catch (e: Exception) {
            // Log.e("StreamServer", "Send error", e)
        }
    }

    private fun yuv420ToNv21(image: androidx.camera.core.ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val rowStrideY = image.planes[0].rowStride
        val rowStrideUV = image.planes[1].rowStride
        val pixelStrideUV = image.planes[1].pixelStride

        // Copy Y
        if (rowStrideY == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            // Remove padding
            for (row in 0 until height) {
                yBuffer.position(row * rowStrideY)
                yBuffer.get(nv21, row * width, width)
            }
        }

        // Copy UV (Interleaved for NV21: V, U, V, U...)
        // NV21 expects V first, then U.
        // ImageProxy gives us U plane and V plane.
        // If pixelStride > 1, they might be interleaved already in the buffer.
        
        val offset = ySize
        
        if (pixelStrideUV == 2 && uBuffer == vBuffer && rowStrideUV == width) {
             // Already interleaved (common in some devices), but check order
             // NV21 is V, U. If buffer is U, V (NV12), we might need swap?
             // Usually CameraX gives proper planes. 
             // Let's stick to safe pixel-by-pixel copy for robustness.
        }

        // Safe copy (slow but correct)
        // NV21: V U V U ...
        val vData = ByteArray(vBuffer.remaining())
        val uData = ByteArray(uBuffer.remaining())
        vBuffer.get(vData)
        uBuffer.get(uData)
        
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vIndex = row * rowStrideUV + col * pixelStrideUV
                val uIndex = row * rowStrideUV + col * pixelStrideUV
                
                // V
                if (vIndex < vData.size) {
                    nv21[offset + (row * width) + (col * 2)] = vData[vIndex]
                }
                // U
                if (uIndex < uData.size) {
                    nv21[offset + (row * width) + (col * 2) + 1] = uData[uIndex]
                }
            }
        }
        
        return nv21
    }
}
