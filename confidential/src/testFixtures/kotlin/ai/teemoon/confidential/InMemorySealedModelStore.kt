package ai.teemoon.confidential

// 1.1.1 audit integ read, item 7: the in-memory SealedModelStore is a test double. It lived in main, so the shipped app carried a
// store that forgets on exit; it is a Gradle test fixture of :confidential now, seen by the tests of :confidential, :app and :inference.
class InMemorySealedModelStore : SealedModelStore {
    var held: Set<String>? = null
    override fun read() = held
    override fun write(ids: Set<String>?) { held = ids }
}
