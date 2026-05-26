# Native build for the NATIVE engine's splice(2) zero-copy shim.
# Produces libagentsplice.so for each enabled ABI; AGP places the
# output alongside the pre-built libproxyagent.so in the APK's
# lib/<abi>/ directory at packaging time.
cmake_minimum_required(VERSION 3.18.1)
project(agentsplice C)

set(CMAKE_C_STANDARD 11)

add_library(agentsplice SHARED splice_shim.c)

# splice / pipe live in libc — no extra link deps. Strip the binary
# to shave a few KiB per ABI in release builds; debug builds keep
# symbols for ndk-stack diagnostics.
target_compile_options(agentsplice PRIVATE
    -Wall -Wextra -O2
    -ffunction-sections -fdata-sections)
target_link_options(agentsplice PRIVATE -Wl,--gc-sections)
