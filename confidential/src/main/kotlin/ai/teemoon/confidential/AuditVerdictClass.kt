// ports: teemoon/Confidential/AuditIndex.swift @ v1.0.4 (AuditIndex.VerdictClass, kept top-level: AuditIndex.kt
//        refers to it by this name)
package ai.teemoon.confidential

/**
 * The five verdict classes the audits repo publishes. Raw values match
 * index.json exactly; an unknown future class fails to null → neutral
 * treatment, never a guessed badge.
 */
enum class AuditVerdictClass(val rawValue: String, /** Display label (badge text). */ val label: String) {
    PRIVATE_REVIEW("private", "private"),
    LEAKS("leaks", "leaks"),
    COMPROMISABLE("compromisable", "compromisable"),
    QUALIFIED_PASS("qualified-pass", "qualified pass"),
    INCONCLUSIVE("inconclusive", "inconclusive");

    companion object {
        /** null for a class this build doesn't know — never a guessed badge. */
        fun fromRaw(raw: String): AuditVerdictClass? = entries.firstOrNull { it.rawValue == raw }
    }
}
