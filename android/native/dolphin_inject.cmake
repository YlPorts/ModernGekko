if(NOT DEFINED MODERNGEKKO_ROOT)
    message(FATAL_ERROR "MODERNGEKKO_ROOT must point to the ModernGekko checkout")
endif()

add_library(moderngekko_android SHARED
    "${MODERNGEKKO_ROOT}/android/native/moderngekko_jni.cpp"
    "${MODERNGEKKO_ROOT}/android/native/android_host.cpp"
    "${MODERNGEKKO_ROOT}/src/runtime/game.cpp"
    "${MODERNGEKKO_ROOT}/src/runtime/module.cpp"
    "${MODERNGEKKO_ROOT}/src/runtime/module_loader.cpp"
)

target_include_directories(moderngekko_android PRIVATE
    "${MODERNGEKKO_ROOT}/include"
    "${PROJECT_SOURCE_DIR}/Source/Core"
    "${PROJECT_SOURCE_DIR}/Source/Android"
    "${PROJECT_SOURCE_DIR}/GXRuntime/include"
)

target_compile_features(moderngekko_android PRIVATE cxx_std_23)
target_compile_definitions(moderngekko_android PRIVATE
    MODERNGEKKO_ANDROID_WITH_DOLPHIN=1
    _ARCH_64=1
    _M_ARM_64=1
)
set_target_properties(moderngekko_android PROPERTIES
    CXX_STANDARD 23
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
    OUTPUT_NAME moderngekko_android
)

function(moderngekko_finish_android_target)
    target_link_libraries(moderngekko_android PRIVATE
        androidcommon
        common
        core
        discio
        inputcommon
        uicommon
        android
        log
    )
endfunction()

# The Dolphin targets are declared later by add_subdirectory(Source).
cmake_language(DEFER CALL moderngekko_finish_android_target)
