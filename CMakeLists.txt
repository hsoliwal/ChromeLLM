cmake_minimum_required(VERSION 3.20)
project(chromellm_diffusion LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(chromellm_diffusion SHARED native/diffusion_native.cpp)
target_include_directories(chromellm_diffusion PUBLIC \${CMAKE_CURRENT_SOURCE_DIR}/native)

find_package(JNI REQUIRED)
add_library(chromellm_diffusion_jni SHARED native/diffusion_jni.cpp)
target_include_directories(chromellm_diffusion_jni PRIVATE \${CMAKE_CURRENT_SOURCE_DIR}/native \${JNI_INCLUDE_DIRS})
target_link_libraries(chromellm_diffusion_jni PRIVATE chromellm_diffusion)
