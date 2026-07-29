package com.andrerinas.headunitrevived.main

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.app.BaseActivity
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.utils.AppPermissions
import com.andrerinas.headunitrevived.utils.AppThemeManager
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.utils.PermissionRowBinder
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.SystemOptimizer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.sqrt

/**
 * Intelligent, device-aware first-run wizard. Replaces the old
 * SafetyDisclaimerDialog + SetupWizard dialog chain with a single guided flow.
 *
 * Shown once to everyone (including upgraders) when the stored onboardingVersion
 * is older than CURRENT_ONBOARDING_VERSION, and re-launchable from Settings.
 *
 * Steps: Welcome(+language) / Safety(mandatory) / Connection / Display scan /
 * Appearance / Automation / Location / Ready.
 *
 * Every step reuses the real settings and their strings, and deep-links into the
 * full settings sub-screens for advanced configuration.
 */
class OnboardingActivity : BaseActivity() {

    private val settings by lazy { App.provide(this).settings }
    private lateinit var flipper: ViewFlipper
    private lateinit var backBtn: MaterialButton
    private lateinit var nextBtn: MaterialButton
    private lateinit var skipBtn: MaterialButton
    private lateinit var stepper: LinearLayout

    private var step = 0
    private var isBinding = false

    private var selectedSize = SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    private var selectedPortrait = false
    private var dpiPicker: com.andrerinas.headunitrevived.view.DpiPickerView? = null

    // Permissions step (registered here, before the activity is RESUMED).
    private var permissionBinder: PermissionRowBinder? = null
    private val permNormalLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionBinder?.rebind() }
    private val permSpecialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionBinder?.rebind() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Match the app theme overlays used by MainActivity/SettingsActivity so the wizard
        // honours Extreme Dark (pure black) and the gradient background.
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (settings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (settings.useExtremeDarkMode && isNightActive)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        } else if (settings.useGradientBackground) {
            theme.applyStyle(R.style.ThemeOverlay_GradientBackground, true)
        }

        setContentView(R.layout.activity_onboarding)

        step = savedInstanceState?.getInt(KEY_STEP, 0) ?: 0

        flipper = findViewById(R.id.onb_flipper)
        backBtn = findViewById(R.id.onb_back)
        nextBtn = findViewById(R.id.onb_next)
        skipBtn = findViewById(R.id.onb_skip)
        stepper = findViewById(R.id.onb_stepper)

        selectedPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        selectedSize = estimateSizePreset()

        buildStepperDots()
        bindSteps()
        bindPermissionsStep()

        backBtn.setOnClickListener { if (step > 0) { step--; render() } }
        nextBtn.setOnClickListener { onNext() }
        skipBtn.setOnClickListener { onDoItLater() }

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
    }

    override fun onResume() {
        super.onResume()
        // Reflect any changes made in deep-linked settings screens (theme, automation) or after
        // returning from a special-permission settings screen (overlay / modify system settings).
        updateThemeButtonText()
        updateNightButtonText()
        permissionBinder?.rebind()
    }

    private fun buildStepperDots() {
        stepper.removeAllViews()
        for (i in 0 until STEP_COUNT) {
            val dot = View(this)
            val h = (5 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(h, h)
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            dot.layoutParams = lp
            stepper.addView(dot)
        }
    }

    private fun updateStepperDots() {
        val density = resources.displayMetrics.density
        val active = resolveAttrColor(com.google.android.material.R.attr.colorPrimary)
        val inactive = 0x33808080
        for (i in 0 until STEP_COUNT) {
            val dot = stepper.getChildAt(i) ?: continue
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            lp.width = ((if (i == step) 26 else 8) * density).toInt()
            dot.layoutParams = lp
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = 5 * density
            bg.setColor(if (i <= step) active else inactive)
            dot.background = bg
        }
    }

    private fun bindSteps() {
        // --- Welcome: language ---
        findViewById<MaterialButton>(R.id.onb_language_button).apply {
            text = currentLanguageLabel()
            setOnClickListener { showLanguageDialog() }
        }

        // --- Safety ---
        findViewById<TextView>(R.id.onb_safety_text).text =
            Html.fromHtml(getString(R.string.disclaimer_text))
        findViewById<MaterialCheckBox>(R.id.onb_safety_accept).apply {
            isChecked = settings.hasAcceptedDisclaimer
            setOnCheckedChangeListener { _, checked ->
                // Persist immediately so "Do it later" works once the terms are accepted.
                settings.hasAcceptedDisclaimer = checked
                if (step == STEP_SAFETY) nextBtn.isEnabled = checked
            }
        }

        // --- Connection: USB (cable or USB adapter) / WiFi / Self Mode ---
        val connGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_conn_group)
        isBinding = true
        when (settings.primaryConnection) {
            Settings.ConnectionKind.WIFI, Settings.ConnectionKind.NATIVE_AA -> connGroup.check(R.id.onb_conn_wifi)
            Settings.ConnectionKind.SELF_MODE -> connGroup.check(R.id.onb_conn_self)
            Settings.ConnectionKind.ALL -> connGroup.check(R.id.onb_conn_all)
            Settings.ConnectionKind.USB_CABLE, Settings.ConnectionKind.USB_WIRELESS_ADAPTER -> connGroup.check(R.id.onb_conn_usb)
            else -> {}
        }
        isBinding = false
        updateConnectionDetail()
        connGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBinding) return@addOnButtonCheckedListener
            settings.primaryConnection = when (checkedId) {
                R.id.onb_conn_wifi -> Settings.ConnectionKind.WIFI
                R.id.onb_conn_self -> Settings.ConnectionKind.SELF_MODE
                R.id.onb_conn_all -> Settings.ConnectionKind.ALL
                else -> Settings.ConnectionKind.USB_CABLE
            }
            updateConnectionDetail()
        }

        // --- Display: detected panel + size/orientation, pre-selected from detection ---
        findViewById<TextView>(R.id.onb_display_detected).text = detectedDisplayText()
        val sizeGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_size_group)
        sizeGroup.check(sizeButtonId(selectedSize))
        sizeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            selectedSize = when (id) {
                R.id.onb_size_phone -> SystemOptimizer.DisplaySizePreset.PHONE_4_6
                R.id.onb_size_small -> SystemOptimizer.DisplaySizePreset.SMALL_7_8
                R.id.onb_size_large -> SystemOptimizer.DisplaySizePreset.LARGE_11_PLUS
                else -> SystemOptimizer.DisplaySizePreset.STANDARD_9_10
            }
        }
        val orientGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_orient_group)
        orientGroup.check(if (selectedPortrait) R.id.onb_orient_port else R.id.onb_orient_land)
        orientGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            selectedPortrait = id == R.id.onb_orient_port
        }

        // Graphical DPI picker (slider + preview + Small/Medium/Large tabs, all synced),
        // pre-set to the recommended DPI for the detected panel.
        dpiPicker = findViewById<com.andrerinas.headunitrevived.view.DpiPickerView>(R.id.onb_dpi_picker).apply {
            val m = realMetrics()
            setPanelResolution(m.widthPixels, m.heightPixels)
            dpi = recommendedDpi()
        }

        // --- Appearance: full theme + night-mode pickers; more options live in Settings ---
        updateThemeButtonText()
        findViewById<MaterialButton>(R.id.onb_theme_button).setOnClickListener { showThemeDialog() }
        updateNightButtonText()
        findViewById<MaterialButton>(R.id.onb_night_button).setOnClickListener { showNightModeDialog() }

        // --- Automation: simple toggles inline, priority + advanced via the real screens ---
        findViewById<SwitchMaterial>(R.id.onb_autoconnect_last_switch).apply {
            isChecked = settings.autoConnectLastSession
            setOnCheckedChangeListener { _, v -> settings.autoConnectLastSession = v }
        }
        findViewById<SwitchMaterial>(R.id.onb_autoconnect_single_switch).apply {
            isChecked = settings.autoConnectSingleUsbDevice
            setOnCheckedChangeListener { _, v -> settings.autoConnectSingleUsbDevice = v }
        }
        // USB-only auto-start toggles (also written to device storage, like AutoStartFragment).
        findViewById<SwitchMaterial>(R.id.onb_autostart_usb_switch).apply {
            isChecked = settings.autoStartOnUsb
            setOnCheckedChangeListener { _, v ->
                settings.autoStartOnUsb = v
                Settings.syncAutoStartOnUsbToDeviceStorage(this@OnboardingActivity, v)
            }
        }
        findViewById<SwitchMaterial>(R.id.onb_reopen_switch).apply {
            isChecked = settings.reopenOnReconnection
            setOnCheckedChangeListener { _, v -> settings.reopenOnReconnection = v }
        }
        // WiFi-only auto-start toggle.
        findViewById<SwitchMaterial>(R.id.onb_autostart_wifi_switch).apply {
            isChecked = settings.autoStartOnWifi
            setOnCheckedChangeListener { _, v ->
                settings.autoStartOnWifi = v
                Settings.syncAutoStartOnWifiToDeviceStorage(this@OnboardingActivity, v)
            }
        }
        // --- Location: this device's GPS vs the connected phone's GPS ---
        val gpsGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_gps_group)
        isBinding = true
        gpsGroup.check(if (settings.useGpsForNavigation) R.id.onb_gps_device else R.id.onb_gps_phone)
        isBinding = false
        gpsGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBinding) return@addOnButtonCheckedListener
            settings.useGpsForNavigation = checkedId == R.id.onb_gps_device
        }

        // --- Car brand: a friendly identity sent to Android Auto (display name + both makes) ---
        bindVehicleStep()
    }

    /**
     * Binds the car-brand step: a live head-unit screen, an editable field and a horizontal
     * strip of brand chips. The current stored value is shown first and pre-selected, and the
     * field is the source of truth (chips are shortcuts).
     */
    private fun bindVehicleStep() {
        val input = findViewById<TextInputEditText>(R.id.onb_vehicle_input)
        val screen = findViewById<TextView>(R.id.onb_vehicle_screen_name)
        val chips = findViewById<ChipGroup>(R.id.onb_vehicle_chips)

        // Steering wheel side: reuse the existing right-hand-drive setting and mirror the
        // dashboard illustration live (wheel moves to the right when right-hand drive is on).
        val dashboard = findViewById<ImageView>(R.id.onb_vehicle_dashboard)
        val rhdSwitch = findViewById<SwitchMaterial>(R.id.onb_vehicle_rhd_switch)
        fun applyWheelSide(rhd: Boolean) { dashboard.scaleX = if (rhd) -1f else 1f }
        rhdSwitch.isChecked = settings.rightHandDrive
        applyWheelSide(settings.rightHandDrive)
        rhdSwitch.setOnCheckedChangeListener { _, checked ->
            settings.rightHandDrive = checked
            applyWheelSide(checked)
        }

        val current = settings.vehicleDisplayName.trim()
        // Build the brand list with the current value first ("option 1"), then the presets.
        val brands = resources.getStringArray(R.array.vehicle_brands).toMutableList()
        if (current.isNotEmpty()) {
            brands.removeAll { it.equals(current, ignoreCase = true) }
            brands.add(0, current)
        }
        chips.removeAllViews()
        brands.forEach { brand ->
            val chip = layoutInflater.inflate(R.layout.item_vehicle_chip, chips, false) as Chip
            chip.text = brand
            chip.id = ViewCompat.generateViewId()
            chips.addView(chip)
        }

        isBinding = true
        input.setText(current)
        screen.text = current
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as Chip
            if (chip.text.toString().equals(current, ignoreCase = true)) {
                chip.isChecked = true
                break
            }
        }
        isBinding = false

        chips.setOnCheckedStateChangeListener { group, checkedIds ->
            if (isBinding) return@setOnCheckedStateChangeListener
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id) ?: return@setOnCheckedStateChangeListener
            isBinding = true
            input.setText(chip.text)
            input.setSelection(input.text?.length ?: 0)
            screen.text = chip.text
            isBinding = false
        }

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                screen.text = text
                if (isBinding) return
                // Clear a chip selection that no longer matches the typed text.
                val checkedId = chips.checkedChipId
                if (checkedId != View.NO_ID) {
                    val chip = chips.findViewById<Chip>(checkedId)
                    if (chip != null && !chip.text.toString().equals(text.trim(), ignoreCase = true)) {
                        isBinding = true
                        chips.clearCheck()
                        isBinding = false
                    }
                }
            }
        })
    }

    private fun render() {
        flipper.displayedChild = step
        backBtn.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        // GONE (not INVISIBLE) so its dedicated row collapses on the final step.
        skipBtn.visibility = if (step == STEP_COUNT - 1) View.GONE else View.VISIBLE
        nextBtn.text = getString(if (step == STEP_COUNT - 1) R.string.onb_ready_finish else R.string.onb_next)
        nextBtn.isEnabled = if (step == STEP_SAFETY)
            findViewById<MaterialCheckBox>(R.id.onb_safety_accept).isChecked else true
        if (step == STEP_READY) findViewById<TextView>(R.id.onb_ready_summary).text = summaryText()
        if (step == STEP_AUTOMATION) applyAutomationVisibility()
        if (step == STEP_PERMISSIONS) permissionBinder?.rebind()
        updateStepperDots()
    }

    /** Wire the permissions checklist. Rows are rendered from the AppPermissions registry, so a
     * future permission only needs a registry entry, not changes here. */
    private fun bindPermissionsStep() {
        val container = findViewById<LinearLayout>(R.id.onb_perms_container)
        permissionBinder = PermissionRowBinder(this, container, permNormalLauncher, permSpecialLauncher)
        findViewById<MaterialButton>(R.id.onb_perms_enable_all).setOnClickListener {
            permissionBinder?.requestAllMissing()
        }
        permissionBinder?.rebind()
    }

    /** Show only the auto-start toggles that match the chosen connection type. Self Mode
     * connects without USB or WiFi, so both groups are hidden. */
    private fun applyAutomationVisibility() {
        val conn = settings.primaryConnection
        val isWifi = conn == Settings.ConnectionKind.WIFI || conn == Settings.ConnectionKind.NATIVE_AA
        val isSelf = conn == Settings.ConnectionKind.SELF_MODE
        val isAll = conn == Settings.ConnectionKind.ALL
        val usbVis = if (isAll || (!isWifi && !isSelf)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.onb_ac_single_row).visibility = usbVis
        findViewById<View>(R.id.onb_as_usb_row).visibility = usbVis
        findViewById<View>(R.id.onb_reopen_row).visibility = usbVis
        // Auto-start on WiFi is a legacy path that only applies up to Android 12L (API 32).
        findViewById<View>(R.id.onb_as_wifi_row).visibility =
            if ((isWifi || isAll) && Build.VERSION.SDK_INT <= 32) View.VISIBLE else View.GONE
    }

    private fun onNext() {
        if (step == STEP_SAFETY) settings.hasAcceptedDisclaimer = true
        // Apply the display recommendation + chosen DPI on Next, so the user does not
        // have to press "Apply recommended settings" for it to take effect.
        if (step == STEP_DISPLAY || step == STEP_DPI) applyDisplaySettings()
        // The chosen car brand fills the display name and both manufacturer fields, so it
        // appears whichever identity field Android Auto reads.
        if (step == STEP_VEHICLE) {
            val brand = findViewById<TextInputEditText>(R.id.onb_vehicle_input)
                .text?.toString()?.trim().orEmpty()
            if (brand.isNotEmpty()) {
                settings.vehicleDisplayName = brand
                settings.vehicleMake = brand
                settings.headUnitMake = brand
            }
        }
        if (step == STEP_COUNT - 1) finishOnboarding() else { step++; render() }
    }

    /**
     * "Do it later": close the wizard for now WITHOUT marking it complete, so it appears
     * again on the next app launch. The safety disclaimer is still mandatory, so if it has
     * not been accepted yet we route to that step first instead of leaving.
     */
    private fun onDoItLater() {
        if (!settings.hasAcceptedDisclaimer) {
            step = STEP_SAFETY
            render()
            return
        }
        deferredThisSession = true
        settings.commit()
        finish()
    }

    override fun onBackPressed() {
        // Back walks the steps; from the first step it behaves like "Do it later".
        if (step > 0) { step--; render() } else onDoItLater()
    }

    private fun finishOnboarding() {
        settings.hasAcceptedDisclaimer = true
        settings.hasCompletedSetupWizard = true
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        settings.commit()
        finish()
    }

    // --- Language ---

    private fun currentLanguageLabel(): String {
        val locale = LocaleHelper.stringToLocale(settings.appLanguage)
        return if (locale == null) getString(R.string.system_default)
        else LocaleHelper.getDisplayName(locale)
    }

    private fun showLanguageDialog() {
        val locales = LocaleHelper.getAvailableLocales(this)
        val labels = ArrayList<String>()
        labels.add(getString(R.string.system_default))
        locales.forEach { labels.add(LocaleHelper.getDisplayName(it)) }
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.app_language)
            .setItems(labels.toTypedArray()) { _, which ->
                val newLang = if (which == 0) "" else LocaleHelper.localeToString(locales[which - 1])
                if (newLang != settings.appLanguage) {
                    settings.appLanguage = newLang
                    recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Connection ---

    private fun updateConnectionDetail() {
        val detail = findViewById<TextView>(R.id.onb_conn_detail)
        detail.text = when (settings.primaryConnection) {
            Settings.ConnectionKind.WIFI, Settings.ConnectionKind.NATIVE_AA -> getString(R.string.onb_connection_wifi_detail)
            Settings.ConnectionKind.SELF_MODE -> getString(R.string.onb_connection_self_detail)
            Settings.ConnectionKind.ALL -> getString(R.string.onb_connection_all_detail)
            Settings.ConnectionKind.USB_CABLE, Settings.ConnectionKind.USB_WIRELESS_ADAPTER -> getString(R.string.onb_connection_usb_detail)
            else -> ""
        }
    }

    // --- Display scan ---

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    /** Estimate the physical diagonal from real pixels and physical DPI, pick the closest preset. */
    private fun estimateSizePreset(): SystemOptimizer.DisplaySizePreset {
        val m = realMetrics()
        val xdpi = if (m.xdpi > 40f) m.xdpi else m.densityDpi.toFloat()
        val ydpi = if (m.ydpi > 40f) m.ydpi else m.densityDpi.toFloat()
        val wIn = m.widthPixels / xdpi
        val hIn = m.heightPixels / ydpi
        val diagonal = sqrt(wIn * wIn + hIn * hIn)
        return SystemOptimizer.DisplaySizePreset.values().minByOrNull {
            kotlin.math.abs(it.diagonalInch - diagonal)
        } ?: SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    }

    private fun sizeButtonId(preset: SystemOptimizer.DisplaySizePreset): Int = when (preset) {
        SystemOptimizer.DisplaySizePreset.PHONE_4_6 -> R.id.onb_size_phone
        SystemOptimizer.DisplaySizePreset.SMALL_7_8 -> R.id.onb_size_small
        SystemOptimizer.DisplaySizePreset.LARGE_11_PLUS -> R.id.onb_size_large
        else -> R.id.onb_size_standard
    }

    private fun detectedDisplayText(): String {
        val m = realMetrics()
        val hevc = VideoDecoder.isHevcSupported()
        val codec = if (hevc) "H.265" else "H.264"
        return getString(R.string.onb_display_detected, m.widthPixels, m.heightPixels, m.densityDpi, codec)
    }

    private fun recommendedDpi(): Int =
        SystemOptimizer(this).calculateOptimalSettings(selectedSize, selectedPortrait).recommendedDpi

    /** Writes the recommended display settings plus the DPI chosen in the picker. Called when
     * leaving the Display and Android Auto size steps, so no separate Apply tap is needed. */
    private fun applyDisplaySettings() {
        val result = SystemOptimizer(this).calculateOptimalSettings(selectedSize, selectedPortrait)
        // Cap the applied resolution to what the physical panel supports (no upscaling waste).
        val panel = realMetrics()
        settings.resolutionId = SystemOptimizer.recommendedResolution(panel.widthPixels, panel.heightPixels).id
        settings.videoCodec = result.recommendedVideoCodec
        settings.viewMode = result.recommendedViewMode
        settings.dpiPixelDensity = dpiPicker?.dpi ?: result.recommendedDpi
        settings.screenOrientation = result.suggestedOrientation
        settings.commit()
    }

    // --- Appearance (reuse the same option arrays as the settings screen) ---

    private fun updateThemeButtonText() {
        val labels = resources.getStringArray(R.array.app_theme)
        val idx = settings.appTheme.value.coerceIn(0, labels.size - 1)
        findViewById<MaterialButton>(R.id.onb_theme_button)?.text = labels[idx]
    }

    private fun updateNightButtonText() {
        val labels = resources.getStringArray(R.array.night_mode)
        val idx = settings.nightMode.value.coerceIn(0, labels.size - 1)
        findViewById<MaterialButton>(R.id.onb_night_button)?.text = labels[idx]
    }

    private fun showThemeDialog() {
        val labels = resources.getStringArray(R.array.app_theme)
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.onb_appearance_theme_label)
            .setSingleChoiceItems(labels, settings.appTheme.value) { dialog, which ->
                val newTheme = Settings.AppTheme.values().firstOrNull { it.value == which }
                    ?: Settings.AppTheme.AUTOMATIC
                settings.appTheme = newTheme
                updateThemeButtonText()
                dialog.dismiss()
                AppThemeManager.applyStaticTheme(settings)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNightModeDialog() {
        val labels = resources.getStringArray(R.array.night_mode)
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.onb_appearance_night_label)
            .setSingleChoiceItems(labels, settings.nightMode.value) { dialog, which ->
                settings.nightMode = Settings.NightMode.fromInt(which) ?: Settings.NightMode.AUTO
                updateNightButtonText()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Ready ---

    private fun summaryText(): String {
        val conn = when (settings.primaryConnection) {
            Settings.ConnectionKind.WIFI, Settings.ConnectionKind.NATIVE_AA -> getString(R.string.connection_kind_wifi)
            Settings.ConnectionKind.SELF_MODE -> getString(R.string.self_mode)
            Settings.ConnectionKind.ALL -> getString(R.string.connection_kind_all)
            Settings.ConnectionKind.USB_CABLE, Settings.ConnectionKind.USB_WIRELESS_ADAPTER -> getString(R.string.connection_kind_usb)
            else -> getString(R.string.connection_kind_unset)
        }
        val res = getString(
            R.string.resolution_recommended_format,
            Settings.Resolution.fromId(settings.resolutionId)?.resName ?: getString(R.string.auto)
        )
        val base = getString(R.string.onb_ready_summary, conn, res, settings.videoCodec)
        val perms = getString(
            R.string.onb_summary_permissions_format,
            AppPermissions.grantedCount(this), AppPermissions.visible().size
        )
        val vehicle = settings.vehicleDisplayName.trim()
        val head = if (vehicle.isNotEmpty())
            getString(R.string.onb_summary_vehicle_format, vehicle) + "\n" + base
        else base
        return head + "\n" + perms
    }

    private fun resolveAttrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    companion object {
        const val CURRENT_ONBOARDING_VERSION = 2
        // Set when the user chooses "Do it later" so MainActivity does not immediately
        // relaunch the wizard this session. Resets on process restart (next app open).
        @Volatile
        var deferredThisSession = false
        private const val KEY_STEP = "onb_step"
        private const val STEP_COUNT = 11
        private const val STEP_SAFETY = 1
        private const val STEP_DISPLAY = 3
        private const val STEP_DPI = 4
        private const val STEP_AUTOMATION = 6
        private const val STEP_VEHICLE = 8
        private const val STEP_PERMISSIONS = 9
        private const val STEP_READY = 10
    }
}
