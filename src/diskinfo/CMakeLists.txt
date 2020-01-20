file(GLOB_RECURSE SOURCE ./*.cc)

add_library(${PROJECT_NAME}-lib ${SOURCE})
target_include_directories(${PROJECT_NAME}-lib PUBLIC ${PROJECT_SOURCE_DIR}/src)
set_target_properties(${PROJECT_NAME}-lib PROPERTIES OUTPUT_NAME ${PROJECT_NAME})
