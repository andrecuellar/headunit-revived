package com.andrerinas.headunitrevived.main

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.andrerinas.headunitrevived.utils.Settings

class HomeFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    private lateinit var self_mode_button: Button
    private lateinit var usb: Button
    private lateinit var settings: Button
    private lateinit var wifi: Button
    private lateinit var wifi_text_view: TextView
    private lateinit var exitButton: Button
    private lateinit var self_mode_text: TextView

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
        return inflater.inflate(R.layout.fragment_home, container, false)
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

        setupListeners()
        updateProjectionButtonText()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { updateProjectionButtonText() }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AapService.scanningState.collect { updateWifiButtonFeedback(it) }
            }
        }

        val appSettings = App.provide(requireContext()).settings

        AppLog.i("HomeFragment: Auto-connect priority order: ${appSettings.autoConnectPriorityOrder}, " +
                "isConnected=${commManager.isConnected}, " +
                "hasAttemptedAutoConnect=$hasAttemptedAutoConnect, " +
                "hasAttemptedSingleUsbAutoConnect=$hasAttemptedSingleUsbAutoConnect, " +
                "hasAutoStarted=$hasAutoStarted")

        // Execute auto-connect methods in user-defined priority order
        for (methodId in appSettings.autoConnectPriorityOrder) {
            if (commManager.isConnected) {
                AppLog.i("HomeFragment: Already connected, stopping auto-connect loop")
                break
            }
            when (methodId) {
                Settings.AUTO_CONNECT_LAST_SESSION -> {
                    if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !commManager.isConnected) {
                        AppLog.i("HomeFragment: Attempting last-session auto-connect")
                        hasAttemptedAutoConnect = true
                        attemptAutoConnect()
                    }
                }
                Settings.AUTO_CONNECT_SELF_MODE -> {
                    if (appSettings.autoStartSelfMode && !hasAutoStarted && !commManager.isConnected) {
                        AppLog.i("HomeFragment: Attempting self-mode auto-start")
                        hasAutoStarted = true
                        startSelfMode()
                    }
                }
                Settings.AUTO_CONNECT_SINGLE_USB -> {
                    if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect && !commManager.isConnected) {
                        AppLog.i("HomeFragment: Attempting single-USB auto-connect")
                        hasAttemptedSingleUsbAutoConnect = true
                        attemptSingleUsbAutoConnect()
                    }
                }
            }
        }
    }

    private fun startSelfMode() {
        AapService.selfMode = true
        val intent = Intent(requireContext(), AapService::class.java)
        intent.action = AapService.ACTION_START_SELF_MODE
        ContextCompat.startForegroundService(requireContext(), intent)
        AppLog.i("Auto start selfmode")
        //Toast.makeText(requireContext(), "Starting Self Mode...", Toast.LENGTH_SHORT).show()
    }

    private fun attemptAutoConnect() {
        val appSettings = App.provide(requireContext()).settings

        if (!appSettings.autoConnectLastSession ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) {
            return
        }

        val connectionType = appSettings.lastConnectionType
        if (connectionType.isEmpty()) {
            AppLog.i("Auto-connect: No last session to reconnect to")
            return
        }

        when (connectionType) {
            Settings.CONNECTION_TYPE_WIFI -> {
                val ip = appSettings.lastConnectionIp
                if (ip.isNotEmpty()) {
                    AppLog.i("Auto-connect: Attempting WiFi connection to $ip")
                    Toast.makeText(requireContext(), getString(R.string.auto_connecting_to, ip), Toast.LENGTH_SHORT).show()
                    val ctx = requireContext()
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(ctx).commManager.connect(ip, 5277) }
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
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
                    } else {
                        AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
                    }
                }
            }
        }
    }

    private fun attemptSingleUsbAutoConnect() {
        val appSettings = App.provide(requireContext()).settings
        if (!appSettings.autoConnectSingleUsbDevice ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) return

        AppLog.i("HomeFragment: Requesting single-USB auto-connect via AapService")
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
    }

    private fun setupListeners() {
        exitButton.setOnClickListener {
            val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_STOP_SERVICE
            }
            ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            requireActivity().finishAffinity()
        }

        self_mode_button.setOnClickListener {
            if (commManager.isConnected) {
                val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                startSelfMode()
            }
        }

        usb.setOnClickListener {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_usbListFragment)
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
                        // Already connected, no toast needed
                    } else if (AapService.scanningState.value) {
                        Toast.makeText(requireContext(), getString(R.string.already_scanning), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.searching_headunit_server), Toast.LENGTH_SHORT).show()
                        val intent = Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS_SCAN
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
                    }
                }
                2 -> { // Helper (Wireless Launcher)
                    if (commManager.isConnected) {
                        // Already connected, no toast needed
                    } else if (AapService.scanningState.value) {
                        Toast.makeText(requireContext(), getString(R.string.already_searching_phone), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT).show()
                        val intent = Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS_SCAN
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
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
    }

    companion object {
        private var hasAutoStarted = false
        private var hasAttemptedAutoConnect = false
        private var hasAttemptedSingleUsbAutoConnect = false

        fun resetAutoStart() {
            hasAutoStarted = false
            hasAttemptedAutoConnect = false
            hasAttemptedSingleUsbAutoConnect = false
        }
    }
}