# fortress-native — JNI detection heuristics for Fortress.
#
# Built as a plain C shared library (no STL exceptions, static libc++ for the
# armeabi-v7a slice). All exported symbols are consumed by
# dev.fortress.scanner.RootkitHeuristics via `external fun` declarations.
cmake_minimum_required(VERSION 3.22.1)
project("fortress-native" CXX)

add_library(fortress-native SHARED fortress-native.cpp)

find_library(log-lib log)
target_link_libraries(fortress-native ${log-lib})

target_compile_options(fortress-native PRIVATE -Wall -Wextra -Os)
