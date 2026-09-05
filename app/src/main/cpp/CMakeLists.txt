cmake_minimum_required(VERSION 3.22.1)
project(svcam_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(SVCAM_ENABLE_VULKAN "Enable llama.cpp Vulkan backend" ON)
set(SVCAM_VULKAN_HEADERS_DIR "" CACHE PATH "Isolated Vulkan-Headers include root for Android cross-builds")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ${SVCAM_ENABLE_VULKAN} CACHE BOOL "" FORCE)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp")
if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp submodule missing. Run: git submodule update --init --recursive")
endif()

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp-build")

# Android NDK exposes the Vulkan loader but may not ship the C++ Vulkan-Hpp header
# required by ggml-vulkan. CI passes an isolated copy containing only /vulkan/*.
# This avoids accidentally mixing Ubuntu host libc headers into the Android target.
if(SVCAM_ENABLE_VULKAN AND SVCAM_VULKAN_HEADERS_DIR)
    if(NOT EXISTS "${SVCAM_VULKAN_HEADERS_DIR}/vulkan/vulkan.hpp")
        message(FATAL_ERROR "vulkan/vulkan.hpp missing under SVCAM_VULKAN_HEADERS_DIR")
    endif()
    if(NOT EXISTS "${SVCAM_VULKAN_HEADERS_DIR}/spirv/unified1/spirv.hpp")
        message(FATAL_ERROR "SPIR-V headers missing under SVCAM_VULKAN_HEADERS_DIR")
    endif()
    target_include_directories(ggml-vulkan PRIVATE "${SVCAM_VULKAN_HEADERS_DIR}")
endif()

# The pinned ggml-vulkan source uses Android POSIX APIs such as getpagesize/pread
# but does not include <unistd.h> on its Android path. Force a tiny compatibility
# header into that target instead of modifying the llama.cpp submodule itself.
if(SVCAM_ENABLE_VULKAN AND ANDROID)
    target_compile_options(
        ggml-vulkan PRIVATE
        "-include${CMAKE_CURRENT_SOURCE_DIR}/vulkan_android_compat.h"
    )
endif()

add_library(svcam_native SHARED "native_bridge.cpp")
target_compile_definitions(svcam_native PRIVATE SVCAM_ENABLE_VULKAN=$<BOOL:${SVCAM_ENABLE_VULKAN}>)

target_include_directories(svcam_native PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include"
    "${LLAMA_CPP_DIR}/tools/mtmd"
)

target_link_libraries(svcam_native PRIVATE
    llama
    mtmd
    android
    log
)
