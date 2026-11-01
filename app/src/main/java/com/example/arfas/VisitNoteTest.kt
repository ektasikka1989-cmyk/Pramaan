package com.example.arfas

/** JVM checks for [VisitNote] and [VisitStore]: run with `java ... com.example.arfas.VisitNoteTest`. */
object VisitNoteTest {
    private var passed = 0
    private fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
        if (!ok) throw AssertionError("FAIL $name ${detail()}")
        passed++
        println("PASS $name")
    }

    private val today = VisitNote.epochDay(2026, 10, 23)
    private fun build(text: String, model: String? = null, day: Long = VisitNote.todayEpochDay()) = VisitNote.build(VisitNote.narrativeOf(text), model, day)

    @JvmStatic
    fun main(args: Array<String>) {
        requests(); english(); hinglish(); hindi(); lists(); realModel(); traps(); modelMerge(); dates(); store()
        println("VisitNoteTest: $passed checks passed")
    }

    private fun requests() {
        check("request: English prefix", VisitNote.isRequest("Visit note: Sunita, BP 150/100"))
        check("request: Hindi prefix", VisitNote.isRequest("विज़िट नोट: सुनीता, बीपी 150/100"))
        check("request: Hinglish prefix", VisitNote.isRequest("ghar bhet note - Rekha 5 din ka baby"))
        check("request: home visit record", VisitNote.isRequest("Home visit record: Meena"))
        check("not a request: a question about visits", !VisitNote.isRequest("What is the home visit schedule for a newborn?"))
        check("not a request: word in the middle", !VisitNote.isRequest("how do I write a visit note?"))
        check("narrative strips the prefix", VisitNote.narrativeOf("Visit note: Sunita, 24 years") == "Sunita, 24 years")
    }

    private fun english() {
        val r = build("Visit note: Sunita Devi, 24 years, 7 months pregnant, BP 150/100, Hb 9.5, weight 52 kg, swelling in feet, gave IFA tablets, next visit in 7 days")
        val rec = r.record
        check("en: name", rec.name == "Sunita Devi") { "${rec.name}" }
        check("en: pregnant 7 months", rec.category == VisitNote.Category.PREGNANT && rec.pregnancyMonths == 7.0) { "${rec.category} ${rec.pregnancyMonths}" }
        check("en: age 24", rec.ageYears == 24.0) { "${rec.ageYears}" }
        check("en: BP 150/100", rec.vitals.bpSys == 150 && rec.vitals.bpDia == 100)
        check("en: Hb 9.5", rec.vitals.hb == 9.5)
        check("en: weight 52", rec.vitals.weightKg == 52.0)
        check("en: high BP in pregnancy -> refer", r.alerts.any { it.code == "bp_high" && it.level == VisitNote.Level.REFER }) { r.alerts.toString() }
        check("en: anaemia in pregnancy -> watch", r.alerts.any { it.code == "hb_low" })
        check("en: IFA recorded so not missing", r.missing.none { it.first.startsWith("IFA") }) { r.missing.toString() }
        check("en: next visit set", rec.nextVisitDay != null)
        val text = VisitNote.render(r, today)
        check("en: render has table and alert", text.contains("| BP | 150/100 mmHg ⚠ |") && text.contains("Refer:")) { text }

        val severe = build("Visit note: Kamla, 8 months pregnant, BP 170/115, severe headache and blurred vision")
        check("en: severe BP -> urgent first", severe.alerts.first().code == "bp_severe" && severe.alerts.first().level == VisitNote.Level.URGENT) { severe.alerts.toString() }
        check("en: headache/vision sign", severe.alerts.any { it.code == "headache_vision" })

        val nb = build("Visit note: baby of Rekha, 5 days old, weight 2.1 kg, temperature 35.2 C, not feeding well, cord dry")
        check("en: newborn", nb.record.category == VisitNote.Category.NEWBORN && nb.record.ageDays == 5) { "${nb.record.category}" }
        check("en: baby-of name", nb.record.name == "Baby of Rekha") { "${nb.record.name}" }
        check("en: LBW", nb.alerts.any { it.code == "low_birth_weight" })
        check("en: hypothermia", nb.alerts.any { it.code == "hypothermia" })
        check("en: not feeding is urgent", nb.alerts.any { it.code == "not_feeding" && it.level == VisitNote.Level.URGENT })
        check("en: newborn missing breastfeeding not flagged (feeding said)", nb.missing.none { it.first == "breastfeeding" }) { nb.missing.toString() }

        val child = build("Visit note: Raju, 10 months old child, breathing 56 per minute, MUAC 11.2 cm, loose motions")
        check("en: child fast breathing (limit 50)", child.alerts.any { it.code == "fast_breathing" }) { child.alerts.toString() + " rr=" + child.record.vitals.respRate + " age=" + child.record.ageYears }
        check("en: SAM", child.alerts.any { it.code == "sam" })
        check("en: diarrhoea watch", child.alerts.any { it.code == "diarrhoea" })

        val adult = build("Visit note: Ramesh, 58 years, BP 146/92, random sugar 245")
        check("en: adult raised BP is watch", adult.alerts.any { it.code == "bp_high" && it.level == VisitNote.Level.WATCH })
        check("en: high sugar", adult.alerts.any { it.code == "sugar_high" })
        check("en: adult category", adult.record.category == VisitNote.Category.ADULT)
    }

    private fun hinglish() {
        val r = build("Visit note: Pooja, 6 mahine ki pregnant, bp 142/94, bukhar 101, khoon aa raha hai, 3 din baad phir aaungi")
        check("hinglish: pregnant 6", r.record.category == VisitNote.Category.PREGNANT && r.record.pregnancyMonths == 6.0) { "${r.record.category} ${r.record.pregnancyMonths}" }
        check("hinglish: fever 101F -> 38.3C", r.record.vitals.tempC == 38.3) { "${r.record.vitals.tempC}" }
        check("hinglish: bleeding urgent", r.alerts.first().code == "bleeding") { r.alerts.toString() }
        check("hinglish: maternal fever", r.alerts.any { it.code == "fever_maternal" })
        check("hinglish: next visit in 3 days", r.record.nextVisitDay == VisitNote.todayEpochDay() + 3) { "${r.record.nextVisitText}" }
        check("hinglish: rendered in English labels", !r.hindi)
    }

    private fun hindi() {
        val r = build("विज़िट नोट: सुनीता, 7 महीने की गर्भवती, बीपी 150/100, वजन 50 किलो, एचबी 6.5, चेहरे पर सूजन, 7 दिन बाद फिर आऊँगी")
        check("hi: Hindi output", r.hindi)
        check("hi: name", r.record.name == "सुनीता") { "${r.record.name}" }
        check("hi: pregnant 7", r.record.category == VisitNote.Category.PREGNANT && r.record.pregnancyMonths == 7.0) { "${r.record.category} ${r.record.pregnancyMonths}" }
        check("hi: BP", r.record.vitals.bpSys == 150)
        check("hi: weight", r.record.vitals.weightKg == 50.0)
        check("hi: severe anaemia", r.alerts.any { it.code == "hb_severe" })
        check("hi: face swelling", r.alerts.any { it.code == "swelling" }) { r.alerts.toString() }
        check("hi: next visit", r.record.nextVisitDay == VisitNote.todayEpochDay() + 7) { "${r.record.nextVisitText}" }
        val text = VisitNote.render(r, today)
        check("hi: Hindi labels", text.contains("विज़िट नोट") && text.contains("रेफर")) { text }
        check("hi: IFA missing listed", r.missing.any { it.first.startsWith("IFA") })

        val nb = build("विज़िट नोट: नवजात, 3 दिन का, वजन 2800 ग्राम, बुखार 38 डिग्री, दूध नहीं पी रहा")
        check("hi: grams -> kg", nb.record.vitals.weightKg == 2.8) { "${nb.record.vitals.weightKg}" }
        check("hi: newborn fever", nb.alerts.any { it.code == "fever_newborn" }) { nb.alerts.toString() }
        check("hi: not feeding", nb.alerts.any { it.code == "not_feeding" })
    }

    private fun lists() {
        val r = build("Visit note: Sunita, 7 months pregnant, BP 150/100, swelling in feet, gave IFA tablets and calcium, advised to rest and eat green vegetables, referred to PHC Bassi, next visit in 7 days")
        check("lists: symptom clause", r.record.symptoms == listOf("swelling in feet")) { r.record.symptoms.toString() }
        check("lists: medicine clause", r.record.medicines.any { it.contains("IFA") }) { r.record.medicines.toString() }
        check("lists: advice clause", r.record.advice.any { it.startsWith("advised to rest") }) { r.record.advice.toString() }
        check("lists: referral clause", r.record.referral == "referred to PHC Bassi") { "${r.record.referral}" }
        check("lists: next visit not a symptom", r.record.symptoms.none { it.contains("next") })
        val h = build("विज़िट नोट: सुनीता, 7 महीने की गर्भवती, पैरों में सूजन, आयरन की गोली दी, आराम करने की सलाह दी, PHC रेफर किया")
        check("lists: Hindi symptom", h.record.symptoms == listOf("पैरों में सूजन")) { h.record.symptoms.toString() }
        check("lists: Hindi medicine", h.record.medicines == listOf("आयरन की गोली दी")) { h.record.medicines.toString() }
        check("lists: Hindi advice", h.record.advice == listOf("आराम करने की सलाह दी")) { h.record.advice.toString() }
        check("lists: Hindi referral", h.record.referral == "PHC रेफर किया") { "${h.record.referral}" }
        val n = build("Visit note: Asha, 5 months pregnant, no bleeding, no swelling")
        check("lists: negated symptoms not listed", n.record.symptoms.isEmpty()) { n.record.symptoms.toString() }
    }

    /** Real Llama 3.2 3B outputs (23 Oct) for these notes. */
    private fun realModel() {
        val pooja = build("Visit note: Pooja, 6 mahine ki pregnant, bp 142/94, bukhar 101, khoon aa raha hai, ambulance 108 bulaya, 3 din baad phir aaungi",
            """```{"name":"Pooja","age_years":6,"category":"pregnant","symptoms":["bp 142/94","bukhar 101","khoon aa raha hai"],"medicines":[],"advice":[],"referral":"ambulance 108 bulaya","next_visit":"3 din baad phir aaungi","notes":null}```""")
        check("llama: pregnancy month is not an age", pooja.record.ageYears == null) { "${pooja.record.ageYears}" }
        check("llama: readings are not symptoms", pooja.record.symptoms == listOf("khoon aa raha hai")) { pooja.record.symptoms.toString() }
        val hi = build("विज़िट नोट: सुनीता, 7 महीने की गर्भवती, बीपी 150/100, वजन 50 किलो, एचबी 6.5, चेहरे पर सूजन, आयरन की गोली दी, PHC रेफर किया, 7 दिन बाद फिर आऊँगी",
            """{"name":"सुनीता","age_years":7,"category":"गर्भवती","symptoms":["बीपी 150/100","वजन 50 किलो","चेहरे पर सूजन"],"medicines":["आयरन की गोली"],"advice":[],"referral":"PHC रेफर किया","next_visit":"7 दिन बाद फिर आऊँगी","notes":null}""")
        check("llama: Hindi readings are not symptoms", hi.record.symptoms == listOf("चेहरे पर सूजन")) { hi.record.symptoms.toString() }
        check("llama: Hindi pregnancy month is not an age", hi.record.ageYears == null)
        val nb = build("विज़िट नोट: नवजात, 3 दिन का, वजन 2300 ग्राम, बुखार 38 डिग्री, दूध नहीं पी रहा, माँ को गर्म रखने की सलाह दी",
            """{"name":"नवजात","age_years":null,"category":"newborn","symptoms":["बुखार 38 डिग्री","दूध नहीं पी रहा"],"medicines":[],"advice":["माँ को गर्म रखने की सलाह दी"],"referral":null,"next_visit":null,"notes":"नवजात का वजन कम होने की संभावना है"}""")
        check("llama: invented note dropped", nb.record.notes == null) { "${nb.record.notes}" }
        val ramesh = build("Visit note: Ramesh, 58 years, BP 146/92, random sugar 245, smokes, advised to stop smoking and visit HWC for confirmation, next visit on 30 Oct",
            """{"name":"Ramesh","age_years":58,"category":"elderly","symptoms":["BP 146/92","random sugar 245"],"medicines":["stop smoking"],"advice":["stop smoking"],"referral":null,"next_visit":"30 Oct","notes":"smokes"}""")
        check("llama: advice is not a medicine", ramesh.record.medicines.isEmpty()) { ramesh.record.medicines.toString() }
        check("llama: real age kept", ramesh.record.ageYears == 58.0)
        check("llama: own-words note kept", ramesh.record.notes == "smokes")
        val raju = build("Visit note: Raju, 10 months old child, breathing 56 per minute, MUAC 11.2 cm, loose motions for 3 days, gave ORS and zinc, referred to NRC",
            """{"name":"Raju","age_years":1,"category":"child","symptoms":["breathing 56 per minute","loose motions for 3 days"],"medicines":["ORS","zinc"],"advice":null,"referral":"NRC","next_visit":null,"notes":"Loose motions for 3 days"}""")
        check("llama: child age shown in months", VisitNote.render(raju, today).contains("10 months")) { VisitNote.render(raju, today) }
        check("llama: repeated note dropped", raju.record.notes == null)
        check("llama: symptom with a duration kept", raju.record.symptoms.contains("loose motions for 3 days")) { raju.record.symptoms.toString() }
        val meena = build("Visit note: Meena, 30 years, PNC day 4, BP 120/80, temperature normal, no bleeding, breastfeeding well, next visit in 3 days",
            """{"name":"Meena","age_years":30,"category":"adult","symptoms":["BP 120/80","temperature normal"],"medicines":[],"advice":[],"referral":null,"next_visit":"in 3 days","notes":"breastfeeding well"}""")
        check("llama: 'temperature normal' counts as recorded", meena.missing.none { it.first == "temperature" }) { meena.missing.toString() }
        check("llama: 'temperature normal' is not a symptom", meena.record.symptoms.isEmpty()) { meena.record.symptoms.toString() }
        check("llama: postnatal from rules beats model 'adult'", meena.record.category == VisitNote.Category.POSTNATAL)
    }

    private fun traps() {
        val a = build("Visit note: Geeta, 30 years, fever since 3 days, saans phool rahi 2 din se")
        check("trap: 'fever since 3 days' is not a temperature", a.record.vitals.tempC == null && a.issues.isEmpty()) { "${a.record.vitals.tempC} ${a.issues}" }
        check("trap: '2 din se' is not a breathing rate", a.record.vitals.respRate == null)
        check("trap: breathing difficulty sign still raised", a.alerts.any { it.code == "breathing" }) { a.alerts.toString() }

        val b = build("Visit note: Asha, 5 months pregnant, no bleeding, no fits, next visit in 2 weeks")
        check("trap: negations are not alerts", b.alerts.none { it.code == "bleeding" || it.code == "convulsions" }) { b.alerts.toString() }
        check("trap: next visit 2 weeks is not pregnancy length", b.record.pregnancyMonths == 5.0 && b.record.nextVisitDay == VisitNote.todayEpochDay() + 14) { "${b.record.pregnancyMonths} ${b.record.nextVisitDay}" }

        val c = build("Visit note: Radha, 8 months pregnant, BP 15/100, temperature 48 C, Hb 95")
        check("trap: impossible BP flagged and not used", c.record.vitals.bpSys == null && c.issues.any { it.field == "BP" }) { c.issues.toString() }
        check("trap: impossible temp flagged", c.issues.any { it.field == "Temperature" })
        check("trap: impossible Hb flagged", c.issues.any { it.field == "Hb" })
        check("trap: no BP alert from a wrong BP", c.alerts.none { it.code.startsWith("bp_") })

        val d = build("Visit note")
        check("trap: empty note asks what to say", d.record.category == VisitNote.Category.UNKNOWN && VisitNote.narrativeOf("Visit note").isBlank())

        val e = build("Visit note: Rekha, 5 days old baby, weight 2600")
        check("trap: newborn weight without unit = grams", e.record.vitals.weightKg == 2.6) { "${e.record.vitals.weightKg}" }
        check("trap: 5 days old is not a next visit", e.record.nextVisitDay == null)
    }

    private fun modelMerge() {
        val note = "Visit note: Sunita, 7 months pregnant, BP 150/100, complained of headache, gave IFA and calcium, referred to PHC Bassi"
        val model = """Here is the JSON: {"name":"Sunita","age_years":31,"category":"pregnant","symptoms":["headache","high fever"],"medicines":["IFA","calcium"],"advice":["rest"],"referral":"PHC Bassi","next_visit":null,"notes":null}"""
        val r = build(note, model)
        check("model: used", r.usedModel)
        check("model: invented age dropped (31 not in note)", r.record.ageYears == null) { "${r.record.ageYears}" }
        check("model: grounded symptom kept, invented one dropped", r.record.symptoms == listOf("headache")) { r.record.symptoms.toString() }
        check("model: medicines kept", r.record.medicines == listOf("IFA", "calcium"))
        check("model: invented advice dropped", r.record.advice.none { it == "rest" }) { r.record.advice.toString() }
        check("model: referral kept", r.record.referral == "PHC Bassi")
        check("model: rules keep BP", r.record.vitals.bpSys == 150)
        val bad = build(note, "I cannot do that")
        check("model: non-JSON output falls back to rules", !bad.usedModel && bad.record.vitals.bpSys == 150)
    }

    private fun dates() {
        check("date: epoch day 1970-01-01", VisitNote.epochDay(1970, 1, 1) == 0L)
        check("date: round trip", VisitNote.civil(VisitNote.epochDay(2026, 10, 23)) == Triple(2026, 10, 23))
        check("date: leap day", VisitNote.civil(VisitNote.epochDay(2028, 2, 29)) == Triple(2028, 2, 29))
        check("date: next visit 30 Oct", VisitNote.nextVisit("next visit on 30 Oct", today)?.second == VisitNote.epochDay(2026, 10, 30))
        check("date: next visit 2026-11-05", VisitNote.nextVisit("follow up 2026-11-05", today)?.second == VisitNote.epochDay(2026, 11, 5))
        check("date: next visit tomorrow", VisitNote.nextVisit("next visit tomorrow", today)?.second == today + 1)
        check("date: dd/mm/yyyy", VisitNote.nextVisit("next visit 05/11/2026", today)?.second == VisitNote.epochDay(2026, 11, 5))
        check("date: label", VisitNote.dateLabel(today) == "23 Oct 2026")
    }

    private fun store() {
        val f = java.io.File.createTempFile("visits", ".jsonl"); f.delete()
        val s = VisitStore(f)
        s.save(build("Visit note: Sunita, 7 months pregnant, BP 150/100, next visit in 2 days", day = today - 5), today - 5)   // due 3 days ago
        s.save(build("Visit note: Meena, 30 years, BP 120/80, next visit in 10 days", day = today), today)                    // later
        s.save(build("Visit note: Rekha, 5 days old baby, weight 2.1 kg, temperature 35 C", day = today - 1), today - 1)     // no date but alerts
        val due = s.due(today)
        check("store: overdue first", due.firstOrNull()?.name == "Sunita" && due.first().daysLate == 3L) { due.toString() }
        check("store: later visit not due", due.none { it.name == "Meena" })
        check("store: alert without next date listed for follow-up", due.any { it.name == "Rekha" && it.dueDay == null }) { due.toString() }
        val text = VisitStore.render(due, today, hindi = false)
        check("store: render table", text.contains("| Sunita |") && text.contains("3 days late")) { text }
        check("store: due request en", VisitStore.isDueRequest("Which visits are due today?"))
        check("store: due request hinglish", VisitStore.isDueRequest("aaj kiske ghar jana hai"))
        check("store: due request hi", VisitStore.isDueRequest("आज किसके घर जाना है"))
        check("store: not due request", !VisitStore.isDueRequest("what is due date of delivery"))
        // A newer note for the same person replaces the older one in the list.
        s.save(build("Visit note: Sunita, 7 months pregnant, BP 130/80, next visit in 14 days", day = today), today)
        check("store: newer visit for same person wins", s.due(today).none { it.name == "Sunita" }) { s.due(today).toString() }
        f.delete()
    }
}
