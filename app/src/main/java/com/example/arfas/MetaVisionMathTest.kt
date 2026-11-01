package com.example.arfas

/** PC checks for the Meta vision maths (run `main`, like KbPhase14RegressionTest). */
object MetaVisionMathTest {
    private var passed = 0
    private fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
        if (!ok) throw AssertionError("FAIL $name ${detail()}")
        passed++
        println("PASS $name")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val m = MetaVisionMath
        // DETR: 3 queries x 92 classes
        val q = 3; val c = 92
        val logits = FloatArray(q * c) { -5f }
        logits[0 * c + 44] = 6f                       // bottle, confident
        logits[1 * c + 91] = 6f                       // "no object"
        logits[2 * c + 44] = 5f                       // duplicate bottle, same place
        val boxes = floatArrayOf(0.5f, 0.5f, 0.2f, 0.4f, 0.1f, 0.1f, 0.1f, 0.1f, 0.51f, 0.5f, 0.2f, 0.4f)
        val det = m.detrPostprocess(logits, boxes, q, c)
        check("DETR: no-object queries are dropped, duplicates merged", det.size == 1) { det.toString() }
        check("DETR: COCO id 44 is bottle", det.first().label == "bottle")
        check("DETR: centre box converted to corner box",
            kotlin.math.abs(det.first().box.x - 0.4f) < 1e-4 && kotlin.math.abs(det.first().box.y - 0.3f) < 1e-4) { det.first().box.toString() }
        check("DETR: label table has 91 entries", m.DETR_LABELS.size == 91)
        val weak = FloatArray(c) { 0f }
        check("DETR: an unsure query is not reported", m.detrPostprocess(weak, floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f), 1, c).isEmpty())

        // CLIP zero-shot
        val img = floatArrayOf(1f, 0f, 0f)
        val ranked = m.zeroShot(img, listOf(floatArrayOf(0f, 1f, 0f), floatArrayOf(0.9f, 0.1f, 0f)), listOf("a", "b"))
        check("CLIP: closest text wins", ranked.first().label == "b" && ranked.first().score > 0.9f) { ranked.toString() }
        check("CLIP: health labels never include a diagnosis",
            m.HEALTH_SCENE_LABELS.none { l -> listOf("rash", "wound", "jaundice", "fever", "disease", "infection").any { l.prompt.contains(it) } })

        // DINOv2 memory
        val items = listOf(
            MetaVisionMath.MemoryItem("ORS packet", floatArrayOf(1f, 0f, 0f)),
            MetaVisionMath.MemoryItem("IFA strip", floatArrayOf(0f, 1f, 0f))
        )
        check("memory: a close match is recognised", m.matchMemory(floatArrayOf(0.95f, 0.1f, 0f), items)?.label == "ORS packet")
        check("memory: an unrelated view is not", m.matchMemory(floatArrayOf(0f, 0f, 1f), items) == null)
        check("memory: two equally close items are not guessed", m.matchMemory(floatArrayOf(0.7f, 0.7f, 0f), items) == null)

        // SAM
        val (coords, labels) = m.samBoxPrompt(MetaVisionMath.Box(0.25f, 0.5f, 0.5f, 0.25f), 2000, 1000)
        check("SAM: box corners scaled to the 1024 long side",
            coords.zip(listOf(256f, 256f, 768f, 384f)).all { (a, b) -> kotlin.math.abs(a - b) < 0.01f } && labels.toList() == listOf(2f, 3f)) { coords.toList().toString() }
        val mw = 20; val mh = 10
        val mask = FloatArray(mw * mh) { i -> val x = i % mw; val y = i / mw; if (x in 5..14 && y in 2..7) 1f else -1f }
        val box = m.maskBox(mask, mw, mh)!!
        check("SAM: tight box around the mask", box == MetaVisionMath.Box(0.25f, 0.2f, 0.5f, 0.6f)) { box.toString() }
        check("SAM: a solid mask fills its box", m.maskFill(mask, mw, mh, box) > 0.95f)
        val poly = m.maskPolygon(mask, mw, mh, stride = 1)
        check("SAM: outline polygon has corners", poly.size in 8..16) { poly.toList().toString() }

        // language
        check("target: 'highlight the ORS packet'", m.targetFromQuery("highlight the ORS packet") == "ors packet") { m.targetFromQuery("highlight the ORS packet") }
        check("target: Hinglish 'thermometer kahan hai'", m.targetFromQuery("thermometer kahan hai") == "thermometer") { m.targetFromQuery("thermometer kahan hai") }
        check("DETR synonym: syrup -> bottle", m.detrLabelFor("syrup") == "bottle")
        check("DETR synonym: mother -> person", m.detrLabelFor("the mother") == "person")
        check("DETR synonym: none for ORS packet", m.detrLabelFor("ors packet") == null)
        check("teach: 'remember this as ORS packet'", m.parseTeachCommand("remember this as ORS packet") == "ORS packet")
        check("teach: 'Remember this as an IFA strip.'", m.parseTeachCommand("Remember this as an IFA strip.") == "IFA strip")
        check("teach: Hinglish", m.parseTeachCommand("ise zinc tablet naam se yaad rakho") == "zinc tablet") { m.parseTeachCommand("ise zinc tablet naam se yaad rakho").toString() }
        check("teach: Hindi", m.parseTeachCommand("इसे ओआरएस पैकेट नाम से याद रखो") == "ओआरएस पैकेट") { m.parseTeachCommand("इसे ओआरएस पैकेट नाम से याद रखो").toString() }
        check("teach: an ordinary question is not a command", m.parseTeachCommand("what is this tablet?") == null)
        check("forget: 'forget ORS packet'", m.parseForgetCommand("forget ORS packet") == "ORS packet")

        // AR line format read by AndroBaselineRuntime.parseArLocalizationObservation
        val line = m.arLine("ors packet", 0.81f, MetaVisionMath.Box(0.1f, 0.2f, 0.3f, 0.4f))
        check("AR line format", line == "AR_LOCALIZATION: FOUND | target=ors packet | confidence=0.81 | x=0.100 | y=0.200 | w=0.300 | h=0.400 | marker=BOX") { line }

        // Observation text for Llama
        val obs = MetaVisionMath.Observation(
            detections = det,
            scene = listOf(MetaVisionMath.Ranked("medicine bottle", 0.6f), MetaVisionMath.Ranked("ORS packet", 0.2f)),
            text = "Paediatric Zinc 20 mg",
            modelsUsed = listOf("DETR", "CLIP", "ML Kit OCR"),
            modelsMissing = listOf("DINOv2")
        ).render()
        check("observation: contract fields present",
            listOf("VISIBLE_FACTS:", "SUPPORTED_INFERENCES:", "VISIBLE_STATUS_TEXT:", "UNKNOWN:").all { obs.contains(it) }) { obs }
        check("observation: real OCR text, never a placeholder", obs.contains("Paediatric Zinc 20 mg") && !obs.contains("Paracetamol"))
        check("observation: says what was not checked", obs.contains("not checked (model not installed): DINOv2"))
        val empty = MetaVisionMath.Observation().render()
        check("observation: an empty frame says nothing was recognised", empty.contains("nothing was recognised")) { empty }
        println("MetaVisionMathTest: $passed checks passed")
    }
}
