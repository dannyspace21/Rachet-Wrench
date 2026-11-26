// ShoppingUi.kt — Smart Shopping on the phone: the "Find supplies" / "Shop"
// buttons and the small dialog that lists what to buy with one button per
// retailer.  A button opens the retailer's own app or website (Android picks
// which); nothing is embedded, sent or saved, and records don't change.
package com.ratchetwrench.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ratchetwrench.app.data.Vehicle
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.domain.ShopContext
import com.ratchetwrench.app.domain.ShopItem
import com.ratchetwrench.app.domain.Shopping

/** What Smart Shopping knows about a vehicle (its specs and saved parts), kept up to date. */
@Composable
fun rememberShopContext(v: Vehicle): ShopContext {
    val repo = LocalRepo.current
    val specs by repo.db.specs().observeFor(v.id).collectAsState(initial = emptyList())
    val parts by repo.db.parts().observeFor(v.id).collectAsState(initial = emptyList())
    return ShopContext(v.year, v.make, v.model,
        specs.associate { it.key to it.value },
        parts.map { Triple(it.type_key, it.part_number, it.updated_at) })
}

/** Is Smart Shopping on (Settings) and is at least one retailer available? */
@Composable
fun shoppingOn(): Boolean {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    return repo.store.shoppingEnabled &&
        Shopping.activeRetailers(Catalog.get(ctx), repo.store.shoppingDisabled, repo.store.shoppingPreferred).isNotEmpty()
}

/** Small cart button used on reminder and part rows. */
@Composable
fun ShopIconButton(description: String, onClick: () -> Unit) {
    IconButton(onClick) { UiIcon("cart", Modifier.size(22.dp), MaterialTheme.colorScheme.primary, description = description) }
}

/** The dialog: each item with its search text and one button per retailer. */
@Composable
fun ShoppingDialog(title: String, items: List<ShopItem>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val repo = LocalRepo.current
    val catalog = Catalog.get(ctx)
    val retailers = Shopping.activeRetailers(catalog, repo.store.shoppingDisabled, repo.store.shoppingPreferred)
    AlertDialog(onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.label, fontWeight = FontWeight.Bold)
                        SelectionContainer { Text("“${item.query}”", style = MaterialTheme.typography.bodySmall) }
                        item.hint?.let { key ->
                            Text("Tip: add “${Catalog.specLabel(ctx, key)}” in Specs for an exact search.",
                                color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            retailers.forEach { r ->
                                AssistChip(onClick = {
                                    repo.store.shoppingPreferred = r.key // listed first next time
                                    runCatching { com.ratchetwrench.app.ui.screens.openLink(ctx, Shopping.retailerUrl(r, item.query)) }
                                }, label = { Text(r.name) }, leadingIcon = { UiIcon(r.icon.ifBlank { "cart" }, Modifier.size(18.dp)) })
                            }
                        }
                    }
                }
                Text("Opens the retailer's app or website. Check that the product fits your vehicle before buying. Nothing in your records changes.",
                    color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } })
}
