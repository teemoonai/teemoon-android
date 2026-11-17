package ai.teemoon.app.imaging

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What turns untrusted image bytes into a stored image: the isolated service in the app, a fake in JVM tests. */
fun interface ImageDecoderPort {
    suspend fun decode(bytes: ByteArray): ImageDecodeFrame
}

/**
 * The app's side of [ImageDecodeService]. Untrusted bytes leave this process through a pipe and only the service's
 * re-encoded result comes back; every failure — the service crashing (an exploit or a decoder bug), hanging past
 * [ImageCaps.DECODE_TIMEOUT_MS], or writing garbage — is a placeholder here, never a crash of the app. A dead service is
 * rebound on the next image.
 */
class IsolatedImageDecoder(context: Context) : ImageDecoderPort {
    private val app = context.applicationContext
    private val lock = Mutex()
    @Volatile private var service: IImageDecodeService? = null
    private var connection: ServiceConnection? = null

    private suspend fun bound(): IImageDecodeService? = lock.withLock {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        unbindLocked()
        val ready = CompletableDeferred<IImageDecodeService?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val s = binder?.let { IImageDecodeService.Stub.asInterface(it) }
                service = s; ready.complete(s)
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
            override fun onBindingDied(name: ComponentName?) { service = null; ready.complete(null) }
            override fun onNullBinding(name: ComponentName?) { ready.complete(null) }
        }
        val ok = runCatching { app.bindService(Intent(app, ImageDecodeService::class.java), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!ok) return null
        connection = conn
        withTimeoutOrNull(5_000) { ready.await() }
    }

    private fun unbindLocked() {
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null; service = null
    }

    override suspend fun decode(bytes: ByteArray): ImageDecodeFrame = withContext(Dispatchers.IO) {
        if (bytes.size > ImageCaps.MAX_BYTES) return@withContext ImageDecodeFrame.Rejected(ImageRejection.TOO_MANY_BYTES)
        val svc = bound() ?: return@withContext ImageDecodeFrame.Rejected(ImageRejection.DECODER_FAILED)
        val (inRead, inWrite) = ParcelFileDescriptor.createPipe()
        val (outRead, outWrite) = ParcelFileDescriptor.createPipe()
        val result = withTimeoutOrNull(ImageCaps.DECODE_TIMEOUT_MS) {
            coroutineScope {
                val writer = async { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(inWrite).use { it.write(bytes) } } }
                val reader = async { ParcelFileDescriptor.AutoCloseInputStream(outRead).use { ImageDecodeFrame.read(it) } }
                val call = async {
                    try { svc.decode(inRead, outWrite, ImageCaps.MAX_LONG_SIDE, ImageCaps.THUMB_LONG_SIDE) }
                    catch (_: Exception) { null }   // DeadObjectException: the service died mid-decode
                    finally { runCatching { inRead.close() }; runCatching { outWrite.close() } }   // our copies: the reader sees EOF
                }
                writer.await(); call.await()
                reader.await()
            }
        }
        if (result == null) {
            // Hung: abandon it. Unbinding lets the system end that process; the next image gets a fresh one.
            runCatching { outRead.close() }
            lock.withLock { unbindLocked() }
            return@withContext ImageDecodeFrame.Rejected(ImageRejection.TIMED_OUT)
        }
        result
    }

    /** Debug builds (ImageDecodeServiceTest): the service ends its own process mid-call, as a decoder crash would. */
    internal suspend fun crashServiceForTest() { runCatching { bound()?.crashForTest() } }
}
