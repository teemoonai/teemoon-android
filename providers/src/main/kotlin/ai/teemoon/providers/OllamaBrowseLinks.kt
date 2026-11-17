// spec: teemoon-ios docs/IMAGES.md §4e "Into a provider's models" @ 7c141b4a (the pull sheet's links)
package ai.teemoon.providers

/**
 * The pull sheet's two "browse" links (OllamaModelDownloadView). Opened from a where sheet with "reads images" on, each
 * narrows to vision models — ollama.com's own vision category, Hugging Face's image-text-to-text task. The shortlist
 * above them has no image data and is never filtered; neither site filters on encryption.
 */
object OllamaBrowseLinks {
    fun ollama(readsImages: Boolean): String =
        "https://ollama.com/search" + if (readsImages) "?c=vision" else ""

    fun huggingFace(readsImages: Boolean): String =
        "https://huggingface.co/models?library=gguf&sort=trending" + if (readsImages) "&pipeline_tag=image-text-to-text" else ""
}
