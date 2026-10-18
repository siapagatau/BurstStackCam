cmake_minimum_required(VERSION 3.18.1)
project(burststack LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(burststack SHARED stacker.cpp)

target_compile_options(burststack PRIVATE -O3 -ffast-math -fvisibility=hidden -fno-rtti)
target_link_options(burststack PRIVATE -Wl,--gc-sections)
