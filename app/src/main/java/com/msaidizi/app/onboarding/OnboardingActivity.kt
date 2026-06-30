package com.msaidizi.app.onboarding

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.msaidizi.app.R
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.databinding.ActivityOnboardingBinding
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class OnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var navController: NavController

    val onboardingData = OnboardingSessionData()
    lateinit var api: MsaidiziApi
        private set

    val whatsappStep: WhatsAppConnectionStep by lazy {
        ViewModelProvider(this, WhatsAppStepFactory(application, api, onboardingData)).get(WhatsAppConnectionStep::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        api = Retrofit.Builder()
            .baseUrl(getString(R.string.api_base_url))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MsaidiziApi::class.java)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_onboarding) as NavHostFragment
        navController = navHostFragment.navController

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateProgress(destination.id)
        }
    }

    private fun updateProgress(destinationId: Int) {
        val step = when (destinationId) {
            R.id.introductionFragment -> 1
            R.id.businessDiscoveryFragment -> 2
            R.id.whatsappConnectionFragment -> 3
            R.id.personalityFragment -> 4
            R.id.firstUseFragment -> 5
            else -> 0
        }
        binding.progressOnboarding.progress = step
        binding.progressOnboarding.max = 5
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}

class WhatsAppStepFactory(
    private val application: android.app.Application,
    private val api: MsaidiziApi,
    private val onboardingData: OnboardingSessionData
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WhatsAppConnectionStep::class.java)) {
            return WhatsAppConnectionStep(application, api, onboardingData) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
