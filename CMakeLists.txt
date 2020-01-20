cmake_minimum_required(VERSION 3.10)
project(CocoaDiskInfo C CXX)

set(CMAKE_CXX_EXTENSIONS  OFF)
set(CMAKE_CXX_STANDARD    17)

# Disable in-source-build
set(CMAKE_DISABLE_IN_SOURCE_BUILD ON)
set(CMAKE_DISABLE_SOURCE_CHANGES  ON)

set(EXECUTABLE_OUTPUT_PATH ${PROJECT_BINARY_DIR})

find_package(PkgConfig REQUIRED)
pkg_check_modules(Gtkmm3 gtkmm-3.0)
include_directories(${Gtkmm3_INCLUDE_DIRS})

include_directories(src)
add_subdirectory(src)
