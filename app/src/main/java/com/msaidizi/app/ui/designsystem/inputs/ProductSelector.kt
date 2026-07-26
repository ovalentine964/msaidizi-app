package com.msaidizi.app.ui.designsystem.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens
import com.msaidizi.app.ui.designsystem.MsaidiziShapes

// ──────────────────────────────────────────────
// Product Item
// ──────────────────────────────────────────────

data class ProductItem(
    val id: Long,
    val name: String,
    val icon: ImageVector,
    val price: Double,
    val stock: Double = 0.0,
    val unit: String = ""
)

// ──────────────────────────────────────────────
// Product Selector
// Grid of product icons with voice search
// ──────────────────────────────────────────────

@Composable
fun ProductSelector(
    products: List<ProductItem>,
    onProductSelected: (ProductItem) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: Long? = null,
    onVoiceSearch: (() -> Unit)? = null,
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    Column(modifier = modifier.fillMaxWidth()) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { onSearchQueryChange?.invoke(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = {
                Text(
                    text = "Tafuta bidhaa...",
                    style = MsaidiziThemeTokens.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Tafuta"
                )
            },
            trailingIcon = {
                if (onVoiceSearch != null) {
                    IconButton(onClick = onVoiceSearch) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Tafuta kwa sauti",
                            tint = colors.primary
                        )
                    }
                }
            },
            shape = MsaidiziShapes().large,
            singleLine = true,
            textStyle = MsaidiziThemeTokens.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Product grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(products, key = { it.id }) { product ->
                ProductGridItem(
                    product = product,
                    isSelected = product.id == selectedId,
                    onClick = { onProductSelected(product) }
                )
            }
        }
    }
}

@Composable
private fun ProductGridItem(
    product: ProductItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    val bgColor = if (isSelected) colors.primaryContainer else colors.surface
    val borderColor = if (isSelected) colors.primary else colors.outlineVariant

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isSelected) colors.primary.copy(alpha = 0.15f)
                    else colors.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = product.icon,
                contentDescription = product.name,
                tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = product.name,
            style = MsaidiziThemeTokens.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) colors.primary else colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = "KES ${"%,.0f".format(product.price)}",
            style = MsaidiziThemeTokens.typography.labelSmall,
            color = colors.onSurfaceVariant
        )

        if (product.stock <= 0) {
            Text(
                text = "Imeisha",
                style = MsaidiziThemeTokens.typography.labelSmall,
                color = colors.error,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
