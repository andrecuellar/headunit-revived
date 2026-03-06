package com.andrerinas.headunitrevived.aap

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.Parcel
import android.os.Parcelable
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.NightModeEvent
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.connection.NetworkDiscovery
import android.support.v4.media.session.MediaSessionCompat
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.connection.UsbReceiver
import com.andrerinas.headunitrevived.location.GpsLocationService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.DeviceIntent
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.utils.NightModeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import android.app.NotificationManager
import java.net.ServerSocket

/**
 * Top-level foreground service that manages the Android Auto connection lifecycle.
 *
 * Responsibilities:
 * - Manages the [CommManager] connection state machine (USB and WiFi)
 * - Drives [AapProjectionActivity] via intents and connection state flow
 * - Runs a [WirelessServer] for the "server" WiFi mode and coordinates [NetworkDiscovery] scans
 * - Keeps a foreground notification updated to reflect the current connection state
 * - Manages car mode, night mode, media session, and GPS location service
 *
 * Connection types:
 * - **USB**: [UsbReceiver] detects attach → [checkAlreadyConnectedUsb] → [connectUsbWithRetry]
 * - **WiFi (client)**: [NetworkDiscovery] finds a Headunit Server → [CommManager.connect]
 * - **WiFi (server)**: [WirelessServer] accepts incoming sockets from AA Wireless / Self Mode
 * - **Self Mode**: starts [WirelessServer] and launches the AA Wireless Setup Activity on-device
 */
class AapService : Service(), UsbReceiver.Listener {

    // SupervisorJob prevents a child coroutine failure from cancelling the whole scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var uiModeManager: UiModeManager
    private lateinit var usbReceiver: UsbReceiver
    private var nightModeManager: NightModeManager? = null
    private var wirelessServer: WirelessServer? = null
    private var networkDiscovery: NetworkDiscovery? = null
    private var mediaSession: MediaSessionCompat? = null

    /**
     * Set to `true` before calling [stopSelf] or entering [onDestroy] to suppress any
     * flow observers that would otherwise update the already-dismissed notification.
     */
    private var isDestroying = false

    private val commManager get() = App.provide(this).commManager

    private var usbStabilityJob: Job? = null
    private var stableDeviceName: String? = null

    /**
     * Tracks the number of consecutive handshake failures on an accessory-mode device
     * (Google 18D1:2D00). Wireless AA dongles stay in accessory mode after a session
     * ends, but the USB endpoints contain stale data from the old SSL session. Connecting
     * to such a device always fails because the handshake reads garbage instead of a
     * fresh VERSION_RESPONSE. After [MAX_STALE_ACCESSORY_RETRIES] failures, the app
     * sends AOA descriptors to force the dongle to re-enumerate with clean USB buffers.
     */
    private var accessoryHandshakeFailures = 0

    fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) {
            android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
        } else {
            android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
        }

        mediaSession?.setPlaybackState(
            android.support.v4.media.session.PlaybackStateCompat.Builder()
                .setState(state, 0, 1.0f)
                .setActions(android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                           android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                           android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP)
                .build()
        )
        AppLog.d("MediaSession: State updated to ${if (isPlaying) "PLAYING" else "STOPPED"}")
    }

    // Receives ACTION_REQUEST_NIGHT_MODE_UPDATE broadcasts sent by the key-binding handler
    // when the user presses the night-mode toggle key.
    private val nightModeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_NIGHT_MODE_UPDATE) {
                AppLog.i("Received request to resend night mode state")
                nightModeManager?.resendCurrentState()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        AppLog.i("AapService creating...")

        startForeground(1, createNotification())
        setupCarMode()
        setupNightMode()
        observeConnectedState()
        observeDisconnectedState()
        observeTransportStartedState()
        observeErrorState()
        registerReceivers()

        startService(GpsLocationService.intent(this))
        initWifiMode()
        checkAlreadyConnectedUsb()
    }

    /** Enables Android Automotive UI mode so the system uses car-optimised layouts. */
    private fun setupCarMode() {
        uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        uiModeManager.enableCarMode(0)
    }

    /** Initialises [NightModeManager] and forwards night-mode changes to Android Auto via AAP. */
    private fun setupNightMode() {
        nightModeManager = NightModeManager(this, App.provide(this).settings) { isNight ->
            AppLog.i("NightMode update: $isNight")
            commManager.send(NightModeEvent(isNight))
        }
        nightModeManager?.start()
    }

    /**
     * Observes [CommManager.ConnectionState.Connected] and calls onConnected
     */
    private fun observeConnectedState() {
        serviceScope.launch {
            commManager.connectionState
                .filterIsInstance<CommManager.ConnectionState.Connected>()
                .collect { onConnected() }
        }
    }

    /**
     * Observes [CommManager.ConnectionState.TransportStarted] and syncs night mode.
     * Night mode must be sent after the transport is ready, not at connection time.
     */
    private fun observeTransportStartedState() {
        serviceScope.launch {
            commManager.connectionState
                .filterIsInstance<CommManager.ConnectionState.TransportStarted>()
                .collect {
                    accessoryHandshakeFailures = 0
                    sendBroadcast(Intent(ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
                        setPackage(packageName)
                    })
        }
    }

    /**
     * Observes [CommManager.ConnectionState.Error] to detect handshake failures on
     * accessory-mode devices (stale wireless AA dongles). When the handshake fails on
     * a device already in accessory mode, the USB endpoints contain leftover data from
     * a previous session. After [MAX_STALE_ACCESSORY_RETRIES] failures, we force the
     * dongle to re-enumerate by sending AOA descriptors, which resets its USB state.
     */
    private fun observeErrorState() {
        serviceScope.launch {
            commManager.connectionState
                .filterIsInstance<CommManager.ConnectionState.Error>()
                .collect { error ->
                    if (error.message.contains("Handshake failed")) {
                        onHandshakeFailed()
                    }
                }
        }
    }

    /**
     * Called when a handshake fails. If an accessory-mode device is still present,
     * it's likely a stale wireless AA dongle. Force re-enumeration by sending AOA
     * descriptors — this resets the dongle's USB state so the next connection
     * starts with clean buffers.
     */
    private fun onHandshakeFailed() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val accessoryDevice = usbManager.deviceList.values.firstOrNull {
            UsbDeviceCompat.isInAccessoryMode(it)
        } ?: return

        accessoryHandshakeFailures++
        val deviceName = UsbDeviceCompat(accessoryDevice).uniqueName
        AppLog.w("Handshake failed on accessory device $deviceName (failure #$accessoryHandshakeFailures)")

        if (accessoryHandshakeFailures > MAX_STALE_ACCESSORY_RETRIES) {
            AppLog.i("Stale accessory detected: forcing re-enumeration via AOA descriptors for $deviceName")
            accessoryHandshakeFailures = 0
            val usbMode = UsbAccessoryMode(usbManager)
            serviceScope.launch(Dispatchers.IO) {
                if (usbMode.connectAndSwitch(accessoryDevice)) {
                    AppLog.i("AOA re-enumeration requested for stale device $deviceName")
                } else {
                    AppLog.w("AOA re-enumeration failed for $deviceName, waiting for device to reset on its own")
                }
            }
        }
    }

    /**
     * Observes [CommManager.ConnectionState.Disconnected] and calls onDisconnect
     * `drop(1)` skips the initial `Disconnected` value that `StateFlow` replays on subscribe.
     */
    private fun observeDisconnectedState() {
        serviceScope.launch {
            commManager.connectionState
                .filterIsInstance<CommManager.ConnectionState.Disconnected>()
                .drop(1) // skip the StateFlow's initial Disconnected emission on startup
                .collect { state -> onDisconnected(state) }
        }
    }

    /**
     * Called by [CommManager.ConnectionState.Connected] observer:
     * 1. Refreshes the foreground notification.
     * 2. Activates a [MediaSessionCompat] so media keys are routed to Android Auto.
     * 3. Starts the SSL handshake ([CommManager.startHandshake]) **in parallel** with
     *    launching [AapProjectionActivity], hiding multi-second handshake latency behind
     *    activity-inflation time.
     *
     * The inbound message loop ([CommManager.startReading]) is intentionally NOT started
     * here. It is deferred until [AapProjectionActivity] confirms its render surface is
     * ready (via [CommManager.ConnectionState.HandshakeComplete] observer), guaranteeing
     * that [VideoDecoder.setSurface] is always called before the first video frame arrives.
     */
    private fun onConnected() {
        updateNotification()
        mediaSession = MediaSessionCompat(this, "HeadunitRevived").apply { isActive = true }
        serviceScope.launch { commManager.startHandshake() }
        startActivity(AapProjectionActivity.intent(this).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }

    /**
     * Called by [CommManager.ConnectionState.Disconnected] observer:
     * 1. Refreshing the notification (unless we are already tearing down)
     * 2. Releasing the [MediaSessionCompat]
     * 3. Stopping audio/video decoders on the IO thread
     * 4. Scheduling a reconnect attempt if applicable (see [scheduleReconnectIfNeeded])
     */
    private fun onDisconnected(state: CommManager.ConnectionState.Disconnected) {
        if (!isDestroying) updateNotification()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        serviceScope.launch(Dispatchers.IO) {
            App.provide(this@AapService).audioDecoder.stop()
            App.provide(this@AapService).videoDecoder.stop("AapService::onDisconnect")
        }
        scheduleReconnectIfNeeded(state)
    }

    /**
     * Schedules a reconnect attempt after a disconnect:
     *
     * - **Server mode** ([wirelessServer] != null): always restarts the WiFi discovery loop.
     * - **Auto WiFi mode** (mode == 1): triggers a one-shot WiFi scan on unclean disconnect.
     * - **USB auto-connect**: if auto-connect is enabled and the last connection was USB,
     *   schedule a USB reconnect attempt. This handles wireless AA dongles that stay in
     *   stale accessory mode after a session ends — the dongle eventually re-enumerates
     *   as its native identity, and [checkAlreadyConnectedUsb] picks it up.
     *
     * [CommManager.ConnectionState.Disconnected.isClean] is `true` only when the phone
     * explicitly sends a `ByeByeRequest`. All other causes (USB detach, read error, explicit
     * disconnect) produce `isClean = false`.
     */
    private fun scheduleReconnectIfNeeded(state: CommManager.ConnectionState.Disconnected) {
        if (wirelessServer != null) {
            AppLog.i("AapService: Disconnected. Restarting discovery loop in 2s...")
            serviceScope.launch {
                delay(2000)
                if (!commManager.isConnected) startDiscovery()
            }
            return
        }

        val settings = App.provide(this).settings
        val lastType = settings.lastConnectionType

        // USB auto-reconnect: try again after a delay to give the dongle time to re-enumerate
        if (lastType == Settings.CONNECTION_TYPE_USB &&
            (settings.autoConnectLastSession || settings.autoConnectSingleUsbDevice)) {
            AppLog.i("AapService: USB disconnect. Scheduling reconnect check in 3s...")
            serviceScope.launch {
                delay(3000)
                if (!commManager.isConnected) {
                    checkAlreadyConnectedUsb(force = true)
                }
            }
        }

        if (!state.isClean) {
            val mode = settings.wifiConnectionMode
            if (mode == 1 && lastType != Settings.CONNECTION_TYPE_USB) {
                AppLog.i("AapService: Unclean disconnect in Auto Mode. Retrying discovery in 2s...")
                serviceScope.launch {
                    delay(2000)
                    if (!commManager.isConnected) startDiscovery(oneShot = true)
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private fun registerReceivers() {
        usbReceiver = UsbReceiver(this)
        ContextCompat.registerReceiver(
            this, nightModeUpdateReceiver,
            IntentFilter(ACTION_REQUEST_NIGHT_MODE_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, usbReceiver,
            UsbReceiver.createFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Starts [WirelessServer] if the user has configured server WiFi mode (mode == 2). */
    private fun initWifiMode() {
        if (App.provide(this).settings.wifiConnectionMode == 2) startWirelessServer()
    }

    override fun onDestroy() {
        AppLog.i("AapService destroying...")
        isDestroying = true
        stopForeground(true)
        stopWirelessServer()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        commManager.disconnect()
        serviceScope.launch(Dispatchers.IO) {
            App.provide(this@AapService).audioDecoder.stop()
            App.provide(this@AapService).videoDecoder.stop("AapService::onDestroy")
        }
        nightModeManager?.stop()
        unregisterReceiver(nightModeUpdateReceiver)
        unregisterReceiver(usbReceiver)
        uiModeManager.disableCarMode(0)
        serviceScope.cancel() // cancel last so the IO decoder-stop launch above can complete
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop before re-posting the notification to avoid a flash
        if (intent?.action == ACTION_STOP_SERVICE) {
            AppLog.i("Stop action received.")
            isDestroying = true
            if (commManager.isConnected) commManager.disconnect()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(1, createNotification())
        when (intent?.action) {
            ACTION_START_SELF_MODE       -> startSelfMode()
            ACTION_START_WIRELESS        -> startWirelessServer()
            ACTION_START_WIRELESS_SCAN   -> {
                val mode = App.provide(this).settings.wifiConnectionMode
                startDiscovery(oneShot = (mode != 2))
            }
            ACTION_STOP_WIRELESS         -> stopWirelessServer()
            ACTION_DISCONNECT            -> {
                AppLog.i("Disconnect action received.")
                if (commManager.isConnected) commManager.disconnect()
            }
            ACTION_CONNECT_SOCKET        -> {
                // Caller already invoked commManager.connect(socket); the connectionState
                // observer in observeConnectedState() handles the rest — nothing to do here.
            }
            ACTION_CHECK_USB             -> checkAlreadyConnectedUsb(force = true)
            else                         -> {
                if (intent?.action == null || intent.action == Intent.ACTION_MAIN) {
                    checkAlreadyConnectedUsb()
                }
            }
        }
        return START_STICKY
    }

    // -------------------------------------------------------------------------
    // USB
    // -------------------------------------------------------------------------

    override fun onUsbAttach(device: UsbDevice) {
        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            // Device already in AOA mode (re-enumerated after UsbAttachedActivity switched it).
            AppLog.i("USB accessory device attached, connecting.")
            checkAlreadyConnectedUsb(force = true)
        }
        // Normal-mode devices are handled exclusively by UsbAttachedActivity (manifest intent
        // filter). Calling connectAndSwitch() here would race with UsbAttachedActivity and
        // cause openDevice() to return null in one of the two callers → "Failed" toast.
    }

    override fun onUsbDetach(device: UsbDevice) {
        if (commManager.isConnectedToUsbDevice(device)) {
            // Cable physically removed — the USB connection is already dead, so skip the
            // ByeByeRequest send (which would block ~1 s trying to write to a gone device).
            commManager.disconnect(sendByeBye = false)
        }
    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {
        val deviceName = UsbDeviceCompat(device).uniqueName
        if (granted) {
            AppLog.i("USB permission granted for $deviceName")
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                serviceScope.launch { connectUsbWithRetry(device) }
            } else {
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val usbMode = UsbAccessoryMode(usbManager)
                serviceScope.launch(Dispatchers.IO) {
                    if (usbMode.connectAndSwitch(device)) {
                        AppLog.i("Successfully requested switch to accessory mode for $deviceName")
                    } else {
                        AppLog.w("USB permission granted but connectAndSwitch failed for $deviceName")
                    }
                }
            }
        } else {
            AppLog.w("USB permission denied for $deviceName")
        }
    }

    /**
     * Scans currently connected USB devices and connects to any that are already in
     * Android Open Accessory (AOA) mode, or attempts to switch a known device into AOA mode.
     *
     * @param force When `true`, bypasses the [autoConnectLastSession] guard. Use `true` when
     *              called in response to an actual USB attach event or from [UsbAttachedActivity],
     *              because the user has explicitly plugged in a device. Use `false` (default)
     *              for the startup scan in [onCreate].
     */
    private fun checkAlreadyConnectedUsb(force: Boolean = false) {
        val settings = App.provide(this).settings
        val lastSession = settings.autoConnectLastSession
        val singleUsb = settings.autoConnectSingleUsbDevice

        if (!force && !lastSession && !singleUsb) return
        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting) return

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        // Check for devices already in accessory mode first
        for (device in deviceList.values) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                AppLog.i("Found device already in accessory mode: ${UsbDeviceCompat(device).uniqueName}")
                serviceScope.launch { connectUsbWithRetry(device) }
                return
            }
        }

        // Last-session mode: reconnect to a known/allowed device
        if (lastSession) {
            for (device in deviceList.values) {
                val deviceCompat = UsbDeviceCompat(device)
                if (settings.isConnectingDevice(deviceCompat)) {
                    if (usbManager.hasPermission(device)) {
                        AppLog.i("Found known USB device with permission: ${deviceCompat.uniqueName}. Switching to accessory mode.")
                        val usbMode = UsbAccessoryMode(usbManager)
                        serviceScope.launch(Dispatchers.IO) {
                            if (usbMode.connectAndSwitch(device)) {
                                AppLog.i("Successfully requested switch to accessory mode for ${deviceCompat.uniqueName}")
                            }
                        }
                        return
                    } else {
                        AppLog.i("Found known USB device but no permission: ${deviceCompat.uniqueName}, requesting...")
                        requestUsbPermission(device)
                        return
                    }
                }
            }
        }

        // Single-USB mode: if exactly one non-accessory device is present, connect to it
        if (singleUsb) {
            val nonAccessoryDevices = deviceList.values.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            if (nonAccessoryDevices.size == 1) {
                // Skip if stability check is already running for this exact device
                val deviceName = UsbDeviceCompat(nonAccessoryDevices[0]).uniqueName
                if (usbStabilityJob != null && stableDeviceName == deviceName) {
                    AppLog.i("checkAlreadyConnectedUsb: stability check already in progress for $deviceName, skipping")
                    return
                }
                startSingleUsbStabilityCheck(nonAccessoryDevices[0])
                return
            } else if (usbStabilityJob != null) {
                cancelUsbStabilityCheck()
            }
        }
    }

    private fun startSingleUsbStabilityCheck(device: UsbDevice) {
        val settings = App.provide(this).settings
        if (!settings.usbStabilityCheck) {
            performSingleUsbConnect(device)
            return
        }

        val deviceName = UsbDeviceCompat(device).uniqueName
        val timeoutMs = settings.usbStabilityTimeout * 1000L

        stableDeviceName = deviceName
        usbStabilityJob?.cancel()

        AppLog.i("USB stability: Starting ${settings.usbStabilityTimeout}s timer for $deviceName")
        Toast.makeText(this, getString(R.string.usb_device_settling), Toast.LENGTH_SHORT).show()

        usbStabilityJob = serviceScope.launch {
            delay(timeoutMs)

            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val stillPresent = usbManager.deviceList.values.any {
                UsbDeviceCompat(it).uniqueName == deviceName
            }

            if (stillPresent) {
                val dev = usbManager.deviceList.values.first {
                    UsbDeviceCompat(it).uniqueName == deviceName
                }
                AppLog.i("USB stability: Device $deviceName stable after ${settings.usbStabilityTimeout}s, connecting")
                performSingleUsbConnect(dev)
            } else {
                AppLog.i("USB stability: Device $deviceName disappeared during wait")
                cancelUsbStabilityCheck()
            }
        }
    }

    private fun performSingleUsbConnect(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Single USB auto-connect: connecting to $deviceName")
            val usbMode = UsbAccessoryMode(usbManager)
            serviceScope.launch(Dispatchers.IO) {
                if (usbMode.connectAndSwitch(device)) {
                    AppLog.i("Successfully requested switch to accessory mode for single USB device. Waiting for re-enumeration...")
                } else {
                    AppLog.w("Single USB auto-connect: connectAndSwitch failed for $deviceName")
                }
            }
        } else {
            AppLog.i("Single USB auto-connect: device found but no permission, requesting...")
            requestUsbPermission(device)
        }
        cancelUsbStabilityCheck()
    }

    private fun cancelUsbStabilityCheck() {
        usbStabilityJob?.cancel()
        usbStabilityJob = null
        stableDeviceName = null
    }

    /**
     * Requests USB host permission for [device] via the Android system dialog.
     * The result is delivered asynchronously to [onUsbPermission] via [UsbReceiver].
     */
    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(UsbReceiver.ACTION_USB_DEVICE_PERMISSION).apply {
                setPackage(packageName)
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        AppLog.i("Requesting USB permission for ${UsbDeviceCompat(device).uniqueName}")
        Toast.makeText(this, getString(R.string.requesting_usb_permission), Toast.LENGTH_SHORT).show()
        usbManager.requestPermission(device, permissionIntent)
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /**
     * Attempts a USB connection up to [maxRetries] times with a 1.5 s delay between attempts.
     *
     * USB accessories occasionally fail on the first attach (the device hasn't fully
     * enumerated yet), so retrying is necessary for reliability.
     */
    private suspend fun connectUsbWithRetry(device: UsbDevice, maxRetries: Int = 3) {
        var retryCount = 0
        var success = false
        while (retryCount <= maxRetries && !success) {
            if (retryCount > 0) {
                AppLog.i("Retrying USB connection (attempt ${retryCount + 1}/$maxRetries)...")
                delay(1500)
                // A USB reattach during the delay could have already started a new connection;
                // bail out to avoid two parallel retry loops competing on the same device.
                if (commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting) return
            }
            commManager.connect(device)
            success = commManager.connectionState.value is CommManager.ConnectionState.Connected
            retryCount++
        }
    }

    // -------------------------------------------------------------------------
    // Wireless
    // -------------------------------------------------------------------------

    /**
     * Starts the [WirelessServer] (TCP on port 5288) and kicks off the initial NSD scan.
     * No-op if the server is already running.
     */
    private fun startWirelessServer() {
        if (wirelessServer != null) return
        wirelessServer = WirelessServer().apply { start() }
        startDiscovery()
    }

    /**
     * Starts an NSD (mDNS) scan for Android Auto Wireless services on the local network.
     *
     * @param oneShot if `true`, does not reschedule after the scan finishes —
     *                used for the "auto WiFi" reconnect case.
     */
    private fun startDiscovery(oneShot: Boolean = false) {
        if (commManager.isConnected || (wirelessServer == null && !oneShot)) return

        networkDiscovery?.stop()
        scanningState.value = true

        networkDiscovery = NetworkDiscovery(this, object : NetworkDiscovery.Listener {
            override fun onServiceFound(ip: String, port: Int, socket: java.net.Socket?) {
                if (commManager.isConnected) {
                    // Already connected by the time this callback fired; discard the socket
                    try { socket?.close() } catch (e: Exception) {}
                    return
                }
                when (port) {
                    5277 -> {
                        // Headunit Server detected — reuse the pre-opened socket when possible
                        AppLog.i("Auto-connecting to Headunit Server at $ip:$port (reusing socket)")
                        serviceScope.launch {
                            if (socket != null && socket.isConnected)
                                commManager.connect(socket)
                            else
                                commManager.connect(ip, 5277)
                        }
                    }
                    5289 -> {
                        // WiFi Launcher detected — no connection needed, just log
                        AppLog.i("Triggered Wifi Launcher at $ip:$port.")
                    }
                }
            }

            override fun onScanFinished() {
                scanningState.value = false
                if (oneShot) {
                    AppLog.i("One-shot scan finished.")
                    return
                }
                // Reschedule the next scan after 10 s to avoid hammering the network
                serviceScope.launch {
                    delay(10000)
                    if (wirelessServer != null && !commManager.isConnected) startDiscovery()
                }
            }
        })
        networkDiscovery?.startScan()
    }

    private fun stopWirelessServer() {
        networkDiscovery?.stop()
        networkDiscovery = null
        wirelessServer?.stopServer()
        wirelessServer = null
        scanningState.value = false
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AapService::class.java).apply { action = ACTION_STOP_SERVICE },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Tap the notification to go back to the projection screen (if connected) or home
        val (notificationIntent, requestCode) = if (commManager.isConnected) {
            AapProjectionActivity.intent(this).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } to 100
        } else {
            Intent(this, com.andrerinas.headunitrevived.main.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } to 101
        }

        val contentText = if (commManager.isConnected)
            getString(R.string.notification_projection_active)
        else
            getString(R.string.notification_service_running)

        return NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentTitle("Headunit Revived")
            .setContentText(contentText)
            .setContentIntent(PendingIntent.getActivity(
                this, requestCode, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            ))
            .addAction(R.drawable.ic_exit_to_app_white_24dp, getString(R.string.exit), stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification())
    }

    // -------------------------------------------------------------------------
    // Self Mode
    // -------------------------------------------------------------------------

    /**
     * "Self Mode" connects the device to itself over the loopback interface.
     *
     * Starts [WirelessServer] on port 5288, then launches the Google AA Wireless Setup
     * Activity pointing at `127.0.0.1:5288`. This causes the AA Wireless app to treat
     * the device as both the head unit and the phone, enabling a loopback session.
     *
     * [createFakeNetwork] and [createFakeWifiInfo] produce the Parcelable extras the
     * AA Wireless activity requires; they are constructed reflectively because the
     * relevant Android classes have no public constructors.
     */
    private fun startSelfMode() {
        startWirelessServer()

        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            connectivityManager.activeNetwork else null
        val networkToUse = activeNetwork ?: createFakeNetwork(99999)
        val fakeWifiInfo = createFakeWifiInfo()

        val magicalIntent = Intent().apply {
            setClassName(
                "com.google.android.projection.gearhead",
                "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
            putExtra("PARAM_SERVICE_PORT", 5288)
            networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
            fakeWifiInfo?.let { putExtra("wifi_info", it) }
        }

        try {
            AppLog.i("Launching AA Wireless Startup...")
            startActivity(magicalIntent)
        } catch (e: Exception) {
            AppLog.e("Failed to launch AA", e)
            Toast.makeText(this, getString(R.string.failed_start_android_auto), Toast.LENGTH_SHORT).show()
        }
    }

    /** Reflectively constructs an `android.net.Network` from a raw network ID integer. */
    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(netId)
            parcel.setDataPosition(0)
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as Parcelable
        } catch (e: Exception) { null } finally { parcel.recycle() }
    }

    /** Reflectively constructs a `WifiInfo` with a fake SSID for the Self Mode intent. */
    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val wifiInfo = wifiInfoClass.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance() as Parcelable
            try {
                wifiInfoClass.getDeclaredField("mSSID")
                    .apply { isAccessible = true }
                    .set(wifiInfo, "\"Headunit-Fake-Wifi\"")
            } catch (e: Exception) {}
            wifiInfo
        } catch (e: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // WirelessServer
    // -------------------------------------------------------------------------

    /**
     * Coroutine-based server that listens for incoming TCP connections on port 5288.
     *
     * Registers the service over mDNS (NSD) as `_aawireless._tcp` so Android Auto
     * Wireless clients can discover it automatically. Each accepted socket is handed
     * off to [CommManager.connect] on the service coroutine scope. Only one connection
     * is allowed at a time; subsequent sockets are closed immediately.
     *
     * Uses [isActive] for cooperative cancellation. [stopServer] cancels the job and
     * closes the server socket to unblock the blocking [ServerSocket.accept] call.
     */
    private inner class WirelessServer {
        private var serverSocket: ServerSocket? = null
        private var nsdManager: NsdManager? = null
        private var registrationListener: NsdManager.RegistrationListener? = null
        private var job: Job? = null

        fun start() {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
            registerNsd()

            job = serviceScope.launch(Dispatchers.IO) {
                try {
                    serverSocket = ServerSocket(5288).apply { reuseAddress = true }
                    AppLog.i("Wireless Server listening on port 5288")
                    logLocalNetworkInterfaces()

                    while (isActive) {
                        val clientSocket = serverSocket?.accept() ?: break
                        AppLog.i("Wireless client connected: ${clientSocket.inetAddress}")
                        serviceScope.launch {
                            if (commManager.isConnected) {
                                AppLog.w("Already connected, dropping wireless client")
                                withContext(Dispatchers.IO) {
                                    try { clientSocket.close() } catch (e: Exception) {}
                                }
                            } else {
                                AppLog.i("Wireless client accepted from ${clientSocket.inetAddress}. Initializing connection...")
                                commManager.connect(clientSocket)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) AppLog.e("Wireless server error", e)
                } finally {
                    unregisterNsd()
                    try { serverSocket?.close() } catch (e: Exception) {}
                }
            }
        }

        /** Logs all non-loopback IPv4 addresses; useful for debugging connectivity issues. */
        private fun logLocalNetworkInterfaces() {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            AppLog.i("Interface: ${iface.name}, IP: ${addr.hostAddress}")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Error logging interfaces", e)
            }
        }

        private fun registerNsd() {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = 5288
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("NSD Registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Reg Fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = AppLog.i("NSD Unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Unreg Fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }

        private fun unregisterNsd() {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            registrationListener = null
        }

        fun stopServer() {
            job?.cancel()
            job = null
            // Close the socket to unblock the accept() call in the coroutine.
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        /** `true` while a Self Mode session is active. */
        var selfMode = false

        /**
         * Emits `true` while a WiFi NSD scan is in progress.
         * Observed by `HomeFragment` via a lifecycle-aware flow collector.
         */
        val scanningState = MutableStateFlow(false)

        // Service action strings used with startService() and sendBroadcast()
        const val ACTION_START_SELF_MODE           = "com.andrerinas.headunitrevived.ACTION_START_SELF_MODE"
        const val ACTION_START_WIRELESS            = "com.andrerinas.headunitrevived.ACTION_START_WIRELESS"
        const val ACTION_START_WIRELESS_SCAN       = "com.andrerinas.headunitrevived.ACTION_START_WIRELESS_SCAN"
        const val ACTION_STOP_WIRELESS             = "com.andrerinas.headunitrevived.ACTION_STOP_WIRELESS"
        const val ACTION_CHECK_USB                 = "com.andrerinas.headunitrevived.ACTION_CHECK_USB"
        const val ACTION_STOP_SERVICE              = "com.andrerinas.headunitrevived.ACTION_STOP_SERVICE"
        const val ACTION_DISCONNECT                = "com.andrerinas.headunitrevived.ACTION_DISCONNECT"
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE = "com.andrerinas.headunitrevived.ACTION_REQUEST_NIGHT_MODE_UPDATE"
        /**
         * Sent after the caller has already invoked [CommManager.connect(socket)].
         * The [observeConnectedState] flow observer handles the result — [onStartCommand]
         * does nothing for this action.
         */
        const val ACTION_CONNECT_SOCKET            = "com.andrerinas.headunitrevived.ACTION_CONNECT_SOCKET"

        /** Max handshake failures on a stale accessory device before forcing AOA re-enumeration. */
        private const val MAX_STALE_ACCESSORY_RETRIES = 1
    }
}
