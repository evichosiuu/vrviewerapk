package com.example.vrviewer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import android.bluetooth.BluetoothDevice
import android.os.Build

class MainActivity : AppCompatActivity() {

    private var vrSender:       ITrackingSender?      = null
    private var cameraManager:  CameraPreviewManager? = null
    private var gamepadManager: GamepadManager?       = null

    private var joyConHid: JoyConHidManager? = null
    private var pcDiscovery:    PcDiscovery?          = null


    private var joyconDebugCounter = 0


    private var sixDofTracker: SixDofTracker? = null
    private var arCoreAvailable = false
    private var arCoreInstallRequested = false
    private var supportsConcurrentCameras = false


    private var sixDofHandMode = SixDofHandMode.NONE


    private var faceEyeTracker: FaceEyeTracker? = null

    private var vrStreamReceiver: VrStreamReceiver? = null
    private var streamActive = false


    private var vrAudioReceiver: VrAudioReceiver? = null


    private lateinit var glRenderer: StereoGLRenderer
    private var glInputSurface: android.view.Surface? = null


    private lateinit var parallax: ParallaxMenuController


    private lateinit var rootLayout: FrameLayout

    private var hubBrowser: HubBrowserView? = null


    private lateinit var connectPanel:           LinearLayout
    private lateinit var connectBackgroundImage: ImageView
    private lateinit var statusText:             TextView
    private lateinit var languageToggleButton:   Button


    private lateinit var tutorialButton:     Button
    private lateinit var tutorialOverlay:    FrameLayout
    private lateinit var tutorialVideoView:  VideoView
    private lateinit var skipTutorialButton: Button

    // ── Menú de Controles de Manos (Inicio) ──
    private lateinit var handControlsButton:      Button
    private lateinit var handControlsOverlay:     FrameLayout
    private lateinit var closeHandControlsButton: Button

    // ── Menú Hub (standalone) ──
    private lateinit var hubMenuButton: Button
    private var hubModeActive = false
    private lateinit var hubWindowController: HubWindowController
    private lateinit var hubPointerController: HubPointerController
    private var lastHubGrabbedState = false
    private var pendingHubStartAfterArCoreInstall = false
    private var pendingHubStartAfterSurface = false
    private var screenTouchTracking = false

    // ── Configuración / Monitor de Manos en Menú Hub (Estilo SteamVR) ──
    private lateinit var hubSettingsButton:        Button
    private lateinit var hubSettingsPanel:         LinearLayout
    private lateinit var closeHubSettingsButton:   Button
    private lateinit var hubLeftHandStatusText:    TextView
    private lateinit var hubLeftPinchText:         TextView
    private lateinit var hubLeftGripText:          TextView
    private lateinit var hubLeftButtonsText:       TextView
    private lateinit var hubRightHandStatusText:   TextView
    private lateinit var hubRightPinchText:        TextView
    private lateinit var hubRightGripText:         TextView
    private lateinit var hubRightButtonsText:      TextView
    private lateinit var switchHubSkeletonOverlay: Switch
    @Volatile private var hubSettingsPanelVisible = false
    @Volatile private var hubSkeletonOverlayEnabled = false

    // ── SBS VIDEO ──
    private lateinit var sbsVideoButton: Button
    private lateinit var videoPlayPauseButton: Button
    private var localVideoPlayer: MediaPlayer? = null
    private var isPlayingLocalVideo = false
    private var pendingSbsVideoUri: Uri? = null

    private var videoHeadTracker: GyroVideoWindowTracker? = null

    private val pickSbsVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { playLocalSbsVideo(it) }
        }

    // ── Tab WiFi ──
    private lateinit var tabWifi:         Button
    private lateinit var tabCable:        Button
    private lateinit var wifiTabContent:  LinearLayout
    private lateinit var cableTabContent: LinearLayout
    private lateinit var ipInput:         EditText
    private lateinit var scanButton:      Button
    private lateinit var pcListLayout:    LinearLayout
    private lateinit var scanStatusText:  TextView

    // ── Tab Cable ──
    private lateinit var cableIpInput:       EditText
    private lateinit var connectCableButton: Button
    private lateinit var usbStatusText:      TextView
    private lateinit var usbStatusDot:       TextView
    private lateinit var refreshUsbButton:   Button

    // ── Vistas: panel de control ──
    private lateinit var controlPanel:         LinearLayout
    private lateinit var controlPanelGrabberZone: FrameLayout
    private lateinit var controlScrollView:    ScrollView
    private lateinit var switchHmd:            Switch
    private lateinit var switchHand:           Switch
    private lateinit var handTrackingSubPanel: LinearLayout
    private lateinit var switchSixDof:         Switch
    private lateinit var sixDofSubPanel:       LinearLayout
    private lateinit var sixDofStatusText:     TextView
    private lateinit var sixDofModeGroup:      RadioGroup
    private lateinit var sixDofModePlain:      RadioButton
    private lateinit var sixDofModeLeds:       RadioButton
    private lateinit var sixDofModeHandTracking: RadioButton
    private lateinit var sixDofModeHandJoycons:  RadioButton
    private lateinit var switchCameraOverlay:  Switch
    private lateinit var switchEyeTracking:    Switch
    private lateinit var eyeTrackingStatusText: TextView
    private lateinit var trackingModeGroup:    RadioGroup
    private lateinit var recenterButton:       Button
    private lateinit var controlStatusText:    TextView
    private lateinit var gamepadStatusText:    TextView
    private lateinit var streamButton:         Button
    private lateinit var connectionTypeBadge:  TextView


    private lateinit var sensDownButton: Button
    private lateinit var sensUpButton:   Button
    private lateinit var sensLabelText:  TextView

    private lateinit var handZoomDownButton: Button
    private lateinit var handZoomUpButton:   Button
    private lateinit var handZoomLabelText:  TextView

    private lateinit var spinnerTrackingCamera: Spinner
    private var availableTrackingCameras: List<TrackingCameraOption> = emptyList()
    private var selectedCameraIndex = 0


    private lateinit var cameraOverlayPanel: FrameLayout
    private lateinit var cameraPreviewView:  PreviewView
    private lateinit var skeletonOverlay:    SkeletonOverlayView


    private lateinit var streamPanel:             FrameLayout
    private lateinit var streamGLSurfaceView:     GLSurfaceView
    private lateinit var streamLoadingOverlay:    LinearLayout
    private lateinit var streamStatusOverlayText: TextView
    private lateinit var closeStreamButton:       Button
    private lateinit var recenterHmdButton:       Button

    private lateinit var qualityToggleButton: Button
    private lateinit var streamQualityPanel:  LinearLayout
    private lateinit var resDownButton:       Button
    private lateinit var resUpButton:         Button
    private lateinit var resLabelText:        TextView
    private lateinit var brDownButton:        Button
    private lateinit var brUpButton:          Button
    private lateinit var brLabelText:         TextView
    private lateinit var fpsDownButton:       Button
    private lateinit var fpsUpButton:         Button
    private lateinit var fpsLabelText:        TextView


    private lateinit var lensToggleButton: Button
    private lateinit var streamLensPanel:  LinearLayout
    private lateinit var distDownButton:   Button
    private lateinit var distUpButton:     Button
    private lateinit var distLabelText:    TextView
    private lateinit var sepDownButton:    Button
    private lateinit var sepUpButton:      Button
    private lateinit var sepLabelText:     TextView
    private lateinit var zoomDownButton:   Button
    private lateinit var zoomUpButton:     Button
    private lateinit var zoomLabelText:    TextView
    private lateinit var fullscreenVideoButton: Button
    private lateinit var envModeVideoButton: Button

    private lateinit var videoDistRow:        LinearLayout
    private lateinit var videoDistDownButton: Button
    private lateinit var videoDistUpButton:   Button
    private lateinit var videoDistLabelText:  TextView


    private var currentTrackingMode = TrackingMode.NONE
    private val discoveredPcs       = LinkedHashMap<String, String>()
    private var connectedPcIp: String = ""
    private var connectionType: ConnectionType = ConnectionType.WIFI

    private var cableAutoMode: ConnectionType = ConnectionType.CABLE


    private var connectingInProgress = false

    enum class ConnectionType { WIFI, CABLE, USB_ADB }

    private data class QualityPreset(val label: String, val w: Int, val h: Int)
    private val RES_PRESETS = listOf(
        QualityPreset("Baja (854×480)", 854, 480),
        QualityPreset("Media (1280×720)", 1280, 720),
        QualityPreset("Alta (1600×900)", 1600, 900),
        QualityPreset("Full HD (1920×1080)", 1920, 1080)
    )
    private var resIndex = 1

    private var bitrateMbps = 6.0f
    private val BITRATE_MIN = 2.0f
    private val BITRATE_MAX = 150.0f

    private val FPS_PRESETS = listOf(30, 45, 60, 72, 90)
    private var fpsIndex = 0

    private val DIST_STEP = 0.02f
    private val DIST_MIN  = 0.0f
    private val DIST_MAX  = 0.6f
    private val SEP_STEP  = 0.01f
    private val SEP_MIN   = -0.2f
    private val SEP_MAX   = 0.2f
    private val ZOOM_STEP = 0.05f
    private val ZOOM_MIN  = 0.5f
    private val ZOOM_MAX  = 2.0f
    private var lensPanelVisible = false

    private val VIDEO_DIST_STEP = 0.1f
    private val VIDEO_DIST_MIN  = 0.5f
    private val VIDEO_DIST_MAX  = 2.5f

    private val SENS_STEP = 0.1f
    private val SENS_MIN  = 0.25f
    private val SENS_MAX  = 3.0f
    private var sixDofSensitivity = 1.0f

    private val HAND_ZOOM_STEP = 0.25f
    private val HAND_ZOOM_MIN  = 1.0f
    private val HAND_ZOOM_MAX  = 4.0f
    private var handCameraZoom = 1.0f

    private lateinit var controlPanelGestureDetector: GestureDetector
    private var controlPanelVisible = true

    private var qualityPanelVisible = false

    private var streamControlsVisible = true


    private var swipeIgnoredForCurrentGesture = false
    private val swipeHitRect = Rect()

    private var pendingCameraAction: (() -> Unit)? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingCameraAction?.invoke()
        else showStatus("Permiso de cámara denegado")
        pendingCameraAction = null
    }

    private val SKELETON_CONNECTIONS = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        0 to 9, 9 to 10, 10 to 11, 11 to 12,
        0 to 13, 13 to 14, 14 to 15, 15 to 16,
        0 to 17, 17 to 18, 18 to 19, 19 to 20,
        5 to 9, 9 to 13, 13 to 17
    )

    private val paintLeftLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ade80")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintRightLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60a5fa")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintDotLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ade80")
        style = Paint.Style.FILL
    }
    private val paintDotRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60a5fa")
        style = Paint.Style.FILL
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        setupFullscreen()

        bindViews()
        setupStreamRenderer()
        setupConnectPanel()
        setupHandControlsMenu()
        setupHubSettingsMenu()
        setupTutorialOverlay()
        setupControlListeners()
        setupQualityControls()
        setupLensControls()
        setupSixDofSensitivityControls()
        setupHandZoomControls()
        setupTrackingCameraControls()
        setupControlPanelSwipe()
        setupGamepad()
        setupSixDofCapabilities()
        setupParallax()
        setupSbsVideo()

        selectTab(ConnectionType.WIFI)
        startDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreamInternal()
        stopAudioInternal()
        pcDiscovery?.stop()
        stopAllTrackers()
        stopSixDof()
        stopEyeTracking()
        joyConHid?.disconnectAll()
        vrSender?.stop()
        stopLocalVideoIfNeeded()
        if (::glRenderer.isInitialized) glRenderer.release()
        if (::tutorialVideoView.isInitialized) tutorialVideoView.stopPlayback()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupFullscreen()
    }

    override fun onResume() {
        super.onResume()
        if (arCoreInstallRequested && switchSixDof.isChecked && sixDofTracker == null) {
            arCoreInstallRequested = false
            ensureArCoreInstalledThenStart()
        }
        if (arCoreInstallRequested && pendingHubStartAfterArCoreInstall) {
            arCoreInstallRequested = false
            pendingHubStartAfterArCoreInstall = false
            ensureArCoreInstalledThenStartHub()
        }
        if (::parallax.isInitialized) parallax.start()
        if (isPlayingLocalVideo) {
            localVideoPlayer?.let { if (!it.isPlaying) it.start() }
            videoHeadTracker?.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::tutorialVideoView.isInitialized && tutorialVideoView.isPlaying) {
            tutorialVideoView.pause()
        }
        if (::parallax.isInitialized) parallax.stop()
        if (isPlayingLocalVideo) {
            localVideoPlayer?.let { if (it.isPlaying) it.pause() }
            videoHeadTracker?.stop()
        }
    }



    private fun bindViews() {
        rootLayout             = findViewById(R.id.rootLayout)
        connectPanel           = findViewById(R.id.connectPanel)
        connectBackgroundImage = findViewById(R.id.connectBackgroundImage)
        statusText             = findViewById(R.id.statusText)
        languageToggleButton   = findViewById(R.id.languageToggleButton)

        tutorialButton     = findViewById(R.id.tutorialButton)
        tutorialOverlay    = findViewById(R.id.tutorialOverlay)
        tutorialVideoView  = findViewById(R.id.tutorialVideoView)
        skipTutorialButton = findViewById(R.id.skipTutorialButton)

        handControlsButton      = findViewById(R.id.handControlsButton)
        handControlsOverlay     = findViewById(R.id.handControlsOverlay)
        closeHandControlsButton = findViewById(R.id.closeHandControlsButton)

        hubMenuButton = findViewById(R.id.hubMenuButton)

        hubSettingsButton        = findViewById(R.id.hubSettingsButton)
        hubSettingsPanel         = findViewById(R.id.hubSettingsPanel)
        closeHubSettingsButton   = findViewById(R.id.closeHubSettingsButton)
        hubLeftHandStatusText    = findViewById(R.id.hubLeftHandStatusText)
        hubLeftPinchText         = findViewById(R.id.hubLeftPinchText)
        hubLeftGripText          = findViewById(R.id.hubLeftGripText)
        hubLeftButtonsText       = findViewById(R.id.hubLeftButtonsText)
        hubRightHandStatusText   = findViewById(R.id.hubRightHandStatusText)
        hubRightPinchText        = findViewById(R.id.hubRightPinchText)
        hubRightGripText         = findViewById(R.id.hubRightGripText)
        hubRightButtonsText      = findViewById(R.id.hubRightButtonsText)
        switchHubSkeletonOverlay = findViewById(R.id.switchHubSkeletonOverlay)

        sbsVideoButton        = findViewById(R.id.sbsVideoButton)
        videoPlayPauseButton  = findViewById(R.id.videoPlayPauseButton)

        tabWifi        = findViewById(R.id.tabWifi)
        tabCable       = findViewById(R.id.tabCable)
        wifiTabContent = findViewById(R.id.wifiTabContent)
        cableTabContent= findViewById(R.id.cableTabContent)
        ipInput        = findViewById(R.id.ipInput)
        scanButton     = findViewById(R.id.scanButton)
        pcListLayout   = findViewById(R.id.pcListLayout)
        scanStatusText = findViewById(R.id.scanStatusText)

        cableIpInput       = findViewById(R.id.cableIpInput)
        connectCableButton = findViewById(R.id.connectCableButton)
        usbStatusText      = findViewById(R.id.usbStatusText)
        usbStatusDot       = findViewById(R.id.usbStatusDot)
        refreshUsbButton   = findViewById(R.id.refreshUsbButton)

        controlPanel            = findViewById(R.id.controlPanel)
        controlPanelGrabberZone = findViewById(R.id.controlPanelGrabberZone)
        controlScrollView       = findViewById(R.id.controlScrollView)
        switchHmd            = findViewById(R.id.switchHmd)
        switchHand           = findViewById(R.id.switchHand)
        handTrackingSubPanel = findViewById(R.id.handTrackingSubPanel)
        switchSixDof         = findViewById(R.id.switchSixDof)
        sixDofSubPanel       = findViewById(R.id.sixDofSubPanel)
        sixDofStatusText     = findViewById(R.id.sixDofStatusText)
        sixDofModeGroup      = findViewById(R.id.sixDofModeGroup)
        sixDofModePlain      = findViewById(R.id.sixDofModePlain)
        sixDofModeLeds       = findViewById(R.id.sixDofModeLeds)
        sixDofModeHandTracking = findViewById(R.id.sixDofModeHandTracking)
        sixDofModeHandJoycons  = findViewById(R.id.sixDofModeHandJoycons)
        switchCameraOverlay  = findViewById(R.id.switchCameraOverlay)
        switchEyeTracking    = findViewById(R.id.switchEyeTracking)
        eyeTrackingStatusText = findViewById(R.id.eyeTrackingStatusText)
        trackingModeGroup    = findViewById(R.id.trackingModeGroup)
        recenterButton       = findViewById(R.id.recenterButton)
        controlStatusText    = findViewById(R.id.controlStatusText)
        gamepadStatusText    = findViewById(R.id.gamepadStatusText)
        streamButton         = findViewById(R.id.streamButton)
        connectionTypeBadge  = findViewById(R.id.connectionTypeBadge)

        sensDownButton = findViewById(R.id.sensDownButton)
        sensUpButton   = findViewById(R.id.sensUpButton)
        sensLabelText  = findViewById(R.id.sensLabelText)

        handZoomDownButton = findViewById(R.id.handZoomDownButton)
        handZoomUpButton   = findViewById(R.id.handZoomUpButton)
        handZoomLabelText  = findViewById(R.id.handZoomLabelText)

        spinnerTrackingCamera = findViewById(R.id.spinnerTrackingCamera)

        cameraOverlayPanel = findViewById(R.id.cameraOverlayPanel)
        cameraPreviewView  = findViewById(R.id.cameraPreviewView)
        skeletonOverlay    = findViewById(R.id.skeletonOverlay)

        cameraPreviewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        streamPanel             = findViewById(R.id.streamPanel)
        streamGLSurfaceView     = findViewById(R.id.streamGLSurfaceView)
        streamLoadingOverlay    = findViewById(R.id.streamLoadingOverlay)
        streamStatusOverlayText = findViewById(R.id.streamStatusOverlayText)
        closeStreamButton       = findViewById(R.id.closeStreamButton)
        recenterHmdButton       = findViewById(R.id.recenterHmdButton)

        qualityToggleButton = findViewById(R.id.qualityToggleButton)
        streamQualityPanel  = findViewById(R.id.streamQualityPanel)
        resDownButton       = findViewById(R.id.resDownButton)
        resUpButton         = findViewById(R.id.resUpButton)
        resLabelText        = findViewById(R.id.resLabelText)
        brDownButton        = findViewById(R.id.brDownButton)
        brUpButton          = findViewById(R.id.brUpButton)
        brLabelText         = findViewById(R.id.brLabelText)
        fpsDownButton       = findViewById(R.id.fpsDownButton)
        fpsUpButton         = findViewById(R.id.fpsUpButton)
        fpsLabelText        = findViewById(R.id.fpsLabelText)

        lensToggleButton = findViewById(R.id.lensToggleButton)
        streamLensPanel  = findViewById(R.id.streamLensPanel)
        distDownButton   = findViewById(R.id.distDownButton)
        distUpButton     = findViewById(R.id.distUpButton)
        distLabelText    = findViewById(R.id.distLabelText)
        sepDownButton    = findViewById(R.id.sepDownButton)
        sepUpButton      = findViewById(R.id.sepUpButton)
        sepLabelText     = findViewById(R.id.sepLabelText)
        zoomDownButton   = findViewById(R.id.zoomDownButton)
        zoomUpButton     = findViewById(R.id.zoomUpButton)
        zoomLabelText    = findViewById(R.id.zoomLabelText)

        fullscreenVideoButton = findViewById(R.id.fullscreenVideoButton)
        envModeVideoButton = findViewById(R.id.envModeVideoButton)

        videoDistRow        = findViewById(R.id.videoDistRow)
        videoDistDownButton = findViewById(R.id.videoDistDownButton)
        videoDistUpButton   = findViewById(R.id.videoDistUpButton)
        videoDistLabelText  = findViewById(R.id.videoDistLabelText)
    }


    private fun setupHandControlsMenu() {
        handControlsButton.setOnClickListener {
            handControlsOverlay.visibility = View.VISIBLE
            handControlsOverlay.bringToFront()
        }
        closeHandControlsButton.setOnClickListener {
            handControlsOverlay.visibility = View.GONE
            window.decorView.requestFocus()
        }
    }

    private fun setupHubSettingsMenu() {
        hubSettingsButton.setOnClickListener {
            if (hubSettingsPanelVisible) hideHubSettingsPanel() else showHubSettingsPanel()
        }
        closeHubSettingsButton.setOnClickListener {
            hideHubSettingsPanel()
        }
        switchHubSkeletonOverlay.setOnCheckedChangeListener { _, isChecked ->
            hubSkeletonOverlayEnabled = isChecked
            showStatus(if (isChecked) "Vista de esqueleto activada" else "Vista de esqueleto desactivada")
        }
    }

    private fun showHubSettingsPanel() {
        hubSettingsButton.visibility = View.VISIBLE
        if (hubSettingsPanelVisible) return
        hubSettingsPanelVisible = true
        hubSettingsPanel.visibility = View.VISIBLE
        hubSettingsPanel.alpha = 0f
        hubSettingsPanel.translationY = 40f
        hubSettingsPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .start()
    }

    private fun hideHubSettingsPanel() {
        hubSettingsPanelVisible = false
        hubSettingsPanel.animate()
            .alpha(0f)
            .translationY(40f)
            .setDuration(200)
            .withEndAction { hubSettingsPanel.visibility = View.GONE }
            .start()
    }



    private fun setupParallax() {
        parallax = ParallaxMenuController(
            context = this,
            backgroundView = connectBackgroundImage,
            foregroundView = connectPanel,
            backgroundMaxOffsetPx = 40f,
            foregroundMaxOffsetPx = 8f,
            smoothing = 0.15f
        )
    }



    private fun setupStreamRenderer() {
        val prefs = getSharedPreferences("vr_lens", MODE_PRIVATE)

        glRenderer = StereoGLRenderer { surface ->
            glInputSurface = surface
            runOnUiThread {
                if (streamActive && vrStreamReceiver == null) {
                    launchStreamReceiver(surface)
                }
                if (pendingHubStartAfterSurface) {
                    startHubInternal()
                }
                pendingSbsVideoUri?.let { uri ->
                    pendingSbsVideoUri = null
                    playLocalSbsVideo(uri)
                }
            }
        }
        glRenderer.distortionK    = prefs.getFloat("distortionK", 0.22f)
        glRenderer.lensSeparation = prefs.getFloat("lensSeparation", 0.0f)
        glRenderer.lensZoom       = prefs.getFloat("lensZoom", 1.0f)

        streamGLSurfaceView.setEGLContextClientVersion(2)
        streamGLSurfaceView.setRenderer(glRenderer)
        streamGLSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        streamGLSurfaceView.preserveEGLContextOnPause = true
    }



    private fun setupConnectPanel() {
        tabWifi.setOnClickListener  { selectTab(ConnectionType.WIFI)  }
        tabCable.setOnClickListener { selectTab(ConnectionType.CABLE) }

        languageToggleButton.setOnClickListener { toggleAppLanguage() }
        updateLanguageButtonLabel()

        switchHand.isChecked = false
        setRadioGroupEnabled(trackingModeGroup, false)
        switchCameraOverlay.isEnabled = false
        switchSixDof.isEnabled = false
        setRadioGroupEnabled(sixDofModeGroup, false)
        switchEyeTracking.isEnabled = false

        findViewById<Button>(R.id.connectButton).setOnClickListener {
            val ip = ipInput.text.toString().trim()
            when {
                connectingInProgress -> { }
                ip.isEmpty() -> statusText.text = "Ingresa una IP válida"
                !isValidIpAddress(ip) -> statusText.text =
                    "IP inválida. Formato esperado: 192.168.1.X"
                else -> connect(ip, ConnectionType.WIFI)
            }
        }
        scanButton.setOnClickListener { startDiscovery() }

        refreshUsbButton.setOnClickListener { refreshUsbStatus() }
        connectCableButton.setOnClickListener {
            when {
                connectingInProgress -> { }
                cableAutoMode == ConnectionType.USB_ADB -> connect("127.0.0.1", ConnectionType.USB_ADB)
                else -> {
                    val ip = cableIpInput.text.toString().trim()
                    when {
                        ip.isEmpty() -> statusText.text = "Ingresa o detecta la IP de la PC"
                        !isValidIpAddress(ip) -> statusText.text =
                            "IP inválida. Formato esperado: 192.168.42.1"
                        else -> connect(ip, ConnectionType.CABLE)
                    }
                }
            }
        }

        hubMenuButton.setOnClickListener { startHubRequestingPermission() }
    }


    private fun isValidIpAddress(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.all { c -> c.isDigit() } &&
                    (part.toIntOrNull()?.let { it in 0..255 } == true)
        }
    }


    private fun setupTutorialOverlay() {
        tutorialButton.setOnClickListener {
            playTutorialVideo()
        }
        skipTutorialButton.setOnClickListener { closeTutorialVideo() }
    }

    private fun playTutorialVideo() {
        tutorialOverlay.visibility = View.VISIBLE
        tutorialOverlay.bringToFront()
        try {
            val cachedFile = copyAssetToCache("girl.mp4")
            val uri = Uri.fromFile(cachedFile)
            tutorialVideoView.setVideoURI(uri)
            tutorialVideoView.setOnPreparedListener { mp ->
                mp.isLooping = false
                tutorialVideoView.start()
            }
            tutorialVideoView.setOnCompletionListener {
                closeTutorialVideo()
            }
            tutorialVideoView.setOnErrorListener { _, what, extra ->
                showStatus("Error reproduciendo tutorial (código $what/$extra)")
                closeTutorialVideo()
                true
            }
            tutorialVideoView.requestFocus()
        } catch (e: Exception) {
            showStatus("No se pudo cargar el tutorial: ${e.message}")
            closeTutorialVideo()
        }
    }

    private fun copyAssetToCache(assetName: String): File {
        val outFile = File(cacheDir, assetName)
        assets.open(assetName).use { input ->
            val assetSize = input.available()
            if (!outFile.exists() || outFile.length() != assetSize.toLong()) {
                assets.open(assetName).use { freshInput ->
                    FileOutputStream(outFile).use { output ->
                        freshInput.copyTo(output)
                    }
                }
            }
        }
        return outFile
    }

    private fun closeTutorialVideo() {
        tutorialVideoView.stopPlayback()
        tutorialOverlay.visibility = View.GONE
        window.decorView.requestFocus()
    }



    private fun toggleAppLanguage() {
        val currentTag = AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
            .lowercase()
        val nextLang = if (currentTag.startsWith("en")) "es" else "en"
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(nextLang)
        )
    }

    private fun updateLanguageButtonLabel() {
        val currentTag = AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
            .lowercase()
        languageToggleButton.text = if (currentTag.startsWith("en")) "ES" else "EN"
    }



    private fun selectTab(type: ConnectionType) {
        when (type) {
            ConnectionType.WIFI -> {
                tabWifi.setTextColor(0xFFFFFFFF.toInt())
                tabWifi.backgroundTintList  =
                    android.content.res.ColorStateList.valueOf(0xFF1E88E5.toInt())
                tabCable.setTextColor(0xFF888888.toInt())
                tabCable.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF1A1A1A.toInt())
                wifiTabContent.visibility  = View.VISIBLE
                cableTabContent.visibility = View.GONE
            }
            ConnectionType.CABLE, ConnectionType.USB_ADB -> {
                tabCable.setTextColor(0xFFFFFFFF.toInt())
                tabCable.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFFD97706.toInt())
                tabWifi.setTextColor(0xFF888888.toInt())
                tabWifi.backgroundTintList  =
                    android.content.res.ColorStateList.valueOf(0xFF1A1A1A.toInt())
                wifiTabContent.visibility  = View.GONE
                cableTabContent.visibility = View.VISIBLE
                refreshUsbStatus()
            }
        }
    }

    private var hubOverlayBitmap: Bitmap? = null
    private var hubOverlayCanvas: Canvas? = null

    private fun renderHubOverlay(leftPose: HandPose, rightPose: HandPose) {
        if (!hubModeActive) return
        var bmp = hubOverlayBitmap
        if (bmp == null || bmp.width != 1280 || bmp.height != 720) {
            bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            hubOverlayBitmap = bmp
            hubOverlayCanvas = Canvas(bmp)
        }
        val canvas = hubOverlayCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if (!::hubWindowController.isInitialized) return
        val landmarksList = sixDofTracker?.currentNormalizedLandmarks() ?: emptyList()
        val ctrl = hubWindowController
        val halfW = 640f
        val canvasH = 720f

        // Renderizado estereoscópico para ojo izquierdo [0..640] y ojo derecho [640..1280]
        for ((eyeIndex, eyeOffset) in listOf(0 to 0f, 1 to halfW)) {
            val eyeSign = if (eyeIndex == 0) -1f else 1f

            // 1) Dibujar esqueletos de manos (simulando manos/controles VR)
            for ((isRight, pts) in landmarksList) {
                if (pts.size < 21) continue
                val linePaint = if (isRight) paintRightLine else paintLeftLine
                val dotPaint  = if (isRight) paintDotRight else paintDotLeft

                for ((a, b) in SKELETON_CONNECTIONS) {
                    val pa = pts[a]
                    val pb = pts[b]
                    canvas.drawLine(
                        eyeOffset + pa.first * halfW, pa.second * canvasH,
                        eyeOffset + pb.first * halfW, pb.second * canvasH,
                        linePaint
                    )
                }
                for (pt in pts) {
                    canvas.drawCircle(eyeOffset + pt.first * halfW, pt.second * canvasH, 6f, dotPaint)
                }
                canvas.drawCircle(eyeOffset + pts[0].first * halfW, pts[0].second * canvasH, 9f, dotPaint)
            }

            // 2) Dibujar rayos láser de puntero y retícula tipo Meta Quest 3 / SteamVR
            for ((isRightHand, hand) in listOf(false to leftPose, true to rightPose)) {
                if (!hand.tracked) continue

                // Origen del rayo: punta del dedo índice (landmark 8)
                val handPts = landmarksList.firstOrNull { it.first == isRightHand }?.second
                val originX = if (handPts != null && handPts.size > 8) {
                    eyeOffset + handPts[8].first * halfW
                } else {
                    eyeOffset + ((hand.x + 1f) * 0.5f) * halfW
                }
                val originY = if (handPts != null && handPts.size > 8) {
                    handPts[8].second * canvasH
                } else {
                    ((1f - hand.y) * 0.5f) * canvasH
                }

                // Posición objetivo del puntero (proyectada con el paralaje del ojo)
                val targetNdcX = if (ctrl.visible) hand.x + eyeSign * ctrl.screenParallax else hand.x
                val targetNdcY = hand.y

                val targetX = eyeOffset + ((targetNdcX + 1f) * 0.5f) * halfW
                val targetY = ((1f - targetNdcY) * 0.5f) * canvasH

                val isPinching = hand.pinch > 0.6f || hand.clicked
                val beamColor = if (isPinching) Color.parseColor("#ff3366") else (if (isRightHand) Color.parseColor("#00e5ff") else Color.parseColor("#00ff88"))

                // Rayo láser del puntero
                val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = beamColor
                    style = Paint.Style.STROKE
                    strokeWidth = if (isPinching) 6f else 4f
                }
                canvas.drawLine(originX, originY, targetX, targetY, rayPaint)

                // Retícula objetivo estilo Quest 3
                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = beamColor
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
                val dotCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isPinching) Color.WHITE else beamColor
                    style = Paint.Style.FILL
                }

                val radius = if (isPinching) 20f else 14f
                canvas.drawCircle(targetX, targetY, radius, ringPaint)
                canvas.drawCircle(targetX, targetY, 6f, dotCenterPaint)
            }
        }

        glRenderer.updateHubCameraBitmap(bmp)
    }



    private fun refreshUsbStatus() {
        usbStatusDot.setTextColor(0xFFF59E0B.toInt())
        usbStatusText.text = "Comprobando ADB…"

        AdbTunnelHelper.checkReachable { reachable ->
            runOnUiThread {
                if (reachable) {
                    cableAutoMode = ConnectionType.USB_ADB
                    usbStatusDot.setTextColor(0xFF4ADE80.toInt())
                    usbStatusText.text = "Depuración USB detectada (ADB) · listo para conectar"
                    cableIpInput.setText("127.0.0.1 (ADB)")
                    cableIpInput.isEnabled = false
                    return@runOnUiThread
                }

                val rndisIp = UsbConnectionHelper.getPcIpViaCable()
                if (rndisIp != null) {
                    cableAutoMode = ConnectionType.CABLE
                    cableIpInput.isEnabled = true
                    usbStatusDot.setTextColor(0xFF4ADE80.toInt())
                    usbStatusText.text = "Cable USB detectado (RNDIS) · PC: $rndisIp"
                    cableIpInput.setText(rndisIp)
                } else {
                    cableAutoMode = ConnectionType.CABLE
                    usbStatusDot.setTextColor(0xFFEF4444.toInt())
                    usbStatusText.text =
                        "Cable no detectado. Conecta el cable y activa 'Depuración USB' en Opciones de desarrollador."
                    cableIpInput.isEnabled = true
                    if (cableIpInput.text.isBlank() || cableIpInput.text.contains("ADB"))
                        cableIpInput.setText("192.168.42.1")
                }
            }
        }
    }



    private fun setupSixDofCapabilities() {
        supportsConcurrentCameras = DeviceCapabilities.supportsConcurrentCameras(this)

        sixDofStatusText.text = "Comprobando compatibilidad 6DoF…"
        DeviceCapabilities.checkArCoreAvailability(this) { availability ->
            runOnUiThread {
                arCoreAvailable = availability == DeviceCapabilities.ArAvailability.SUPPORTED
                sixDofStatusText.text = when {
                    !arCoreAvailable ->
                        "6DoF no disponible: este dispositivo no soporta ARCore"
                    supportsConcurrentCameras ->
                        "6DoF disponible · puede usarse junto con tracking de manos (2 cámaras)"
                    else ->
                        "6DoF disponible · solo 1 cámara: usa '6DoF + LEDs' o '6DoF + Manos' para trackear manos a la vez"
                }
                setRadioGroupEnabled(sixDofModeGroup, arCoreAvailable)
                updateTrackingExclusivity()
            }
        }

        sixDofModeGroup.setOnCheckedChangeListener { _, checkedId ->
            sixDofHandMode = when (checkedId) {
                R.id.sixDofModeLeds         -> SixDofHandMode.LED
                R.id.sixDofModeHandTracking -> SixDofHandMode.MEDIAPIPE
                R.id.sixDofModeHandJoycons  -> SixDofHandMode.MEDIAPIPE_JOYCONS
                else                        -> SixDofHandMode.NONE
            }
            updateHandJoyconsFlag()

            if (sixDofHandMode != SixDofHandMode.NONE) {
                if (switchHand.isChecked) switchHand.isChecked = false
                showStatus(
                    when (sixDofHandMode) {
                        SixDofHandMode.LED ->
                            "6DoF + LEDs: manos verde=izq 🟢  azul=der 🔵 (cámara compartida con 6DoF)"
                        SixDofHandMode.MEDIAPIPE ->
                            "6DoF + Manos (MediaPipe): tracking real de manos, cámara compartida con 6DoF"
                        SixDofHandMode.MEDIAPIPE_JOYCONS ->
                            "6DoF + Manos + Joycons: posición=cámara, rotación/botones=Joy-Con si hay emparejado"
                        else -> ""
                    }
                )
            }
            updateTrackingExclusivity()
            refreshGamepadStatus()

            if (switchSixDof.isChecked && sixDofTracker != null) {
                startSixDofInternal()
            }
        }
    }

    private fun updateTrackingExclusivity() {
        if (sixDofHandMode != SixDofHandMode.NONE && switchSixDof.isChecked) {
            switchHand.isEnabled   = false
            switchSixDof.isEnabled = arCoreAvailable
            return
        }
        if (supportsConcurrentCameras) {
            switchHand.isEnabled   = true
            switchSixDof.isEnabled = arCoreAvailable
            return
        }
        val sixDofOn = switchSixDof.isChecked
        val handOn   = switchHand.isChecked
        switchHand.isEnabled   = !sixDofOn
        switchSixDof.isEnabled = arCoreAvailable && !handOn
    }



    private fun setupSixDofSensitivityControls() {
        val prefs = getSharedPreferences("vr_sixdof", MODE_PRIVATE)
        sixDofSensitivity = prefs.getFloat("movementSensitivity", 1.0f)

        sensDownButton.setOnClickListener {
            sixDofSensitivity = (sixDofSensitivity - SENS_STEP).coerceIn(SENS_MIN, SENS_MAX)
            applySixDofSensitivity(prefs)
        }
        sensUpButton.setOnClickListener {
            sixDofSensitivity = (sixDofSensitivity + SENS_STEP).coerceIn(SENS_MIN, SENS_MAX)
            applySixDofSensitivity(prefs)
        }
        updateSensLabel()
    }

    private fun applySixDofSensitivity(prefs: android.content.SharedPreferences) {
        updateSensLabel()
        prefs.edit().putFloat("movementSensitivity", sixDofSensitivity).apply()
        sixDofTracker?.movementSensitivity = sixDofSensitivity
    }

    private fun updateSensLabel() {
        sensLabelText.text = "Sensibilidad: %.1f×".format(sixDofSensitivity)
    }


    private fun setupHandZoomControls() {
        val prefs = getSharedPreferences("vr_handzoom", MODE_PRIVATE)
        handCameraZoom = prefs.getFloat("cameraZoom", 1.0f)

        handZoomDownButton.setOnClickListener {
            handCameraZoom = (handCameraZoom - HAND_ZOOM_STEP).coerceIn(HAND_ZOOM_MIN, HAND_ZOOM_MAX)
            applyHandCameraZoom(prefs)
        }
        handZoomUpButton.setOnClickListener {
            handCameraZoom = (handCameraZoom + HAND_ZOOM_STEP).coerceIn(HAND_ZOOM_MIN, HAND_ZOOM_MAX)
            applyHandCameraZoom(prefs)
        }
        updateHandZoomLabel()
    }

    private fun applyHandCameraZoom(prefs: android.content.SharedPreferences) {
        updateHandZoomLabel()
        prefs.edit().putFloat("cameraZoom", handCameraZoom).apply()
    }

    private fun updateHandZoomLabel() {
        handZoomLabelText.text = "Zoom cámara: %.2f×".format(handCameraZoom)
    }

    private fun setupTrackingCameraControls() {
        availableTrackingCameras = discoverTrackingCameras()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            availableTrackingCameras.map { it.name }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTrackingCamera.adapter = adapter

        val prefs = getSharedPreferences("vr_camera_tracking", MODE_PRIVATE)
        val savedId = prefs.getString("selected_camera_id", null)
        if (savedId != null) {
            val foundIdx = availableTrackingCameras.indexOfFirst { it.id == savedId }
            if (foundIdx >= 0) selectedCameraIndex = foundIdx
        }
        if (selectedCameraIndex in availableTrackingCameras.indices) {
            spinnerTrackingCamera.setSelection(selectedCameraIndex)
        }

        spinnerTrackingCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in availableTrackingCameras.indices && position != selectedCameraIndex) {
                    selectedCameraIndex = position
                    val selectedOption = availableTrackingCameras[position]
                    prefs.edit().putString("selected_camera_id", selectedOption.id).apply()
                    if (currentTrackingMode != TrackingMode.NONE) {
                        startTracker(currentTrackingMode)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun discoverTrackingCameras(): List<TrackingCameraOption> {
        val list = mutableListOf<TrackingCameraOption>()
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val characteristics = cm.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facingLabel = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Trasera"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Frontal"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "Externa"
                    else -> "Cámara"
                }
                val selector = CameraSelector.Builder()
                    .addCameraFilter { cameraInfos ->
                        cameraInfos.filter { info ->
                            try {
                                Camera2CameraInfo.from(info).cameraId == id
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }
                    .build()
                list.add(TrackingCameraOption(id, "$facingLabel ($id)", selector))
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error descubriendo cámaras: ${e.message}")
        }

        if (list.isEmpty()) {
            list.add(TrackingCameraOption("back", "Cámara Trasera", CameraSelector.DEFAULT_BACK_CAMERA))
            list.add(TrackingCameraOption("front", "Cámara Frontal", CameraSelector.DEFAULT_FRONT_CAMERA))
        }
        return list
    }

    private fun getSelectedCameraSelector(): CameraSelector {
        return if (selectedCameraIndex in availableTrackingCameras.indices) {
            availableTrackingCameras[selectedCameraIndex].selector
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }



    private fun startSixDofRequestingPermission() {
        pendingCameraAction = { ensureArCoreInstalledThenStart() }
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> ensureArCoreInstalledThenStart()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun ensureArCoreInstalledThenStart() {
        try {
            val status = ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested)
            when (status) {
                ArCoreApk.InstallStatus.INSTALLED -> startSixDofInternal()
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arCoreInstallRequested = true
                    showStatus("Instalando/actualizando Google Play Services para RA…")
                }
                else -> {
                    switchSixDof.isChecked = false
                    showStatus("6DoF: estado de instalación inesperado ($status)")
                }
            }
        } catch (e: UnavailableDeviceNotCompatibleException) {
            switchSixDof.isChecked = false
            showStatus("Este dispositivo no es compatible con ARCore")
        } catch (e: UnavailableUserDeclinedInstallationException) {
            switchSixDof.isChecked = false
            showStatus("Instalación de ARCore cancelada por el usuario")
        } catch (e: Exception) {
            switchSixDof.isChecked = false
            showStatus("Error preparando 6DoF: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startSixDofInternal() {
        sixDofTracker?.stop()
        sixDofTracker = SixDofTracker(
            context = this,
            onPose  = { pose -> vrSender?.updateSixDofPose(pose) },
            onError = { msg  -> runOnUiThread {
                showStatus(msg)
                val esAvisoInformativo = msg.contains("sin señal") || msg.contains("perdió el tracking")
                if (!esAvisoInformativo) {
                    switchSixDof.isChecked = false
                }
            } },
            handMode = sixDofHandMode,
            onHands = if (sixDofHandMode != SixDofHandMode.NONE)
                { l, r -> vrSender?.updateHands(l, r) } else null,
            onTrackingStarted = {
                if (sixDofHandMode == SixDofHandMode.MEDIAPIPE_JOYCONS) {
                    runOnUiThread { warnIfNoJoyConsConnectedSixDof() }
                }
            }
        )
        sixDofTracker?.movementSensitivity = sixDofSensitivity
        vrSender?.setSixDofEnabled(true)
        sixDofTracker?.start()
        showStatus(
            when (sixDofHandMode) {
                SixDofHandMode.LED ->
                    "Tracking 6DoF + LEDs manos iniciado (cámara compartida)"
                SixDofHandMode.MEDIAPIPE ->
                    "Tracking 6DoF + Manos (MediaPipe) iniciado (cámara compartida)"
                SixDofHandMode.MEDIAPIPE_JOYCONS ->
                    "Tracking 6DoF + Manos + Joycons iniciado (cámara compartida)"
                else ->
                    "Tracking 6DoF iniciado (posición real vía cámara)"
            }
        )
    }

    private fun stopSixDof() {
        sixDofTracker?.stop()
        sixDofTracker = null
        vrSender?.setSixDofEnabled(false)
        if (sixDofHandMode != SixDofHandMode.NONE) {
            vrSender?.updateHands(
                HandPose(-0.35f, 0.1f, -0.5f, false),
                HandPose( 0.35f, 0.1f, -0.5f, false)
            )
        }
    }


    private fun warnIfNoJoyConsConnectedSixDof() {
        if (sixDofHandMode != SixDofHandMode.MEDIAPIPE_JOYCONS) return
        val pads = GamepadManager.connectedGamepads()
        val hasJoyCon = pads.any { it.lowercase().contains("joy-con") || it.lowercase().contains("joycon") }
        if (!hasJoyCon) {
            showStatus("6DoF+Manos+Joycons: ningún Joy-Con emparejado todavía — rotación en reposo hasta emparejar uno")
        }
    }


    // ═══════════════════════ MENÚ HUB (standalone) ═══════════════════════

    private fun startHubRequestingPermission() {
        if (streamActive || hubModeActive) return
        pendingCameraAction = { ensureArCoreInstalledThenStartHub() }
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> ensureArCoreInstalledThenStartHub()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun ensureArCoreInstalledThenStartHub() {
        try {
            val status = ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested)
            when (status) {
                ArCoreApk.InstallStatus.INSTALLED -> startHubInternal()
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arCoreInstallRequested = true
                    pendingHubStartAfterArCoreInstall = true
                    showStatus("Instalando/actualizando Google Play Services para RA…")
                }
                else -> showStatus("Menú Hub: estado de instalación de ARCore inesperado ($status)")
            }
        } catch (e: UnavailableDeviceNotCompatibleException) {
            showStatus("Este dispositivo no es compatible con ARCore — el Menú Hub requiere 6DoF")
        } catch (e: UnavailableUserDeclinedInstallationException) {
            showStatus("Instalación de ARCore cancelada — no se pudo iniciar el Menú Hub")
        } catch (e: Exception) {
            showStatus("Error preparando Menú Hub: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startHubInternal() {
        if (hubModeActive) return

        connectPanel.visibility           = View.GONE
        connectBackgroundImage.visibility = View.GONE
        tutorialButton.visibility         = View.GONE
        handControlsButton.visibility     = View.GONE
        hubMenuButton.visibility          = View.GONE
        sbsVideoButton.visibility         = View.GONE

        streamPanel.visibility          = View.VISIBLE
        streamLoadingOverlay.visibility = View.VISIBLE

        qualityToggleButton.visibility = View.GONE
        streamQualityPanel.visibility  = View.GONE
        qualityPanelVisible = false

        lensToggleButton.visibility = View.VISIBLE
        streamLensPanel.visibility  = View.GONE
        lensPanelVisible = false

        fullscreenVideoButton.visibility = View.GONE
        envModeVideoButton.visibility = View.GONE
        videoDistRow.visibility = View.GONE

        hubSettingsButton.visibility = View.VISIBLE
        hubSettingsPanel.visibility  = View.GONE
        hubSettingsPanelVisible = false

        closeStreamButton.visibility = View.VISIBLE
        recenterHmdButton.visibility = View.VISIBLE
        streamControlsVisible = true

        val surface = glInputSurface
        if (surface == null || !surface.isValid) {
            pendingHubStartAfterSurface = true
            streamStatusOverlayText.text = "Preparando superficie de vídeo…"
            return
        }
        pendingHubStartAfterSurface = false

        hubModeActive = true
        hubWindowController = HubWindowController()
        lastHubGrabbedState = false
        glRenderer.ensureBufferSize(1280, 720)

        streamStatusOverlayText.text = "Iniciando cámara y ARCore…"

        glRenderer.setWindowVisible(true)
        glRenderer.setHubCameraActive(true)
        hubBrowser?.stop()
        hubBrowser = null
        hubBrowser = HubBrowserView(this, rootLayout) { bmp, release -> glRenderer.updateWindowBitmap(bmp, release) }
        hubBrowser?.start("https://www.google.com")

        hubPointerController = HubPointerController(
            window = hubWindowController,
            onTouchDown = { u, v -> hubBrowser?.touchDown(u, v) },
            onTouchMove = { u, v -> hubBrowser?.touchMove(u, v) },
            onTouchUp   = { u, v -> hubBrowser?.touchUp(u, v) },
            onPointerUpdate = { u, v, active -> hubBrowser?.updatePointer(u, v, active) }
        )

        sixDofTracker?.stop()
        sixDofTracker = SixDofTracker(
            context = this,
            onPose  = { },
            onError = { msg ->
                runOnUiThread {
                    showStatus(msg)
                    val esAvisoInformativo = msg.contains("sin señal") || msg.contains("perdió el tracking")
                    if (!esAvisoInformativo && hubModeActive) stopHubMode()
                }
            },
            handMode = SixDofHandMode.MEDIAPIPE,
            onHands  = { left, right -> handleHubHands(left, right) },
            onTrackingStarted = {
                runOnUiThread { streamLoadingOverlay.visibility = View.GONE }
            }
        )
        sixDofTracker?.start()

        showStatus("Menú Hub iniciado: cámara + 6DoF + manos")
    }

    private fun stopHubMode() {
        if (!hubModeActive && !pendingHubStartAfterSurface) return
        hubModeActive = false
        pendingHubStartAfterSurface = false

        sixDofTracker?.stop()
        sixDofTracker = null
        glRenderer.setWindowVisible(false)
        glRenderer.setHubCameraActive(false)

        if (::hubPointerController.isInitialized) {
            hubPointerController.update(
                HandPose(-0.35f, 0.1f, -0.5f, false),
                HandPose( 0.35f, 0.1f, -0.5f, false)
            )
        }
        screenTouchTracking = false
        hubBrowser?.stop()
        hubBrowser = null

        streamPanel.visibility          = View.GONE
        streamLoadingOverlay.visibility = View.GONE
        streamQualityPanel.visibility   = View.GONE
        qualityToggleButton.visibility  = View.VISIBLE
        qualityPanelVisible = false
        streamLensPanel.visibility      = View.GONE
        lensToggleButton.visibility     = View.VISIBLE
        lensPanelVisible = false

        hubSettingsButton.visibility = View.GONE
        hubSettingsPanel.visibility  = View.GONE
        hubSettingsPanelVisible = false

        connectPanel.visibility           = View.VISIBLE
        connectBackgroundImage.visibility = View.VISIBLE
        tutorialButton.visibility         = View.VISIBLE
        handControlsButton.visibility     = View.VISIBLE
        hubMenuButton.visibility          = View.VISIBLE
        sbsVideoButton.visibility         = View.VISIBLE

        showStatus("Menú Hub detenido")
    }

    /** Se llama desde el hilo de procesamiento de manos de SixDofTracker */
    private fun handleHubHands(left: HandPose, right: HandPose) {
        if (!hubModeActive || !::hubWindowController.isInitialized) return
        val tracker = sixDofTracker ?: return
        val (camPos, camQuat) = tracker.currentCameraPose()

        val ctrl = hubWindowController
        ctrl.update(left, right, camPos, camQuat)

        glRenderer.setWindowVisible(ctrl.visible)
        if (ctrl.visible) {
            glRenderer.setWindowScreenRect(ctrl.screenX, ctrl.screenY, ctrl.screenHalfW, ctrl.screenHalfH, ctrl.screenParallax)
        }

        val grabbed = ctrl.isGrabbed()
        if (grabbed != lastHubGrabbedState) {
            lastHubGrabbedState = grabbed
            hubBrowser?.grabbedIndicator = grabbed
        }

        renderHubOverlay(left, right)

        if (::hubPointerController.isInitialized) {
            runOnUiThread {
                if (hubModeActive) {
                    hubPointerController.update(left, right)

                    if (hubSettingsPanelVisible || hubSettingsPanel.visibility == View.VISIBLE) {
                        hubLeftHandStatusText.text =
                            if (left.tracked) "Mano Izquierda: 🟢 Detectada"
                            else "Mano Izquierda: 🔴 No detectada"
                        hubRightHandStatusText.text =
                            if (right.tracked) "Mano Derecha: 🟢 Detectada"
                            else "Mano Derecha: 🔴 No detectada"

                        hubLeftPinchText.text = "Pellizco: ${(left.pinch * 100).toInt()}%"
                        hubLeftGripText.text  = "Agarre: ${(left.grip * 100).toInt()}%"

                        hubRightPinchText.text = "Pellizco: ${(right.pinch * 100).toInt()}%"
                        hubRightGripText.text  = "Agarre: ${(right.grip * 100).toInt()}%"

                        val leftBtns = mutableListOf<String>()
                        if (left.clicked) leftBtns.add("Click")
                        if (left.curlIndex > 0.3f) leftBtns.add("Avanzar")
                        hubLeftButtonsText.text = if (leftBtns.isEmpty()) "Acciones: Ninguna" else "Acciones: ${leftBtns.joinToString(", ")}"

                        val rightBtns = mutableListOf<String>()
                        if (right.clicked) rightBtns.add("Click")
                        hubRightButtonsText.text = if (rightBtns.isEmpty()) "Acciones: Ninguna" else "Acciones: ${rightBtns.joinToString(", ")}"
                    }
                }
            }
        }
    }



    // ═══════════════════════ SBS VIDEO ═══════════════════════

    private fun setupSbsVideo() {
        sbsVideoButton.setOnClickListener {
            if (streamActive || hubModeActive || isPlayingLocalVideo) return@setOnClickListener
            pickSbsVideoLauncher.launch("video/*")
        }

        videoPlayPauseButton.setOnClickListener {
            val player = localVideoPlayer ?: return@setOnClickListener
            if (player.isPlaying) {
                player.pause()
                videoPlayPauseButton.text = getString(R.string.str_play)
            } else {
                player.start()
                videoPlayPauseButton.text = getString(R.string.str_pause)
            }
        }
    }

    private fun playLocalSbsVideo(uri: Uri) {
        if (streamActive || hubModeActive) {
            showStatus("Cierra el stream/Menú Hub activo antes de reproducir un video SBS")
            return
        }

        val surface = glInputSurface
        if (surface == null || !surface.isValid) {
            pendingSbsVideoUri = uri
            streamPanel.visibility          = View.VISIBLE
            streamLoadingOverlay.visibility = View.VISIBLE
            streamStatusOverlayText.text    = "Preparando superficie de vídeo…"
            connectPanel.visibility           = View.GONE
            connectBackgroundImage.visibility = View.GONE
            tutorialButton.visibility         = View.GONE
            handControlsButton.visibility     = View.GONE
            hubMenuButton.visibility          = View.GONE
            sbsVideoButton.visibility         = View.GONE
            return
        }

        stopLocalVideoIfNeeded()
        videoHeadTracker = GyroVideoWindowTracker(
            context = this,
            onOffsetChanged = { _, _ -> },
            onHeadAnglesChanged = { _, _, _ -> }
        )
        videoHeadTracker?.start()

        connectPanel.visibility           = View.GONE
        connectBackgroundImage.visibility = View.GONE
        tutorialButton.visibility         = View.GONE
        handControlsButton.visibility     = View.GONE
        hubMenuButton.visibility          = View.GONE
        sbsVideoButton.visibility         = View.GONE

        streamPanel.visibility          = View.VISIBLE
        streamLoadingOverlay.visibility = View.VISIBLE
        streamStatusOverlayText.text    = "Cargando video…"

        qualityToggleButton.visibility = View.GONE
        streamQualityPanel.visibility  = View.GONE
        qualityPanelVisible = false

        lensToggleButton.visibility = View.VISIBLE
        streamLensPanel.visibility  = View.GONE
        lensPanelVisible = false

        hubSettingsButton.visibility = View.GONE
        hubSettingsPanel.visibility  = View.GONE
        hubSettingsPanelVisible = false

        fullscreenVideoButton.visibility = View.GONE
        envModeVideoButton.visibility = View.GONE
        videoDistRow.visibility = View.GONE

        closeStreamButton.visibility  = View.VISIBLE
        recenterHmdButton.visibility  = View.VISIBLE
        videoPlayPauseButton.visibility = View.VISIBLE
        videoPlayPauseButton.text = getString(R.string.str_pause)
        streamControlsVisible = true

        isPlayingLocalVideo = true

        localVideoPlayer = MediaPlayer().apply {
            try {
                setDataSource(this@MainActivity, uri)
                setSurface(surface)
                isLooping = true
                setOnPreparedListener {
                    streamLoadingOverlay.visibility = View.GONE
                    it.start()
                }
                setOnErrorListener { _, what, extra ->
                    showStatus("Error reproduciendo video SBS (código $what/$extra)")
                    stopLocalVideoAndReturnToMenu()
                    true
                }
                prepareAsync()
            } catch (e: Exception) {
                showStatus("No se pudo abrir el video: ${e.message}")
                stopLocalVideoAndReturnToMenu()
            }
        }

        showStatus("Reproduciendo video SBS")
    }

    private fun stopLocalVideoIfNeeded() {
        if (!isPlayingLocalVideo && localVideoPlayer == null) return
        try {
            localVideoPlayer?.stop()
        } catch (_: Exception) { }
        localVideoPlayer?.release()
        localVideoPlayer = null
        isPlayingLocalVideo = false
        videoHeadTracker?.stop()
        videoHeadTracker = null
    }

    private fun stopLocalVideoAndReturnToMenu() {
        stopLocalVideoIfNeeded()
        runOnUiThread {
            streamPanel.visibility            = View.GONE
            streamLoadingOverlay.visibility   = View.GONE
            streamQualityPanel.visibility     = View.GONE
            qualityToggleButton.visibility    = View.VISIBLE
            qualityPanelVisible = false
            streamLensPanel.visibility        = View.GONE
            lensToggleButton.visibility       = View.VISIBLE
            lensPanelVisible = false
            hubSettingsButton.visibility      = View.GONE
            hubSettingsPanel.visibility       = View.GONE
            hubSettingsPanelVisible = false
            videoPlayPauseButton.visibility   = View.GONE
            fullscreenVideoButton.visibility  = View.GONE
            envModeVideoButton.visibility     = View.GONE
            videoDistRow.visibility           = View.GONE
            closeStreamButton.visibility      = View.VISIBLE
            recenterHmdButton.visibility      = View.VISIBLE
            streamControlsVisible = true

            connectPanel.visibility           = View.VISIBLE
            connectBackgroundImage.visibility = View.VISIBLE
            tutorialButton.visibility         = View.VISIBLE
            handControlsButton.visibility     = View.VISIBLE
            hubMenuButton.visibility          = View.VISIBLE
            sbsVideoButton.visibility         = View.VISIBLE
        }
    }



    private fun startEyeTrackingRequestingPermission() {
        pendingCameraAction = { startEyeTrackingInternal() }
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startEyeTrackingInternal()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startEyeTrackingInternal() {
        if (connectionType == ConnectionType.USB_ADB) {
            switchEyeTracking.isChecked = false
            showStatus("Eye Tracking (OSC/UDP) no está disponible en modo USB/ADB — usa WiFi o cable RNDIS")
            return
        }
        if (connectedPcIp.isEmpty()) {
            switchEyeTracking.isChecked = false
            showStatus("Conecta primero con una PC para usar Eye Tracking")
            return
        }
        faceEyeTracker?.stop()
        faceEyeTracker = FaceEyeTracker(
            context = this,
            pcIp    = connectedPcIp,
            onError = { msg ->
                runOnUiThread {
                    eyeTrackingStatusText.text = msg
                    showStatus(msg)
                    switchEyeTracking.isChecked = false
                }
            },
            onReady = {
                runOnUiThread {
                    eyeTrackingStatusText.text = "Eye tracking activo → VRChat OSC (puerto 9000)"
                }
            }
        )
        faceEyeTracker?.start(this)
        showStatus("Eye Tracking VRChat iniciado (cámara frontal)")
    }

    private fun stopEyeTracking() {
        faceEyeTracker?.stop()
        faceEyeTracker = null
        eyeTrackingStatusText.text = "Cámara frontal · OSC → VRChat puerto 9000"
    }



    private fun startStream() {
        if (connectedPcIp.isEmpty()) { showStatus("No hay PC conectada"); return }
        stopStreamInternal()
        streamActive = true

        vrSender?.setHmdEnabled(true)
        vrSender?.triggerSystemButtonPulse()

        applyQuality()

        vrAudioReceiver?.stop()
        vrAudioReceiver = VrAudioReceiver(connectedPcIp) { msg ->
            runOnUiThread { showStatus(msg) }
        }
        vrAudioReceiver?.start()

        cameraOverlayPanel.visibility = View.GONE
        switchCameraOverlay.isEnabled = false

        streamPanel.visibility          = View.VISIBLE
        streamLoadingOverlay.visibility = View.VISIBLE
        streamStatusOverlayText.text    = "Conectando al stream…"

        qualityToggleButton.visibility = View.VISIBLE
        streamQualityPanel.visibility  = View.GONE
        qualityPanelVisible = false

        lensToggleButton.visibility = View.VISIBLE
        streamLensPanel.visibility  = View.GONE
        lensPanelVisible = false

        hubSettingsButton.visibility = View.GONE
        hubSettingsPanel.visibility  = View.GONE
        hubSettingsPanelVisible = false

        fullscreenVideoButton.visibility = View.GONE
        envModeVideoButton.visibility = View.GONE
        videoDistRow.visibility = View.GONE

        closeStreamButton.visibility  = View.VISIBLE
        recenterHmdButton.visibility  = View.VISIBLE
        videoPlayPauseButton.visibility = View.GONE
        streamControlsVisible = true

        hideControlPanel()

        val surface = glInputSurface
        if (surface != null && surface.isValid) {
            launchStreamReceiver(surface)
        }
    }

    private fun launchStreamReceiver(surface: android.view.Surface) {
        if (!surface.isValid) {
            showStatus("Surface no válida, reintenta")
            streamPanel.visibility = View.GONE
            streamActive = false
            return
        }
        vrStreamReceiver = VrStreamReceiver(
            pcIp    = connectedPcIp,
            surface = surface,
            onStatus = { msg ->
                runOnUiThread {
                    streamStatusOverlayText.text = msg
                    when {
                        msg.contains("✓") ->
                            streamLoadingOverlay.visibility = View.GONE
                        msg.contains("reconectando") || msg.contains("error", ignoreCase = true) ->
                            streamLoadingOverlay.visibility = View.VISIBLE
                    }
                }
            }
        )
        vrStreamReceiver?.start()
    }

    private fun stopStreamInternal() {
        vrStreamReceiver?.stop()
        vrStreamReceiver = null
        stopAudioInternal()
    }

    private fun stopAudioInternal() {
        vrAudioReceiver?.stop()
        vrAudioReceiver = null
    }

    private fun stopStream() {
        stopStreamInternal()
        streamActive = false
        runOnUiThread {
            streamPanel.visibility          = View.GONE
            streamLoadingOverlay.visibility = View.GONE
            streamQualityPanel.visibility   = View.GONE
            qualityToggleButton.visibility  = View.VISIBLE
            qualityPanelVisible = false
            streamLensPanel.visibility      = View.GONE
            lensToggleButton.visibility     = View.VISIBLE
            lensPanelVisible = false
            hubSettingsButton.visibility    = View.GONE
            hubSettingsPanel.visibility     = View.GONE
            hubSettingsPanelVisible = false

            closeStreamButton.visibility    = View.VISIBLE
            recenterHmdButton.visibility    = View.VISIBLE
            streamControlsVisible = true
        }

        switchCameraOverlay.isEnabled = switchHand.isChecked
        if (switchCameraOverlay.isChecked && switchHand.isChecked
            && currentTrackingMode != TrackingMode.NONE) {
            cameraOverlayPanel.visibility = View.VISIBLE
        }

        showControlPanel()
    }



    private fun setupQualityControls() {
        qualityToggleButton.setOnClickListener {
            if (qualityPanelVisible) hideQualityPanel() else showQualityPanel()
        }
        resDownButton.setOnClickListener {
            if (resIndex > 0) { resIndex--; applyQuality() }
        }
        resUpButton.setOnClickListener {
            if (resIndex < RES_PRESETS.size - 1) { resIndex++; applyQuality() }
        }
        brDownButton.setOnClickListener {
            val step = when {
                bitrateMbps <= 10.0f -> 1.0f
                bitrateMbps <= 50.0f -> 5.0f
                else -> 10.0f
            }
            bitrateMbps = (bitrateMbps - step).coerceAtLeast(BITRATE_MIN)
            applyQuality()
        }
        brUpButton.setOnClickListener {
            val step = when {
                bitrateMbps < 10.0f -> 1.0f
                bitrateMbps < 50.0f -> 5.0f
                else -> 10.0f
            }
            bitrateMbps = (bitrateMbps + step).coerceAtMost(BITRATE_MAX)
            applyQuality()
        }
        fpsDownButton.setOnClickListener {
            if (fpsIndex > 0) { fpsIndex--; applyQuality() }
        }
        fpsUpButton.setOnClickListener {
            if (fpsIndex < FPS_PRESETS.size - 1) { fpsIndex++; applyQuality() }
        }
        updateQualityLabels()
    }

    private fun updateQualityLabels() {
        resLabelText.text = RES_PRESETS[resIndex].label
        brLabelText.text  = if (bitrateMbps % 1f == 0f) "${bitrateMbps.toInt()} Mbps" else "%.1f Mbps".format(bitrateMbps)
        fpsLabelText.text = "${FPS_PRESETS[fpsIndex]} FPS"
    }

    private fun applyQuality() {
        updateQualityLabels()
        val preset = RES_PRESETS[resIndex]
        val fps = FPS_PRESETS[fpsIndex]
        val bitrate = (bitrateMbps * 1_000_000).toInt()
        if (connectedPcIp.isNotEmpty()) {
            if (connectionType == ConnectionType.USB_ADB) {
                (vrSender as? UsbTrackingSender)?.sendQualityChange(preset.w, preset.h, bitrate, fps)
            } else {
                StreamQualityController.sendQuality(connectedPcIp, preset.w, preset.h, bitrate, fps)
            }
            val brStr = if (bitrateMbps % 1f == 0f) "${bitrateMbps.toInt()}" else "%.1f".format(bitrateMbps)
            showStatus("Calidad: ${preset.label} · $brStr Mbps · ${fps}fps")
        }
    }

    private fun showQualityPanel() {
        qualityToggleButton.visibility = View.VISIBLE
        if (qualityPanelVisible) return
        qualityPanelVisible = true
        streamQualityPanel.visibility = View.VISIBLE
        streamQualityPanel.alpha = 0f
        streamQualityPanel.translationY = 40f
        streamQualityPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .start()
    }

    private fun hideQualityPanel() {
        qualityPanelVisible = false
        streamQualityPanel.animate()
            .alpha(0f)
            .translationY(40f)
            .setDuration(200)
            .withEndAction { streamQualityPanel.visibility = View.GONE }
            .start()
        qualityToggleButton.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                qualityToggleButton.visibility = View.GONE
                qualityToggleButton.alpha = 1f
            }
            .start()
    }



    private fun setupLensControls() {
        val prefs = getSharedPreferences("vr_lens", MODE_PRIVATE)

        lensToggleButton.setOnClickListener {
            if (lensPanelVisible) hideLensPanel() else showLensPanel()
        }

        distDownButton.setOnClickListener {
            glRenderer.distortionK = (glRenderer.distortionK - DIST_STEP).coerceIn(DIST_MIN, DIST_MAX)
            updateLensLabels()
            prefs.edit().putFloat("distortionK", glRenderer.distortionK).apply()
        }
        distUpButton.setOnClickListener {
            glRenderer.distortionK = (glRenderer.distortionK + DIST_STEP).coerceIn(DIST_MIN, DIST_MAX)
            updateLensLabels()
            prefs.edit().putFloat("distortionK", glRenderer.distortionK).apply()
        }
        sepDownButton.setOnClickListener {
            glRenderer.lensSeparation = (glRenderer.lensSeparation - SEP_STEP).coerceIn(SEP_MIN, SEP_MAX)
            updateLensLabels()
            prefs.edit().putFloat("lensSeparation", glRenderer.lensSeparation).apply()
        }
        sepUpButton.setOnClickListener {
            glRenderer.lensSeparation = (glRenderer.lensSeparation + SEP_STEP).coerceIn(SEP_MIN, SEP_MAX)
            updateLensLabels()
            prefs.edit().putFloat("lensSeparation", glRenderer.lensSeparation).apply()
        }
        zoomDownButton.setOnClickListener {
            glRenderer.lensZoom = (glRenderer.lensZoom - ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
            updateLensLabels()
            prefs.edit().putFloat("lensZoom", glRenderer.lensZoom).apply()
        }
        zoomUpButton.setOnClickListener {
            glRenderer.lensZoom = (glRenderer.lensZoom + ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
            updateLensLabels()
            prefs.edit().putFloat("lensZoom", glRenderer.lensZoom).apply()
        }
        updateLensLabels()
    }

    private fun updateLensLabels() {
        distLabelText.text = "Distorsión: %.2f".format(glRenderer.distortionK)
        sepLabelText.text  = "Separación: %.2f".format(glRenderer.lensSeparation)
        zoomLabelText.text = "🔎 Lupa: %.2f×".format(glRenderer.lensZoom)
    }

    private fun showLensPanel() {
        lensToggleButton.visibility = View.VISIBLE
        if (lensPanelVisible) return
        lensPanelVisible = true
        streamLensPanel.visibility = View.VISIBLE
        streamLensPanel.alpha = 0f
        streamLensPanel.translationY = 40f
        streamLensPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .start()
    }

    private fun hideLensPanel() {
        lensPanelVisible = false
        streamLensPanel.animate()
            .alpha(0f)
            .translationY(40f)
            .setDuration(200)
            .withEndAction { streamLensPanel.visibility = View.GONE }
            .start()
        lensToggleButton.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                lensToggleButton.visibility = View.GONE
                lensToggleButton.alpha = 1f
            }
            .start()
    }



    private fun showStreamControls() {
        if (streamControlsVisible) return
        streamControlsVisible = true

        val buttons = if (isPlayingLocalVideo)
            listOf(lensToggleButton, closeStreamButton, videoPlayPauseButton)
        else if (hubModeActive)
            listOf(lensToggleButton, closeStreamButton, recenterHmdButton, hubSettingsButton)
        else
            listOf(qualityToggleButton, lensToggleButton, closeStreamButton, recenterHmdButton)

        buttons.forEach { btn ->
            btn.visibility = View.VISIBLE
            btn.alpha = 0f
            btn.animate().alpha(1f).setDuration(200).start()
        }

        if (qualityPanelVisible) {
            streamQualityPanel.visibility = View.VISIBLE
            streamQualityPanel.alpha = 0f
            streamQualityPanel.translationY = 40f
            streamQualityPanel.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        if (lensPanelVisible) {
            streamLensPanel.visibility = View.VISIBLE
            streamLensPanel.alpha = 0f
            streamLensPanel.translationY = 40f
            streamLensPanel.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        if (hubSettingsPanelVisible) {
            hubSettingsPanel.visibility = View.VISIBLE
            hubSettingsPanel.alpha = 0f
            hubSettingsPanel.translationY = 40f
            hubSettingsPanel.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
    }

    private fun hideStreamControls() {
        if (!streamControlsVisible) return
        streamControlsVisible = false

        if (qualityPanelVisible) {
            streamQualityPanel.animate()
                .alpha(0f).translationY(40f).setDuration(200)
                .withEndAction { streamQualityPanel.visibility = View.GONE }
                .start()
        }
        if (lensPanelVisible) {
            streamLensPanel.animate()
                .alpha(0f).translationY(40f).setDuration(200)
                .withEndAction { streamLensPanel.visibility = View.GONE }
                .start()
        }
        if (hubSettingsPanelVisible) {
            hubSettingsPanel.animate()
                .alpha(0f).translationY(40f).setDuration(200)
                .withEndAction { hubSettingsPanel.visibility = View.GONE }
                .start()
        }

        val buttons = if (isPlayingLocalVideo)
            listOf(lensToggleButton, closeStreamButton, videoPlayPauseButton)
        else if (hubModeActive)
            listOf(lensToggleButton, closeStreamButton, recenterHmdButton, hubSettingsButton)
        else
            listOf(qualityToggleButton, lensToggleButton, closeStreamButton, recenterHmdButton)

        buttons.forEach { btn ->
            btn.animate()
                .alpha(0f).setDuration(200)
                .withEndAction { btn.visibility = View.GONE; btn.alpha = 1f }
                .start()
        }
    }



    private fun startDiscovery() {
        discoveredPcs.clear()
        runOnUiThread {
            pcListLayout.removeAllViews()
            scanStatusText.text  = "🔍 Buscando PC con SteamVR…"
            scanButton.isEnabled = true
        }
        pcDiscovery?.stop()
        pcDiscovery = PcDiscovery(
            onFound = { ip, name -> runOnUiThread { addDiscoveredPc(ip, name) } },
            onError = { msg ->
                runOnUiThread {
                    scanStatusText.text  = "Error: $msg"
                    scanButton.isEnabled = true
                }
            }
        )
        pcDiscovery?.start()
        rootLayout.postDelayed({
            runOnUiThread {
                scanButton.isEnabled = true
                if (discoveredPcs.isEmpty())
                    scanStatusText.text =
                        "No se encontró ninguna PC.\nAsegúrate de que SteamVR esté abierto y en la misma red."
            }
        }, 8000)
    }

    private fun addDiscoveredPc(ip: String, name: String) {
        if (discoveredPcs.containsKey(ip)) return
        discoveredPcs[ip] = name
        scanStatusText.text  = "PC encontrada:"
        scanButton.isEnabled = true
        val btn = Button(this).apply {
            text = "🖥  $name\n$ip"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF1E5631.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 8, 0, 0)
            layoutParams = lp
            setPadding(16, 16, 16, 16)
            setOnClickListener { if (!connectingInProgress) connect(ip, ConnectionType.WIFI) }
        }
        pcListLayout.addView(btn)
        if (discoveredPcs.size == 1 && ipInput.text.isBlank()) {
            ipInput.setText(ip)
            rootLayout.postDelayed({ if (!connectingInProgress) connect(ip, ConnectionType.WIFI) }, 1200)
        }
    }



    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadManager?.onGenericMotionEvent(event) == true) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = when (event.action) {
            KeyEvent.ACTION_DOWN -> gamepadManager?.onKeyDown(event.keyCode, event) == true
            KeyEvent.ACTION_UP   -> gamepadManager?.onKeyUp(event.keyCode, event)   == true
            else -> false
        }
        return if (handled) true else super.dispatchKeyEvent(event)
    }



    private fun setupControlPanelSwipe() {
        controlPanelGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {

                private val density = resources.displayMetrics.density
                private val SWIPE_THRESHOLD          = 64 * density
                private val SWIPE_VELOCITY_THRESHOLD = 900f

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    val dy = e2.y - (e1?.y ?: e2.y)
                    val dx = e2.x - (e1?.x ?: e2.x)

                    val esVertical = abs(dy) > abs(dx) * 1.5f
                    if (esVertical &&
                        abs(dy) > SWIPE_THRESHOLD &&
                        abs(velocityY) > SWIPE_VELOCITY_THRESHOLD
                    ) {
                        if (streamPanel.visibility == View.VISIBLE) {
                            if (dy > 0) hideStreamControls() else showStreamControls()
                            return true
                        }

                        if (connectPanel.visibility != View.VISIBLE) {
                            if (dy > 0) hideControlPanel() else showControlPanel()
                            return true
                        }
                    }
                    return false
                }
            }
        )
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swipeIgnoredForCurrentGesture = isInsideSwipeExclusionZone(event.rawX, event.rawY)
        }

        if (handleHubScreenTouch(event)) {
            return true
        }

        if (!swipeIgnoredForCurrentGesture) {
            controlPanelGestureDetector.onTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }


    private fun isInsideSwipeExclusionZone(rawX: Float, rawY: Float): Boolean {
        val x = rawX.toInt()
        val y = rawY.toInt()

        if (controlPanel.visibility == View.VISIBLE && ::controlScrollView.isInitialized) {
            controlScrollView.getGlobalVisibleRect(swipeHitRect)
            if (swipeHitRect.contains(x, y)) return true
        }
        if (streamPanel.visibility == View.VISIBLE) {
            if (qualityPanelVisible && streamQualityPanel.visibility == View.VISIBLE) {
                streamQualityPanel.getGlobalVisibleRect(swipeHitRect)
                if (swipeHitRect.contains(x, y)) return true
            }
            if (lensPanelVisible && streamLensPanel.visibility == View.VISIBLE) {
                streamLensPanel.getGlobalVisibleRect(swipeHitRect)
                if (swipeHitRect.contains(x, y)) return true
            }
            if (hubSettingsPanelVisible && hubSettingsPanel.visibility == View.VISIBLE) {
                hubSettingsPanel.getGlobalVisibleRect(swipeHitRect)
                if (swipeHitRect.contains(x, y)) return true
            }
        }
        return false
    }

    private fun isInsideHubButtonZone(rawX: Float, rawY: Float): Boolean {
        val x = rawX.toInt()
        val y = rawY.toInt()
        listOf(closeStreamButton, recenterHmdButton, lensToggleButton, hubSettingsButton).forEach { v ->
            if (v.visibility == View.VISIBLE) {
                v.getGlobalVisibleRect(swipeHitRect)
                if (swipeHitRect.contains(x, y)) return true
            }
        }
        if (lensPanelVisible && streamLensPanel.visibility == View.VISIBLE) {
            streamLensPanel.getGlobalVisibleRect(swipeHitRect)
            if (swipeHitRect.contains(x, y)) return true
        }
        if (hubSettingsPanelVisible && hubSettingsPanel.visibility == View.VISIBLE) {
            hubSettingsPanel.getGlobalVisibleRect(swipeHitRect)
            if (swipeHitRect.contains(x, y)) return true
        }
        return false
    }

    private fun handleHubScreenTouch(event: MotionEvent): Boolean {
        if (!hubModeActive) return false
        if (!::streamGLSurfaceView.isInitialized || streamGLSurfaceView.visibility != View.VISIBLE) return false
        if (!::glRenderer.isInitialized) return false

        val loc = IntArray(2)
        streamGLSurfaceView.getLocationOnScreen(loc)
        val localX = event.rawX - loc[0]
        val localY = event.rawY - loc[1]
        val w = streamGLSurfaceView.width
        val h = streamGLSurfaceView.height

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isInsideHubButtonZone(event.rawX, event.rawY)) return false
                val uv = glRenderer.screenToWindowUv(localX, localY, w, h) ?: return false
                screenTouchTracking = true
                hubBrowser?.touchDown(uv[0], uv[1])
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!screenTouchTracking) return false
                val uv = glRenderer.screenToWindowUvClamped(localX, localY, w, h)
                hubBrowser?.touchMove(uv[0], uv[1])
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!screenTouchTracking) return false
                screenTouchTracking = false
                val uv = glRenderer.screenToWindowUvClamped(localX, localY, w, h)
                hubBrowser?.touchUp(uv[0], uv[1])
                true
            }
            else -> false
        }
    }

    private fun hideControlPanel() {
        if (!controlPanelVisible) return
        controlPanelVisible = false
        controlPanel.animate()
            .translationY(controlPanel.height.toFloat())
            .setDuration(250)
            .withEndAction {
                controlPanel.visibility = View.GONE
                window.decorView.requestFocus()
            }.start()
    }

    private fun showControlPanel() {
        if (controlPanelVisible) return
        controlPanelVisible = true
        controlPanel.visibility = View.VISIBLE
        controlPanel.translationY = controlPanel.height.toFloat()
        controlPanel.animate().translationY(0f).setDuration(250).start()
    }



    private fun setupGamepad() {
        gamepadManager = GamepadManager(
            onLeft  = { state -> vrSender?.updateLeftGamepad(state)  },
            onRight = { state -> vrSender?.updateRightGamepad(state) }
        )

        joyConHid = JoyConHidManager(this) { side, state ->
            gamepadManager?.applyJoyConState(side, state)
            joyconDebugCounter++
            if (joyconDebugCounter % 6 == 0) {
                runOnUiThread { refreshGamepadStatus(side, state) }
            }
        }

        connectPairedJoyCons()
        refreshGamepadStatus()
    }


    private fun connectPairedJoyCons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showStatus("Joy-Con: giroscopio no soportado en esta versión de Android (necesita Android 10+)")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        val hid = joyConHid ?: return
        val paired = hid.pairedJoyCons()
        if (paired.isEmpty()) {
            showStatus("Joy-Con: ninguno emparejado (Ajustes > Bluetooth)")
            return
        }
        paired.forEach { device: BluetoothDevice -> hid.connect(device) }
        showStatus("Joy-Con: conectando ${paired.size} dispositivo(s)…")
    }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) connectPairedJoyCons()
            else showStatus("Permiso de Bluetooth denegado — el Joy-Con no podrá conectarse")
        }

    private fun refreshGamepadStatus(
        lastSide: JoyConHidManager.Side? = null,
        lastState: JoyConHidManager.JoyConState? = null
    ) {
        val pads = GamepadManager.connectedGamepads()
        val base = if (pads.isEmpty()) "Sin mando conectado" else "🎮 ${pads.joinToString(", ")}"

        val debugLine = if (lastState != null) {
            val gyroTxt = if (lastState.hasGyro) "SI" else "no"
            "  ·  [$lastSide] gyro=$gyroTxt  q=(%.2f, %.2f, %.2f, %.2f)".format(
                lastState.qw, lastState.qx, lastState.qy, lastState.qz
            )
        } else ""

        gamepadStatusText.text = when {
            currentTrackingMode == TrackingMode.HAND_JOYCONS ->
                "$base · HandJoycons: posición=cámara, rotación/botones=Joy-Con$debugLine"
            sixDofHandMode == SixDofHandMode.MEDIAPIPE_JOYCONS ->
                "$base · 6DoF+Manos+Joycons: posición=cámara, rotación/botones=Joy-Con$debugLine"
            else -> "$base$debugLine"
        }
    }


    private fun updateHandJoyconsFlag() {
        val active = currentTrackingMode == TrackingMode.HAND_JOYCONS ||
                sixDofHandMode == SixDofHandMode.MEDIAPIPE_JOYCONS
        vrSender?.setHandJoyconsMode(active)
        vrSender?.setPlainHandJoyconsMode(currentTrackingMode == TrackingMode.HAND_JOYCONS)
    }


    private fun connect(pcIp: String, type: ConnectionType) {
        if (connectingInProgress) return
        connectingInProgress = true
        connectionType = type
        pcDiscovery?.stop()

        val typeLabel = when (type) {
            ConnectionType.CABLE   -> "Cable USB (RNDIS)"
            ConnectionType.USB_ADB -> "Cable USB (ADB)"
            ConnectionType.WIFI    -> "WiFi"
        }
        statusText.text = "Conectando a $pcIp ($typeLabel)…"
        setConnectInputsEnabled(false)

        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val onStatusCb: (String) -> Unit = { msg -> runOnUiThread { showStatus(msg) } }

        val onConnectedCb: () -> Unit = {
            runOnUiThread {
                connectingInProgress = false
                connectedPcIp = if (type == ConnectionType.USB_ADB) "127.0.0.1" else pcIp

                vrSender?.setHmdEnabled(switchHmd.isChecked)
                vrSender?.setSixDofEnabled(switchSixDof.isChecked && sixDofTracker != null)
                updateHandJoyconsFlag()

                if (currentTrackingMode != TrackingMode.NONE) requestCameraAndStart(currentTrackingMode)
                refreshGamepadStatus()

                switchEyeTracking.isEnabled = (type != ConnectionType.USB_ADB)
                if (type == ConnectionType.USB_ADB && switchEyeTracking.isChecked) {
                    switchEyeTracking.isChecked = false
                    showStatus("Eye Tracking (OSC/UDP) no está disponible en modo USB/ADB — usa WiFi o cable RNDIS")
                }

                connectionTypeBadge.text = when (type) {
                    ConnectionType.USB_ADB -> "🔌 ADB"
                    ConnectionType.CABLE   -> "🔌 Cable"
                    ConnectionType.WIFI    -> "📶 WiFi"
                }
                connectionTypeBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        if (type == ConnectionType.WIFI) 0xFF1A3A5C.toInt() else 0xFF6B3A10.toInt()
                    )

                connectPanel.visibility           = View.GONE
                connectBackgroundImage.visibility = View.GONE
                tutorialButton.visibility         = View.GONE
                handControlsButton.visibility     = View.GONE
                hubMenuButton.visibility          = View.GONE
                sbsVideoButton.visibility         = View.GONE
                controlPanel.visibility           = View.VISIBLE
                controlPanelVisible               = true
                window.decorView.requestFocus()

                setConnectInputsEnabled(true)
            }
        }

        val onFailedCb: (String) -> Unit = { msg ->
            runOnUiThread {
                connectingInProgress = false
                statusText.text = "No se pudo conectar: $msg"
                setConnectInputsEnabled(true)
                vrSender?.stop()
                vrSender = null

                if (pcDiscovery == null || discoveredPcs.isEmpty()) startDiscovery()
            }
        }

        vrSender?.stop()
        vrSender = if (type == ConnectionType.USB_ADB) {
            UsbTrackingSender(onStatus = onStatusCb, onConnected = onConnectedCb, onFailed = onFailedCb)
        } else {
            VrUdpSender(pcIp = pcIp, onStatus = onStatusCb, onConnected = onConnectedCb, onFailed = onFailedCb)
        }
        vrSender?.start(sensorManager)
    }


    private fun setConnectInputsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.connectButton).isEnabled = enabled
        connectCableButton.isEnabled = enabled
        scanButton.isEnabled = enabled
        ipInput.isEnabled = enabled
        cableIpInput.isEnabled = enabled
    }



    private fun setupControlListeners() {

        switchHmd.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) vrSender?.recenter()
            vrSender?.setHmdEnabled(isChecked)
            showStatus(if (isChecked) "HMD activado — orientación desde móvil"
            else "HMD desactivado — orientación desde PC")
        }

        switchHand.setOnCheckedChangeListener { _, isChecked ->

            handTrackingSubPanel.visibility = if (isChecked) View.VISIBLE else View.GONE

            setRadioGroupEnabled(trackingModeGroup, isChecked)
            switchCameraOverlay.isEnabled = isChecked
            if (isChecked) {
                if (switchSixDof.isChecked && (!supportsConcurrentCameras || sixDofHandMode != SixDofHandMode.NONE)) {
                    switchSixDof.isChecked = false
                }
                if (currentTrackingMode == TrackingMode.NONE)
                    trackingModeGroup.check(R.id.modeHand)
                else
                    requestCameraAndStart(currentTrackingMode)
            } else {
                stopAllTrackers()
                switchCameraOverlay.isChecked = false
                cameraOverlayPanel.visibility = View.GONE
                vrSender?.updateHands(
                    HandPose(-0.35f, 0.1f, -0.5f, false),
                    HandPose( 0.35f, 0.1f, -0.5f, false)
                )
                showStatus("Tracking de manos desactivado")
                window.decorView.requestFocus()
            }
            updateTrackingExclusivity()
        }

        switchSixDof.setOnCheckedChangeListener { _, isChecked ->

            sixDofSubPanel.visibility = if (isChecked) View.VISIBLE else View.GONE

            if (isChecked) {
                if (!arCoreAvailable) {
                    switchSixDof.isChecked = false
                    sixDofSubPanel.visibility = View.GONE
                    showStatus("6DoF no disponible: dispositivo no compatible con ARCore")
                } else if (sixDofHandMode == SixDofHandMode.NONE && !supportsConcurrentCameras
                    && switchHand.isChecked && currentTrackingMode != TrackingMode.NONE) {
                    switchSixDof.isChecked = false
                    sixDofSubPanel.visibility = View.GONE
                    showStatus("Solo 1 cámara disponible: desactiva el tracking de manos o usa un modo '6DoF + Manos'")
                } else {
                    if (switchHand.isChecked) {
                        switchHand.isChecked = false
                    }
                    startSixDofRequestingPermission()
                }
            } else {
                stopSixDof()
                showStatus("Tracking 6DoF desactivado")
            }
            updateTrackingExclusivity()
        }

        trackingModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.modeHand         -> TrackingMode.HAND
                R.id.modeLedBlue      -> TrackingMode.LED_BLUE
                R.id.modeLedGreen     -> TrackingMode.LED_GREEN
                R.id.modeHandJoycons  -> TrackingMode.HAND_JOYCONS
                else                  -> TrackingMode.NONE
            }
            if (newMode != currentTrackingMode) {
                currentTrackingMode = newMode
                updateHandJoyconsFlag()
                if (newMode == TrackingMode.NONE) {
                    stopAllTrackers()
                    switchCameraOverlay.isChecked = false
                    cameraOverlayPanel.visibility = View.GONE
                    vrSender?.updateHands(
                        HandPose(-0.35f, 0.1f, -0.5f, false),
                        HandPose( 0.35f, 0.1f, -0.5f, false)
                    )
                    showStatus("Tracking de cámara: ninguno")
                    window.decorView.requestFocus()
                } else if (switchHand.isChecked) {
                    stopAllTrackers()
                    requestCameraAndStart(currentTrackingMode)
                }
                refreshGamepadStatus()
            }
        }

        switchCameraOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !streamActive) {
                cameraOverlayPanel.visibility = View.VISIBLE
            } else {
                cameraOverlayPanel.visibility = View.GONE
            }
            showStatus(if (isChecked) "Vista cámara activada" else "Vista cámara oculta")
            window.decorView.requestFocus()
        }

        switchEyeTracking.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startEyeTrackingRequestingPermission()
            } else {
                stopEyeTracking()
                showStatus("Eye Tracking VRChat desactivado")
            }
        }

        recenterButton.setOnClickListener {
            vrSender?.recenter()
            sixDofTracker?.recenter()
            joyConHid?.recenterBoth()
            showStatus("Recentrado ✓")
        }

        streamButton.setOnClickListener { startStream() }

        closeStreamButton.setOnClickListener {
            when {
                isPlayingLocalVideo -> stopLocalVideoAndReturnToMenu()
                hubModeActive || pendingHubStartAfterSurface -> stopHubMode()
                else -> stopStream()
            }
        }

        recenterHmdButton.setOnClickListener {
            when {
                isPlayingLocalVideo -> {
                    videoHeadTracker?.recenter()
                    showStatus("Video recentrado enfrente tuyo ✓")
                }
                hubModeActive -> {
                    hubWindowController.recenter()
                    sixDofTracker?.recenter()
                    showStatus("Recentrado ✓ (HMD)")
                }
                else -> {
                    vrSender?.recenter()
                    sixDofTracker?.recenter()
                    joyConHid?.recenterBoth()
                    showStatus("Recentrado ✓ (HMD)")
                }
            }
        }
    }



    private fun requestCameraAndStart(mode: TrackingMode) {
        if (mode == TrackingMode.NONE) { stopAllTrackers(); return }
        currentTrackingMode = mode
        pendingCameraAction = { startTracker(mode) }
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startTracker(mode)
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startTracker(mode: TrackingMode) {
        stopAllTrackers()
        if (mode == TrackingMode.NONE) return
        cameraManager = CameraPreviewManager(this, cameraPreviewView, skeletonOverlay)
        runOnUiThread {
            cameraOverlayPanel.visibility =
                if (switchCameraOverlay.isChecked && !streamActive) View.VISIBLE else View.GONE
        }
        val selector = getSelectedCameraSelector()
        when (mode) {
            TrackingMode.HAND, TrackingMode.HAND_JOYCONS -> {
                cameraManager?.startWithHandTracker(
                    owner          = this,
                    onHands        = { l, r -> vrSender?.updateHands(l, r) },
                    onError        = { msg  -> runOnUiThread { showStatus(msg) } },
                    onReady        = {
                        runOnUiThread {
                            if (switchCameraOverlay.isChecked && !streamActive)
                                cameraOverlayPanel.visibility = View.VISIBLE
                            window.decorView.requestFocus()
                            if (mode == TrackingMode.HAND_JOYCONS) warnIfNoJoyConsConnected()
                        }
                    },
                    cameraSelector = selector
                )
                showStatus(
                    if (mode == TrackingMode.HAND_JOYCONS)
                        "HandJoycons: posición de manos por cámara + rotación/botones de Joy-Con (iniciando…)"
                    else
                        "Tracking: MediaPipe manos (iniciando…)"
                )
            }
            TrackingMode.LED_BLUE, TrackingMode.LED_GREEN -> {
                cameraManager?.startWithColorTracker(
                    owner          = this,
                    onHands        = { l, r -> vrSender?.updateHands(l, r) },
                    onError        = { msg  -> runOnUiThread { showStatus(msg) } },
                    onReady        = {
                        runOnUiThread {
                            if (switchCameraOverlay.isChecked && !streamActive)
                                cameraOverlayPanel.visibility = View.VISIBLE
                            window.decorView.requestFocus()
                        }
                    },
                    cameraSelector = selector
                )
                showStatus("Tracking: LED verde=izq 🟢  azul=der 🔵")
            }
            TrackingMode.NONE -> {}
        }
    }


    private fun warnIfNoJoyConsConnected() {
        if (currentTrackingMode != TrackingMode.HAND_JOYCONS) return
        val pads = GamepadManager.connectedGamepads()
        val hasJoyCon = pads.any { it.lowercase().contains("joy-con") || it.lowercase().contains("joycon") }
        if (!hasJoyCon) {
            showStatus("HandJoycons: ningún Joy-Con emparejado todavía — rotación en reposo hasta emparejar uno")
        }
    }

    private fun stopAllTrackers() {
        cameraManager?.stop()
        cameraManager = null
        if (::skeletonOverlay.isInitialized) skeletonOverlay.clear()
    }



    private fun showStatus(msg: String) {
        runOnUiThread {
            if (controlPanel.visibility == View.VISIBLE)
                controlStatusText.text = msg
            else
                statusText.text = msg

            if (msg.contains("6DoF") || msg.contains("ARCore")) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Diagnóstico 6DoF")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun setRadioGroupEnabled(group: RadioGroup, enabled: Boolean) {
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = enabled
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.decorView.isFocusable = true
        window.decorView.isFocusableInTouchMode = true
        window.decorView.requestFocus()
    }
}

data class TrackingCameraOption(
    val id: String,
    val name: String,
    val selector: CameraSelector
)
