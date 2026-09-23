cmake_minimum_required(VERSION 3.20)
project(chromellm_diffusion LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(chromellm_diffusion SHARED native/diffusion_native.cpp)
target_include_directories(chromellm_diffusion PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/native)

find_package(JNI REQUIRED)
add_library(chromellm_diffusion_jni SHARED native/diffusion_jni.cpp)
target_include_directories(chromellm_diffusion_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/native ${JNI_INCLUDE_DIRS})
target_link_libraries(chromellm_diffusion_jni PRIVATE chromellm_diffusion)


add_library(chromellm_gpu SHARED native/gpu_opencl.cpp)
target_include_directories(chromellm_gpu PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/native)
if(CMAKE_DL_LIBS)
  target_link_libraries(chromellm_gpu PRIVATE ${CMAKE_DL_LIBS})
endif()

add_library(chromellm_gpu_jni SHARED native/gpu_jni.cpp)
target_include_directories(chromellm_gpu_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/native
    ${JNI_INCLUDE_DIRS})
target_link_libraries(chromellm_gpu_jni PRIVATE chromellm_gpu)
