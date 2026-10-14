// ports: teemoon/Confidential/VerificationRun.swift @ v1.0.1 (the SectionResult value type only —
//        the run itself, RunStep / RunLink / VerificationSection and AttestationSummary.verificationRun,
//        is its own row)
package ai.teemoon.confidential

/**
 * Rolled-up result of a verification section.
 *
 * Chip taxonomy (design review): one consistent register — passed / advisory /
 * failed / checking / pending — and every chip carries a glyph, never color
 * alone (colorblind safety). [FAILED] is defined at full severity even when
 * unused, so a hard break has somewhere to land.
 */
enum class SectionResult(val chipLabel: String, /** SF Symbol name paired with the chip label. */ val chipIcon: String) {
    PASSED("passed", "checkmark.circle.fill"),
    FLAGGED("advisory", "exclamationmark.triangle.fill"),
    FAILED("failed", "xmark.octagon.fill"),
    RUNNING("checking", "arrow.triangle.2.circlepath"),
    NOT_RUN("pending", "circle.dotted"),
}
