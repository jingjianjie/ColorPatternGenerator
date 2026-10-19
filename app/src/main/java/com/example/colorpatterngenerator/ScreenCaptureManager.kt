package com.example.colorpatterngenerator

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    
    // Config
    private val width = 540
    private val height = 960
    private val density = 160
    
    var onFrameReady: ((ByteArray) -> Unit)? = null

    fun start(mp: MediaProjection) {
        stop()
        mediaProjection = mp
        
        handlerThread = HandlerThread("ScreenCapture")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                FileLogger.log("RD", "MediaProjection stopped by system")
                releaseCaptureResources(stopProjection = false)
            }
        }
        projectionCallback = callback
        mediaProjection?.registerCallback(callback, handler)
        
        // Setup ImageReader
        // PixelFormat.RGBA_8888 is standard.
        // We will need to compress to JPEG manually.
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    
                    // Create bitmap
                    // This is heavy. Optimized approach: Use YUV ImageReader if supported?
                    // Or Render to Surface -> MediaCodec (H.264) -> but we need MJPEG for compatibility.
                    // For now, let's stick to Bitmap compression on background thread.
                    
                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    
                    // Crop if there is padding
                    val finalBitmap = if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    if (bitmap != finalBitmap) bitmap.recycle()
                    
                    // Compress to JPEG
                    val baos = ByteArrayOutputStream()
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                    finalBitmap.recycle()
                    
                    val jpegBytes = baos.toByteArray()
                    onFrameReady?.invoke(jpegBytes)
                    
                } catch (e: Exception) {
                    FileLogger.e("RD", "Capture Error", e)
                } finally {
                    image.close()
                }
            }
        }, handler)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RemoteDesktop",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        
        FileLogger.log("RD", "Screen Capture Started")
    }

    fun stop() {
        releaseCaptureResources(stopProjection = true)
        FileLogger.log("RD", "Screen Capture Stopped")
    }

    @Synchronized
    private fun releaseCaptureResources(stopProjection: Boolean) {
        virtualDisplay?.release()
        imageReader?.close()

        val projection = mediaProjection
        val callback = projectionCallback
        if (callback != null) {
            try { projection?.unregisterCallback(callback) } catch (_: Exception) {}
        }
        if (stopProjection) {
            try { projection?.stop() } catch (_: Exception) {}
        }

        handlerThread?.quitSafely()
        
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        projectionCallback = null
        handlerThread = null
        handler = null
    }
}
