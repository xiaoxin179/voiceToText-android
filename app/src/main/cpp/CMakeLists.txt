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
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    include_directories(
        SYSTEM
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/SPIRV-Headers-v1.3.296/include"
    )
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()
list(PREPEND CMAKE_PREFIX_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake")
set(
    SPIRV-Headers_DIR
    "${CMAKE_CURRENT_LIST_DIR}/cmake"
    CACHE PATH "Bundled SPIR-V package metadata" FORCE
)
if (GGML_VULKAN AND ANDROID AND DEFINED ANDROID_NDK)
    set(
        Vulkan_INCLUDE_DIR
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/Vulkan-Headers-v1.3.296/include"
        CACHE PATH "Pinned Khronos Vulkan headers" FORCE
    )
    set(
        Vulkan_GLSLC_EXECUTABLE
        "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe"
        CACHE FILEPATH "Vulkan shader compiler" FORCE
    )
endif()

if (GGML_VULKAN AND CMAKE_CROSSCOMPILING AND CMAKE_HOST_WIN32)
    file(TO_CMAKE_PATH "$ENV{VTT_HOST_TOOLCHAIN_BIN}" VTT_HOST_TOOLCHAIN_BIN)
    unset(VTT_HOST_C_COMPILER CACHE)
    unset(VTT_HOST_CXX_COMPILER CACHE)
    find_program(
        VTT_HOST_C_COMPILER
        NAMES x86_64-w64-mingw32-clang.exe gcc.exe clang.exe
        PATHS "${VTT_HOST_TOOLCHAIN_BIN}"
        NO_DEFAULT_PATH
        NO_CMAKE_FIND_ROOT_PATH
    )
    find_program(
        VTT_HOST_CXX_COMPILER
        NAMES x86_64-w64-mingw32-clang++.exe g++.exe clang++.exe
        PATHS "${VTT_HOST_TOOLCHAIN_BIN}"
        NO_DEFAULT_PATH
        NO_CMAKE_FIND_ROOT_PATH
    )
    if (NOT VTT_HOST_C_COMPILER OR NOT VTT_HOST_CXX_COMPILER)
        message(FATAL_ERROR "Vulkan shader generation requires a Windows host C/C++ compiler")
    endif()
    file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" VTT_HOST_NINJA)
    configure_file(
        "${CMAKE_CURRENT_LIST_DIR}/cmake/vulkan-host-toolchain.cmake.in"
        "${CMAKE_BINARY_DIR}/vtt-vulkan-host-toolchain.cmake"
        @ONLY
    )
    set(
        GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
        "${CMAKE_BINARY_DIR}/vtt-vulkan-host-toolchain.cmake"
        CACHE FILEPATH "Host toolchain for Vulkan shader generation" FORCE
    )
endif()

set(WHISPER_CPP_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp-v1.9.1")
if(NOT EXISTS "${WHISPER_CPP_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Missing vendored whisper.cpp source at ${WHISPER_CPP_SOURCE_DIR}")
endif()
add_subdirectory("${WHISPER_CPP_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/whispercpp")

add_library(whisper_jni SHARED whisper_jni.cpp)
target_include_directories(whisper_jni PRIVATE ${WHISPER_CPP_SOURCE_DIR}/include)
target_compile_features(whisper_jni PRIVATE cxx_std_17)
if (GGML_VULKAN)
    target_compile_definitions(whisper_jni PRIVATE VTT_VULKAN_ENABLED=1)
endif()
target_link_libraries(whisper_jni PRIVATE whisper log android)
