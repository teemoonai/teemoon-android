// Where the attestation checks run (the maintainer 2026-11-13 on the Pixel: "when teemoon is verifying near the ui slows down").
//
// The session's scope is the main thread, and the verifiers used to leave it only for their HTTP: quote parsing, the
// native DCAP check (X.509 chain, signatures, CRLs) up to four times, the TDX chain, the NRAS JWT, the report decodes
// and the provenance cache's file all ran on it between the fetches. Each verifier's entry point now moves its own work
// here, so a caller on the main thread waits without blocking it. What the checks prove, and the order the session
// applies their results in, are unchanged: the results still land on the session's scope.
package ai.teemoon.confidential

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object VerifyWork {
    /**
     * One worker: the checks ran one at a time on the main thread, and dcap-qvl's native verifier is not promised
     * thread-safe, so they still run one at a time — just not on the thread that draws the frames.
     */
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
}
