cmake_minimum_required(VERSION 3.22)
project(inkling CXX)

set(CMAKE_CXX_STANDARD 17)

# OmniVoice's bridge to LiteRT's C API (omni_rt.cpp): opens libLiteRt.so at run time; nothing else is native since LFMP-8.
add_library(omnirt SHARED omni_rt.cpp)
target_link_libraries(omnirt dl android log)
