// The isolated decoder's native half: Wuffs (vendored, pinned — docs/IMAGE_DECODER.md) behind two JNI calls. It runs
// only in ImageDecodeService's isolated process. Wuffs is memory-safe by construction (bounds and arithmetic checked by
// its compiler; the C is generated, not hand-written); this file only moves bytes in and pixels out, and checks every
// size before it allocates.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// Only the modules the untrusted set needs: PNG (zlib, deflate, adler32, crc32), JPEG, GIF (lzw), and BMP for a picked
// or pasted image (the maintainer 2026-11-11; the import never sends one here).
#define WUFFS_IMPLEMENTATION
#define WUFFS_CONFIG__STATIC_FUNCTIONS
#define WUFFS_CONFIG__MODULES
#define WUFFS_CONFIG__MODULE__BASE
#define WUFFS_CONFIG__MODULE__ADLER32
#define WUFFS_CONFIG__MODULE__CRC32
#define WUFFS_CONFIG__MODULE__DEFLATE
#define WUFFS_CONFIG__MODULE__ZLIB
#define WUFFS_CONFIG__MODULE__PNG
#define WUFFS_CONFIG__MODULE__JPEG
#define WUFFS_CONFIG__MODULE__LZW
#define WUFFS_CONFIG__MODULE__GIF
#define WUFFS_CONFIG__MODULE__BMP
#include "third_party/wuffs/wuffs-v0.4.c"

// Mirrors ImageCaps: 50 MP, and a work buffer bound so a crafted JPEG cannot ask for more than this.
#define MAX_PIXELS 50000000ULL
#define MAX_WORKBUF (256ULL * 1024 * 1024)

/** The decoder for the bytes' magic number (as ImageFormat.sniff), or NULL. The caller frees it. */
static wuffs_base__image_decoder* decoder_for(const uint8_t* p, size_t n) {
    if (n >= 3 && p[0] == 0xFF && p[1] == 0xD8 && p[2] == 0xFF) return wuffs_jpeg__decoder__alloc_as__wuffs_base__image_decoder();
    if (n >= 8 && memcmp(p, "\x89PNG\r\n\x1a\n", 8) == 0) return wuffs_png__decoder__alloc_as__wuffs_base__image_decoder();
    if (n >= 6 && (memcmp(p, "GIF87a", 6) == 0 || memcmp(p, "GIF89a", 6) == 0)) return wuffs_gif__decoder__alloc_as__wuffs_base__image_decoder();
    if (n >= 26 && p[0] == 'B' && p[1] == 'M') return wuffs_bmp__decoder__alloc_as__wuffs_base__image_decoder();
    return NULL;
}

/** Header only: fills [ic] and returns 1, or returns 0. */
static int image_config(wuffs_base__image_decoder* dec, wuffs_base__io_buffer* src, wuffs_base__image_config* ic) {
    wuffs_base__status st = wuffs_base__image_decoder__decode_image_config(dec, ic, src);
    return wuffs_base__status__is_ok(&st) && wuffs_base__image_config__is_valid(ic);
}

JNIEXPORT jintArray JNICALL Java_ai_teemoon_app_imaging_WuffsImages_probe(JNIEnv* env, jclass cls, jbyteArray bytes) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, bytes);
    uint8_t* p = (uint8_t*)(*env)->GetByteArrayElements(env, bytes, NULL);
    if (!p) return NULL;
    jintArray out = NULL;
    wuffs_base__image_decoder* dec = decoder_for(p, (size_t)n);
    if (dec) {
        wuffs_base__io_buffer src = wuffs_base__ptr_u8__reader(p, (size_t)n, true);
        wuffs_base__image_config ic = {0};
        if (image_config(dec, &src, &ic)) {
            jint wh[2] = { (jint)wuffs_base__pixel_config__width(&ic.pixcfg), (jint)wuffs_base__pixel_config__height(&ic.pixcfg) };
            out = (*env)->NewIntArray(env, 2);
            if (out) (*env)->SetIntArrayRegion(env, out, 0, 2, wh);
        }
        free(dec);
    }
    (*env)->ReleaseByteArrayElements(env, bytes, (jbyte*)p, JNI_ABORT);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_ai_teemoon_app_imaging_WuffsImages_decodeRgba(JNIEnv* env, jclass cls, jbyteArray bytes, jint width, jint height) {
    (void)cls;
    if (width <= 0 || height <= 0 || (uint64_t)width * (uint64_t)height > MAX_PIXELS) return NULL;
    jsize n = (*env)->GetArrayLength(env, bytes);
    uint8_t* p = (uint8_t*)(*env)->GetByteArrayElements(env, bytes, NULL);
    if (!p) return NULL;
    jbyteArray out = NULL;
    uint8_t* pixels = NULL;
    uint8_t* work = NULL;
    wuffs_base__image_decoder* dec = decoder_for(p, (size_t)n);
    if (!dec) goto done;
    wuffs_base__io_buffer src = wuffs_base__ptr_u8__reader(p, (size_t)n, true);
    wuffs_base__image_config ic = {0};
    if (!image_config(dec, &src, &ic)) goto done;
    // The header must say what the probe said: the caller checked the caps against these numbers.
    if ((jint)wuffs_base__pixel_config__width(&ic.pixcfg) != width || (jint)wuffs_base__pixel_config__height(&ic.pixcfg) != height) goto done;
    wuffs_base__pixel_config__set(&ic.pixcfg, WUFFS_BASE__PIXEL_FORMAT__RGBA_PREMUL, WUFFS_BASE__PIXEL_SUBSAMPLING__NONE, (uint32_t)width, (uint32_t)height);
    uint64_t len = (uint64_t)width * (uint64_t)height * 4;
    pixels = (uint8_t*)calloc((size_t)len, 1);
    if (!pixels) goto done;
    wuffs_base__pixel_buffer pb = {0};
    wuffs_base__status st = wuffs_base__pixel_buffer__set_from_slice(&pb, &ic.pixcfg, wuffs_base__make_slice_u8(pixels, (size_t)len));
    if (!wuffs_base__status__is_ok(&st)) goto done;
    uint64_t wlen = wuffs_base__image_decoder__workbuf_len(dec).max_incl;
    if (wlen > MAX_WORKBUF) goto done;
    if (wlen > 0) { work = (uint8_t*)malloc((size_t)wlen); if (!work) goto done; }
    // The first frame (for a GIF, the first only), composited onto a transparent canvas.
    st = wuffs_base__image_decoder__decode_frame(dec, &pb, &src, WUFFS_BASE__PIXEL_BLEND__SRC, wuffs_base__make_slice_u8(work, (size_t)wlen), NULL);
    if (!wuffs_base__status__is_ok(&st)) goto done;
    out = (*env)->NewByteArray(env, (jsize)len);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte*)pixels);
done:
    free(work);
    free(pixels);
    free(dec);
    (*env)->ReleaseByteArrayElements(env, bytes, (jbyte*)p, JNI_ABORT);
    return out;
}
