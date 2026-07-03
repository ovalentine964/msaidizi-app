package com.msaidizi.app.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.msaidizi.app.R
import com.msaidizi.app.databinding.FragmentWhatsAppConnectionBinding
import com.msaidizi.app.data.model.WhatsAppError
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WhatsAppConnectionStepFragment : Fragment() {
    private var _binding: FragmentWhatsAppConnectionBinding? = null
    private val binding get() = requireNotNull(_binding) { "Fragment binding accessed before onCreateView or after onDestroyView" }
    private lateinit var viewModel: WhatsAppConnectionStep

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWhatsAppConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = (requireActivity() as OnboardingActivity).whatsappStep
        setupUI()
        observeState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupUI() {
        binding.editTextPhone.doAfterTextChanged { text -> viewModel.onPhoneInputChanged(text?.toString() ?: "") }
        binding.editTextPhone.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { viewModel.onPhoneSubmit(); true } else false
        }
        binding.buttonSubmitPhone.setOnClickListener { viewModel.onPhoneSubmit() }
        binding.buttonConfirmPhone.setOnClickListener { viewModel.onPhoneConfirmed() }
        binding.buttonEditPhone.setOnClickListener { viewModel.onEditPhone() }
        binding.buttonReceiptConfirmed.setOnClickListener { viewModel.onReceiptConfirmed() }
        binding.buttonRetry.setOnClickListener { viewModel.onRetry() }
        binding.buttonSkip.setOnClickListener { viewModel.onSkip(); navigateToNext() }
        binding.buttonContinue.setOnClickListener { viewModel.onContinue(); navigateToNext() }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state -> renderState(state) }
            }
        }
    }

    private fun renderState(state: WhatsAppConnectionStep.UiState) {
        binding.layoutPhoneInput.visibility = View.GONE
        binding.layoutPhoneConfirm.visibility = View.GONE
        binding.layoutSending.visibility = View.GONE
        binding.layoutWaitingReceipt.visibility = View.GONE
        binding.layoutConnected.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        when (state.phase) {
            WhatsAppConnectionStep.StepPhase.PHONE_INPUT -> showPhoneInput(state)
            WhatsAppConnectionStep.StepPhase.PHONE_CONFIRM -> showPhoneConfirm(state)
            WhatsAppConnectionStep.StepPhase.SENDING -> showSending()
            WhatsAppConnectionStep.StepPhase.WAITING_FOR_RECEIPT -> showWaitingForReceipt()
            WhatsAppConnectionStep.StepPhase.CONNECTED -> showConnected(state)
            WhatsAppConnectionStep.StepPhase.ERROR -> showError(state)
        }
    }

    private fun showPhoneInput(state: WhatsAppConnectionStep.UiState) {
        binding.layoutPhoneInput.visibility = View.VISIBLE
        binding.textViewPrompt.text = "Sasa ningependa kukutumia ripoti ya biashara yako kila siku kupitia WhatsApp.\n\nNamba yako ya WhatsApp ni ipi?"
        binding.textInputLayoutPhone.error = state.validationError
        binding.buttonSubmitPhone.isEnabled = state.phoneInput.isNotBlank()
        binding.buttonSkip.visibility = View.VISIBLE
    }

    private fun showPhoneConfirm(state: WhatsAppConnectionStep.UiState) {
        binding.layoutPhoneConfirm.visibility = View.VISIBLE
        binding.textViewConfirmPrompt.text = "Namba yako ni"
        binding.textViewFormattedNumber.text = state.formattedPhone
        binding.textViewConfirmQuestion.text = "Sawa?"
    }

    private fun showSending() {
        binding.layoutSending.visibility = View.VISIBLE
        binding.textViewSendingStatus.text = "Nakutumia ujumbe wa WhatsApp sasa..."
        val pulse = AlphaAnimation(0.3f, 1.0f).apply { duration = 800; interpolator = LinearInterpolator(); repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE }
        binding.imageviewSendingIcon.startAnimation(pulse)
    }

    private fun showWaitingForReceipt() {
        binding.layoutWaitingReceipt.visibility = View.VISIBLE
        binding.textViewWaitingStatus.text = "Ujumbe umetumwa! Angalia WhatsApp yako."
        binding.buttonReceiptConfirmed.isEnabled = true
        binding.buttonReceiptConfirmed.text = "✅ Nimepokea!"
        binding.buttonSkip.visibility = View.VISIBLE
        val pulse = AlphaAnimation(0.4f, 1.0f).apply { duration = 1200; interpolator = LinearInterpolator(); repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE }
        binding.imageviewWhatsAppIcon.startAnimation(pulse)
    }

    private fun showConnected(state: WhatsAppConnectionStep.UiState) {
        binding.layoutConnected.visibility = View.VISIBLE
        binding.textViewConnectedTitle.text = "🎉 Umefanikiwa!"
        binding.textViewConnectedMessage.text = "Sawa ${state.userName}! Sasa kila jioni nitakutumia muhtasari wa mauzo yako, faida, na vidokezo kupitia WhatsApp."
        binding.textViewConnectedPhone.text = "📱 ${state.formattedPhone}"
        binding.buttonContinue.isEnabled = true
    }

    private fun showError(state: WhatsAppConnectionStep.UiState) {
        binding.layoutError.visibility = View.VISIBLE
        binding.textViewErrorTitle.text = "😔 ${state.errorMessage ?: "Kuna tatizo"}"
        val guidance = when (state.error) {
            WhatsAppError.NUMBER_NOT_ON_WHATSAPP -> "Hakikisha namba yako ina WhatsApp imewashwa."
            WhatsAppError.NETWORK_ERROR -> "Hakikisha una mtandao mzuri."
            WhatsAppError.RATE_LIMIT -> "Subiri dakika chache kisha jaribu tena."
            WhatsAppError.VERIFICATION_EXPIRED -> "Muda umekwisha. Weka namba tena."
            else -> "Jaribu tena au ruka hatua hii."
        }
        binding.textViewErrorGuidance.text = guidance
        binding.buttonRetry.visibility = if (state.canRetry) View.VISIBLE else View.GONE
        binding.buttonSkip.visibility = View.VISIBLE
    }

    private fun navigateToNext() {
        findNavController().navigate(R.id.action_whatsapp_to_personality)
    }
}
