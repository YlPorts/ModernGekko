cmake_minimum_required(VERSION 3.22.1)
project(ModernGekkoAndroidBridge LANGUAGES CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "This CMake project must be built with the Android NDK toolchain")
endif()

add_library(moderngekko_android SHARED
    moderngekko_jni.cpp
    ../../src/runtime/game.cpp
)

target_include_directories(moderngekko_android
    PRIVATE
        ../../include
)

target_compile_features(moderngekko_android PRIVATE cxx_std_23)
set_target_properties(moderngekko_android PROPERTIES
    CXX_STANDARD 23
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
    OUTPUT_NAME moderngekko_android
)

target_compile_options(moderngekko_android PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fvisibility=hidden
)

find_library(ANDROID_LOG_LIBRARY log REQUIRED)
target_link_libraries(moderngekko_android PRIVATE ${ANDROID_LOG_LIBRARY})
