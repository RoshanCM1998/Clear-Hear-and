cmake_minimum_required(VERSION 3.18.1)
project(rnnoise_jni)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

add_library(
        rnnoise
        SHARED
        rnnoise_jni.c)

# If you later add rnnoise sources under rnnoise_src/, you can enable them here
# file(GLOB RNNOISE_SRC "rnnoise_src/*.c")
# target_sources(rnnoise PRIVATE ${RNNOISE_SRC})

find_library(log-lib log)

# Fix for 16KB page size compatibility (Google Play requirement for Android 15+)
# This ensures LOAD segments are aligned at 16KB boundaries
target_link_options(
        rnnoise
        PRIVATE
        -Wl,-z,max-page-size=16384)

target_link_libraries(
        rnnoise
        ${log-lib})