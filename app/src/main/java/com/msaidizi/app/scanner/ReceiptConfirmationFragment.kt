package com.msaidizi.app.scanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.msaidizi.app.R
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.voice.TextToSpeech
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Receipt Confirmation Fragment — shows parsed receipt items for user review.
 *
 * Flow:
 * 1. Display scanned items with prices
 * 2. User can edit item names and prices (tap to edit)
 * 3. User can remove items (swipe or X button)
 * 4. User confirms → create transactions
 * 5. Learn from corrections for future scans
 *
 * ## Voice Integration
 * Speaks: "Nimesoma risiti — unanunua nini?" (I read the receipt — what are you buying?)
 * After confirmation: "Nimescan risiti — nilinunua nyanya KSh 200, vitunguu KSh 100"
 *
 * ## Accessibility
 * - Large touch targets for editing
 * - Voice feedback for all actions
 * - Clear visual distinction between scanned vs corrected items
 */
@AndroidEntryPoint
class ReceiptConfirmationFragment : Fragment() {

    @Inject
    lateinit var receiptScanner: ReceiptScanner

    @Inject
    lateinit var ttsEngine: TextToSpeech

    private lateinit var itemsRecyclerView: RecyclerView
    private lateinit var totalText: TextView
    private lateinit var merchantText: TextView
    private lateinit var confirmButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var rescanButton: MaterialButton

    private var receiptData: ReceiptData? = null
    private val editableItems = mutableListOf<EditableReceiptItem>()
    private var onConfirmed: ((List<EditableReceiptItem>) -> Unit)? = null
    private var onCancelled: (() -> Unit)? = null

    companion object {
        fun newInstance(receiptData: ReceiptData): ReceiptConfirmationFragment {
            return ReceiptConfirmationFragment().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList(
                        "items",
                        ArrayList(receiptData.items.map { ReceiptItemParcel.fromReceiptItem(it) })
                    )
                    putString("merchant", receiptData.merchantName)
                    putString("date", receiptData.date)
                    putDouble("total", receiptData.total)
                    putString("payment_method", receiptData.paymentMethod)
                    putString("raw_ocr", receiptData.rawOcrText)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_receipt_confirmation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews(view)
        loadReceiptData()
        speakConfirmation()
    }

    private fun setupViews(view: View) {
        itemsRecyclerView = view.findViewById(R.id.items_recycler)
        totalText = view.findViewById(R.id.total_text)
        merchantText = view.findViewById(R.id.merchant_text)
        confirmButton = view.findViewById(R.id.confirm_button)
        cancelButton = view.findViewById(R.id.cancel_button)
        rescanButton = view.findViewById(R.id.rescan_button)

        itemsRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        confirmButton.setOnClickListener {
            confirmAndSave()
        }

        cancelButton.setOnClickListener {
            onCancelled?.invoke()
            parentFragmentManager.popBackStack()
        }

        rescanButton.setOnClickListener {
            onCancelled?.invoke()
            parentFragmentManager.popBackStack()
        }

        // Accessibility
        confirmButton.contentDescription = "Thibitisha na kurekodi (Confirm and save)"
        cancelButton.contentDescription = "Ghairi (Cancel)"
        rescanButton.contentDescription = "Piga picha tena (Rescan)"
    }

    private fun loadReceiptData() {
        val args = arguments ?: return

        val itemParcels: List<ReceiptItemParcel> =
            args.getParcelableArrayList<ReceiptItemParcel>("items") ?: emptyList()
        val merchant = args.getString("merchant", "")
        val total = args.getDouble("total", 0.0)

        merchantText.text = if (merchant.isNotBlank()) "🏪 $merchant" else "📋 Risiti Iliyosomwa"

        editableItems.clear()
        editableItems.addAll(itemParcels.map { parcel ->
            EditableReceiptItem(
                originalName = parcel.itemName,
                currentName = parcel.itemName,
                quantity = parcel.quantity,
                unitPrice = parcel.unitPrice,
                totalPrice = parcel.totalPrice,
                isEdited = false
            )
        })

        val adapter = ReceiptItemAdapter(
            items = editableItems,
            onItemEdited = { position, newName, newPrice ->
                editableItems[position] = editableItems[position].copy(
                    currentName = newName,
                    totalPrice = newPrice,
                    isEdited = true
                )
                updateTotal()
            },
            onItemRemoved = { position ->
                editableItems.removeAt(position)
                (itemsRecyclerView.adapter as? ReceiptItemAdapter)?.notifyItemRemoved(position)
                updateTotal()
            }
        )

        itemsRecyclerView.adapter = adapter
        updateTotal()
    }

    private fun updateTotal() {
        val total = editableItems.sumOf { it.totalPrice }
        totalText.text = "Jumla: KSh ${"%.0f".format(total)}"
    }

    /**
     * Speak confirmation prompt in Swahili.
     */
    private fun speakConfirmation() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val itemCount = editableItems.size
                val text = if (itemCount > 0) {
                    val itemNames = editableItems.take(3).joinToString(", ") { it.currentName }
                    "Nimesoma risiti. Unanunua $itemNames. Thibitisha au hariri."
                } else {
                    "Nimesoma risiti. Thibitisha au hariri bidhaa."
                }
                ttsEngine.speak(text, "sw")
            } catch (e: Exception) {
                Timber.w(e, "TTS failed for receipt confirmation")
            }
        }
    }

    /**
     * Confirm items and create transactions.
     * Learns from corrections before saving.
     */
    private fun confirmAndSave() {
        // Learn from corrections
        editableItems.filter { it.isEdited }.forEach { item ->
            receiptScanner.learnCorrection(item.originalName, item.currentName)
        }

        // Callback to caller
        onConfirmed?.invoke(editableItems.toList())

        // Speak confirmation
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val summary = editableItems.joinToString(", ") { "${it.currentName} KSh ${"%.0f".format(it.totalPrice)}" }
                ttsEngine.speak("Nimescan risiti. $summary. Imerekodwa.", "sw")
            } catch (e: Exception) {
                Timber.w(e, "TTS failed for receipt save confirmation")
            }
        }

        Timber.d("Receipt confirmed: %d items, total=%.0f",
            editableItems.size, editableItems.sumOf { it.totalPrice })
    }

    /**
     * Set callbacks for confirmation and cancellation.
     */
    fun setCallbacks(
        onConfirmed: (List<EditableReceiptItem>) -> Unit,
        onCancelled: () -> Unit
    ) {
        this.onConfirmed = onConfirmed
        this.onCancelled = onCancelled
    }
}

/**
 * Editable receipt item with original + current values.
 */
data class EditableReceiptItem(
    val originalName: String,
    val currentName: String,
    val quantity: Double,
    val unitPrice: Double,
    val totalPrice: Double,
    val isEdited: Boolean
)

/**
 * RecyclerView adapter for receipt items — editable inline.
 */
class ReceiptItemAdapter(
    private val items: List<EditableReceiptItem>,
    private val onItemEdited: (Int, String, Double) -> Unit,
    private val onItemRemoved: (Int) -> Unit
) : RecyclerView.Adapter<ReceiptItemAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameEdit: EditText = view.findViewById(R.id.item_name_edit)
        val priceEdit: EditText = view.findViewById(R.id.item_price_edit)
        val removeButton: ImageButton = view.findViewById(R.id.remove_button)
        val editedIndicator: View = view.findViewById(R.id.edited_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_receipt_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.nameEdit.setText(item.currentName)
        holder.priceEdit.setText("%.0f".format(item.totalPrice))
        holder.editedIndicator.visibility = if (item.isEdited) View.VISIBLE else View.GONE

        // Edit name
        holder.nameEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val newName = holder.nameEdit.text.toString().trim()
                val newPrice = holder.priceEdit.text.toString().toDoubleOrNull() ?: item.totalPrice
                if (newName != item.currentName || newPrice != item.totalPrice) {
                    onItemEdited(holder.adapterPosition, newName, newPrice)
                }
            }
        }

        // Edit price
        holder.priceEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val newName = holder.nameEdit.text.toString().trim()
                val newPrice = holder.priceEdit.text.toString().toDoubleOrNull() ?: item.totalPrice
                if (newName != item.currentName || newPrice != item.totalPrice) {
                    onItemEdited(holder.adapterPosition, newName, newPrice)
                }
            }
        }

        // Remove item
        holder.removeButton.setOnClickListener {
            onItemRemoved(holder.adapterPosition)
        }

        // Accessibility
        holder.nameEdit.contentDescription = "Jina la bidhaa (Item name)"
        holder.priceEdit.contentDescription = "Bei (Price)"
        holder.removeButton.contentDescription = "Ondoa bidhaa (Remove item)"
    }

    override fun getItemCount() = items.size
}
