// ports: teemoon/Presentation/ImageModelChoice.swift @ b8457654 (the marker words and serverReads; the note, its
//        alternative and "switch to …" are gone — IMAGES.md §4d @ teemoon-ios f04a55d2, the 1.1.0 image flow: a model
//        that can't see images opens the where sheet filtered to ones that can, CantSeeRoute)
package ai.teemoon.app.imaging

/**
 * Which models read images, in the words the where sheet and the browser show (review entry images-model-marker,
 * approved 2026-11-01). The one rule for whether a model reads them is AppState.canSeeImages over the provider as it
 * would be with that model active — the same rule the attach control, the where filter and the send use.
 */
object ImageModelChoice {
    const val MARKER = "reads images"
    const val CAPTION_TAG = "images"

    /**
     * A server model reads images when its saved list says so, or its equipped row does (iOS: modelCapabilities, filled
     * from the row; a home machine's list is never in the catalogue, so its row is the only place it is kept, a15f0cec).
     */
    fun serverReads(catalog: Set<ai.teemoon.providers.ModelCapability>?, row: ai.teemoon.providers.ModelCapabilities?): Boolean =
        catalog?.contains(ai.teemoon.providers.ModelCapability.VISION) == true || row?.contains(ai.teemoon.providers.ModelCapabilities.VISION) == true
}
