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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.msaidizi.app.core.util.DeviceTier
import com.msaidizi.app.update.AutoUpdater
import dagger.hilt.android.AndroidEntryPoint
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
        setContentView(R.layout.activity_main)

        setupNavigation()
        requestAudioPermission()
        handleUpdateIntent(intent)

        Timber.d("MainActivity: Created on ${DeviceTier.current} tier device")
    }

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
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
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
