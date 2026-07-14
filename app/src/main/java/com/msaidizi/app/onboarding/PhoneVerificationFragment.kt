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
import com.msaidizi.app.data.model.SmsVerificationError
import com.msaidizi.app.data.model.VerificationChannel
import com.msaidizi.app.data.model.WhatsAppError
import com.msaidizi.app.databinding.FragmentPhoneVerificationBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * PhoneVerificationFragment — unified phone verification step.
 *
 * Presents two verification channels:
 * 1. SMS (default, highlighted) — works on all phones
 * 2. WhatsApp (optional) — for users who have it
 *
 * Also provides:
 * - Manual code entry for SMS
 * - Skip option (WhatsApp not required)
 * - Channel switching on error
 *
 * ## Design Decisions
 * - SMS is the primary/default button because it works for everyone
 * - WhatsApp is secondary because 30% of Kenyan workers don't have it
 * - Skip is always visible — core features work without verification
 * - "Don't have WhatsApp?" is addressed by making SMS the first option
 */
class PhoneVerificationFragment : Fragment() {

    private var _binding: FragmentPhoneVerificationBinding? = null
    private val binding get() = requireNotNull(_binding) { "Fragment binding accessed before onCreateView or after onDestroyView" }
    private lateinit var viewModel: PhoneVerificationStep

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhoneVerificationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = (requireActivity() as OnboardingActivity).phoneVerificationStep
        setupUI()
        observeState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupUI() {
        // Phone input
        binding.editTextPhone.doAfterTextChanged { text ->
            viewModel.onPhoneInputChanged(text?.toString() ?: "")
        }
        binding.editTextPhone.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { viewModel.onPhoneSubmit(); true } else false
        }
        binding.buttonSubmitPhone.setOnClickListener { viewModel.onPhoneSubmit() }

        // Channel selection
        binding.buttonSms.setOnClickListener { viewModel.onSelectSms() }
        binding.buttonWhatsapp.setOnClickListener { viewModel.onSelectWhatsApp() }
        binding.buttonSkipChannel.setOnClickListener { viewModel.onSkip(); navigateToNext() }
        binding.buttonEditPhone.setOnClickListener { viewModel.onEditPhone() }

        // SMS code input
        binding.editTextCode.doAfterTextChanged { text ->
            viewModel.onCodeInputChanged(text?.toString() ?: "")
        }
        binding.editTextCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { viewModel.onCodeSubmit(); true } else false
        }
        binding.buttonVerifyCode.setOnClickListener { viewModel.onCodeSubmit() }
        binding.buttonResendSms.setOnClickListener { viewModel.onResendSms() }
        binding.buttonSwitchWhatsapp.setOnClickListener { viewModel.onSelectWhatsApp() }
        binding.buttonSkipSms.setOnClickListener { viewModel.onSkip(); navigateToNext() }

        // WhatsApp waiting
        binding.buttonWhatsappReceiptConfirmed.setOnClickListener { viewModel.onWhatsAppReceiptConfirmed() }
        binding.buttonSwitchSms.setOnClickListener { viewModel.onSwitchToSms() }
        binding.buttonSkipWhatsapp.setOnClickListener { viewModel.onSkip(); navigateToNext() }

        // Continue after verification
        binding.buttonContinue.setOnClickListener {
            viewModel.onContinue()
            navigateToNext()
        }

        // Error actions
        binding.buttonRetry.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.selectedChannel == VerificationChannel.SMS) {
                viewModel.onResendSms()
            } else {
                viewModel.onRetryWhatsApp()
            }
        }
        binding.buttonSwitchChannel.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.selectedChannel == VerificationChannel.SMS) {
                viewModel.onSelectWhatsApp()
            } else {
                viewModel.onSwitchToSms()
            }
        }
        binding.buttonSkipError.setOnClickListener { viewModel.onSkip(); navigateToNext() }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state -> renderState(state) }
            }
        }
    }

    private fun renderState(state: PhoneVerificationStep.UiState) {
        // Hide all layouts first
        binding.layoutPhoneInput.visibility = View.GONE
        binding.layoutChannelSelect.visibility = View.GONE
        binding.layoutSmsSending.visibility = View.GONE
        binding.layoutSmsCodeInput.visibility = View.GONE
        binding.layoutSmsVerifying.visibility = View.GONE
        binding.layoutWhatsappSending.visibility = View.GONE
        binding.layoutWhatsappWaiting.visibility = View.GONE
        binding.layoutVerified.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        when (state.phase) {
            PhoneVerificationStep.StepPhase.PHONE_INPUT -> showPhoneInput(state)
            PhoneVerificationStep.StepPhase.CHANNEL_SELECT -> showChannelSelect(state)
            PhoneVerificationStep.StepPhase.SMS_SENDING -> showSmsSending()
            PhoneVerificationStep.StepPhase.SMS_CODE_INPUT -> showSmsCodeInput(state)
            PhoneVerificationStep.StepPhase.SMS_VERIFYING -> showSmsVerifying()
            PhoneVerificationStep.StepPhase.WHATSAPP_SENDING -> showWhatsAppSending()
            PhoneVerificationStep.StepPhase.WHATSAPP_WAITING -> showWhatsAppWaiting(state)
            PhoneVerificationStep.StepPhase.VERIFIED -> showVerified(state)
            PhoneVerificationStep.StepPhase.ERROR -> showError(state)
        }
    }

    private fun showPhoneInput(state: PhoneVerificationStep.UiState) {
        binding.layoutPhoneInput.visibility = View.VISIBLE
        binding.textViewPrompt.text = "Sasa ninahitaji namba yako ya simu ili nikutumie ripoti na ujumbe muhimu.\n\nNamba yako ya simu ni ipi?"
        binding.textInputLayoutPhone.error = state.validationError
        binding.buttonSubmitPhone.isEnabled = state.phoneInput.isNotBlank()
    }

    private fun showChannelSelect(state: PhoneVerificationStep.UiState) {
        binding.layoutChannelSelect.visibility = View.VISIBLE
        binding.textViewChannelPrompt.text = "Namba yako ni"
        binding.textViewFormattedNumber.text = state.formattedPhone
    }

    private fun showSmsSending() {
        binding.layoutSmsSending.visibility = View.VISIBLE
        binding.textViewSmsSendingStatus.text = "Nakutumia SMS ya uthibitisho..."
        val pulse = AlphaAnimation(0.3f, 1.0f).apply {
            duration = 800
            interpolator = LinearInterpolator()
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        // Animate sending icon if available
    }

    private fun showSmsCodeInput(state: PhoneVerificationStep.UiState) {
        binding.layoutSmsCodeInput.visibility = View.VISIBLE
        binding.textInputLayoutCode.error = state.codeError
        binding.buttonVerifyCode.isEnabled = state.codeInput.length >= 4
        binding.editTextCode.requestFocus()

        // Show remaining time hint
        if (state.smsExpiresInSeconds > 0) {
            val minutes = state.smsExpiresInSeconds / 60
            val seconds = state.smsExpiresInSeconds % 60
            binding.textViewCodePrompt.text = "Nambari ya uthibitisho imetumwa!\n\nWeka nambari ya tarakimu 6 uliyopokea kwa SMS:\n(Muda: ${minutes}:${String.format("%02d", seconds)})"
        }
    }

    private fun showSmsVerifying() {
        binding.layoutSmsVerifying.visibility = View.VISIBLE
    }

    private fun showWhatsAppSending() {
        binding.layoutWhatsappSending.visibility = View.VISIBLE
        binding.textViewWhatsappSendingStatus.text = "Nakutumia ujumbe wa WhatsApp sasa..."
        val pulse = AlphaAnimation(0.3f, 1.0f).apply {
            duration = 800
            interpolator = LinearInterpolator()
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
    }

    private fun showWhatsAppWaiting(state: PhoneVerificationStep.UiState) {
        binding.layoutWhatsappWaiting.visibility = View.VISIBLE
        binding.textViewWhatsappWaitingStatus.text = "Ujumbe umetumwa!\nAngalia WhatsApp yako."
        binding.buttonWhatsappReceiptConfirmed.isEnabled = true
    }

    private fun showVerified(state: PhoneVerificationStep.UiState) {
        binding.layoutVerified.visibility = View.VISIBLE
        val channelText = if (state.selectedChannel == VerificationChannel.SMS) "SMS" else "WhatsApp"
        binding.textViewVerifiedTitle.text = "🎉 Umefanikiwa!"
        binding.textViewVerifiedMessage.text = "Sawa ${state.userName}! Namba yako ya simu imehakikishwa kupitia $channelText.\n\nSasa nitaweza kukutumia ripoti na ujumbe muhimu."
        binding.textViewVerifiedPhone.text = "📱 ${state.formattedPhone}"
        binding.buttonContinue.isEnabled = true
    }

    private fun showError(state: PhoneVerificationStep.UiState) {
        binding.layoutError.visibility = View.VISIBLE
        binding.textViewErrorTitle.text = "😔 ${state.errorMessage ?: "Kuna tatizo"}"

        val guidance = when (state.error) {
            is SmsVerificationError -> {
                when (state.error) {
                    SmsVerificationError.INVALID_PHONE -> "Hakikisha namba yako ni sahihi."
                    SmsVerificationError.NETWORK_ERROR -> "Hakikisha una mtandao mzuri."
                    SmsVerificationError.RATE_LIMIT -> "Subiri dakika chache kisha jaribu tena."
                    SmsVerificationError.CODE_EXPIRED -> "Muda umekwisha. Omba nambari mpya."
                    SmsVerificationError.INVALID_CODE -> "Nambari si sahihi. Hakikisha umeandika nambari sahihi."
                    SmsVerificationError.TOO_MANY_ATTEMPTS -> "Umekosea mara nyingi. Subiri kabla ya kujaribu tena."
                    SmsVerificationError.SEND_FAILED -> "SMS imeshindikana. Jaribu WhatsApp badala yake."
                    SmsVerificationError.UNKNOWN_ERROR -> "Jaribu tena au ruka hatua hii."
                }
            }
            is WhatsAppError -> {
                when (state.error) {
                    WhatsAppError.NUMBER_NOT_ON_WHATSAPP -> "Hakikisha namba yako ina WhatsApp imewashwa. Jaribu SMS badala yake."
                    WhatsAppError.NETWORK_ERROR -> "Hakikisha una mtandao mzuri."
                    WhatsAppError.RATE_LIMIT -> "Subiri dakika chache kisha jaribu tena."
                    WhatsAppError.VERIFICATION_EXPIRED -> "Muda umekwisha. Weka namba tena."
                    WhatsAppError.UNKNOWN_ERROR -> "Jaribu tena au ruka hatua hii."
                }
            }
            else -> "Jaribu tena au ruka hatua hii."
        }
        binding.textViewErrorGuidance.text = guidance

        binding.buttonRetry.visibility = if (state.canRetry) View.VISIBLE else View.GONE

        // Show channel switch option
        val switchText = if (state.selectedChannel == VerificationChannel.SMS) {
            "💬 Jaribu WhatsApp badala yake"
        } else {
            "📱 Jaribu SMS badala yake"
        }
        binding.buttonSwitchChannel.text = switchText
        binding.buttonSwitchChannel.visibility = View.VISIBLE

        binding.buttonSkipError.visibility = View.VISIBLE
    }

    private fun navigateToNext() {
        findNavController().navigate(R.id.action_phone_verification_to_personality)
    }
}
