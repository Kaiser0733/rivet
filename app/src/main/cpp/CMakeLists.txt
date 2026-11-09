cmake_minimum_required(VERSION 3.22)
project(rivet_llama C CXX)

# Pinned llama.cpp release: v0.9.0 (commit cd959bae99ff4bed22bef2604f45241c09bbb8c8).
# The CI workflow clones this exact tag into .native/llama.cpp before configuring.
if(NOT DEFINED LLAMA_CPP_DIR OR NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_CPP_DIR must point at the pinned llama.cpp checkout")
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CONNECT OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# CPU only on arm64: no Vulkan/OpenCL/CUDA/Metal backends, no native-host flags.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

add_library(rivet_llama SHARED rivet_llama.cpp)
target_compile_features(rivet_llama PRIVATE cxx_std_17)
target_compile_options(rivet_llama PRIVATE -O3 -fvisibility=hidden)
# Android 15+ devices may use 16 KB pages; keep the packaged .so compatible.
target_link_options(rivet_llama PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(rivet_llama PRIVATE llama android log)
