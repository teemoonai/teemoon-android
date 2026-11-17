// The binder contract of the isolated decoder (IMAGES.md §2; the maintainer 2026-10-25: a malformed image must never get code
// execution in, or crash, the app). Bytes go in through a pipe and come back through a pipe: nothing but those two file
// descriptors crosses, and the service can open nothing itself.
package ai.teemoon.app.imaging;

interface IImageDecodeService {
    /**
     * Reads at most 20 MiB + 1 from `input`, decodes it inside the caps, re-encodes it, and writes one result frame to
     * `output` (ImageDecodeFrame). Returns the frame's status code as well. Never throws across the binder for bad input.
     */
    int decode(in ParcelFileDescriptor input, in ParcelFileDescriptor output, int maxLongSide, int thumbLongSide);

    /** Debug builds only (ImageDecodeServiceTest): ends the service's process mid-call, as a decoder crash would. */
    void crashForTest();
}
