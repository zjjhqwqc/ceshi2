cmake_minimum_required(VERSION 3.10.2)
project("sigbypass")

set(CMAKE_CXX_STANDARD 11)

add_library(sigbypass SHARED sigbypass.cpp)

find_library(log-lib log)

target_link_libraries(sigbypass ${log-lib})
