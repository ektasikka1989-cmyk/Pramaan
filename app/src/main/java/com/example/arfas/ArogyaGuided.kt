package com.example.arfas

import java.time.LocalDate

/* Visit type labels for the person picker, and the field types used by the official form definitions (ArogyaFormSpecs). */
enum class QType { NUMBER, YESNO, CHOICE, TEXT }
enum class GuidedKind(val title: String, val hi: String) { NEWBORN("Newborn & mother (HBNC)", "नवजात और माँ (HBNC)"), PREGNANT("Pregnant woman (ANC)", "गर्भवती (ANC)"),
    CHILD("Young child (HBYC)", "छोटा बच्चा (HBYC)"), COUPLE("Family planning", "परिवार नियोजन") }

object ArogyaGuided {
    fun kindFor(b: Beneficiary, today: LocalDate = LocalDate.now()): GuidedKind {
        val age = ArogyaRegistryLogic.ageDays(b, today)
        return when {
            b.category == BenCategory.CHILD && age != null && age <= 60 -> GuidedKind.NEWBORN
            b.category == BenCategory.CHILD -> GuidedKind.CHILD
            b.category == BenCategory.PREGNANT && b.deliveryDate.isBlank() -> GuidedKind.PREGNANT
            else -> GuidedKind.COUPLE
        }
    }
}
