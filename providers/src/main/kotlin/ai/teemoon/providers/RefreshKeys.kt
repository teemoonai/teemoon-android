package ai.teemoon.providers

import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The keys a refresh sends, read on [io] and handed back as a lookup. Opening the where sheet starts the home probe and
 * the catalogue refresh from the main thread, and each read the key it sends there: 20 binder calls to keystore2, ~160
 * ms, in the sheet's first frames (the maintainer's Pixel, Perfetto, 2026-11-12). The keys live as long as the refresh,
 * as they did when each was read beside its request.
 */
suspend fun refreshKeys(providers: List<Provider>, io: CoroutineContext, read: (Provider) -> String): (Provider) -> String {
    val keys = withContext(io) { providers.associate { it.id to read(it) } }
    return { keys[it.id].orEmpty() }
}
