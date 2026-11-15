cmake_minimum_required(VERSION 3.22.1)
project(freebuff_native CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Native inference is optional. The Kotlin app builds and runs without it; the
# engine then reports that on-device inference is not part of the build instead
# of fabricating answers.
#
#   ./scripts/fetch-native.sh
#   ./gradlew assembleDebug -PwithNative=true
#
# whisper.cpp is fetched alongside llama.cpp but is not compiled into this CMake
# project: both projects vendor a ggml target, and CMake target names are global,
# so adding both subdirectories collides. See app/src/main/cpp/README.md for the
# two supported ways to build whisper.cpp.
# ---------------------------------------------------------------------------

set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llama.cpp)

if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp is missing at ${LLAMA_DIR}. Run ./scripts/fetch-native.sh first, "
        "or build without -PwithNative to get an APK without on-device inference.")
endif()

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama)

find_library(log-lib log)

add_library(freebuff_llama SHARED llama_jni.cpp)
target_link_libraries(freebuff_llama PRIVATE llama ${log-lib} android)
target_include_directories(freebuff_llama PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)
target_compile_options(freebuff_llama PRIVATE -O3 -fexceptions -frtti)
