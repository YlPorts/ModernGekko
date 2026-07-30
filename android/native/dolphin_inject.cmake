# CMAKE_PROJECT_INCLUDE is evaluated after every project() call, including all
# vendored dependencies. Only inject ModernGekko into Dolphin's root project.
if(NOT PROJECT_SOURCE_DIR STREQUAL CMAKE_SOURCE_DIR)
    return()
endif()

if(TARGET moderngekko_android)
    return()
endif()

if(NOT DEFINED MODERNGEKKO_ROOT)
    message(FATAL_ERROR "MODERNGEKKO_ROOT must point to the ModernGekko checkout")
endif()

cmake_policy(SET CMP0079 NEW)

add_library(moderngekko_android SHARED
    "${MODERNGEKKO_ROOT}/android/native/moderngekko_jni.cpp"
    "${MODERNGEKKO_ROOT}/src/runtime/game.cpp"
)

target_include_directories(moderngekko_android PRIVATE
    "${MODERNGEKKO_ROOT}/include"
    "${CMAKE_SOURCE_DIR}/Source/Core"
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
        common
        discio
        android
        log
    )
endfunction()

# Dolphin declares common/discio later in add_subdirectory(Source). Defer this
# operation to the root directory's end so those targets exist first.
cmake_language(DEFER DIRECTORY "${CMAKE_SOURCE_DIR}" CALL moderngekko_finish_android_target)
