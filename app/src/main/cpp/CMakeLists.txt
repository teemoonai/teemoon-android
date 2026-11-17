# The isolated decoder's native library: Wuffs (app/src/main/cpp/third_party/wuffs, pinned by sha256 in
# docs/IMAGE_DECODER.md) and its JNI glue. Deterministic flags only, so the reproducible build still matches: no
# timestamps, no absolute paths in the output.
cmake_minimum_required(VERSION 3.22.1)
project(teemoon_wuffs C)

add_library(teemoon_wuffs SHARED wuffs_jni.c)
# -g0: no debug info at all. With it, the Mac and Linux builds differed only in DWARF — the build directory and the host
# clang's own build string (the NDK's macOS clang is built "-bolt -mlgo", Linux "+bolt +mlgo") — while every code and
# data section was identical (2026-10-25). There is no crash reporting to read symbols anyway. -fno-ident: the same host
# string sat in .comment too; without it the section holds only "Linker: LLD 18.0.3", alike on both.
target_compile_options(teemoon_wuffs PRIVATE -O2 -g0 -fno-ident -fvisibility=hidden -ffile-prefix-map=${CMAKE_SOURCE_DIR}=. -Wno-unused-function)
target_link_options(teemoon_wuffs PRIVATE -Wl,--build-id=none -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,separate-loadable-segments)

# The NDK's own startup object (sysroot crtbegin_so.o) carries the host clang's identity in .comment, and the NDK's
# macOS and Linux packages differ there ("-bolt -mlgo" vs "+bolt +mlgo"), so no compile flag can reach it. Removing the
# section after the link leaves code and data untouched and makes a Mac build and a Linux build the same bytes.
add_custom_command(TARGET teemoon_wuffs POST_BUILD
    COMMAND ${CMAKE_OBJCOPY} --remove-section=.comment $<TARGET_FILE:teemoon_wuffs>
    VERBATIM)
