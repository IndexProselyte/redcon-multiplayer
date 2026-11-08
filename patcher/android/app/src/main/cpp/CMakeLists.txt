cmake_minimum_required(VERSION 3.22.1)
project(redconpatch LANGUAGES C CXX)

add_library(xdelta3decoder STATIC third_party/xdelta3/xdelta3.c)
target_include_directories(xdelta3decoder PRIVATE third_party/xdelta3)
target_compile_features(xdelta3decoder PRIVATE c_std_99)
target_compile_definitions(xdelta3decoder PRIVATE
    HAVE_CONFIG_H=1 XD3_USE_LARGESIZET=0 REGRESSION_TEST=0
    SECONDARY_DJW=1 SECONDARY_FGK=0 SECONDARY_LZMA=0
    XD3_MAIN=0 XD3_ENCODER=0 XD3_DEBUG=0 EXTERNAL_COMPRESSION=0 VCDIFF_TOOLS=0)
target_compile_options(xdelta3decoder PRIVATE -fvisibility=hidden -fno-builtin)

add_library(redconpatch SHARED patch_jni.cpp)
target_include_directories(redconpatch PRIVATE third_party/xdelta3)
target_compile_definitions(redconpatch PRIVATE HAVE_CONFIG_H=1 XD3_USE_LARGESIZET=0)
target_link_libraries(redconpatch PRIVATE xdelta3decoder log)
target_compile_options(redconpatch PRIVATE -fvisibility=hidden -Wall -Wextra -Werror)
