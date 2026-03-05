cmake_minimum_required(VERSION 3.18.1)
project(rnnoise_jni)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# RNNoise source files
set(RNNOISE_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src)
set(RNNOISE_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/include)

# RNNoise source files (simple version with compiled-in weights)
set(RNNOISE_SOURCES
    "${RNNOISE_SRC_DIR}/denoise.c"
    "${RNNOISE_SRC_DIR}/rnn.c"
    "${RNNOISE_SRC_DIR}/pitch.c"
    "${RNNOISE_SRC_DIR}/kiss_fft.c"
    "${RNNOISE_SRC_DIR}/celt_lpc.c"
    "${RNNOISE_SRC_DIR}/rnn_data.c"
)

add_library(
        rnnoise
        SHARED
        rnnoise_jni.c
        ${RNNOISE_SOURCES})

# Include RNNoise headers
target_include_directories(
        rnnoise
        PRIVATE
        ${RNNOISE_INCLUDE_DIR}
        ${RNNOISE_SRC_DIR}
)

# Add compiler definitions for RNNoise
target_compile_definitions(
        rnnoise
        PRIVATE
        # Disable training mode (we only need inference)
        RNNOISE_BUILD=1
)

find_library(log-lib log)
find_library(m-lib m)

# Fix for 16KB page size compatibility (Google Play requirement for Android 15+)
# This ensures LOAD segments are aligned at 16KB boundaries
target_link_options(
        rnnoise
        PRIVATE
        -Wl,-z,max-page-size=16384)

target_link_libraries(
        rnnoise
        ${log-lib}
        ${m-lib})