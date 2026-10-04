cmake_minimum_required(VERSION 3.22.1)
project("tessmemory")

add_library(tessmemory SHARED
    tesseract_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(tessmemory
    ${log-lib}
)
