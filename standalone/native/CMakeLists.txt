cmake_minimum_required(VERSION 3.20)
project(chromellm_llama_jni LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(JNI REQUIRED)
option(CHROMELLM_SYNTHETIC_TEST "Build test-only fake llama API (never for distribution)" OFF)
set(CHROMELLM_LLAMA_SOURCE_DIR "" CACHE PATH "Operator-supplied llama.cpp checkout to build with the JNI bridge")
option(CHROMELLM_WEBGPU "Build the supplied llama.cpp checkout with its native Dawn/WebGPU provider" OFF)
if(CHROMELLM_SYNTHETIC_TEST)
  if(CHROMELLM_LLAMA_SOURCE_DIR OR CHROMELLM_WEBGPU)
    message(FATAL_ERROR "The synthetic fixture cannot build a real llama.cpp or WebGPU provider")
  endif()
  add_library(chromellm_llama_stub SHARED tests/llama_stub.cpp)
  set_target_properties(chromellm_llama_stub PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
  target_include_directories(chromellm_llama_stub PRIVATE tests)
  set(LLAMA_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/tests)
  set(LLAMA_LIBRARY chromellm_llama_stub)
  set(GGML_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/tests)
  set(CHROMELLM_GGML_LIBRARIES chromellm_llama_stub)
elseif(CHROMELLM_LLAMA_SOURCE_DIR)
  if(NOT EXISTS "${CHROMELLM_LLAMA_SOURCE_DIR}/CMakeLists.txt"
      OR NOT EXISTS "${CHROMELLM_LLAMA_SOURCE_DIR}/include/llama.h"
      OR NOT EXISTS "${CHROMELLM_LLAMA_SOURCE_DIR}/ggml/include/ggml-backend.h")
    message(FATAL_ERROR "CHROMELLM_LLAMA_SOURCE_DIR must contain a compatible llama.cpp checkout")
  endif()
  if(GGML_BACKEND_DL)
    message(FATAL_ERROR "The source build uses linked GGML providers; set GGML_BACKEND_DL=OFF")
  endif()
  set(CMAKE_POSITION_INDEPENDENT_CODE ON)
  set(GGML_BACKEND_DL OFF CACHE BOOL "Use linked GGML backends")
  set(GGML_WEBGPU ${CHROMELLM_WEBGPU} CACHE BOOL "Native WebGPU provider" FORCE)
  set(LLAMA_BUILD_COMMON OFF CACHE BOOL "Build llama.cpp common helpers" FORCE)
  set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build llama.cpp tests" FORCE)
  set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build llama.cpp examples" FORCE)
  set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build llama.cpp server" FORCE)
  set(LLAMA_CURL OFF CACHE BOOL "Enable llama.cpp downloads" FORCE)
  # The existing GGML WebGPU provider discovers Dawn through find_package(Dawn REQUIRED).
  # Supply Dawn_DIR or CMAKE_PREFIX_PATH for an independently built Dawn installation.
  add_subdirectory("${CHROMELLM_LLAMA_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)
  if(NOT TARGET llama OR NOT TARGET ggml)
    message(FATAL_ERROR "The supplied llama.cpp checkout must define the llama and ggml CMake targets")
  endif()
  if(CHROMELLM_WEBGPU AND NOT TARGET ggml-webgpu)
    message(FATAL_ERROR "The supplied llama.cpp checkout does not provide GGML WebGPU")
  endif()
  set(LLAMA_INCLUDE_DIR "${CHROMELLM_LLAMA_SOURCE_DIR}/include")
  set(GGML_INCLUDE_DIR "${CHROMELLM_LLAMA_SOURCE_DIR}/ggml/include")
  set(LLAMA_LIBRARY llama)
  set(CHROMELLM_GGML_LIBRARIES ggml)
else()
  if(CHROMELLM_WEBGPU)
    message(FATAL_ERROR "CHROMELLM_WEBGPU requires CHROMELLM_LLAMA_SOURCE_DIR; prebuilt libraries must already enable GGML WebGPU")
  endif()
  find_path(LLAMA_INCLUDE_DIR NAMES llama.h REQUIRED)
  find_path(GGML_INCLUDE_DIR NAMES ggml.h REQUIRED)
  foreach(GGML_HEADER ggml-cpu.h ggml-backend.h ggml-alloc.h ggml-opt.h)
    if(NOT EXISTS "${GGML_INCLUDE_DIR}/${GGML_HEADER}")
      message(FATAL_ERROR "Missing llama.cpp ggml API header: ${GGML_HEADER} in ${GGML_INCLUDE_DIR}")
    endif()
  endforeach()
  find_library(LLAMA_LIBRARY NAMES llama REQUIRED)
  # Some distributions export GGML from one libllama; others split it into ggml and
  # ggml-base. Preserve the former instead of requiring nonexistent extra libraries.
  include(CheckCXXSourceCompiles)
  include(CMakePushCheckState)
  cmake_push_check_state(RESET)
  set(CMAKE_REQUIRED_INCLUDES ${LLAMA_INCLUDE_DIR} ${GGML_INCLUDE_DIR})
  set(CMAKE_REQUIRED_LIBRARIES ${LLAMA_LIBRARY})
  set(CHROMELLM_GGML_LINK_CHECK "
    #include <llama.h>
    #include <ggml-backend.h>
    int main() {
      auto params = llama_model_default_params();
      params.devices = nullptr;
      params.split_mode = LLAMA_SPLIT_MODE_NONE;
      params.main_gpu = 0;
      auto reg = ggml_backend_reg_by_name(\"WebGPU\");
      const char* text = nullptr;
      if (ggml_backend_dev_count() > 0) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(0);
        reg = ggml_backend_dev_backend_reg(dev);
        text = ggml_backend_dev_name(dev);
        text = ggml_backend_dev_description(dev);
        text = ggml_backend_reg_name(reg);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_ACCEL) text = nullptr;
      }
      return reg != nullptr || text != nullptr || params.main_gpu != 0;
    }")
  function(chromellm_check_ggml_link result)
    # A static-library try-compile cannot detect missing exported symbols.
    set(CMAKE_TRY_COMPILE_TARGET_TYPE EXECUTABLE)
    # Recheck when an operator replaces artifacts at the same configured paths.
    unset(${result} CACHE)
    check_cxx_source_compiles("${CHROMELLM_GGML_LINK_CHECK}" ${result})
    set(${result} "${${result}}" PARENT_SCOPE)
  endfunction()
  chromellm_check_ggml_link(CHROMELLM_LLAMA_EXPORTS_GGML)
  if(CHROMELLM_LLAMA_EXPORTS_GGML)
    set(CHROMELLM_GGML_LIBRARIES "")
  else()
    get_filename_component(LLAMA_LIBRARY_DIR "${LLAMA_LIBRARY}" DIRECTORY)
    find_library(GGML_LIBRARY NAMES ggml HINTS "${LLAMA_LIBRARY_DIR}" REQUIRED)
    find_library(GGML_BASE_LIBRARY NAMES ggml-base HINTS "${LLAMA_LIBRARY_DIR}" REQUIRED)
    set(CHROMELLM_GGML_LIBRARIES ${GGML_LIBRARY} ${GGML_BASE_LIBRARY})
    set(CMAKE_REQUIRED_LIBRARIES ${LLAMA_LIBRARY} ${CHROMELLM_GGML_LIBRARIES})
    chromellm_check_ggml_link(CHROMELLM_SPLIT_GGML_LINKS)
    if(NOT CHROMELLM_SPLIT_GGML_LINKS)
      message(FATAL_ERROR "The supplied llama.cpp/GGML libraries and headers do not provide the required device-selection API")
    endif()
  endif()
  cmake_pop_check_state()
endif()
# The operator supplies license-reviewed source or matching native libraries and headers.
add_library(chromellm_llama_jni SHARED llama_jni.cpp)
target_include_directories(chromellm_llama_jni PRIVATE ${JNI_INCLUDE_DIRS} ${LLAMA_INCLUDE_DIR} ${GGML_INCLUDE_DIR})
target_link_libraries(chromellm_llama_jni PRIVATE ${LLAMA_LIBRARY} ${CHROMELLM_GGML_LIBRARIES})
