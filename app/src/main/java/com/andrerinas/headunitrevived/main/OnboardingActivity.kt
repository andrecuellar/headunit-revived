package com.andrerinas.headunitrevived.main

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.app.BaseActivity
import com.andrerinas.headunitrevived.utils.AppThemeManager
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.SystemOptimizer
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Redesign 3.2: "Meet the new design" onboarding.
 *
 * Replaces the old SafetyDisclaimerDialog + SetupWizard chain with a single
 * guided flow shown once after the update (gated by hasSeenRedesignIntro) and
 * re-launchable from Settings. It still writes the same flags the old flow did
 * (hasAcceptedDisclaimer, hasCompletedSetupWizard) so nothing regresses.
 *
 * Steps: Welcome(+language) / Safety(mandatory) / Theme(live) / Connection /
 * Optimize / Permissions / Ready.
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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the Extreme Dark / gradient overlay before inflating so the live
        // preview matches the real app (mirrors MainActivity's theme handling).
        applyThemeOverlay()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        step = savedInstanceState?.getInt(KEY_STEP, 0) ?: 0

        flipper = findViewById(R.id.onb_flipper)
        backBtn = findViewById(R.id.onb_back)
        nextBtn = findViewById(R.id.onb_next)
        skipBtn = findViewById(R.id.onb_skip)
        stepper = findViewById(R.id.onb_stepper)

        buildStepperDots()
        bindSteps()

        backBtn.setOnClickListener { if (step > 0) { step--; render() } }
        nextBtn.setOnClickListener { onNext() }
        skipBtn.setOnClickListener { onSkip() }

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    private fun applyThemeOverlay() {
        val s = App.provide(this).settings
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (s.appTheme == Settings.AppTheme.EXTREME_DARK || (s.useExtremeDarkMode && night)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        } else if (s.useGradientBackground) {
            theme.applyStyle(R.style.ThemeOverlay_GradientBackground, true)
        }
    }

    private fun buildStepperDots() {
        stepper.removeAllViews()
        for (i in 0 until STEP_COUNT) {
            val dot = View(this)
            val h = (5 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(h, h)
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            dot.layoutParams = lp
            dot.tag = "dot$i"
            stepper.addView(dot)
        }
    }

    private fun updateStepperDots() {
        val density = resources.displayMetrics.density
        val active = resolveAttrColor(R.attr.dsPrimary)
        val inactive = resolveAttrColor(R.attr.dsSurfaceElevated)
        for (i in 0 until STEP_COUNT) {
            val dot = stepper.getChildAt(i)
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
        // Welcome: language picker
        findViewById<MaterialButton>(R.id.onb_language_button).apply {
            text = currentLanguageLabel()
            setOnClickListener { showLanguageDialog() }
        }

        // Safety: disclaimer text + accept checkbox
        findViewById<TextView>(R.id.onb_safety_text).text =
            Html.fromHtml(getString(R.string.disclaimer_text))
        findViewById<MaterialCheckBox>(R.id.onb_safety_accept).apply {
            isChecked = settings.hasAcceptedDisclaimer
            setOnCheckedChangeListener { _, checked -> if (step == STEP_SAFETY) nextBtn.isEnabled = checked }
        }

        // Theme: reflect current, apply live on change
        val themeGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_theme_group)
        isBinding = true
        themeGroup.check(
            when (settings.appTheme) {
                Settings.AppTheme.EXTREME_DARK -> R.id.onb_theme_extreme
                Settings.AppTheme.DARK -> R.id.onb_theme_dark
                else -> R.id.onb_theme_light
            }
        )
        isBinding = false
        themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBinding) return@addOnButtonCheckedListener
            val newTheme = when (checkedId) {
                R.id.onb_theme_extreme -> Settings.AppTheme.EXTREME_DARK
                R.id.onb_theme_dark -> Settings.AppTheme.DARK
                else -> Settings.AppTheme.CLEAR
            }
            if (newTheme != settings.appTheme) {
                settings.appTheme = newTheme
                // Applies day/night + signals themeVersion -> BaseActivity recreate.
                AppThemeManager.applyStaticTheme(settings)
            }
        }

        // Home layout variant
        val homeGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_home_group)
        isBinding = true
        homeGroup.check(
            when (settings.homeStyle) {
                Settings.HOME_STYLE_MINIMAL -> R.id.onb_home_minimal
                Settings.HOME_STYLE_FOCUS -> R.id.onb_home_focus
                else -> R.id.onb_home_full
            }
        )
        isBinding = false
        homeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBinding) return@addOnButtonCheckedListener
            settings.homeStyle = when (checkedId) {
                R.id.onb_home_minimal -> Settings.HOME_STYLE_MINIMAL
                R.id.onb_home_focus -> Settings.HOME_STYLE_FOCUS
                else -> Settings.HOME_STYLE_FULL
            }
        }

        // Optimize
        findViewById<MaterialButtonToggleGroup>(R.id.onb_size_group).addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            selectedSize = when (id) {
                R.id.onb_size_phone -> SystemOptimizer.DisplaySizePreset.PHONE_4_6
                R.id.onb_size_small -> SystemOptimizer.DisplaySizePreset.SMALL_7_8
                R.id.onb_size_large -> SystemOptimizer.DisplaySizePreset.LARGE_11_PLUS
                else -> SystemOptimizer.DisplaySizePreset.STANDARD_9_10
            }
        }
        findViewById<MaterialButton>(R.id.onb_optimize_run).setOnClickListener { runOptimization() }

        // Permissions
        findViewById<MaterialButton>(R.id.onb_perms_grant).setOnClickListener { requestPermissions() }
    }

    private fun render() {
        flipper.displayedChild = step
        backBtn.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        skipBtn.visibility = if (step == STEP_COUNT - 1) View.INVISIBLE else View.VISIBLE
        nextBtn.text = getString(if (step == STEP_COUNT - 1) R.string.onb_ready_finish else R.string.onb_next)
        nextBtn.isEnabled = if (step == STEP_SAFETY)
            findViewById<MaterialCheckBox>(R.id.onb_safety_accept).isChecked else true
        updateStepperDots()
    }

    private fun onNext() {
        if (step == STEP_SAFETY) {
            settings.hasAcceptedDisclaimer = true
        }
        if (step == STEP_COUNT - 1) {
            finishOnboarding()
        } else {
            step++
            render()
        }
    }

    private fun onSkip() {
        // Safety is mandatory: skipping still requires accepting the terms.
        if (!settings.hasAcceptedDisclaimer) {
            step = STEP_SAFETY
            render()
            return
        }
        finishOnboarding()
    }

    private fun finishOnboarding() {
        settings.hasSeenRedesignIntro = true
        settings.hasCompletedSetupWizard = true
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
            .setTitle(R.string.onb_welcome_lang)
            .setItems(labels.toTypedArray()) { _, which ->
                val newLang = if (which == 0) "" else LocaleHelper.localeToString(locales[which - 1])
                if (newLang != settings.appLanguage) {
                    settings.appLanguage = newLang
                    recreate() // BaseContext re-wraps the locale on recreate.
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Optimize ---

    private fun runOptimization() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val optimizer = SystemOptimizer(this)
        val result = optimizer.calculateOptimalSettings(selectedSize, portrait)
        settings.resolutionId = result.recommendedResolutionId
        settings.videoCodec = result.recommendedVideoCodec
        settings.viewMode = result.recommendedViewMode
        settings.dpiPixelDensity = result.recommendedDpi
        settings.screenOrientation = result.suggestedOrientation
        settings.commit()
        findViewById<TextView>(R.id.onb_optimize_result).apply {
            visibility = View.VISIBLE
            text = getString(
                R.string.onb_optimize_done,
                Settings.Resolution.fromId(result.recommendedResolutionId)?.resName ?: "-",
                result.recommendedDpi.toString(),
                result.recommendedVideoCodec
            )
        }
    }

    // --- Permissions ---

    private fun requestPermissions() {
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.RECORD_AUDIO)
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS")
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add("android.permission.BLUETOOTH_CONNECT")
            perms.add("android.permission.BLUETOOTH_SCAN")
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun resolveAttrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    companion object {
        @Volatile
        var isForeground = false
            private set
        private const val KEY_STEP = "onb_step"
        private const val STEP_COUNT = 7
        private const val STEP_SAFETY = 1
    }
}
