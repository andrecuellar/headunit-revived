package com.andrerinas.headunitrevived.main

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.graphics.Color
import android.content.res.ColorStateList
import android.widget.*
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import android.net.VpnService
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.NearbyManager
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import android.content.res.Configuration
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import com.andrerinas.headunitrevived.utils.AppLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.VpnControl
import com.andrerinas.headunitrevived.utils.BluetoothHelper
import com.andrerinas.headunitrevived.connection.UsbReceiver
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            AppLog.i("VPN permission granted. Starting DummyVpnService and Self Mode.")
            VpnControl.startVpn(requireContext());
            startSelfModeInternal()
        } else {
            AppLog.w("VPN permission denied. Offline Self Mode might fail.")
            Toast.makeText(requireContext(), getString(R.string.failed_start_android_auto), Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            showNativeAaDeviceSelector()
        } else {
            Toast.makeText(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var self_mode_button: Button
    private lateinit var usb: Button
    private lateinit var settings: Button
    private lateinit var wifi: Button
    private lateinit var wifi_text_view: TextView
    private lateinit var exitButton: Button
    private lateinit var self_mode_text: TextView
    private var hasAttemptedAutoConnect = false
    private var hasAttemptedSingleUsbAutoConnect = false
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null

    private fun updateWifiButtonFeedback(scanning: Boolean) {
        if (scanning) {
            wifi_text_view.text = getString(R.string.searching)
            wifi.alpha = 0.6f
        } else {
            wifi_text_view.text = getString(R.string.wifi)
            wifi.alpha = 1.0f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Redesign 3.2: pick the Home layout variant. All three variants contain
        // the 4 tiles + exit (same IDs) so the shared binding never breaks; they
        // differ only in the "hero" area above the tiles.
        val layout = when (App.provide(requireContext()).settings.homeStyle) {
            Settings.HOME_STYLE_MINIMAL -> R.layout.fragment_home_minimal
            Settings.HOME_STYLE_FOCUS -> R.layout.fragment_home_focus
            else -> R.layout.fragment_home
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        self_mode_button = view.findViewById(R.id.self_mode_button)
        usb = view.findViewById(R.id.usb_button)
        settings = view.findViewById(R.id.settings_button)
        wifi = view.findViewById(R.id.wifi_button)
        wifi_text_view = view.findViewById(R.id.wifi_text)
        exitButton = view.findViewById(R.id.exit_button)
        self_mode_text = view.findViewById(R.id.self_mode_text)

        // Portrait layout: cap grid width so square buttons never overflow
        // into the WiFi-pill or Exit-button areas on compact/square devices.
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            constrainPortraitGridWidth(view)
        }

        setupListeners()
        updateProjectionButtonText()
        updateStatusCard()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect {
                    updateProjectionButtonText()
                    updateStatusCard()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AapService.scanningState.collect { updateWifiButtonFeedback(it) }
            }
        }

        val appSettings = App.provide(requireContext()).settings

        if (appSettings.autoStartOnScreenOn || appSettings.autoStartOnBoot) {
            ContextCompat.startForegroundService(requireContext(),
                Intent(requireContext(), AapService::class.java))
        }

        for (methodId in appSettings.autoConnectPriorityOrder) {
            if (commManager.isConnected) break
            when (methodId) {
                Settings.AUTO_CONNECT_LAST_SESSION -> {
                    if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !commManager.isConnected) {
                        hasAttemptedAutoConnect = true
                        if (attemptAutoConnect()) {
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "auto-connect last session",
                                MainActivity.ConnectionUiMode.PILL
                            )
                        }
                    }
                }
                Settings.AUTO_CONNECT_SELF_MODE -> {
                    if ((appSettings.autoStartSelfMode || forceSelfModeLaunch) && !hasAutoStarted && !commManager.isConnected) {
                        hasAutoStarted = true
                        forceSelfModeLaunch = false // Reset once processed
                        (requireActivity() as? MainActivity)?.beginAutoConnect(
                            "auto-start self mode",
                            MainActivity.ConnectionUiMode.PILL
                        )
                        startSelfMode()
                    }
                }
                Settings.AUTO_CONNECT_SINGLE_USB -> {
                    if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect && !commManager.isConnected) {
                        hasAttemptedSingleUsbAutoConnect = true
                        if (attemptSingleUsbAutoConnect()) {
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "auto-connect single USB",
                                MainActivity.ConnectionUiMode.PILL
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startSelfModeInternal() {
        AapService.selfMode = true
        val intent = Intent(requireContext(), AapService::class.java)
        intent.action = AapService.ACTION_START_SELF_MODE
        ContextCompat.startForegroundService(requireContext(), intent)
        AppLog.i("Auto start selfmode")
    }

    private fun startSelfMode() {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else null

        if (activeNetwork == null && VpnControl.isVpnAvailable()) {
            AppLog.i("Device is offline. Preparing Dummy VPN for Self Mode.")
            val vpnIntent = VpnService.prepare(requireContext())
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
                return
            } else {
                AppLog.i("VPN permission already granted. Starting VPN service.")
                VpnControl.startVpn(requireContext());
            }
        } else if (activeNetwork == null) {
            AppLog.i("Device is offline and VPN is not available in this build. Self Mode may fail.")
        }
        startSelfModeInternal()
    }

    /**
     * Tries to start an auto-reconnect to the last session.
     *
     * @return `true` if a connection attempt was actually dispatched (so the
     *   caller should surface the pill), `false` if nothing was started (e.g.
     *   Native AA mode, no last session, missing USB device or permission).
     *   Returning a flag prevents the pill from being shown for 30 s when no
     *   work was queued.
     */
    private fun attemptAutoConnect(): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings

        // [FIX] Skip manual WiFi connection if Native AA is selected.
        // Native AA handles its own handshake via Bluetooth/P2P.
        if (appSettings.wifiConnectionMode == 3) {
            AppLog.i("HomeFragment: Native AA mode active. Skipping manual auto-connect attempt.")
            return false
        }

        if (!appSettings.autoConnectLastSession ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) {
            return false
        }

        val connectionType = appSettings.lastConnectionType
        if (connectionType.isEmpty()) {
            AppLog.i("Auto-connect: No last session to reconnect to")
            return false
        }

        return when (connectionType) {
            Settings.CONNECTION_TYPE_WIFI -> {
                if (appSettings.wifiConnectionMode == 1) {
                    val ip = appSettings.lastConnectionIp
                    if (ip.isNotEmpty()) {
                        AppLog.i("Auto-connect: Attempting WiFi connection to $ip")
                        Toast.makeText(ctx, getString(R.string.auto_connecting_to, ip), Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch(Dispatchers.IO) { App.provide(ctx).commManager.connect(ip, 5277) }
                        ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
                            action = AapService.ACTION_CONNECT_SOCKET
                        })
                        true
                    } else false
                } else {
                    AppLog.i("Auto-connect: Last session was WiFi, but connection mode is not Headunit Server. Skipping active connect.")
                    false
                }
            }
            Settings.CONNECTION_TYPE_USB -> {
                val lastUsbDevice = appSettings.lastConnectionUsbDevice
                if (lastUsbDevice.isNotEmpty()) {
                    val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                    val matchingDevice = usbManager.deviceList.values.find { device ->
                        UsbDeviceCompat.getUniqueName(device) == lastUsbDevice
                    }
                    if (matchingDevice != null && usbManager.hasPermission(matchingDevice)) {
                        AppLog.i("Auto-connect: Attempting USB connection to $lastUsbDevice")
                        Toast.makeText(requireContext(), getString(R.string.auto_connecting_usb), Toast.LENGTH_SHORT).show()
                        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_CHECK_USB
                        })
                        true
                    } else {
                        AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
                        false
                    }
                } else false
            }
            Settings.CONNECTION_TYPE_NEARBY -> {
                AppLog.i("Auto-connect: Last session was via Google Nearby. AapService will handle discovery.")
                // No manual connect(ip) needed, NearbyManager in AapService manages this automatically on start.
                true
            }
            else -> false
        }
    }

    /**
     * @return `true` if a single-USB connection attempt was dispatched,
     *   `false` if guards (setting disabled, disclaimer pending, already
     *   connected) blocked it. Same intent as [attemptAutoConnect].
     */
    private fun attemptSingleUsbAutoConnect(): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings
        if (!appSettings.autoConnectSingleUsbDevice ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) return false

        AppLog.i("HomeFragment: Requesting single-USB auto-connect via AapService")
        ContextCompat.startForegroundService(ctx,
            Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
        return true
    }

    private class PrimaryEntry(
        val iconRes: Int,
        val title: String,
        val subtitle: String,
        val action: () -> Unit
    )

    /**
     * Redesign 3.2: computes the primary "connect" entry shared by all three
     * Home variants. Priority: active session → attached USB (prefers last-used)
     * → last session (Self Mode via 127.x loopback, WiFi IP, USB device, Nearby)
     * → null (empty). Self-mode sessions are recorded as WiFi with a 127.x
     * loopback address (design fallback rule).
     */
    private fun computePrimaryEntry(): PrimaryEntry? {
        val ctx = context ?: return null
        val appSettings = App.provide(ctx).settings
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val attached = usbManager.deviceList.values.let { devices ->
            devices.find { UsbDeviceCompat.getUniqueName(it) == appSettings.lastConnectionUsbDevice }
                ?: devices.firstOrNull()
        }
        val type = appSettings.lastConnectionType
        val ip = appSettings.lastConnectionIp

        return when {
            commManager.isConnected -> PrimaryEntry(
                R.drawable.ic_launch_white, getString(R.string.to_android_auto),
                getString(R.string.connected)
            ) { self_mode_button.performClick() }

            attached != null -> {
                val label = (if (Build.VERSION.SDK_INT >= 21) attached.productName else null)
                    ?: UsbDeviceCompat.getUniqueName(attached)
                PrimaryEntry(R.drawable.ic_usb_white, label, getString(R.string.home_sub_usb_ready)) {
                    if (usbManager.hasPermission(attached)) {
                        (activity as? MainActivity)?.beginAutoConnect("home USB", MainActivity.ConnectionUiMode.OVERLAY)
                        ContextCompat.startForegroundService(ctx,
                            Intent(ctx, AapService::class.java).apply { action = AapService.ACTION_CHECK_USB })
                    } else usb.performClick()
                }
            }

            type == Settings.CONNECTION_TYPE_WIFI && ip.startsWith("127.") -> PrimaryEntry(
                R.drawable.ic_launch_white, getString(R.string.self_mode), getString(R.string.home_sub_self_mode)
            ) { self_mode_button.performClick() }

            type == Settings.CONNECTION_TYPE_WIFI && ip.isNotEmpty() -> PrimaryEntry(
                R.drawable.ic_network_wifi_white, ip, getString(R.string.home_sub_wifi_last)
            ) {
                if (appSettings.wifiConnectionMode == 0 || appSettings.wifiConnectionMode == 1) {
                    (activity as? MainActivity)?.beginAutoConnect("home WiFi", MainActivity.ConnectionUiMode.OVERLAY)
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(ctx).commManager.connect(ip, 5277) }
                    ContextCompat.startForegroundService(ctx,
                        Intent(ctx, AapService::class.java).apply { action = AapService.ACTION_CONNECT_SOCKET })
                } else wifi.performClick()
            }

            type == Settings.CONNECTION_TYPE_USB && appSettings.lastConnectionUsbDevice.isNotEmpty() -> PrimaryEntry(
                R.drawable.ic_usb_white, appSettings.lastConnectionUsbDevice, getString(R.string.home_sub_usb_last)
            ) { usb.performClick() }

            type == Settings.CONNECTION_TYPE_NEARBY -> PrimaryEntry(
                R.drawable.ic_network_wifi_white,
                appSettings.lastNearbyDeviceName.ifEmpty { getString(R.string.wifi) },
                getString(R.string.home_sub_wireless_last)
            ) { wifi.performClick() }

            else -> null
        }
    }

    /**
     * Binds the primary entry to whichever hero the active Home variant provides:
     * Full's status card, Minimal's hero card, or Focus's daily button. Each is
     * guarded by findViewById so only the present variant's views are touched.
     */
    private fun updateStatusCard() {
        val root = view ?: return
        val ctx = context ?: return
        val appSettings = App.provide(ctx).settings
        val entry = computePrimaryEntry()

        // Full variant: "Available devices" status card.
        root.findViewById<View>(R.id.home_device_card)?.let { deviceCard ->
            val icon = root.findViewById<ImageView>(R.id.home_device_icon)
            val name = root.findViewById<TextView>(R.id.home_device_name)
            val sub = root.findViewById<TextView>(R.id.home_device_sub)
            val empty = root.findViewById<TextView>(R.id.home_status_empty)
            val connect = root.findViewById<Button>(R.id.home_connect_button)
            val footer = root.findViewById<TextView>(R.id.home_status_footer)
            if (entry != null) {
                deviceCard.visibility = View.VISIBLE
                connect?.visibility = View.VISIBLE
                empty?.visibility = View.GONE
                icon?.setImageResource(entry.iconRes)
                name?.text = entry.title
                sub?.text = entry.subtitle
                connect?.text = getString(R.string.home_connect)
                connect?.setOnClickListener { entry.action() }
                footer?.visibility = if (appSettings.autoConnectLastSession) View.VISIBLE else View.GONE
            } else {
                deviceCard.visibility = View.GONE
                connect?.visibility = View.GONE
                footer?.visibility = View.GONE
                empty?.visibility = View.VISIBLE
            }
        }

        // Minimal variant: the hero card IS the connect button.
        root.findViewById<View>(R.id.home_hero_card)?.let { hero ->
            root.findViewById<ImageView>(R.id.home_hero_icon)?.setImageResource(entry?.iconRes ?: R.drawable.ic_launch_white)
            root.findViewById<TextView>(R.id.home_hero_name)?.text = entry?.title ?: getString(R.string.home_ready)
            root.findViewById<TextView>(R.id.home_hero_sub)?.text = entry?.subtitle ?: getString(R.string.home_tap_to_connect)
            hero.setOnClickListener { entry?.action?.invoke() }
        }

        // Focus variant: one big daily connect button + device label above it.
        root.findViewById<Button>(R.id.home_daily_button)?.let { btn ->
            btn.setOnClickListener { entry?.action?.invoke() }
            root.findViewById<TextView>(R.id.home_daily_label)?.text = entry?.title ?: getString(R.string.home_ready)
        }
    }

    private val originalBackgrounds = mapOf(
        R.id.self_mode_button to R.drawable.tile_gradient_self,
        R.id.usb_button to R.drawable.tile_gradient_usb,
        R.id.wifi_button to R.drawable.tile_gradient_wifi,
        R.id.settings_button to R.drawable.tile_gradient_settings
    )

    private fun applyMonochromeStyle() {
        val monochromeBackground = ContextCompat.getDrawable(requireContext(), R.drawable.gradient_monochrome)
        val grayTint = ColorStateList.valueOf(0xFF808080.toInt())
        listOf(self_mode_button, usb, wifi, settings).forEach { button ->
            button.background = monochromeBackground?.constantState?.newDrawable()?.mutate()
            (button as? com.google.android.material.button.MaterialButton)?.iconTint = grayTint
        }
    }

    private fun restoreOriginalStyle() {
        val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        val buttons = listOf(self_mode_button, usb, wifi, settings)
        val ids = listOf(R.id.self_mode_button, R.id.usb_button, R.id.wifi_button, R.id.settings_button)
        buttons.zip(ids).forEach { (button, id) ->
            originalBackgrounds[id]?.let { drawableRes ->
                button.background = ContextCompat.getDrawable(requireContext(), drawableRes)
            }
            (button as? com.google.android.material.button.MaterialButton)?.iconTint = whiteTint
        }
    }

    private fun updateButtonStyle() {
        val appSettings = App.provide(requireContext()).settings
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isDarkTheme = appSettings.appTheme == Settings.AppTheme.DARK ||
                          appSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
                          isNightActive
        if (isDarkTheme && appSettings.monochromeIcons) {
            applyMonochromeStyle()
        } else {
            restoreOriginalStyle()
        }
    }

    private fun setupListeners() {
        exitButton.setOnClickListener {
            val appSettings = App.provide(requireContext()).settings
            val keepServiceAlive = appSettings.autoStartOnBoot ||
                appSettings.autoStartOnScreenOn ||
                (appSettings.autoStartOnUsb && appSettings.reopenOnReconnection)
            if (keepServiceAlive) {
                val disconnectIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(requireContext(), disconnectIntent)
            } else {
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
            requireActivity().finishAffinity()
        }

        self_mode_button.setOnClickListener {
            if (commManager.isConnected) {
                val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "manual self mode",
                    MainActivity.ConnectionUiMode.OVERLAY
                )
                startSelfMode()
            }
        }

        usb.setOnClickListener {
            // Already connected to Android Auto - just show projection
            if (commManager.isConnected) {
                val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
                return@setOnClickListener
            }

            // Get list of Android USB devices
            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
            val androidDevices = usbManager.deviceList.values
                .filter { UsbDeviceCompat.isAndroidDevice(it) }

            // If exactly one device found - auto-connect
            if (androidDevices.size == 1) {
                val device = UsbDeviceCompat(androidDevices[0])
                AppLog.i("USB button: Single device found - ${device.uniqueName}, auto-connecting")
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "USB button auto-connect",
                    MainActivity.ConnectionUiMode.OVERLAY
                )

                if (device.isInAccessoryMode) {
                    ContextCompat.startForegroundService(requireContext(),
                        Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_CHECK_USB
                        })
                } else {
                    if (usbManager.hasPermission(device.wrappedDevice)) {
                        val usbMode = UsbAccessoryMode(usbManager)
                        val useLibusb = App.provide(requireContext()).settings.useLibusb
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val success = usbMode.connectAndSwitch(device.wrappedDevice, useLibusb)
                            withContext(Dispatchers.Main) {
                                context?.let { ctx ->
                                    if (success) {
                                        Toast.makeText(ctx, R.string.switching_to_android_auto, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(ctx, R.string.switch_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    } else {
                        Toast.makeText(requireContext(), R.string.requesting_usb_permission, Toast.LENGTH_SHORT).show()
                        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
                        usbManager.requestPermission(
                            device.wrappedDevice,
                            UsbReceiver.createPermissionPendingIntent(requireContext())
                        )
                    }
                }
            } else {
                // 0 or multiple devices - open the list
                val controller = findNavController()
                if (controller.currentDestination?.id == R.id.homeFragment) {
                    controller.navigate(R.id.action_homeFragment_to_usbListFragment)
                }
            }
        }

        settings.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        wifi.setOnClickListener {
            val mode = App.provide(requireContext()).settings.wifiConnectionMode
            when (mode) {
                1 -> { // Auto (Headunit Server) - One-Shot Scan
                    if (commManager.isConnected) {
                        // Already connected
                    } else if (AapService.scanningState.value) {
                        Toast.makeText(requireContext(), getString(R.string.already_scanning), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.searching_headunit_server), Toast.LENGTH_SHORT).show()
                        (requireActivity() as? MainActivity)?.beginAutoConnect(
                            "manual WiFi headunit server scan",
                            MainActivity.ConnectionUiMode.OVERLAY
                        )
                        val intent = Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS_SCAN
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
                    }
                }
                2 -> { // Helper (Wireless Launcher)
                    if (commManager.isConnected) {
                        // Already connected
                    } else {
                        val strategy = App.provide(requireContext()).settings.helperConnectionStrategy
                        if (strategy == 4) {
                            if (!AapService.scanningState.value) {
                                (requireActivity() as? MainActivity)?.beginAutoConnect(
                                    "manual WiFi helper scan",
                                    MainActivity.ConnectionUiMode.OVERLAY
                                )
                                val intent = Intent(requireContext(), AapService::class.java).apply {
                                    action = AapService.ACTION_START_WIRELESS_SCAN
                                }
                                ContextCompat.startForegroundService(requireContext(), intent)
                            }
                            if (App.provide(requireContext()).settings.autoEnableHotspot) {
                                com.andrerinas.headunitrevived.utils.ShareHotspotQrDialog.show(
                                    requireContext()
                                )
                            } else {
                                Toast.makeText(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT).show()
                            }
                        } else if (strategy == 2) {
                            // Nearby Devices — show live discovery dialog
                            showNearbyDeviceSelector()
                        } else if (AapService.scanningState.value) {
                            Toast.makeText(requireContext(), getString(R.string.already_searching_phone), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT).show()
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "manual WiFi helper scan",
                                MainActivity.ConnectionUiMode.OVERLAY
                            )
                            val intent = Intent(requireContext(), AapService::class.java).apply {
                                action = AapService.ACTION_START_WIRELESS_SCAN
                            }
                            ContextCompat.startForegroundService(requireContext(), intent)
                        }
                    }
                }
                3 -> { // Native AA
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        showNativeAaDeviceSelector()
                    }
                }
                else -> { // Manual (0) -> Open List
                    val controller = findNavController()
                    if (controller.currentDestination?.id == R.id.homeFragment) {
                        controller.navigate(R.id.action_homeFragment_to_networkListFragment)
                    }
                }
            }
        }

        wifi.setOnLongClickListener {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_networkListFragment)
            }
            true
        }
    }

    /**
     * Portrait-only: after the first layout pass we know the exact pixel
     * dimensions of the screen and the reserved areas (WiFi-pill spacer at
     * the top, Exit button at the bottom).  We calculate the largest square
     * button that fits in a 2-row grid and constrain the grid's max-width
     * accordingly.  This prevents overflow on square / wide-portrait tablets
     * while allowing full-width buttons on tall phones.
     *
     * Formula:
     *   availableH  = containerH − topSpacerH − exitButtonH
     *   maxBtnSize  = min(containerW / 2, availableH / 2) − cell-padding
     *   maxGridW    = maxBtnSize * 2 + cell-padding * 4   (2 cols, padding each side)
     */
    private fun constrainPortraitGridWidth(rootView: View) {
        val gridLayout = rootView.findViewById<android.widget.LinearLayout>(R.id.main_buttons_layout)
            ?: return
        // Capture density before the callback — accessing `resources` inside onGlobalLayout
        // is unsafe if the fragment has been detached by the time the layout pass fires.
        val density = resources.displayMetrics.density
        gridLayout.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                gridLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (!isAdded) return

                val container = gridLayout.parent as? View ?: return
                val containerW = container.width
                val containerH = container.height
                if (containerW == 0 || containerH == 0) return

                // Reserved vertical space: 64 dp top-spacer + ~56 dp exit button (margin incl.)
                val reservedPx = (64 + 56) * density
                // Extra per-row overhead: label (~20 sp≈20dp) + marginTop (6 dp) + cell padding top+bottom (24 dp)
                val rowOverheadPx = 50 * density

                val availableH = containerH - reservedPx
                // Max button edge that fits in one row without labels overflowing
                val maxBtnFromH = ((availableH / 2f) - rowOverheadPx).toInt()
                val maxBtnFromW = containerW / 2
                val maxBtn = minOf(maxBtnFromH, maxBtnFromW)

                if (maxBtn <= 0) return

                // Grid max-width = 2 buttons + 4 × cell-horizontal-padding (12 dp each side)
                val cellPadPx = (12 * 2 * 2 * density).toInt() // 2 cols × 2 sides × 12 dp
                val maxGridW = maxBtn * 2 + cellPadPx

                // Only shrink, never grow beyond what the existing constraints allow
                if (maxGridW < containerW) {
                    val params = gridLayout.layoutParams as? ConstraintLayout.LayoutParams ?: return
                    params.matchConstraintMaxWidth = maxGridW
                    gridLayout.layoutParams = params
                }
            }
        })
    }

    private fun updateProjectionButtonText() {
        if (commManager.isConnected) {
            self_mode_text.text = getString(R.string.to_android_auto)
        } else {
            self_mode_text.text = getString(R.string.self_mode)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("HomeFragment: onResume. isConnected=${commManager.isConnected}")
        updateProjectionButtonText()
        updateButtonStyle()
        updateTextColors()
        updateStatusCard()
    }

    override fun onPause() {
        super.onPause()
        activeDialog?.dismiss()
        activeDialog = null
    }

    private fun showNativeAaDeviceSelector() {
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(requireContext(), getString(R.string.bt_not_enabled), Toast.LENGTH_SHORT).show()
            return
        }

        val bondedDevices = adapter.bondedDevices?.toList() ?: emptyList()
        if (bondedDevices.isEmpty()) {
            Toast.makeText(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = bondedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()


        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.select_bt_device)
            .setItems(deviceNames) { _, which ->
                val device = bondedDevices[which]
                AppLog.i("HomeFragment: Manually selected ${device.name} for Native-AA poke")

                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "manual Native-AA poke",
                    MainActivity.ConnectionUiMode.OVERLAY
                )
                val intent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_NATIVE_AA_POKE
                    putExtra(AapService.EXTRA_MAC, device.address)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
                Toast.makeText(requireContext(), "Searching for ${device.name}...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNearbyDeviceSelector() {
        // Ensure NearbyManager discovery is running via AapService
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS_SCAN
            })

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_nearby_selection, null)
        val deviceListView = dialogView.findViewById<ListView>(R.id.deviceList)
        val searchingText = dialogView.findViewById<TextView>(R.id.searchingText)
        val connectionProgress = dialogView.findViewById<ProgressBar>(R.id.connectionProgress)

        // Ensure the loading spinner is visible in both Light and Dark modes by forcing our brand color.
        val brandTeal = ContextCompat.getColor(requireContext(), R.color.brand_teal)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connectionProgress.indeterminateTintList = ColorStateList.valueOf(brandTeal)
            connectionProgress.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        } else {
            @Suppress("DEPRECATION")
            connectionProgress.indeterminateDrawable?.setColorFilter(brandTeal, android.graphics.PorterDuff.Mode.SRC_IN)
        }

        // Custom adapter to handle rounded backgrounds like in USB/Network lists
        val listAdapter = object : ArrayAdapter<NearbyManager.DiscoveredEndpoint>(requireContext(), R.layout.list_item_nearby) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_nearby, parent, false)
                val endpoint = getItem(position)
                view.findViewById<TextView>(R.id.deviceName).text = endpoint?.name ?: "Unknown"

                // Apply rounded backgrounds based on position
                val isTop = position == 0
                val isBottom = position == count - 1
                val bgRes = when {
                    isTop && isBottom -> R.drawable.bg_setting_single
                    isTop -> R.drawable.bg_setting_top
                    isBottom -> R.drawable.bg_setting_bottom
                    else -> R.drawable.bg_setting_middle
                }
                view.setBackgroundResource(bgRes)
                return view
            }
        }
        deviceListView.adapter = listAdapter

        var collectJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(getString(R.string.searching)) // Initial title
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                collectJob?.cancel()
                if (activeDialog == it) activeDialog = null
            }
            .create()

        activeDialog = dialog

        deviceListView.setOnItemClickListener { _, _, which, _ ->
            val endpoints = NearbyManager.discoveredEndpoints.value
            if (which < endpoints.size) {
                val endpoint = endpoints[which]
                AppLog.i("HomeFragment: Selected Nearby device: ${endpoint.name} (${endpoint.id})")

                // Hand off to the auto-connect overlay so the user gets the same
                // visual treatment as every other connection path. Pass the
                // endpoint name so the loading screen can show "Connecting to
                // <device>…" instead of the generic status text.
                val statusText = getString(R.string.connecting_to_nearby, endpoint.name)
                dialog.dismiss()
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "manual Nearby select ${endpoint.name}",
                    MainActivity.ConnectionUiMode.OVERLAY,
                    statusText
                )

                val intent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_NEARBY_CONNECT
                    putExtra(AapService.EXTRA_ENDPOINT_ID, endpoint.id)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
            }
        }

        dialog.show()

        // Live-update the dialog list as endpoints are discovered
        collectJob = viewLifecycleOwner.lifecycleScope.launch {
            NearbyManager.discoveredEndpoints.collect { endpoints ->
                listAdapter.clear()
                listAdapter.addAll(endpoints)
                listAdapter.notifyDataSetChanged()

                if (endpoints.isEmpty()) {
                    dialog.setTitle(getString(R.string.searching))
                    searchingText.visibility = View.GONE
                } else {
                    dialog.setTitle(getString(R.string.nearby_device_found))
                    searchingText.visibility = View.VISIBLE
                    searchingText.text = getString(R.string.select_nearby_device) + " (${endpoints.size})"
                }
            }
        }
    }

    private fun updateTextColors() {
        val appSettings = App.provide(requireContext()).settings
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isLightMode = nightModeFlags != Configuration.UI_MODE_NIGHT_YES

        val labelViews = listOf(self_mode_text, wifi_text_view,
            view?.findViewById<TextView>(R.id.usb_text),
            view?.findViewById<TextView>(R.id.settings_text))

        if (appSettings.useGradientBackground && isLightMode) {
            val darkColor = Color.parseColor("#1a1a1a")
            labelViews.filterNotNull().forEach { tv ->
                tv.setTextColor(darkColor)
                tv.setShadowLayer(2f, 0f, 0f, Color.WHITE)
            }
        } else {
            val lightColor = Color.parseColor("#f7f7f7")
            labelViews.filterNotNull().forEach { tv ->
                tv.setTextColor(lightColor)
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }

        exitButton.setTextColor(Color.WHITE)
    }

    companion object {
        private var hasAutoStarted = false
        var forceSelfModeLaunch = false
        fun resetAutoStart() {
            hasAutoStarted = false
        }
    }
}
