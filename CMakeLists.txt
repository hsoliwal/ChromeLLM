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


find_package(OpenCV QUIET COMPONENTS core imgproc photo video)
if(OpenCV_FOUND)
  add_library(chromellm_opencv_jni SHARED native/opencv_jni.cpp)
  target_include_directories(chromellm_opencv_jni PRIVATE
      ${JNI_INCLUDE_DIRS}
      ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(chromellm_opencv_jni PRIVATE ${OpenCV_LIBS})
  message(STATUS "OpenCV JNI enabled: ${OpenCV_VERSION}")
else()
  message(STATUS "OpenCV not found; chromellm_opencv_jni will not be built")
endif()

find_package(PkgConfig QUIET)
if(PkgConfig_FOUND)
  pkg_check_modules(FFMPEG QUIET IMPORTED_TARGET
      libavformat
      libavcodec
      libavutil
      libswscale)
endif()

if(FFMPEG_FOUND)
  add_library(chromellm_ffmpeg_jni SHARED native/ffmpeg_decoder_jni.cpp)
  target_include_directories(chromellm_ffmpeg_jni PRIVATE ${JNI_INCLUDE_DIRS})
  target_link_libraries(chromellm_ffmpeg_jni PRIVATE PkgConfig::FFMPEG)
  message(STATUS "FFmpeg JNI enabled")
else()
  message(STATUS "FFmpeg development libraries not found; chromellm_ffmpeg_jni will not be built")
endif()
