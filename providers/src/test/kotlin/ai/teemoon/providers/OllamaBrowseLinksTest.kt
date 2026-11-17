// spec: teemoon-ios docs/IMAGES.md §4e "Into a provider's models" @ 7c141b4a — the pull sheet's two links narrow to
// vision models under "reads images"; neither site filters on encryption, and nothing there passes it anyway.
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class OllamaBrowseLinksTest {
    @Test fun readsImagesNarrowsBothLinks() {
        assertEquals("https://ollama.com/search", OllamaBrowseLinks.ollama(readsImages = false))
        assertEquals("https://ollama.com/search?c=vision", OllamaBrowseLinks.ollama(readsImages = true))
        assertEquals("https://huggingface.co/models?library=gguf&sort=trending", OllamaBrowseLinks.huggingFace(readsImages = false))
        assertEquals("https://huggingface.co/models?library=gguf&sort=trending&pipeline_tag=image-text-to-text", OllamaBrowseLinks.huggingFace(readsImages = true))
    }
}
