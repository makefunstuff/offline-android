cmake_minimum_required(VERSION 3.22.1)

project("lfm" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# CPU, plus llama.cpp's Vulkan backend for the GPU (docs/acceleration.md). Each is its own libggml-*.so, loaded at run
# time; a phone without a working Vulkan driver just has no GPU device and stays on the CPU.
# OpenMP on both ABIs: without it ggml starts and joins its worker threads for every graph, and speech runs six
# graphs per 80 ms frame (the emulator made speech at half speed that way).
set(GGML_OPENMP ON CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

# Vulkan: the NDK's libvulkan.so and glslc, with C++ headers (vulkan.hpp) and SPIR-V headers from submodules.
set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/third_party)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(Vulkan_INCLUDE_DIR ${THIRD_PARTY}/Vulkan-Headers/include CACHE PATH "" FORCE)
file(GLOB NDK_GLSLC ${ANDROID_NDK}/shader-tools/*/glslc)
set(Vulkan_GLSLC_EXECUTABLE ${NDK_GLSLC} CACHE FILEPATH "" FORCE)
set(SPIRV-Headers_DIR ${THIRD_PARTY}/cmake CACHE PATH "" FORCE)
include_directories(BEFORE ${THIRD_PARTY}/Vulkan-Headers/include ${THIRD_PARTY}/SPIRV-Headers/include)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(lfm SHARED lfm_jni.cpp talk.cpp)

target_include_directories(lfm PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(lfm llama mtmd ggml android log)
