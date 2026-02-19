package com.andrerinas.headunitrevived.main

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.app.BaseActivity
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

class MainActivity : BaseActivity() {

    private var lastBackPressTime: Long = 0
    var keyListener: KeyListener? = null

    private val viewModel: MainViewModel by viewModels()

    interface KeyListener {
        fun onKeyEvent(event: KeyEvent?): Boolean
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val appSettings = Settings(this)
        requestedOrientation = appSettings.screenOrientation.androidOrientation

        // Start main service immediately to handle connections and wireless server
        val serviceIntent = Intent(this, AapService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        setFullscreen()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_content) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.navigateUp()) {
                    return
                } else if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        requestPermissions()
        viewModel.register()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            val data = intent.data
            if (data?.scheme == "headunit" && data.host == "connect") {
                val ip = data.getQueryParameter("ip")
                if (!ip.isNullOrEmpty()) {
                    AppLog.i("Received connect intent for IP: $ip")
                    ContextCompat.startForegroundService(this, AapService.createIntent(ip, this))
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        ActivityCompat.requestPermissions(
            this,
            permissionsToRequest.toTypedArray(),
            permissionRequestCode
        )
    }

    private fun setFullscreen() {
        val root = findViewById<View>(R.id.root)
        val appSettings = Settings(this)
        com.andrerinas.headunitrevived.utils.SystemUI.apply(window, root, appSettings.startInFullscreenMode)
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()

        // If an Android Auto session is active, bring the projection activity to front
        if (AapService.isConnected) {
            AppLog.i("MainActivity: Active session detected, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(aapIntent)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i("onKeyDown: %d", keyCode)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event)
        }
        return keyListener?.onKeyEvent(event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i("onKeyUp: %d", keyCode)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyUp(keyCode, event)
        }
        return keyListener?.onKeyEvent(event) ?: super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            AppLog.i("MainActivity finishing, resetting auto-start flag.")
            com.andrerinas.headunitrevived.main.HomeFragment.resetAutoStart()
        }
    }

    companion object {
        private const val permissionRequestCode = 97
    }
}
