cmake_minimum_required(VERSION 3.22.1)

project("whisper_jni")

# Build against the pinned upstream source checked into this repository. This
# keeps multi-ABI Android builds offline and reproducible after cloning.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

set(WHISPER_CPP_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp-v1.9.1")
if(NOT EXISTS "${WHISPER_CPP_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Missing vendored whisper.cpp source at ${WHISPER_CPP_SOURCE_DIR}")
endif()
add_subdirectory("${WHISPER_CPP_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/whispercpp")

add_library(whisper_jni SHARED whisper_jni.cpp)
target_include_directories(whisper_jni PRIVATE ${WHISPER_CPP_SOURCE_DIR}/include)
target_compile_features(whisper_jni PRIVATE cxx_std_17)
target_link_libraries(whisper_jni PRIVATE whisper log android)
