// ports: tools/test_self_verify_script.py @ c9498d5 (iOS) — the harness itself is vendored in tools/, unchanged; this case
//        puts it in the JVM gate, so the script is RUN on every `./gradlew test`, not only read.
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The self-verify script, RUN — not read. Its string assertions (SelfVerifyScriptTest) had nothing to say when near.ai's
 * gateway began requiring authentication and every run cried wolf (iOS, 2026-08-22), nor when a keyless run printed
 * "everything checked out" over a gateway it never checked (c9498d5). The harness executes the rendered script against
 * fixture responses, offline, and asserts the tallies. Needs a python3 on the PATH; skips — never fails — without one.
 */
class SelfVerifyScriptRunTest {
    @Test fun theRenderedScriptPassesTheBehaviourHarness() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "tools/test_self_verify_script.py").isFile }
        assumeTrue("tools/test_self_verify_script.py not found above ${File("").absolutePath}", root != null)
        val python = runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("no python3 on the PATH", python)
        val proc = ProcessBuilder("python3", "tools/test_self_verify_script.py").directory(root).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assumeTrue("the harness timed out", proc.waitFor(120, TimeUnit.SECONDS))
        assertEquals("the harness failed:\n$out", 0, proc.exitValue())
    }
}
