import com.example.arfas.*

// Daily practice: fresh questions every round (pure logic).
var pf = 0; var pp = 0
fun p(c: Boolean, m: String) { if (c) { pp++; println("PASS $m") } else { pf++; println("FAIL $m") } }

fun main() {
    val pool = listOf("a", "b", "c", "d", "e", "f")
    // 1. Asked ids are skipped.
    val r1 = ArogyaPractice.pickFresh(pool, emptySet(), 3)
    p(r1.size == 3 && r1.all { it in pool }, "first round picks from the pool: $r1")
    val r2 = ArogyaPractice.pickFresh(pool, r1.toSet(), 3)
    p(r2.size == 3 && r2.intersect(r1.toSet()).isEmpty(), "second round avoids the first: $r2")
    // 2. Pool restarts when fewer than n fresh remain.
    val r3 = ArogyaPractice.pickFresh(pool, (r1 + r2).toSet(), 3)
    p(r3.size == 3, "exhausted pool restarts instead of short rounds: $r3")
    p(ArogyaPractice.pickFresh(pool, pool.toSet(), 3).size == 3, "fully asked pool still fills the round")
    // 3. Edges.
    p(ArogyaPractice.pickFresh(emptyList(), emptySet(), 3).isEmpty(), "empty pool -> empty")
    p(ArogyaPractice.pickFresh(pool, emptySet(), 0).isEmpty(), "n=0 -> empty")
    p(ArogyaPractice.pickFresh(pool, setOf("zzz"), 2).size == 2, "unknown asked ids ignored")
    // 4. Asked-list bookkeeping.
    p(ArogyaPractice.recordAsked("", listOf("a", "b")) == "a,b", "first record")
    p(ArogyaPractice.recordAsked("a,b", listOf("c", "a")) == "a,b,c", "append de-duplicated")
    p(ArogyaPractice.recordAsked("  ", listOf("x")) == "x", "blank history tolerated")
    val many = (1..150).map { "q$it" }
    val capped = ArogyaPractice.recordAsked("", many)
    p(capped.split(",").size == 100 && capped.endsWith("q150"), "asked list capped at newest 100")
    println("PracticeTest: $pp passed, $pf failed")
    if (pf > 0) kotlin.system.exitProcess(1)
}
