cmake_minimum_required(VERSION 3.10)
project(CocoaDiskInfo C CXX)

set(CMAKE_CXX_EXTENSIONS  OFF)
set(CMAKE_CXX_STANDARD    17)

# Disable in-source-build
set(CMAKE_DISABLE_IN_SOURCE_BUILD ON)
set(CMAKE_DISABLE_SOURCE_CHANGES  ON)

set(EXECUTABLE_OUTPUT_PATH ${PROJECT_BINARY_DIR})


set(CMAKE_CXX_FLAGS         "${CMAKE_CXX_FLAGS}         -Wall -Wextra -pedantic")
set(CMAKE_CXX_FLAGS_DEBUG   "${CMAKE_CXX_FLAGS_DEBUG}   -g -DDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O2 -march=native -DNDEBUG")
set(CMAKE_EXE_LINKER_FLAGS  "${CMAKE_EXE_LINKER_FLAGS}  -Wl,-O1,--sort-common,--as-needed,-z,relro")


if(CMAKE_GENERATOR STREQUAL "Ninja")
		if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
				set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fdiagnostics-color=always")
		elseif(CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
				set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fcolor-diagnostics")
		endif()
endif()

# Sanitizers
if(ENABLE_SANITIZERS)
		if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
				message(STATUS "Enabling GCC's address sanitizer")
				set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fsanitize=address")
		elseif(CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
				message(STATUS "Enabling Clang's address sanitizer")
				set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fsanitize=address -fno-omit-frame-pointer")
		endif()
endif()


find_package(Boost 1.61.0 COMPONENTS coroutine system unit_test_framework REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(Gtkmm3 gtkmm-3.0)
include_directories(
		${PROJECT_SOURCE_DIR}/src
		${Gtkmm3_INCLUDE_DIRS}
		${Boost_INCLUDE_DIRS})



add_subdirectory(src)
