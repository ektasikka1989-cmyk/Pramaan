package com.example.arfas

/** Conservative referral prompt, not diagnosis. Requires clinician review before field deployment.
 * Null means no rule matched; it NEVER means the patient is safe.
 * Delegates to [ArogyaDangerSigns] (subject, negation, age, temperature and Hindi/Hinglish aware).
 * Returns a message for URGENT findings and for CHECK findings (uncertain / past / jaundice),
 * so a worker always sees what the rules noticed.
 */
object ArogyaUrgentTriage {
    fun assess(input: String, visit: ArogyaVisit): String? = ArogyaDangerSigns.message(ArogyaDangerSigns.evaluate(input, visit))
    fun urgent(input: String, visit: ArogyaVisit): Boolean = ArogyaDangerSigns.evaluate(input, visit).urgent
}
