cmake_minimum_required(VERSION 3.20)
project(chromellm_llama_jni LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(JNI REQUIRED)
option(CHROMELLM_SYNTHETIC_TEST "Build test-only fake llama API (never for distribution)" OFF)
if(CHROMELLM_SYNTHETIC_TEST)
  add_library(chromellm_llama_stub SHARED tests/llama_stub.cpp)
  target_include_directories(chromellm_llama_stub PRIVATE tests)
  set(LLAMA_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/tests)
  set(CHROMELLM_NATIVE_LIBRARIES chromellm_llama_stub)
else()
  find_path(LLAMA_INCLUDE_DIR NAMES llama.h REQUIRED)
  find_path(GGML_INCLUDE_DIR NAMES ggml.h REQUIRED)
  foreach(GGML_HEADER ggml-cpu.h ggml-backend.h ggml-alloc.h ggml-opt.h)
    if(NOT EXISTS "${GGML_INCLUDE_DIR}/${GGML_HEADER}")
      message(FATAL_ERROR "Missing llama.cpp ggml API header: ${GGML_HEADER} in ${GGML_INCLUDE_DIR}")
    endif()
  endforeach()
  find_library(LLAMA_LIBRARY NAMES llama REQUIRED)
  # Device enumeration is exported by ggml; device metadata comes from ggml-base.
  # Link both directly, including their import libraries when building on Windows.
  # Prefer sibling libraries from the explicitly selected llama.cpp build. Existing
  # GGML_* cache overrides take precedence; ordinary system discovery remains a fallback.
  get_filename_component(CHROMELLM_LLAMA_LIBRARY_DIR "${LLAMA_LIBRARY}" DIRECTORY)
  find_library(GGML_LIBRARY NAMES ggml HINTS "${CHROMELLM_LLAMA_LIBRARY_DIR}" NO_DEFAULT_PATH)
  find_library(GGML_BASE_LIBRARY NAMES ggml-base HINTS "${CHROMELLM_LLAMA_LIBRARY_DIR}" NO_DEFAULT_PATH)
  find_library(GGML_LIBRARY NAMES ggml REQUIRED)
  find_library(GGML_BASE_LIBRARY NAMES ggml-base REQUIRED)
  set(CHROMELLM_NATIVE_LIBRARIES ${LLAMA_LIBRARY} ${GGML_LIBRARY} ${GGML_BASE_LIBRARY})
endif()
# The operator supplies an independently built, license-reviewed llama.cpp shared library.
add_library(chromellm_llama_jni SHARED llama_jni.cpp)
target_include_directories(chromellm_llama_jni PRIVATE ${JNI_INCLUDE_DIRS} ${LLAMA_INCLUDE_DIR} ${GGML_INCLUDE_DIR})
target_link_libraries(chromellm_llama_jni PRIVATE ${CHROMELLM_NATIVE_LIBRARIES})

if(CHROMELLM_SYNTHETIC_TEST)
  include(CTest)
  if(BUILD_TESTING)
    add_test(NAME external-library-discovery
      COMMAND ${CMAKE_COMMAND}
        "-DCHROMELLM_NATIVE_SOURCE_DIR=${CMAKE_CURRENT_SOURCE_DIR}"
        "-DCHROMELLM_TEST_BINARY_DIR=${CMAKE_CURRENT_BINARY_DIR}/library-discovery-test"
        "-DCHROMELLM_TEST_GENERATOR=${CMAKE_GENERATOR}"
        "-DCHROMELLM_TEST_GENERATOR_PLATFORM=${CMAKE_GENERATOR_PLATFORM}"
        "-DCHROMELLM_TEST_GENERATOR_TOOLSET=${CMAKE_GENERATOR_TOOLSET}"
        "-DCHROMELLM_TEST_CXX_COMPILER=${CMAKE_CXX_COMPILER}"
        "-DCHROMELLM_TEST_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        "-DCHROMELLM_TEST_SHARED_PREFIX=${CMAKE_SHARED_LIBRARY_PREFIX}"
        "-DCHROMELLM_TEST_SHARED_SUFFIX=${CMAKE_SHARED_LIBRARY_SUFFIX}"
        "-DCHROMELLM_TEST_IMPORT_PREFIX=${CMAKE_IMPORT_LIBRARY_PREFIX}"
        "-DCHROMELLM_TEST_IMPORT_SUFFIX=${CMAKE_IMPORT_LIBRARY_SUFFIX}"
        "-DCHROMELLM_TEST_JAVA_INCLUDE_PATH=${JAVA_INCLUDE_PATH}"
        "-DCHROMELLM_TEST_JAVA_INCLUDE_PATH2=${JAVA_INCLUDE_PATH2}"
        "-DCHROMELLM_TEST_JAVA_AWT_INCLUDE_PATH=${JAVA_AWT_INCLUDE_PATH}"
        "-DCHROMELLM_TEST_JAVA_AWT_LIBRARY=${JAVA_AWT_LIBRARY}"
        "-DCHROMELLM_TEST_JAVA_JVM_LIBRARY=${JAVA_JVM_LIBRARY}"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/library_discovery/CMakeLists.txt")
  endif()
endif()


