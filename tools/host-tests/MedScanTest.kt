import com.example.arfas.*

// A4: medicine-scan parser + grounding (SYNTHETIC OCR text only).
var mf = 0; var mp = 0
fun m(c: Boolean, m: String) { if (c) { mp++; println("PASS $m") } else { mf++; println("FAIL $m") } }

fun main() {
    val ocr = "ORS POWDER\nBatch: B-2024-118\nEXP: 09/27\nMRP Rs 22\n100 sachets"
    // 1. Deterministic parse of raw OCR.
    val p = ArogyaMedScan.parse(ocr)
    m(p.name == "ORS POWDER", "strip name parsed: '${p.name}'")
    m(p.batch == "B-2024-118", "batch parsed: '${p.batch}'")
    m(p.expiry == "09/27", "expiry parsed: '${p.expiry}'")
    m(p.qty == 100, "qty parsed: ${p.qty}")
    // 2. LLM 4-line answer parse.
    val llm = ArogyaMedScan.parse("NAME: ORS Powder\nBATCH: B-2024-118\nEXPIRY: 09/27\nQTY: 100")
    m(llm == MedScan("ORS Powder", "B-2024-118", "09/27", 100), "4-line LLM answer parsed")
    m(ArogyaMedScan.parse("NAME: X\nhello").batch.isEmpty(), "single NAME line is not a 4-line answer")
    // 3. Grounding keeps what is on the label, drops inventions.
    val g = ArogyaMedScan.ground(llm, ocr)
    m(g == llm, "grounded scan keeps all real values")
    val invented = ArogyaMedScan.ground(MedScan("Crocin", "XYZ-999", "12/30", 500), ocr)
    m(invented == MedScan("", "", "", 0), "invented name/batch/expiry/qty all dropped: $invented")
    m(ArogyaMedScan.ground(MedScan("ORS", "B-2024-118", "09/27", 22), ocr).qty == 0, "qty 22 (the MRP) dropped: not a pack quantity")
    m(ArogyaMedScan.ground(MedScan("Zinc", "", "", 50), "ZINC TABLETS\nQty: 50").qty == 50, "qty after Qty: label kept")
    m(ArogyaMedScan.ground(MedScan("AB", "B-2024-118", "09/27", 100), ocr).name.isEmpty(), "short name below min length dropped")
    // 4. Nothing found -> blanks, never invented.
    val empty = ArogyaMedScan.parse("blurry photo no text here at all really")
    m(empty.batch.isEmpty() && empty.expiry.isEmpty() && empty.qty == 0, "no batch/expiry/qty invented: $empty")
    // 5. Expiry normalisation to record format.
    m(ArogyaMedScan.normExpiry("09/27") == "2027-09-30", "MM/YY -> month end")
    m(ArogyaMedScan.normExpiry("09/2027") == "2027-09-30", "MM/YYYY -> month end")
    m(ArogyaMedScan.normExpiry("SEP 2027") == "2027-09-30", "MON YYYY -> month end")
    m(ArogyaMedScan.normExpiry("30/09/2027") == "2027-09-30", "DD/MM/YYYY kept")
    m(ArogyaMedScan.normExpiry("2027-09-30") == "2027-09-30", "ISO passes through")
    m(ArogyaMedScan.normExpiry("") == "", "blank stays blank")
    m(ArogyaMedScan.normExpiry("soon") == "soon", "unparseable passes through for the ASHA")
    // 6. Kit matching.
    val kit = listOf("ORS", "Zinc tablets", "Iron folic acid (IFA)")
    m(ArogyaMedScan.matchItem("ORS", kit) == "ORS", "exact match")
    m(ArogyaMedScan.matchItem("ORS Powder", kit) == "ORS", "contains match")
    m(ArogyaMedScan.matchItem("Zinc tablets 20mg", kit) == "Zinc tablets", "word match")
    m(ArogyaMedScan.matchItem("Crocin", kit).isEmpty(), "unknown -> no match (Add new item path)")
    m(ArogyaMedScan.matchItem("", kit).isEmpty(), "blank -> no match")
    println("MedScanTest: $mp passed, $mf failed")
    if (mf > 0) kotlin.system.exitProcess(1)
}
