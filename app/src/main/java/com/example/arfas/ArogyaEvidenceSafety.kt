package com.example.arfas
/** Provenance constraints, not a medical entailment model. */
object ArogyaEvidenceSafety {
 fun normalise(t:String)=t.replace(Regex("[\\u0080-\\u009f]")," ").replace(Regex("\\s+")," ").trim()
 fun sentences(t:String)=t.replace(Regex("\\s*\\[(?:S|W)?\\d+\\]"),"").split(Regex("(?<=[.!?।])\\s+|\\n+")).map {normalise(it).removePrefix("• ").trim()}.filter {it.isNotBlank()}
 fun citedExtractive(answer:String,sources:Map<String,List<String>>):Boolean {
  val parts=Regex("(?s)(.*?)\\[(S\\d+)\\]").findAll(answer).toList()
  if(parts.isEmpty())return false
  var end=0
  for(part in parts){
   val passages=sources[part.groupValues[2]] ?: return false
   val statements=sentences(part.groupValues[1].trim().trimStart('.', '।').trim())
   if(statements.isEmpty() || statements.any {s->passages.none {p->sentences(p).any {normalise(it).trimEnd('.', '।')==normalise(s).trimEnd('.', '।')}}})return false
   end=part.range.last+1
  }
  return answer.substring(end).trim().trim('.', '।').isBlank()
 }
 fun meaningConflict(statement:String,passage:String):Boolean {
  fun nums(t:String)=Regex("\\d+(?:[.,]\\d+)*").findAll(t).map {it.value.replace(",","")}.toSet()
  val s=normalise(statement).lowercase();val p=normalise(passage).lowercase()
  if(!nums(p).containsAll(nums(s)))return true
  val unit=Regex("(\\d+(?:\\.\\d+)?)\\s*(?:°\\s*)?(c|f|kg|g|mg|ml|mmhg|days?|weeks?|months?)\\b")
  if(unit.findAll(s).any {m->unit.findAll(p).none {it.groupValues[1]==m.groupValues[1]&&it.groupValues[2]==m.groupValues[2]}})return true
  val words=Regex("[\\p{L}]+").findAll(s).map {it.value}.toSet()
  val nearest=sentences(passage).maxByOrNull {c->Regex("[\\p{L}]+").findAll(c.lowercase()).count {it.value in words}}.orEmpty()
  val neg=Regex("(?i)\\b(no|not|never|without|cannot|can't|don't|doesn't|unable)\\b|नहीं|मत\\s|बिना")
  return neg.containsMatchIn(s)!=neg.containsMatchIn(nearest)
 }
 /**
  * Therapeutic medicine selection or dosing: out of scope for this assistant (the ASHA refers; the
  * protocol and supervisor decide). Programme supplements an ASHA distributes and counsels on (IFA,
  * folic acid, calcium, ORS, zinc, vitamin A, albendazole) are NOT matched here; see [supplement].
  * Previously "tablet", "goli" and "dawa" alone blocked routine IFA/calcium counselling.
  */
 private val THERAPEUTIC=Regex("(?i)\\b(paracetamol|acetaminophen|crocin|calpol|amoxicillin|amoxycillin|cotrimoxazole|co-trimoxazole|gentamicin|ceftriaxone|"+
  "metronidazole|ciprofloxacin|antibiotics?|antipyretics?|ibuprofen|aspirin|insulin|cetirizine|azithromycin|misoprostol|oxytocin|"+
  "injections?|syrups?|capsules?|prescrib(e|ing|ed)|ointment|antimalarial|chloroquine|artesunate)\\b|"+
  "पैरासिटामोल|एंटीबायोटिक|इंजेक्शन|सिरप|कैप्सूल|\\b(dawai|dawa|davai|dava|injection)\\b|दवा|दवाई")
 private val DOSING=Regex("(?i)\\b(dos(e|es|age|ing)|how many (tablets?|goli)|kitni goli|kitni dawa|mg|mcg|milligrams?)\\b|खुराक|कितनी गोली|\\b(?:give|take)\\s+\\d+(?:\\.\\d+)?\\s*ml\\b")
 private val SUPPLEMENT=Regex("(?i)\\b(ifa|iron(?:[- ]folic)?|folic acid|folic|calcium|ors|zinc|vitamin[- ]a|albendazole|deworm\\w*)\\b|आयरन|फोलिक|कैल्शियम|ओआरएस|जिंक|विटामिन ए|एल्बेंडाजोल|कृमि")
 private val TABLET_WORDS=Regex("(?i)\\b(tablets?|goli|pills?)\\b|गोली|गोलियां|गोलियाँ")
 fun medication(t:String):Boolean {
  if(THERAPEUTIC.containsMatchIn(t)) return true
  val supplement=SUPPLEMENT.containsMatchIn(t)
  if(DOSING.containsMatchIn(t) && !supplement) return true
  return TABLET_WORDS.containsMatchIn(t) && !supplement
 }
 /** The programme supplement named in [t], normalised, or null. */
 fun supplement(t:String):String? = SUPPLEMENT.find(t)?.value?.lowercase()?.let { v -> when {
  v.startsWith("ifa")||v.startsWith("iron")||v=="आयरन" -> "iron"; v.startsWith("folic")||v=="फोलिक" -> "folic"
  v=="calcium"||v=="कैल्शियम" -> "calcium"; v=="ors"||v=="ओआरएस" -> "ors"; v=="zinc"||v=="जिंक" -> "zinc"
  v.startsWith("vitamin")||v=="विटामिन ए" -> "vitamin a"; else -> "deworming" } }
 fun supplementNamedIn(term:String,passage:String):Boolean { val p=passage.lowercase(); return when(term) {
  "iron" -> Regex("\\bifa\\b|\\biron\\b").containsMatchIn(p); "folic" -> p.contains("folic"); "calcium" -> p.contains("calcium")
  "ors" -> Regex("\\bors\\b|oral rehydration").containsMatchIn(p); "zinc" -> p.contains("zinc"); "vitamin a" -> Regex("vitamin[- ]a\\b").containsMatchIn(p)
  else -> Regex("albendazole|deworm").containsMatchIn(p) } }
}
