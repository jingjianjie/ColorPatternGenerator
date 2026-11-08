package com.example.colorpatterngenerator

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

// Sensor Imports
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    // UI
    private lateinit var infoText: TextView
    private lateinit var fullColorView: View
    private lateinit var circleContainer: FrameLayout
    private lateinit var circleView: View
    private lateinit var imageView: ImageView
    private lateinit var cameraPreview: PreviewView
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var frameLayout: FrameLayout
    private lateinit var positionOverlay: PositionOverlayView
    private lateinit var statusLogScroll: ScrollView
    private lateinit var statusLogText: TextView
    private lateinit var updatePanel: View
    private lateinit var appUpdateController: AppUpdateController
    private lateinit var idleSleepOverlay: View
    private lateinit var idleSleepController: IdleSleepController
    private var idleSavedBrightness = 1.0f
    private var isActivityResumed = false
    private var consumeIdleWakeGesture = false

    // State
    private var circleSizePx = 300
    private val UDP_PORT = 9999
    private var isTcpMode = false
    private val activeTcpSockets = TcpControllerConnections()
    private var streamTargetSocket: Socket? = null
    private var streamJob: Job? = null
    private val commandParser = CommandParser()
    private var streamStartTime = 0L
    private val MAX_STREAM_DURATION_MS = 24 * 60 * 60 * 1000L // 24 Hours
    private val commandMutex = Mutex()
    private val brightnessSettler = BrightnessSettler()
    private var isPositionMode = false
    private var currentPatternName = "None" // Tracks current pattern (Color, Disk, Image, Position)
    private var currentColorInfo = "0,0,0"  // Tracks RGB for Color/Disk
    
    private lateinit var historyManager: HistoryManager
    private lateinit var gestureDetector: android.view.GestureDetector
    private var gestureAnalyzer: SimpleGestureAnalyzer? = null
    private var isStreaming = false
    private var initializedCameraProvider: ProcessCameraProvider? = null
    private var cameraSessionGeneration = 0L
    private var isSleeping = false
    private var isTouchLocked = false
    private var sleepSavedPatternState: PatternState? = null
    private var sleepSavedBrightness = 1.0f
    private val statusLogLines = java.util.ArrayDeque<String>()
    private val maxStatusLogLines = 2000

    // Config
    private var screenWidthMm = 0f
    private var screenHeightMm = 0f
    private var screenDpiX = 0f
    private var screenDpiY = 0f
    private var multicastLock: WifiManager.MulticastLock? = null
    
    // WakeLock
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Disk Config
    private var diskDiameterMm = 20f
    private var diskOffsetYPercent = 0f // Offset from Center Y in Percentage (-1.0 to 1.0)
    private var savedDiskDiameterMm = 20f
    private var savedDiskOffsetYPercent = 0f
    // diskCenterX/Y are removed in favor of offset logic relative to center
    
    // State for gesture debouncing
    private var lastPointerDownTime = 0L
    private var maxPointersInGesture = 0
    
    // Double Tap State
    private var lastDoubleTapTime = 0L
    private val DOUBLE_TAP_TIMEOUT = 300L
    private var tapCount = 0
    private var firstTapTime = 0L
    private val THREE_FINGER_LONG_PRESS_MS = 1000L
    private var threeFingerLongPressArmed = false
    private var threeFingerLongPressTriggered = false
    private val threeFingerLongPressRunnable = Runnable {
        if (threeFingerLongPressArmed && maxPointersInGesture == 3) {
            threeFingerLongPressTriggered = true
            setTouchLocked(!isTouchLocked, "三指长按")
        }
    }

    // Sensor
    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var pitch = 0.0
    private var roll = 0.0
    private val sensorWindowSize = 33 // ms
    private var lastSensorTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        
        // Max Brightness & Keep Screen On
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val params = window.attributes
        params.screenBrightness = 1.0f
        window.attributes = params
        
        // Try to enable Wide Color Gamut (Oreo+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }

        FileLogger.init(this)
        FileLogger.log("MainActivity", "App Started. Version ${packageManager.getPackageInfo(packageName, 0).versionName}")

        setContentView(R.layout.activity_main)
        // Keep screen on at View level too
        findViewById<View>(android.R.id.content).keepScreenOn = true

        // Bind Views
        frameLayout = findViewById(R.id.rootLayout)
        cameraPreview = findViewById(R.id.cameraPreview)
        infoText = findViewById(R.id.infoText)
        statusLogScroll = findViewById(R.id.statusLogScroll)
        statusLogText = findViewById(R.id.statusLogText)
        positionOverlay = findViewById(R.id.positionOverlay)
        fullColorView = findViewById(R.id.fullColorView)
        circleContainer = findViewById(R.id.circleContainer)
        circleView = findViewById(R.id.circleView)
        imageView = findViewById(R.id.imageView)
        updatePanel = findViewById(R.id.updatePanel)
        idleSleepOverlay = findViewById(R.id.idleSleepOverlay)
        idleSleepController = IdleSleepController(
            schedule = { callback, delayMs -> frameLayout.postDelayed(callback, delayMs) },
            cancel = { callback -> frameLayout.removeCallbacks(callback) },
            onSleep = {
                idleSavedBrightness = window.attributes.screenBrightness
                idleSleepOverlay.visibility = View.VISIBLE
                idleSleepOverlay.bringToFront()
                val attributes = window.attributes
                attributes.screenBrightness = 0.01f
                window.attributes = attributes
                appUpdateController.refreshButton()
                FileLogger.log("Idle", "Waiting screen asleep; network remains available")
            },
            onWake = {
                val attributes = window.attributes
                attributes.screenBrightness = idleSavedBrightness
                window.attributes = attributes
                idleSleepOverlay.visibility = View.GONE
                appUpdateController.refreshButton()
            }
        )
        appUpdateController = AppUpdateController(
            this,
            findViewById(R.id.checkUpdateButton),
            findViewById(R.id.updateStatusText),
            savedInstanceState
        ) {
            !isTouchLocked && !idleSleepController.isSleeping &&
                updatePanel.visibility == View.VISIBLE
        }
        
        // Refresh layout on orientation/size change
        frameLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateCircleLayout()
            if (isPositionMode) {
                enterPositionMode()
            }
        }
        
        // Init ScaleGestureDetector
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isTouchLocked) return false
                if (currentPatternName == "Disk") {
                    // Check if this might be a double tap gesture instead of scale
                    // If scaling happens very briefly/small, it might be noise during a tap.
                    // But ScaleGestureDetector consumes events.
                    
                    val scaleFactor = detector.scaleFactor
                    // Filter small accidental scales during tap?
                    // If scale is close to 1.0, ignore?
                    // if (Math.abs(scaleFactor - 1.0f) < 0.05f) return true
                    
                    diskDiameterMm *= scaleFactor
                    diskDiameterMm = diskDiameterMm.coerceIn(5f, screenWidthMm)
                    updateCircleLayout()
                    return true
                }
                return false
            }
        })
        
        // Calculate screen size in mm
        val metrics = resources.displayMetrics
        screenDpiX = metrics.xdpi
        screenDpiY = metrics.ydpi
        screenWidthMm = metrics.widthPixels / screenDpiX * 25.4f
        screenHeightMm = metrics.heightPixels / screenDpiY * 25.4f
        
        // Default Disk Config (20mm, Center)
        diskDiameterMm = 20f
        diskOffsetYPercent = 0f
        updateCircleLayout()

        // Sensor Init
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // WakeLock Init
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PG:PartialWakeLock")
        
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PG:WifiLock")

        // Init History
        historyManager = HistoryManager(this)

        // Touch Gesture for History
        gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isTouchLocked) return false
                if (currentPatternName == "Disk") {
                    // Only allow moving if 2 fingers are used (e.g. during a scroll that isn't a scale?)
                    // Problem: ScaleGestureDetector consumes events.
                    // If 2 fingers are down, it's likely a Scale.
                    // Standard Android: Scroll with 2 fingers usually means drag if not pinching.
                    
                    // Check pointer count from events
                    val p1 = e1?.pointerCount ?: 0
                    val p2 = e2.pointerCount
                    
                    // Requirement: "Disable single finger vertical drag, only allow two finger slide"
                    if (p2 < 2) return false
                    
                    // Move disk - Y Axis Only
                    // Convert pixels to mm
                    // Use screen height in mm to calculate percentage change
                    val dyMm = distanceY / screenDpiY * 25.4f
                    val deltaPercent = dyMm / (screenHeightMm / 2f)
                    
                    diskOffsetYPercent -= deltaPercent
                    
                    // Clamp to [-1, 1]
                    diskOffsetYPercent = diskOffsetYPercent.coerceIn(-1.0f, 1.0f)
                    
                    updateCircleLayout()
                    return true
                }
                return false
            }

            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (isTouchLocked) return false

                // 1. Multi-touch Filter: Ignore if any event involved > 1 pointer
                if (e1 != null && e2 != null) {
                    if (e1.pointerCount > 1 || e2.pointerCount > 1) return false
                }
                
                // Also check our tracked max pointers
                if (maxPointersInGesture > 1) return false
                
                // 2. Latency Filter: Wait 100ms after Down event to ensure no second finger appeared
                // If the fling happened too quickly after down (likely a flick), accept it?
                // Or if it's too quick, maybe it's just noise?
                // User Requirement: "Wait 100ms after first finger signal... confirm no multi-touch"
                // e1 is the down event.
                // If (now - e1.time) < 100ms, and we are already flinging?
                // This means the user flicked incredibly fast.
                // But the requirement implies we should have waited to see if a 2nd finger lands.
                // Since we are here in onFling (ACTION_UP happened), the gesture is OVER.
                // So we just check if the total duration was long enough to have detected a 2nd finger?
                // Actually, if the user intended to pinch, the 2nd finger would likely arrive within 100ms of the 1st.
                // So if the gesture duration (e2.time - e1.time) < 100ms, it's risky?
                // Let's assume: If gesture was very short, it might be a false positive or a super fast flick.
                // But if we enforce > 100ms duration, super fast flicks will be ignored.
                
                // Let's look at the requirement: "Wait 100ms ... confirm no multi-touch"
                // If the entire gesture (Down -> Up) took < 100ms, we can't be sure a 2nd finger wasn't about to land.
                // So let's ignore gestures that are too short?
                // Or rather, if maxPointersInGesture never exceeded 1, AND duration > 100ms?
                
                val duration = (e2?.eventTime ?: 0) - (e1?.eventTime ?: 0)
                if (duration < 50) return false // Too short, maybe noise or incomplete pinch
                
                // 3. Direction Filter: Restrict to strictly Horizontal Fling
                if (Math.abs(velocityX) > Math.abs(velocityY) * 2 && Math.abs(velocityX) > 100) {
                    if (velocityX > 0) performUndo() else performRedo()
                    return true
                }
                return false
            }
        })
        
        gestureDetector.setIsLongpressEnabled(false)
        
        // Simple Optical Flow Gesture
        gestureAnalyzer = SimpleGestureAnalyzer { gesture ->
            if (isStreaming && !isTouchLocked) {
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, "Gesture: $gesture", android.widget.Toast.LENGTH_SHORT).show()
                    if (gesture == "SWIPE_RIGHT") performUndo()
                    else if (gesture == "SWIPE_LEFT") performRedo()
                }
            }
        }

        requestPermissions()
        
        // Init Remote Desktop (Lazy load)
        RemoteDesktopServer.init(this)
    }
    
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (::idleSleepController.isInitialized) {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                consumeIdleWakeGesture = idleSleepController.isSleeping
            }
            idleSleepController.userActivity()
            if (consumeIdleWakeGesture) {
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                    event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                    consumeIdleWakeGesture = false
                }
                // The first gesture wakes only; never click a hidden update/install button.
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (event == null) return super.onTouchEvent(event)

        // Track max pointers for this gesture sequence and detect the lock toggle first.
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                maxPointersInGesture = 1
                lastPointerDownTime = System.currentTimeMillis()
                cancelThreeFingerLongPress()
                threeFingerLongPressTriggered = false

                // Tap detection logic
                val currentTime = System.currentTimeMillis()
                if (currentTime - firstTapTime > DOUBLE_TAP_TIMEOUT) {
                    tapCount = 0
                }
                if (tapCount == 0) {
                    firstTapTime = currentTime
                }
                tapCount++
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointersInGesture = Math.max(maxPointersInGesture, event.pointerCount)

                if (event.pointerCount == 3) {
                    scheduleThreeFingerLongPress()
                } else if (event.pointerCount > 3) {
                    cancelThreeFingerLongPress()
                }
            }
            android.view.MotionEvent.ACTION_POINTER_UP,
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                cancelThreeFingerLongPress()

                // If 2 fingers were down, and one lifts, check if it was a tap.
                if (!isTouchLocked && event.pointerCount == 2 && maxPointersInGesture == 2) {
                    val duration = System.currentTimeMillis() - lastPointerDownTime
                    if (duration < 200) {
                        if (tapCount >= 2 && (System.currentTimeMillis() - lastDoubleTapTime < DOUBLE_TAP_TIMEOUT)) {
                            handleDoubleTwoFingerTap()
                            tapCount = 0
                        }
                        lastDoubleTapTime = System.currentTimeMillis()
                    }
                }
            }
            else -> {
                maxPointersInGesture = Math.max(maxPointersInGesture, event.pointerCount)
            }
        }

        if (isTouchLocked || threeFingerLongPressTriggered) {
            return true
        }

        // Always pass to ScaleDetector first
        scaleDetector.onTouchEvent(event)

        // If scaling is in progress, do NOT pass to GestureDetector
        if (scaleDetector.isInProgress) {
            return true
        }

        // Only pass to GestureDetector if not scaling
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun scheduleThreeFingerLongPress() {
        if (!::frameLayout.isInitialized) return
        cancelThreeFingerLongPress()
        threeFingerLongPressArmed = true
        threeFingerLongPressTriggered = false
        frameLayout.postDelayed(threeFingerLongPressRunnable, THREE_FINGER_LONG_PRESS_MS)
    }

    private fun cancelThreeFingerLongPress() {
        if (::frameLayout.isInitialized) {
            frameLayout.removeCallbacks(threeFingerLongPressRunnable)
        }
        threeFingerLongPressArmed = false
    }

    private fun setTouchLocked(locked: Boolean, source: String) {
        if (isTouchLocked == locked) return
        isTouchLocked = locked
        val message = if (locked) "Touch Locked" else "Touch Unlocked"
        FileLogger.log("LOCK", "$message by $source")
        appendStatusLog("$message by $source")
        runOnUiThread {
            appUpdateController.refreshButton()
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDoubleTwoFingerTap() {
         if (isTouchLocked) return
         if (currentPatternName == "Disk") {
             runOnUiThread {
                 val isCurrentlyDefault = diskDiameterMm == 20f && Math.abs(diskOffsetYPercent) < 0.001f
                 
                 if (isCurrentlyDefault) {
                     // Restore to saved state
                     diskDiameterMm = savedDiskDiameterMm
                     diskOffsetYPercent = savedDiskOffsetYPercent
                     android.widget.Toast.makeText(this, "Restored Custom Disk", android.widget.Toast.LENGTH_SHORT).show()
                 } else {
                     // Save current state and reset to default
                     savedDiskDiameterMm = diskDiameterMm
                     savedDiskOffsetYPercent = diskOffsetYPercent
                     diskDiameterMm = 20f
                     diskOffsetYPercent = 0f
                     android.widget.Toast.makeText(this, "Reset Disk (Saved Current)", android.widget.Toast.LENGTH_SHORT).show()
                 }
                 updateCircleLayout()
             }
         }
     }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        idleSleepController.setForeground(hasWindowFocus())
        enableImmersiveMode()
        gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::idleSleepController.isInitialized) {
            idleSleepController.setForeground(isActivityResumed && hasFocus)
        }
        if (hasFocus) enableImmersiveMode()
    }

    override fun onPause() {
        isActivityResumed = false
        idleSleepController.setForeground(false)
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCircleLayout()
        if (isPositionMode) {
            enterPositionMode()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        
        // Simple smoothing window check
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSensorTimestamp < sensorWindowSize) return
        lastSensorTimestamp = currentTime

        if (event.sensor.type == Sensor.TYPE_GRAVITY || event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            // Calculate Pitch and Roll
            // Roll: Rotation around Y axis
            // Pitch: Rotation around X axis
            roll = Math.toDegrees(atan2(x.toDouble(), sqrt((y * y + z * z).toDouble())))
            pitch = Math.toDegrees(atan2(y.toDouble(), sqrt((x * x + z * z).toDouble())))
            
            // Calculate gravity angle in screen plane (for cross rotation)
            // atan2(x, y) instead of -atan2(x, y) to reverse direction
            val angleRad = atan2(x.toDouble(), y.toDouble())
            
            if (isPositionMode) {
                positionOverlay.updateGravityAngle(angleRad, pitch)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateCircleLayout() {
        if (!::circleView.isInitialized) return
        
        runOnUiThread {
            // Update screen metrics in case of orientation change
            val metrics = resources.displayMetrics
            screenDpiX = metrics.xdpi
            screenDpiY = metrics.ydpi
            screenWidthMm = metrics.widthPixels / screenDpiX * 25.4f
            screenHeightMm = metrics.heightPixels / screenDpiY * 25.4f

            val params = circleView.layoutParams as FrameLayout.LayoutParams
            
            // Size
            circleSizePx = (diskDiameterMm * (screenDpiX / 25.4f)).toInt()
            params.width = circleSizePx
            params.height = circleSizePx
            
            // Position (TopLeft = Center - Radius)
            // Center is always ScreenCenter + OffsetY
            // X is always ScreenCenter (width/2)
            
            val screenCenterXPx = metrics.widthPixels / 2
            val screenCenterYPx = metrics.heightPixels / 2
            
            val offsetYPx = (diskOffsetYPercent * (metrics.heightPixels / 2f)).toInt()
            
            val cxPx = screenCenterXPx
            val cyPx = screenCenterYPx + offsetYPx
            
            params.leftMargin = cxPx - circleSizePx / 2
            params.topMargin = cyPx - circleSizePx / 2
            // Clear other margins to ensure absolute positioning
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            
            circleView.layoutParams = params
            circleView.requestLayout()
        }
    }
    
    // Legacy method redirection
    private fun updateCircleSizePx() {
        updateCircleLayout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        appUpdateController.saveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        idleSleepController.close()
        super.onDestroy()
        activeTcpSockets.close()
        multicastLock?.release()
    }

    private fun requestPermissions() {
        // Request Screen Capture permission if not granted
        // For simplicity, we launch the intent. But this needs to be user-initiated usually.
        // We will trigger it when we receive a specific command or via UI?
        // Requirement: "Integrate with MainActivity (Permission Request only)"
        // Let's add a button or just launch it on start for now (might be annoying).
        // Better: Wait for "rd_start" UDP command or similar?
        // But the requirement said "Protocol v3 - RD Only" is on 8889.
        // So RemoteDesktopServer starts listening on 8889 immediately.
        // But it needs MediaProjection.
        
        // Let's auto-start RD Server listening, but capture won't work until permission.
        RemoteDesktopServer.startServer()
        
        // We need an activity result launcher for Screen Capture
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val mp = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                RemoteDesktopServer.setMediaProjection(mp)
                FileLogger.log("RD", "MediaProjection Granted")
            } else {
                FileLogger.e("RD", "MediaProjection Denied")
            }
        }
        
        // Store launcher reference? Or just launch it?
        // Let's add a hidden trigger: Triple Tap on InfoText?
        infoText.setOnClickListener {
            // screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
        infoText.setOnLongClickListener {
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            true
        }
        
        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val camera = perms[Manifest.permission.CAMERA] ?: false
            FileLogger.log("Permissions", "Camera: $camera")
            
            if (camera) {
                startUdpListener()
            } else {
                FileLogger.e("Permissions", "Camera permission denied")
                infoText.text = "Camera Permission Denied!"
            }
        }
        launcher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun updateCircleSize() {
        if (!::circleView.isInitialized) return
        
        runOnUiThread {
            val params = circleView.layoutParams
            // Log.d("Disk", "Updating Size: $circleSizePx")
            if (params != null) {
                params.width = circleSizePx
                params.height = circleSizePx
                circleView.layoutParams = params
                circleView.requestLayout()
                circleContainer.invalidate() // Force redraw
            }
        }
    }

    private fun startUdpListener() {
        // Acquire Multicast Lock
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("PG_UDP_LOCK")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                var socket: DatagramSocket? = null
                try {
                    // Bind to 0.0.0.0 explicitly to listen on all interfaces
                    socket = DatagramSocket(null)
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.bind(java.net.InetSocketAddress(UDP_PORT))

                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)

                    showNetworkStatus("Waiting for UDP on $UDP_PORT...\nIP: ${getLocalIpAddress()}")
                    FileLogger.log("PG", "UDP Listener Started on $UDP_PORT")
                    appendStatusLog("UDP listening on 0.0.0.0:$UDP_PORT, local IP ${getLocalIpAddress()}")

                    while (isActive) {
                        try {
                            packet.length = buffer.size
                            socket.receive(packet)
                            val msg = String(packet.data, 0, packet.length).trim()
                            FileLogger.log("PG", "UDP Received: $msg")
                            appendStatusLog("UDP recv: $msg")
                            handleUdpMessage(msg)
                        } catch (e: java.net.SocketException) {
                            FileLogger.e("UDP", "Socket receive failed; rebinding UDP listener", e)
                            showNetworkStatus("UDP socket recovered: ${e.message}")
                            break
                        } catch (e: java.io.IOException) {
                            FileLogger.e("UDP", "UDP receive failed; rebinding UDP listener", e)
                            showNetworkStatus("UDP receive recovered: ${e.message}")
                            break
                        } catch (e: Exception) {
                            FileLogger.e("UDP", "Bad UDP packet ignored", e)
                            appendStatusLog("UDP bad packet ignored: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    FileLogger.e("PG", "UDP bind/listen failed; retrying", e)
                    showNetworkStatus("UDP retrying: ${e.message}")
                } finally {
                    socket?.close()
                }

                if (isActive) {
                    appendStatusLog("UDP listener rebinding in 1s")
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    private fun handleUdpMessage(msg: String) {
        if (msg.startsWith("hello_pg_0927:")) {
            val parts = msg.split(":")
            if (parts.size >= 3) {
                val targetIp = parts[1]
                val targetPort = parts[2].toIntOrNull()
                if (targetIp.isNotBlank() && targetPort != null && targetPort in 1..65535) {
                    FileLogger.log("UDP", "Handshake received. Connecting to $targetIp:$targetPort")
                    showNetworkStatus("UDP handshake -> TCP connecting $targetIp:$targetPort")
                    connectTcp(targetIp, targetPort)
                } else {
                    FileLogger.log("UDP", "Ignored bad handshake port: $msg")
                    appendStatusLog("UDP ignored bad handshake port: $msg")
                }
            }
        } else {
            // UDP is only for discovery; display commands must arrive over TCP.
            FileLogger.log("UDP", "Ignored non-handshake message: $msg")
            appendStatusLog("UDP ignored non-handshake message: $msg")
        }
    }

    private fun connectTcp(ip: String, port: Int) {
        // Each controller has its own socket and read loop, even when ports match.
        lifecycleScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                withContext(Dispatchers.Main) {
                    val count = activeTcpSockets.size + 1
                    infoText.text = "Connecting TCP ($count)...\n$ip:$port"
                    infoText.visibility = View.VISIBLE
                    statusLogScroll.visibility = View.VISIBLE
                }
                appendStatusLog("TCP connecting to $ip:$port", showPanel = true)
                
                socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 5_000)
                
                // Only reconnects to the exact same endpoint replace an existing session.
                // Register first so the old reader cannot observe an empty connection set.
                val existing = activeTcpSockets.add(socket)
                if (existing != null) {
                    FileLogger.log("TCP", "Replacing connection to $ip:$port")
                    appendStatusLog("TCP replacing connection to $ip:$port")
                    try { existing.close() } catch(e: Exception){}
                }

                isTcpMode = true
                FileLogger.log("TCP", "Connected to $ip:$port. Total: ${activeTcpSockets.size}")
                appendStatusLog("TCP connected to $ip:$port, total ${activeTcpSockets.size}", showPanel = true)
                
                withContext(Dispatchers.Main) {
                    infoText.text = "Connected TCP (${activeTcpSockets.size})\n$ip:$port"
                    infoText.visibility = View.VISIBLE 
                    statusLogScroll.visibility = View.VISIBLE
                }

                val inputStream = socket.getInputStream()
                
                while (isActive && socket.isConnected) {
                    val line = readLineFromStream(inputStream) ?: break
                    FileLogger.log("TCP", "Recv from $ip:$port: $line")
                    appendStatusLog("TCP recv $ip:$port: $line")
                    handleTcpCommand(line, inputStream, socket)
                }
                
            } catch (e: Exception) {
                FileLogger.e("PG", "TCP Error ($ip:$port)", e)
                appendStatusLog("TCP error $ip:$port ${e.javaClass.simpleName}: ${e.message}", showPanel = true)
            } finally {
                socket?.let { 
                    try { it.close() } catch(e: Exception){} 
                    activeTcpSockets.remove(it)
                    
                    if (streamTargetSocket == it) {
                        FileLogger.log("TCP", "Stream owner disconnected; stopping stream")
                        appendStatusLog("TCP stream owner disconnected; stopping stream")
                        streamTargetSocket = null
                        StreamServer.stop()
                        withContext(Dispatchers.Main) {
                            isStreaming = false
                            if (currentPatternName == "CameraStream") {
                                currentPatternName = "None"
                                currentColorInfo = "N/A"
                                isPositionMode = false
                            }
                            stopCameraStream()
                        }
                    }
                }
                
                FileLogger.log("TCP", "Disconnected $ip:$port. Remaining: ${activeTcpSockets.size}")
                appendStatusLog("TCP disconnected $ip:$port. Remaining: ${activeTcpSockets.size}", showPanel = true)
                
                withContext(Dispatchers.Main) {
                    if (activeTcpSockets.isEmpty()) {
                        isTcpMode = false
                        infoText.text = "All TCP Disconnected\nUDP listening on $UDP_PORT\nIP: ${getLocalIpAddress()}"
                        
                        // Cleanup all streaming
                        StreamServer.stop()
                        isStreaming = false
                        isPositionMode = false
                        currentPatternName = "None"
                        currentColorInfo = "N/A"
                        stopCameraStream()
                        resetUi()
                        infoText.visibility = View.VISIBLE
                        statusLogScroll.visibility = View.VISIBLE
                        
                    } else {
                        infoText.text = "Connected TCP (${activeTcpSockets.size})"
                        statusLogScroll.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun readLineFromStream(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        try {
            var b = input.read()
            while (b != -1) {
                if (b == '\n'.code) return baos.toString("UTF-8").trim()
                baos.write(b)
                b = input.read()
            }
        } catch(e: Exception) { return null }
        return if (baos.size() > 0) baos.toString("UTF-8").trim() else null
    }

    private suspend fun handleTcpCommand(cmdLine: String, input: InputStream, socket: Socket) {
        val cmd = commandParser.parse(cmdLine)
        // Receive uploads before taking the shared display gate: a slow sender must
        // not block the other controllers while it transfers the image.
        val imageBitmap = if (cmd is Command.ImageHeader) {
            val imgData = ByteArray(cmd.size)
            var totalRead = 0
            while (totalRead < imgData.size) {
                val read = input.read(imgData, totalRead, imgData.size - totalRead)
                if (read == -1) throw java.io.EOFException("Incomplete image payload")
                totalRead += read
            }
            requireNotNull(BitmapFactory.decodeByteArray(imgData, 0, imgData.size)) {
                "Invalid image payload"
            }
        } else null

        commandMutex.withLock {
            // Shared by every TCP client. Suspending here keeps the main/UI thread free.
            brightnessSettler.awaitReady()
            applyTcpCommand(cmd, cmdLine, socket, imageBitmap)
        }
    }

    // Called on Main while holding commandMutex. Keeping the before/apply/after
    // snapshot in one UI turn prevents an idle timeout racing the brightness check.
    private fun applyDisplayChange(change: () -> Unit) {
        val previousBrightness = window.attributes.screenBrightness
        change()
        brightnessSettler.brightnessApplied(previousBrightness, window.attributes.screenBrightness)
    }

    private suspend fun applyTcpCommand(
        cmd: Command,
        cmdLine: String,
        socket: Socket,
        imageBitmap: android.graphics.Bitmap?
    ) {
         when (cmd) {
             is Command.ImageHeader -> {
                 withContext(Dispatchers.Main) {
                   applyDisplayChange {
                     // Save current state to history before switching to image
                     historyManager.saveState(currentPatternName, currentColorInfo)
                     isPositionMode = false
                     exitPositionMode()
                     currentPatternName = "Image:${cmd.name}"
                     currentColorInfo = "N/A"
                     showImage(requireNotNull(imageBitmap))
                   }
                 }
             }
             is Command.StreamStart -> {
                // Negotiation: Start Stream Server and return port
                val port = StreamServer.start()
                if (port > 0) {
                    streamTargetSocket = socket
                    val response = "stream_ready:$port\n"
                    withContext(Dispatchers.IO) {
                        val out = socket.getOutputStream()
                        out.write(response.toByteArray())
                        out.flush()
                    }
                    FileLogger.log("TCP", "Stream negotiated on port $port")
                    
                    withContext(Dispatchers.Main) {
                      applyDisplayChange {
                        // Streaming is active work even while its status panel is visible.
                        isStreaming = true
                        isPositionMode = false
                        exitPositionMode()
                        currentPatternName = "CameraStream"
                        currentColorInfo = "N/A"
                        idleSleepController.setIdle(false)
                        updatePanel.visibility = View.GONE
                        appUpdateController.refreshButton()
                        val toast = android.widget.Toast.makeText(this@MainActivity, "Stream Starting...", android.widget.Toast.LENGTH_SHORT)
                        toast.show()
                        infoText.text = "Stream Ready on $port"
                        infoText.visibility = View.VISIBLE
                      }
                    }
                    
                    // Start Camera Analysis
                    withContext(Dispatchers.Main) {
                        startCameraStream()
                    }
                } else {
                    FileLogger.e("TCP", "Failed to start stream server")
                    val response = "stream_error:bind_failed\n"
                    withContext(Dispatchers.IO) {
                        val out = socket.getOutputStream()
                        out.write(response.toByteArray())
                        out.flush()
                    }
                }
            }
            is Command.StreamStop -> {
                FileLogger.log("TCP", "Stream Stop requested from ${socket.inetAddress}")
                // Only owner can stop
                if (streamTargetSocket == socket) {
                    streamTargetSocket = null
                    StreamServer.stop()
                    withContext(Dispatchers.Main) {
                        isStreaming = false
                        isPositionMode = false
                        currentPatternName = "None"
                        currentColorInfo = "N/A"
                        stopCameraStream()
                    }
                } else {
                    FileLogger.log("TCP", "Ignored Stop from non-owner")
                }
            }
            is Command.BasicConfig -> {
                val response = "${screenWidthMm},${screenHeightMm},1.0\n"
                withContext(Dispatchers.IO) {
                    socket.getOutputStream().write(response.toByteArray())
                }
                FileLogger.log("TCP", "Sent config: $response")
            }
            is Command.GetDiskProperty -> {
                // Return Absolute X, Y for compatibility
                val absX = screenWidthMm / 2f
                val absY = screenHeightMm / 2f + (diskOffsetYPercent * (screenHeightMm / 2f))
                val response = "${diskDiameterMm},${absX},${absY}\n"
                 withContext(Dispatchers.IO) {
                    socket.getOutputStream().write(response.toByteArray())
                }
            }
            is Command.SetDiskProperty -> {
                // Legacy: diameter, centerX, centerY
                // We calculate OffsetPercent from absolute Y.
                // OffsetY = AbsY - ScreenHeight/2
                
                diskDiameterMm = cmd.diameterMm
                val offsetYMm = cmd.centerY - (screenHeightMm / 2f)
                diskOffsetYPercent = offsetYMm / (screenHeightMm / 2f)
                diskOffsetYPercent = diskOffsetYPercent.coerceIn(-1.0f, 1.0f)
                
                withContext(Dispatchers.Main) {
                    updateCircleLayout()
                }
                
                // Response: Diameter, AbsX, AbsY
                val absX = screenWidthMm / 2f
                val absY = screenHeightMm / 2f + (diskOffsetYPercent * (screenHeightMm / 2f))
                
                val response = "${diskDiameterMm},${absX},${absY}\n"
                 withContext(Dispatchers.IO) {
                    socket.getOutputStream().write(response.toByteArray())
                }
            }
            is Command.ResetPos -> {
                diskDiameterMm = 20f
                diskOffsetYPercent = 0f
                withContext(Dispatchers.Main) {
                    updateCircleLayout()
                }
            }
            is Command.GetPositionAngle -> {
                // Return Pitch and Yaw (Mapped from internal Roll)
                val response = "${String.format("%.2f", pitch)},${String.format("%.2f", roll)}\n"
                withContext(Dispatchers.IO) {
                    socket.getOutputStream().write(response.toByteArray())
                }
            }
            is Command.GetStatus -> {
                // Format: PatternName,Color(R,G,B),Pitch,Yaw,Diameter,PosPercentageY,Brightness
                // PosPercentageY: -100% (Top) to 100% (Bottom). 0% is Center.
                val response = withContext(Dispatchers.Main) {
                    val percentY = diskOffsetYPercent * 100f
                    val brightness = (window.attributes.screenBrightness * 100f).coerceAtLeast(0f)
                    "$currentPatternName,$currentColorInfo,${String.format("%.2f", pitch)},${String.format("%.2f", roll)},${String.format("%.2f", diskDiameterMm)},0.00,${String.format("%.2f", percentY)},${String.format("%.2f", brightness)}\n"
                }
                withContext(Dispatchers.IO) {
                    socket.getOutputStream().write(response.toByteArray())
                }
            }
            is Command.GetBrightnessSettleMs -> {
                socket.getOutputStream().write(
                    "brightness_settle_ms:${brightnessSettler.delayMs}\n".toByteArray()
                )
            }
            is Command.SetBrightnessSettleMs -> {
                brightnessSettler.configure(cmd.delayMs)
                socket.getOutputStream().write(
                    "brightness_settle_ms:${brightnessSettler.delayMs}\n".toByteArray()
                )
            }
            is Command.InvalidBrightnessSettleMs -> {
                socket.getOutputStream().write(
                    "error:brightness_settle_ms_must_be_integer_0_to_60000\n".toByteArray()
                )
            }
            is Command.Lock -> {
                withContext(Dispatchers.Main) {
                    setTouchLocked(true, "TCP")
                }
            }
            is Command.Unlock -> {
                withContext(Dispatchers.Main) {
                    setTouchLocked(false, "TCP")
                }
            }
            is Command.Sleep -> {
                FileLogger.log("CMD", "Sleep Requested")
                withContext(Dispatchers.Main) {
                  applyDisplayChange {
                    // Restore pre-idle brightness before saving the explicit sleep state.
                    idleSleepController.setIdle(false)
                    if (!isSleeping) {
                        sleepSavedPatternState = PatternState(currentPatternName, currentColorInfo)
                        sleepSavedBrightness = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 1.0f
                    }
                    isSleeping = true

                    showFullColor(Color.BLACK)
                    
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val params = window.attributes
                    params.screenBrightness = 0.01f // Dim as much as possible
                    window.attributes = params
                    
                    // Acquire locks to keep CPU/WiFi alive
                    if (wakeLock?.isHeld != true) wakeLock?.acquire(24*60*60*1000L)
                    if (wifiLock?.isHeld != true) wifiLock?.acquire()
                    
                    infoText.text = "Sleeping (Network Active)"
                    infoText.visibility = View.VISIBLE
                  }
                }
                // We do NOT stop the TCP server.
            }
            is Command.Wake -> {
                FileLogger.log("CMD", "Wake Requested")
                withContext(Dispatchers.Main) {
                  applyDisplayChange {
                    if (!isSleeping) {
                        idleSleepController.wake()
                        return@applyDisplayChange
                    }
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val params = window.attributes
                    params.screenBrightness = sleepSavedBrightness.coerceIn(0.01f, 1.0f)
                    window.attributes = params
                    
                    if (wakeLock?.isHeld == true) try { wakeLock?.release() } catch(e:Exception){}
                    if (wifiLock?.isHeld == true) try { wifiLock?.release() } catch(e:Exception){}
                    
                    val state = sleepSavedPatternState
                    isSleeping = false
                    sleepSavedPatternState = null
                    if (state != null) {
                        currentPatternName = state.name
                        currentColorInfo = state.info
                        restoreCurrentPatternUi()
                    } else {
                        // Fallback
                        infoText.text = "Waked Up"
                        resetUi(infoText)
                    }
                  }
                }
            }
            is Command.SetPositionMode -> {
                withContext(Dispatchers.Main) {
                    applyDisplayChange {
                        isPositionMode = true
                        currentPatternName = "PositionMode"
                        currentColorInfo = "N/A"
                        enterPositionMode()
                    }
                }
            }
            else -> {
                // Any other command exits position mode
                if (isPositionMode) {
                    isPositionMode = false
                    withContext(Dispatchers.Main) {
                        exitPositionMode()
                    }
                }
                // Await application on the UI thread before starting the settling clock.
                withContext(Dispatchers.Main) {
                    applyDisplayChange { handleCommand(cmdLine) }
                }
            }
        }
    }

    private fun enterPositionMode() {
        idleSleepController.setIdle(false)
        updatePanel.visibility = View.GONE
        appUpdateController.refreshButton()
        val cx = screenWidthMm / 2f
        val cy = screenHeightMm / 2f + (diskOffsetYPercent * (screenHeightMm / 2f))
        positionOverlay.setCenter(
            (cx * screenDpiX / 25.4f),
            (cy * screenDpiY / 25.4f)
        )
        positionOverlay.visibility = View.VISIBLE
        // Hide other pattern views if necessary, or just overlay
        // Assuming positionOverlay is on top (z-order)
    }

    private fun exitPositionMode() {
        positionOverlay.visibility = View.GONE
    }

    private fun handleCommand(cmdLine: String) {
        // Save state before changing
        historyManager.saveState(currentPatternName, currentColorInfo)
        
        val cmd = commandParser.parse(cmdLine)
        FileLogger.log("CMD", "Handling: $cmdLine -> $cmd")
        runOnUiThread {
            when (cmd) {
                is Command.SetColor -> {
                idleSleepController.setIdle(false)
                currentPatternName = "FullColor"
                currentColorInfo = "${cmd.r},${cmd.g},${cmd.b}"
                // Apply brightness if provided
                if (cmd.brightness != null) {
                    val params = window.attributes
                    params.screenBrightness = cmd.brightness / 100f
                    window.attributes = params
                }
                showFullColor(Color.rgb(cmd.r, cmd.g, cmd.b))
            }
            is Command.SetCircleColor -> {
                idleSleepController.setIdle(false)
                currentPatternName = "Disk"
                currentColorInfo = "${cmd.r},${cmd.g},${cmd.b}"
                // Apply brightness if provided
                if (cmd.brightness != null) {
                    val params = window.attributes
                    params.screenBrightness = cmd.brightness / 100f
                    window.attributes = params
                }
                showCircle(Color.rgb(cmd.r, cmd.g, cmd.b))
            }
                is Command.ImageHeader -> {
                     currentPatternName = "Image:${cmd.name}"
                     currentColorInfo = "N/A"
                }
                // StreamStart/Stop handled in handleTcpCommand
                else -> {}
            }
        }
    }
    
    private fun performUndo() {
        val state = historyManager.undo()
        applyState(state, "Undo")
    }

    private fun performRedo() {
        val state = historyManager.redo()
        applyState(state, "Redo")
    }

    private fun applyState(state: PatternState?, action: String) {
        if (state != null) {
            currentPatternName = state.name
            currentColorInfo = state.info
            runOnUiThread {
                restoreCurrentPatternUi()
                android.widget.Toast.makeText(this, "$action: ${state.name}", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
             runOnUiThread {
                val reason = if (action == "Redo") "Already at Newest" else "Already at Oldest"
                android.widget.Toast.makeText(this, "$reason ($action Failed)", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // REMOVED restoreState() as it is replaced by performUndo/Redo

    private fun restoreCurrentPatternUi() {
        // History restoration and returning to idle must not retain an old mode flag.
        isPositionMode = currentPatternName == "PositionMode"
        // Restore UI based on currentPatternName
        when {
            currentPatternName == "FullColor" -> {
                val parts = currentColorInfo.split(",")
                if (parts.size == 3) {
                    showFullColor(Color.rgb(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()))
                }
            }
            currentPatternName == "Disk" -> {
                val parts = currentColorInfo.split(",")
                if (parts.size == 3) {
                    showCircle(Color.rgb(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()))
                }
            }
            currentPatternName.startsWith("Image") -> {
                imageView.visibility = View.VISIBLE
                resetUi(except = imageView)
            }
            currentPatternName == "PositionMode" -> {
                positionOverlay.visibility = View.VISIBLE
                resetUi(except = positionOverlay)
            }
            else -> {
                // If None, show InfoText
                infoText.visibility = View.VISIBLE
                resetUi(except = infoText)
            }
        }
    }

    private fun resetUi(except: View? = null) {
        val idle = (except == null || except == infoText) &&
            !isSleeping && !isStreaming && !isPositionMode
        idleSleepController.setIdle(idle)
        updatePanel.visibility = if (idle) View.VISIBLE else View.GONE
        appUpdateController.refreshButton()
        if (except != infoText) infoText.visibility = View.GONE
        if (except != infoText) statusLogScroll.visibility = View.GONE
        if (except != fullColorView) fullColorView.visibility = View.GONE
        if (except != circleContainer) circleContainer.visibility = View.GONE
        if (except != imageView) imageView.visibility = View.GONE
        if (except != positionOverlay) positionOverlay.visibility = View.GONE
        // Do NOT hide cameraPreview here, as it needs to run in background
    }

    private fun showFullColor(color: Int) {
        resetUi(except = fullColorView)
        fullColorView.setBackgroundColor(color)
        fullColorView.visibility = View.VISIBLE
    }

    private fun showCircle(color: Int) {
        resetUi(except = circleContainer)
        circleContainer.visibility = View.VISIBLE
        (circleView.background as android.graphics.drawable.GradientDrawable).setColor(color)
    }

    private fun showImage(bitmap: android.graphics.Bitmap) {
        resetUi(except = imageView)
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
    }

    private fun startCameraStream() {
        val generation = ++cameraSessionGeneration
        streamStartTime = System.currentTimeMillis()
        FileLogger.log("Camera", "Starting Camera Stream")
        // Ensure preview is attached but hidden/behind
        runOnUiThread {
            cameraPreview.visibility = View.VISIBLE
            frameLayout.removeView(cameraPreview)
            frameLayout.addView(cameraPreview, 0)
            
            // Force UI update to bring pattern to front
            restoreCurrentPatternUi()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            // A stop or newer start can arrive before CameraX finishes initializing.
            if (generation != cameraSessionGeneration || !isStreaming || isDestroyed) {
                return@addListener
            }
            try {
                val cameraProvider = cameraProviderFuture.get()
                initializedCameraProvider = cameraProvider
                
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(cameraPreview.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    // Ensure output is rotated to match device orientation
                    .setOutputImageRotationEnabled(true)
                    .build()

                var lastFrameTime = 0L
                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
                    // 1. Streaming (Priority)
                    if (isStreaming) {
                        // Send Frame (Synchronous)
                        if (System.currentTimeMillis() - lastFrameTime >= 66) { // ~15 FPS
                            StreamServer.sendFrame(image)
                            lastFrameTime = System.currentTimeMillis()
                        }
                        
                        // Pass to Gesture Analyzer (It will close image if not handled? No, analyze closes it)
                        // StreamServer extracts bytes, doesn't close.
                        gestureAnalyzer?.analyze(image)
                    } else {
                        // Not streaming, just close
                        image.close()
                    }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, 
                    CameraSelector.DEFAULT_FRONT_CAMERA, 
                    preview, 
                    imageAnalysis
                )
                
                // Auto Exposure / ISO (Attempt)
                try {
                    val cameraControl = cameraProvider.bindToLifecycle(
                        this, 
                        CameraSelector.DEFAULT_FRONT_CAMERA, 
                        preview, 
                        imageAnalysis
                    ).cameraControl
                    
                    cameraControl.cancelFocusAndMetering()
                } catch(e: Exception) {}
                
                FileLogger.log("Camera", "Camera Bound Successfully")
            } catch(e: Exception) {
                FileLogger.e("PG", "Camera Bind Failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraStream() {
        runOnUiThread {
            cameraSessionGeneration++
            try {
                // Do not wait for CameraX initialization on Main. Pending starts
                // are invalidated above; only an initialized provider can be bound.
                initializedCameraProvider?.unbindAll()
                FileLogger.log("Camera", "Camera Stopped")
            } catch (e: Exception) {
                FileLogger.e("Camera", "Camera stop failed", e)
            } finally {
                cameraPreview.visibility = View.GONE
                // If stream stops, ensure we restore the pattern UI (in case it was hidden)
                restoreCurrentPatternUi()
            }
        }
    }

    // REMOVED sendFrame method. Using StreamServer.sendFrame directly.

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun showNetworkStatus(message: String) {
        appendStatusLog(message, showPanel = true)
        runOnUiThread {
            if (::infoText.isInitialized) {
                infoText.text = message
                infoText.visibility = View.VISIBLE
            }
            if (::statusLogScroll.isInitialized) {
                statusLogScroll.visibility = View.VISIBLE
            }
        }
    }

    private fun appendStatusLog(message: String, showPanel: Boolean = false) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val line = "$timestamp $message"
        val text = synchronized(statusLogLines) {
            statusLogLines.addLast(line)
            while (statusLogLines.size > maxStatusLogLines) {
                statusLogLines.removeFirst()
            }
            statusLogLines.joinToString("\n")
        }

        runOnUiThread {
            if (::statusLogText.isInitialized) {
                statusLogText.text = text
            }
            if (showPanel && ::statusLogScroll.isInitialized) {
                statusLogScroll.visibility = View.VISIBLE
            }
            if (::statusLogScroll.isInitialized && statusLogScroll.visibility == View.VISIBLE) {
                statusLogScroll.post {
                    statusLogScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }
    
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) { }
        return "Unknown"
    }
}
