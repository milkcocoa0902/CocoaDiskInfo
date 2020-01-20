add_subdirectory(diskinfo)

add_executable(${PROJECT_NAME} main.cc)
target_link_libraries(${PROJECT_NAME}
				${PROJECT_NAME}-lib
				stdc++
				atasmart)
