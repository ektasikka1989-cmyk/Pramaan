cmake_minimum_required(VERSION 3.22.1)
project(pramaan_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp pinned to the release tag this bridge was compiled and tested against.
# Override with -DPRAMAAN_LLAMA_SRC=/path/to/llama.cpp to build from a local checkout (offline builds).
set(PRAMAAN_LLAMA_TAG "b11799" CACHE STRING "llama.cpp release tag")
set(PRAMAAN_LLAMA_SRC "" CACHE PATH "Local llama.cpp checkout (optional)")
# CPU features the phone must have. armv8.2-a+dotprod = practically every phone since 2018/19
# (Cortex-A55/A75 and newer) and ~2-3x faster than plain armv8-a. Use "armv8-a" for very old phones.
set(PRAMAAN_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "-march for the llama.cpp CPU kernels")

# The app's :whisper module already ships its own libggml*.so (whisper.cpp's ggml). To avoid two
# different ggml versions with the same file names, llama.cpp's ggml is linked STATICALLY into
# libpramaan_llama.so and its symbols are hidden: one self-contained .so, no name clash.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)          # host CPU is not the phone CPU
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "${PRAMAAN_ARM_ARCH}" CACHE STRING "" FORCE)
endif()

if(PRAMAAN_LLAMA_SRC)
    add_subdirectory(${PRAMAAN_LLAMA_SRC} llama-build)
else()
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG ${PRAMAAN_LLAMA_TAG}
        GIT_SHALLOW TRUE)
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(pramaan_llama SHARED pramaan_llama.cpp)
target_link_libraries(pramaan_llama PRIVATE llama ggml)
# Export only the JNI functions; everything from the static libraries stays private.
target_link_options(pramaan_llama PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-Bsymbolic")
if(ANDROID)
    target_link_libraries(pramaan_llama PRIVATE log android)
else()
    # Desktop build is only used to test the bridge on a PC (same code, JVM instead of ART).
    find_package(JNI REQUIRED)
    target_include_directories(pramaan_llama PRIVATE ${JNI_INCLUDE_DIRS})
endif()
