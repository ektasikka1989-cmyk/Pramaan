package com.example.arfas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate

fun stockKindLabel(kind: String, hindi: Boolean): String = when (kind) {
    "receive" -> if (hindi) "मिला" else "Received"
    "give" -> if (hindi) "दिया" else "Given"
    "adjust" -> if (hindi) "सुधार" else "Correction"
    "writeoff" -> if (hindi) "एक्सपायर/ख़राब" else "Expired/damaged"
    else -> kind
}

@Composable
private fun StockField(label: String, value: String, onChange: (String) -> Unit, single: Boolean = true) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = single, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ItemPicker(hindi: Boolean, items: List<StockItem>, selected: String, onSelect: (String) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
        items.forEach { it ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(it.name) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == it.name, onClick = { onSelect(it.name) })
                Text(it.name + (if (it.unit.isNotBlank()) " (${it.unit})" else ""), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (items.isEmpty()) Text(tr("Add an item first.", "पहले दवा जोड़ें।"), style = MaterialTheme.typography.bodySmall)
    }
}

/** Add a new medicine/item to the kit catalogue (name, unit, minimum level). */
@Composable
fun StockAddItemDialog(hindi: Boolean, onDismiss: () -> Unit, onSave: (StockItem) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var min by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Add medicine / item", "दवा / सामान जोड़ें"), fontWeight = FontWeight.Bold)
                StockField(tr("Name", "नाम"), name, { name = it.take(60) })
                StockField(tr("Unit (tablets, packets…)", "इकाई (गोली, पैकेट…)"), unit, { unit = it.take(20) })
                StockField(tr("Minimum level", "कम से कम"), min, { min = it.filter { c -> c.isDigit() }.take(5) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(tr("Cancel", "रद्द करें")) }
                    Button(onClick = { onSave(StockItem(name.trim(), unit.trim(), min.toIntOrNull() ?: 0)) },
                        enabled = name.isNotBlank(), modifier = Modifier.weight(1f)) { Text(tr("Save", "सहेजें")) }
                }
            }
        }
    }
}

/** Stock received: quantity, date, batch, expiry, source. */
@Composable
fun StockReceiveDialog(hindi: Boolean, items: List<StockItem>, onDismiss: () -> Unit, onSave: (StockTxn) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var item by remember { mutableStateOf(items.firstOrNull()?.name.orEmpty()) }
    var qty by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var batch by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    val ok = item.isNotBlank() && (qty.toIntOrNull() ?: 0) > 0 && runCatching { LocalDate.parse(date) }.isSuccess
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Stock received", "स्टॉक मिला"), fontWeight = FontWeight.Bold)
                ItemPicker(hindi, items, item, { item = it })
                StockField(tr("Quantity", "मात्रा"), qty, { qty = it.filter { c -> c.isDigit() }.take(5) })
                StockField(tr("Date (yyyy-MM-dd)", "तारीख़"), date, { date = it.take(10) })
                StockField(tr("Batch", "बैच"), batch, { batch = it.take(24) })
                StockField(tr("Expiry (yyyy-MM-dd, optional)", "एक्सपायरी (वैकल्पिक)"), expiry, { expiry = it.take(10) })
                StockField(tr("Source (sub-centre…)", "स्रोत"), source, { source = it.take(40) })
                if (expiry.isNotBlank() && runCatching { LocalDate.parse(expiry) }.isFailure) Text(tr("Expiry must be yyyy-MM-dd or blank.", "एक्सपायरी yyyy-MM-dd या ख़ाली रखें।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(tr("Cancel", "रद्द करें")) }
                    Button(onClick = {
                        onSave(StockTxn(date = date, item = item, qty = qty.toInt(), note = "Received", kind = "receive",
                            batch = batch.trim(), expiry = expiry.trim(), source = source.trim(),
                            unit = items.firstOrNull { it.name == item }?.unit.orEmpty()))
                    }, enabled = ok && (expiry.isBlank() || runCatching { LocalDate.parse(expiry) }.isSuccess), modifier = Modifier.weight(1f)) { Text(tr("Save", "सहेजें")) }
                }
            }
        }
    }
}

/** Correction: counted quantity + reason. Saves the difference as an adjustment. */
@Composable
fun StockAdjustDialog(hindi: Boolean, items: List<StockItem>, balances: Map<String, Int>, onDismiss: () -> Unit, onSave: (StockTxn) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var item by remember { mutableStateOf(items.firstOrNull()?.name.orEmpty()) }
    var counted by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val bal = balances[item] ?: 0
    val diff = (counted.toIntOrNull() ?: 0) - bal
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Correction / adjustment", "सुधार"), fontWeight = FontWeight.Bold)
                ItemPicker(hindi, items, item, { item = it })
                Text(tr("On record: ", "रिकॉर्ड में: ") + bal)
                StockField(tr("Actually counted", "असल में गिना"), counted, { counted = it.filter { c -> c.isDigit() }.take(5) })
                if (counted.isNotBlank()) Text(tr("Adjustment: ", "सुधार: ") + (if (diff >= 0) "+" else "") + diff)
                StockField(tr("Reason (required)", "कारण (ज़रूरी)"), reason, { reason = it.take(120) }, single = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(tr("Cancel", "रद्द करें")) }
                    Button(onClick = { onSave(StockTxn(item = item, qty = diff, note = "Correction: counted ${counted.ifBlank { "0" }} (was $bal)", kind = "adjust", reason = reason.trim())) },
                        enabled = item.isNotBlank() && counted.isNotBlank() && diff != 0 && reason.isNotBlank(), modifier = Modifier.weight(1f)) { Text(tr("Save", "सहेजें")) }
                }
            }
        }
    }
}

/** Mark stock expired or damaged: writes it off with a reason. */
@Composable
fun StockWriteoffDialog(hindi: Boolean, items: List<StockItem>, balances: Map<String, Int>, onDismiss: () -> Unit, onSave: (StockTxn) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var item by remember { mutableStateOf(items.firstOrNull()?.name.orEmpty()) }
    var qty by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val bal = balances[item] ?: 0
    val q = qty.toIntOrNull() ?: 0
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Mark expired / damaged", "एक्सपायर / ख़राब दर्ज करें"), fontWeight = FontWeight.Bold)
                ItemPicker(hindi, items, item, { item = it })
                Text(tr("Balance: ", "बचा: ") + bal)
                StockField(tr("Quantity to write off", "ख़त्म मात्रा"), qty, { qty = it.filter { c -> c.isDigit() }.take(5) })
                if (q > bal) Text(tr("More than the balance — receive stock or correct the count first.", "बचे से ज़्यादा — पहले स्टॉक लें या गिनती सुधारें।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                StockField(tr("Reason (expired / damaged…)", "कारण"), reason, { reason = it.take(120) }, single = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(tr("Cancel", "रद्द करें")) }
                    Button(onClick = { onSave(StockTxn(item = item, qty = -q, note = "Written off", kind = "writeoff", reason = reason.trim())) },
                        enabled = item.isNotBlank() && q > 0 && q <= bal && reason.isNotBlank(), modifier = Modifier.weight(1f)) { Text(tr("Save", "सहेजें")) }
                }
            }
        }
    }
}

/** Give to a person: item, quantity, and the person (fixed, or search when giving from the register). */
@Composable
fun StockGiveDialog(
    hindi: Boolean, people: List<Beneficiary>, items: List<StockItem>, balances: Map<String, Int>,
    fixedPerson: Beneficiary? = null, onDismiss: () -> Unit, onSave: (StockTxn) -> Unit
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var item by remember { mutableStateOf(items.firstOrNull()?.name.orEmpty()) }
    var qty by remember { mutableStateOf("") }
    var query by remember { mutableStateOf(fixedPerson?.name.orEmpty()) }
    var person by remember { mutableStateOf(fixedPerson) }
    val bal = balances[item] ?: 0
    val q = qty.toIntOrNull() ?: 0
    val matches = remember(query) {
        if (fixedPerson != null || query.isBlank()) emptyList()
        else people.filter { it.active && it.name.contains(query, ignoreCase = true) }.take(8)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((if (fixedPerson != null) tr("Give to ", "दें: ") + fixedPerson.name else tr("Give to person", "व्यक्ति को दें")), fontWeight = FontWeight.Bold)
                ItemPicker(hindi, items, item, { item = it })
                Text(tr("Balance: ", "बचा: ") + bal)
                StockField(tr("Quantity", "मात्रा"), qty, { qty = it.filter { c -> c.isDigit() }.take(5) })
                if (q > bal) Text(tr("More than the balance.", "बचे से ज़्यादा।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (fixedPerson == null) {
                    StockField(tr("Search person", "व्यक्ति खोजें"), query, { query = it; person = null })
                    matches.forEach { b ->
                        Text(b.name + " · " + b.village, Modifier.fillMaxWidth().clickable { person = b; query = b.name }.padding(6.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (person != null) Text(tr("Selected: ", "चुना: ") + person!!.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(tr("Cancel", "रद्द करें")) }
                    Button(onClick = { onSave(StockTxn(item = item, qty = -q, beneficiaryId = person!!.id, note = "Given to ${person!!.name}", kind = "give")) },
                        enabled = item.isNotBlank() && q > 0 && q <= bal && person != null, modifier = Modifier.weight(1f)) { Text(tr("Save", "सहेजें")) }
                }
            }
        }
    }
}

/** Every entry for one item, newest first. */
@Composable
fun StockHistoryDialog(hindi: Boolean, item: String, txns: List<StockTxn>, names: Map<String, String>, onDismiss: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item, fontWeight = FontWeight.Bold)
                if (txns.isEmpty()) Text(tr("No entries yet.", "अभी कोई प्रविष्टि नहीं।"), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(txns, key = { it.id }) { t ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text("${t.date} · ${stockKindLabel(ArogyaRegistryLogic.txnKind(t), hindi)} · " +
                                    (if (t.qty >= 0) "+" else "") + t.qty, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                val bits = listOfNotNull(
                                    names[t.beneficiaryId]?.takeIf { it.isNotBlank() },
                                    t.batch.takeIf { it.isNotBlank() }?.let { (if (hindi) "बैच " else "batch ") + it },
                                    t.expiry.takeIf { it.isNotBlank() }?.let { (if (hindi) "एक्सपायरी " else "exp ") + it },
                                    t.source.takeIf { it.isNotBlank() },
                                    t.reason.takeIf { it.isNotBlank() },
                                    t.note.takeIf { it.isNotBlank() })
                                if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(tr("Close", "बंद करें")) }
            }
        }
    }
}
