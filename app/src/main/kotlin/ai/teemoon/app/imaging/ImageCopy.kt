package ai.teemoon.app.imaging

/**
 * IMAGES.md §7's strings, approved by the maintainer (review page, 2026-10-25; "{n} earlier images weren't sent, to keep the
 * request small" added, "too large to import" dropped) — all here, so a later edit is one file.
 */
object ImageCopy {
    const val ATTACH = "attach images"
    /** §4a: the paperclip's two choices. */
    const val CHOOSE_PHOTOS = "choose photos"
    const val TAKE_PHOTO = "take photo"

    /**
     * The attach sheet's two buttons, left to right (IMAGES.md §4d, the 1.1.0 image flow; the maintainer 2026-11-11):
     * take photo, then choose photos. No "take photo" without a camera app: no CAMERA permission is declared, so the
     * system camera is the only one.
     */
    fun attachSheet(hasCamera: Boolean): List<String> = if (hasCamera) listOf(TAKE_PHOTO, CHOOSE_PHOTOS) else listOf(CHOOSE_PHOTOS)
    /** DRAFT (IMAGES.md §4d): the footnote under the two buttons. */
    const val ATTACH_FOOTNOTE = "photos are resized on this phone and their location is removed before they're sent."
    /**
     * §4b's second download on a Tensor phone (the maintainer 2026-10-26, (a)): the review page's entry ondevice-image-download,
     * approved as proposed (the maintainer, 2026-10-28) — shown only while LocalModelCatalog.imagesOnDevice is on.
     */
    const val OFFER_DOWNLOAD_TITLE = "use images on this phone?"
    const val OFFER_DOWNLOAD_BODY = "this phone's fastest model can't see images. images need a second model, 2.6 GB. your text chats stay on the faster one."
    const val OFFER_DOWNLOAD = "download"
    const val OFFER_NOT_NOW = "not now"
    /** §7 (draft 3): the note names the limit that applied. */
    fun upTo(limit: Int) = "up to $limit images per message"
    const val UNREADABLE = "this image couldn't be read"
    const val EARLIER_LINE = ImageHistory.EARLIER
    const val SHARE = "share"
    const val DONE = "done"
    /** The viewer's gallery save (review item android-viewer-save-zoom, the maintainer 2026-11-07). */
    const val SAVE = "save"
    /** The toast after a save the gallery took (the maintainer 2026-11-07, via pm). */
    const val SAVED = "saved to photos"
    fun leftOutCantSee(n: Int) = if (n == 1) "1 earlier image wasn't sent: this model can't see images" else "$n earlier images weren't sent: this model can't see images"
    fun leftOutForSize(n: Int) = if (n == 1) "1 earlier image wasn't sent, to keep the request small" else "$n earlier images weren't sent, to keep the request small"
    fun preview(n: Int) = ai.teemoon.chat.ChatRepository.imagesTitle(n)
    fun imported(n: Int) = if (n == 1) "1 image imported." else "$n images imported."
    fun importSkipped(n: Int) = if (n == 1) "1 image couldn't be imported." else "$n images couldn't be imported."
    /** The last-request view: never base64. */
    fun requestLine(type: String, bytes: Int, width: Int, height: Int) = "image · $type · ${kb(bytes)} · ${width}×${height}"
    private fun kb(n: Int) = if (n < 1024 * 1024) "${maxOf(1, (n + 512) / 1024)} KB" else "%.1f MB".format(n / 1048576.0)
}
