cmake_minimum_required(VERSION 3.18)
project(sliman_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# third_party/zygisk.hpp is fetched by fetch-deps.sh (official Zygisk API header).
add_library(sliman_native SHARED src/module.cpp src/config.cpp src/hooks.cpp)
target_include_directories(sliman_native PRIVATE third_party src)
target_compile_options(sliman_native PRIVATE -Wall -Wextra -Wno-unused-parameter -fno-exceptions -fno-rtti -O2)
target_link_libraries(sliman_native log)
