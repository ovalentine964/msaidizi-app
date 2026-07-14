package com.msaidizi.app.scanner

import android.os.Parcel
import android.os.Parcelable

/**
 * Parcelable wrapper for ReceiptItem — used to pass receipt data between activities.
 */
data class ReceiptItemParcel(
    val itemName: String,
    val quantity: Double,
    val unitPrice: Double,
    val totalPrice: Double,
    val category: String
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readDouble(),
        parcel.readDouble(),
        parcel.readDouble(),
        parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(itemName)
        parcel.writeDouble(quantity)
        parcel.writeDouble(unitPrice)
        parcel.writeDouble(totalPrice)
        parcel.writeString(category)
    }

    override fun describeContents(): Int = 0

    fun toReceiptItem(): ReceiptItem = ReceiptItem(
        itemName = itemName,
        quantity = quantity,
        unitPrice = unitPrice,
        totalPrice = totalPrice,
        category = category
    )

    companion object CREATOR : Parcelable.Creator<ReceiptItemParcel> {
        override fun createFromParcel(parcel: Parcel): ReceiptItemParcel {
            return ReceiptItemParcel(parcel)
        }

        override fun newArray(size: Int): Array<ReceiptItemParcel?> {
            return arrayOfNulls(size)
        }

        fun fromReceiptItem(item: ReceiptItem): ReceiptItemParcel = ReceiptItemParcel(
            itemName = item.itemName,
            quantity = item.quantity,
            unitPrice = item.unitPrice,
            totalPrice = item.totalPrice,
            category = item.category
        )
    }
}
