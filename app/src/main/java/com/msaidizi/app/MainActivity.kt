package com.msaidizi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.msaidizi.app.core.util.DeviceTier
import com.msaidizi.app.onboarding.bootstrap.BootstrapActivity
import com.msaidizi.app.update.AutoUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Single Activity for the entire app.
 * Uses Navigation Component with BottomNavigationView.
 *
 * Navigation: Home → Record → Dashboard → History → Settings
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var autoUpdater: AutoUpdater

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safety net: redirect to onboarding if not completed
        val prefs = getSharedPreferences("worker_profile", 0)
        if (!prefs.getBoolean("onboarding_complete", false)) {
            Timber.i("Onboarding not complete — redirecting to BootstrapActivity")
            startActivity(Intent(this, BootstrapActivity::class.java))
            finish()
            return
        }

        // App lock: require PIN/biometric before showing financial data
        val appLockPrefs = getSharedPreferences("app_lock", 0)
        val pinHash = appLockPrefs.getString("pin_hash", null)
        if (pinHash != null) {
            // PIN is set — require authentication before showing content
            showAppLockScreen()
            return
        }

        setContentView(R.layout.activity_main)

        setupNavigation()
        requestAudioPermission()
        handleUpdateIntent(intent)

        Timber.d("MainActivity: Created on ${DeviceTier.current} tier device")
    }

    /**
     * Show app lock screen — PIN entry or biometric.
     * Financial data on shared phones MUST be protected.
     */
    private fun showAppLockScreen() {
        setContentView(R.layout.activity_app_lock)

        val pinInput = findViewById<EditText>(R.id.pin_input)
        val unlockButton = findViewById<Button>(R.id.unlock_button)
        val biometricButton = findViewById<Button>(R.id.biometric_button)
        val errorText = findViewById<TextView>(R.id.error_text)

        // Try biometric first if available
        val biometricManager = androidx.biometric.BiometricManager.from(this)
        if (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            biometricButton.visibility = View.VISIBLE
            biometricButton.setOnClickListener {
                val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Fungua Msaidizi")
                    .setNegativeButtonText("Tumia PIN")
                    .build()
                val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor,
                    object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                            runOnUiThread { onUnlockSuccess() }
                        }
                    })
                biometricPrompt.authenticate(promptInfo)
            }
        }

        unlockButton.setOnClickListener {
            val enteredPin = pinInput.text.toString()
            if (enteredPin.length < 4) {
                errorText.text = "Weka PIN angalau tarakimu 4"
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val appLockPrefs = getSharedPreferences("app_lock", 0)
            val storedHash = appLockPrefs.getString("pin_hash", "") ?: ""
            val salt = appLockPrefs.getString("pin_salt", "") ?: ""
            val enteredHash = hashPin(enteredPin, salt)
            if (enteredHash == storedHash) {
                onUnlockSuccess()
            } else {
                errorText.text = "PIN si sahihi. Jaribu tena."
                errorText.visibility = View.VISIBLE
                pinInput.text.clear()
            }
        }
    }

    private fun onUnlockSuccess() {
        setContentView(R.layout.activity_main)
        setupNavigation()
        requestAudioPermission()
        handleUpdateIntent(intent)
    }

    private fun hashPin(pin: String, salt: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUpdateIntent(intent)
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setupWithNavController(navController)

        // Hide bottom nav on splash or fullscreen screens
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.recordFragment -> {
                    // Hide bottom nav during recording for immersive experience
                    bottomNav.visibility = View.GONE
                }
                else -> {
                    bottomNav.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Timber.d("Audio permission granted")
                } else {
                    Timber.w("Audio permission denied — voice features will not work")
                }
            }
        }
    }

    /**
     * Handles the intent from the update notification.
     * Shows a dialog letting the user download/install the update
     * or skip it.
     */
    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("show_update", false) != true) return

        val version = intent.getStringExtra("update_version") ?: return
        val notes = intent.getStringExtra("update_notes") ?: ""

        Timber.i("Showing update dialog for v$version")

        MaterialAlertDialogBuilder(this)
            .setTitle("Update Available")
            .setMessage("Version $version is ready to install.\n\n${notes.take(300)}")
            .setPositiveButton("Install") { _, _ ->
                // Trigger update download + install via AutoUpdater
                lifecycleScope.launch {
                    val result = autoUpdater.forceCheck()
                    if (result is AutoUpdater.UpdateResult.Available) {
                        autoUpdater.downloadAndInstall(result.info)
                    }
                }
            }
            .setNeutralButton("Later", null)
            .setNegativeButton("Skip this version") { _, _ ->
                // Parse version code from version name
                val versionCode = version.replace(".", "").toIntOrNull() ?: return@setNegativeButton
                autoUpdater.skipVersion(versionCode)
            }
            .show()
    }
}
