cmake_minimum_required(VERSION 3.22.1)
project(aman_inference VERSION 1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ── Release optimisations ─────────────────────────────────────────────────────
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_options(-O3 -ffast-math -funroll-loops)
    # ARM-specific: enable NEON SIMD
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        add_compile_options(-march=armv8-a+simd)
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        add_compile_options(-march=armv7-a -mfpu=neon -mfloat-abi=softfp)
    endif()
endif()

# ── Android system libraries ──────────────────────────────────────────────────
find_library(log-lib      log)
find_library(android-lib  android)
find_library(jnigraphics-lib jnigraphics)
find_library(EGL-lib      EGL)
find_library(GLESv3-lib   GLESv3)

# ── Source files ──────────────────────────────────────────────────────────────
add_library(
    aman_inference
    SHARED
    src/jni_bridge.cpp
    src/inference_engine.cpp
    src/image_processor.cpp
    src/thread_pool.cpp
)

target_include_directories(aman_inference PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(
    aman_inference
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    ${EGL-lib}
    ${GLESv3-lib}
)
