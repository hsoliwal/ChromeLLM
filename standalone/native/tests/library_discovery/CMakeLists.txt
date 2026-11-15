# SPDX-License-Identifier: MIT
# Synthetic link-probe archives only; never use them for inference or distribution.
cmake_minimum_required(VERSION 3.20)

foreach(required CHROMELLM_NATIVE_SOURCE_DIR CHROMELLM_TEST_BINARY_DIR CHROMELLM_TEST_GENERATOR)
  if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
    message(FATAL_ERROR "Missing ${required}; run this test through CTest")
  endif()
endforeach()

file(REMOVE_RECURSE "${CHROMELLM_TEST_BINARY_DIR}")
file(MAKE_DIRECTORY "${CHROMELLM_TEST_BINARY_DIR}")
set(prefix "${CHROMELLM_TEST_BINARY_DIR}/isolated runtime prefix")
set(overrides "${CHROMELLM_TEST_BINARY_DIR}/explicit overrides")
set(fallback "${CHROMELLM_TEST_BINARY_DIR}/ordinary library path")
set(orphan "${CHROMELLM_TEST_BINARY_DIR}/llama without siblings")
set(monolithic "${CHROMELLM_TEST_BINARY_DIR}/monolithic runtime")
set(probe_source "${CHROMELLM_TEST_BINARY_DIR}/probe-source")
set(probe_build "${CHROMELLM_TEST_BINARY_DIR}/probe-build")
file(MAKE_DIRECTORY "${prefix}/include" "${prefix}/lib" "${overrides}" "${fallback}"
  "${orphan}" "${monolithic}" "${probe_source}")

# Reuse the repository's explicitly synthetic API declarations. Only the symbols
# exercised by CMake's compile/link probe are implemented in the archives below.
file(COPY "${CHROMELLM_NATIVE_SOURCE_DIR}/tests/llama.h"
  "${CHROMELLM_NATIVE_SOURCE_DIR}/tests/ggml-backend.h" DESTINATION "${prefix}/include")
foreach(header ggml.h ggml-cpu.h ggml-alloc.h ggml-opt.h)
  file(WRITE "${prefix}/include/${header}" "/* Synthetic link-probe header only. */\n")
endforeach()
file(WRITE "${probe_source}/llama-probe.cpp" [=[
#include "llama.h"
extern "C" llama_model_params llama_model_default_params(void) {
  return {nullptr, 0, LLAMA_SPLIT_MODE_NONE, 0};
}
]=])
file(WRITE "${probe_source}/ggml-probe.cpp" [=[
#include "ggml-backend.h"
extern "C" {
ggml_backend_reg_t ggml_backend_reg_by_name(const char*) { return nullptr; }
size_t ggml_backend_dev_count(void) { return 0; }
ggml_backend_dev_t ggml_backend_dev_get(size_t) { return nullptr; }
}
]=])
file(WRITE "${probe_source}/ggml-base-probe.cpp" [=[
#include "ggml-backend.h"
extern "C" {
const char* ggml_backend_reg_name(ggml_backend_reg_t) { return "synthetic"; }
const char* ggml_backend_dev_name(ggml_backend_dev_t) { return "synthetic"; }
const char* ggml_backend_dev_description(ggml_backend_dev_t) { return "link probe only"; }
enum ggml_backend_dev_type ggml_backend_dev_type(ggml_backend_dev_t) {
  return GGML_BACKEND_DEVICE_TYPE_CPU;
}
ggml_backend_reg_t ggml_backend_dev_backend_reg(ggml_backend_dev_t) { return nullptr; }
}
]=])
file(WRITE "${probe_source}/CMakeLists.txt" [=[
cmake_minimum_required(VERSION 3.20)
project(chromellm_library_probe LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_library(probe_llama STATIC llama-probe.cpp)
add_library(probe_ggml STATIC ggml-probe.cpp)
add_library(probe_base STATIC ggml-base-probe.cpp)
add_library(probe_monolithic STATIC llama-probe.cpp ggml-probe.cpp ggml-base-probe.cpp)
set_target_properties(probe_llama PROPERTIES OUTPUT_NAME llama)
set_target_properties(probe_ggml PROPERTIES OUTPUT_NAME ggml)
set_target_properties(probe_base PROPERTIES OUTPUT_NAME ggml-base)
set_target_properties(probe_monolithic PROPERTIES OUTPUT_NAME llama-monolithic)
foreach(target probe_llama probe_ggml probe_base probe_monolithic)
  target_include_directories(${target} PRIVATE "${PROBE_INCLUDE_DIR}")
endforeach()
file(GENERATE OUTPUT "${CMAKE_BINARY_DIR}/probe-paths-$<CONFIG>.cmake" CONTENT
  "set(probe_llama \"$<TARGET_FILE:probe_llama>\")\nset(probe_ggml \"$<TARGET_FILE:probe_ggml>\")\nset(probe_base \"$<TARGET_FILE:probe_base>\")\nset(probe_monolithic \"$<TARGET_FILE:probe_monolithic>\")\n")
]=])

set(toolchain_args -G "${CHROMELLM_TEST_GENERATOR}" -DCMAKE_BUILD_TYPE=Release)
if(NOT "${CHROMELLM_TEST_GENERATOR_PLATFORM}" STREQUAL "")
  list(APPEND toolchain_args -A "${CHROMELLM_TEST_GENERATOR_PLATFORM}")
endif()
if(NOT "${CHROMELLM_TEST_GENERATOR_TOOLSET}" STREQUAL "")
  list(APPEND toolchain_args -T "${CHROMELLM_TEST_GENERATOR_TOOLSET}")
endif()
foreach(pair CXX_COMPILER MAKE_PROGRAM)
  if(CHROMELLM_TEST_${pair})
    list(APPEND toolchain_args "-DCMAKE_${pair}:FILEPATH=${CHROMELLM_TEST_${pair}}")
  endif()
endforeach()
set(common_args ${toolchain_args} -DBUILD_TESTING=OFF)
# Reuse the parent build's resolved JNI locations, including explicitly supplied JDK headers.
foreach(name JAVA_INCLUDE_PATH JAVA_INCLUDE_PATH2 JAVA_AWT_INCLUDE_PATH JAVA_AWT_LIBRARY JAVA_JVM_LIBRARY)
  if(CHROMELLM_TEST_${name})
    list(APPEND common_args "-D${name}:PATH=${CHROMELLM_TEST_${name}}")
  endif()
endforeach()

execute_process(
  COMMAND "${CMAKE_COMMAND}" -S "${probe_source}" -B "${probe_build}"
    ${toolchain_args} "-DPROBE_INCLUDE_DIR:PATH=${prefix}/include"
  RESULT_VARIABLE result OUTPUT_VARIABLE output ERROR_VARIABLE error TIMEOUT 60)
if(NOT result EQUAL 0)
  message(FATAL_ERROR "Synthetic link-probe configure failed (${result}):\n${output}\n${error}")
endif()
execute_process(
  COMMAND "${CMAKE_COMMAND}" --build "${probe_build}" --config Release --parallel 2
  RESULT_VARIABLE result OUTPUT_VARIABLE output ERROR_VARIABLE error TIMEOUT 60)
if(NOT result EQUAL 0)
  message(FATAL_ERROR "Synthetic link-probe build failed (${result}):\n${output}\n${error}")
endif()
include("${probe_build}/probe-paths-Release.cmake")
get_filename_component(llama_name "${probe_llama}" NAME)
get_filename_component(ggml_name "${probe_ggml}" NAME)
get_filename_component(base_name "${probe_base}" NAME)
configure_file("${probe_llama}" "${prefix}/lib/${llama_name}" COPYONLY)
configure_file("${probe_llama}" "${orphan}/${llama_name}" COPYONLY)
configure_file("${probe_monolithic}" "${monolithic}/${llama_name}" COPYONLY)
foreach(directory "${prefix}/lib" "${overrides}" "${fallback}")
  configure_file("${probe_ggml}" "${directory}/${ggml_name}" COPYONLY)
  configure_file("${probe_base}" "${directory}/${base_name}" COPYONLY)
endforeach()

function(configure_case name)
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -S "${CHROMELLM_NATIVE_SOURCE_DIR}"
      -B "${CHROMELLM_TEST_BINARY_DIR}/${name}" ${common_args} ${ARGN}
    RESULT_VARIABLE result OUTPUT_VARIABLE output ERROR_VARIABLE error TIMEOUT 60)
  if(NOT result EQUAL 0)
    message(FATAL_ERROR "${name} configure failed (${result}):\n${output}\n${error}")
  endif()
endfunction()

function(require_cache name key expected)
  file(STRINGS "${CHROMELLM_TEST_BINARY_DIR}/${name}/CMakeCache.txt" entries REGEX "^${key}:[^=]*=")
  list(LENGTH entries count)
  if(NOT count EQUAL 1)
    message(FATAL_ERROR "${name}: expected one ${key} cache entry")
  endif()
  string(REGEX REPLACE "^[^=]*=" "" actual "${entries}")
  if(NOT "${actual}" STREQUAL "${expected}")
    message(FATAL_ERROR "${name}: ${key} selected '${actual}', expected '${expected}'")
  endif()
endfunction()

set(external_args -DCHROMELLM_SYNTHETIC_TEST=OFF
  "-DLLAMA_INCLUDE_DIR:PATH=${prefix}/include" "-DGGML_INCLUDE_DIR:PATH=${prefix}/include")
set(llama "${prefix}/lib/${llama_name}")
configure_case(sibling ${external_args} "-DLLAMA_LIBRARY:FILEPATH=${llama}"
  "-DCMAKE_LIBRARY_PATH:PATH=${fallback}")
require_cache(sibling GGML_LIBRARY "${prefix}/lib/${ggml_name}")
require_cache(sibling GGML_BASE_LIBRARY "${prefix}/lib/${base_name}")
require_cache(sibling CHROMELLM_SPLIT_GGML_LINKS "1")

configure_case(override ${external_args} "-DLLAMA_LIBRARY:FILEPATH=${llama}"
  "-DGGML_LIBRARY:FILEPATH=${overrides}/${ggml_name}"
  "-DGGML_BASE_LIBRARY:FILEPATH=${overrides}/${base_name}")
require_cache(override GGML_LIBRARY "${overrides}/${ggml_name}")
require_cache(override GGML_BASE_LIBRARY "${overrides}/${base_name}")
require_cache(override CHROMELLM_SPLIT_GGML_LINKS "1")

configure_case(fallback ${external_args}
  "-DLLAMA_LIBRARY:FILEPATH=${orphan}/${llama_name}"
  "-DCMAKE_LIBRARY_PATH:PATH=${fallback}")
require_cache(fallback GGML_LIBRARY "${fallback}/${ggml_name}")
require_cache(fallback GGML_BASE_LIBRARY "${fallback}/${base_name}")
require_cache(fallback CHROMELLM_SPLIT_GGML_LINKS "1")

# A compatible monolithic libllama must not gain a dependency on split GGML files.
configure_case(monolithic ${external_args}
  "-DLLAMA_LIBRARY:FILEPATH=${monolithic}/${llama_name}")
require_cache(monolithic CHROMELLM_LLAMA_EXPORTS_GGML "1")
file(STRINGS "${CHROMELLM_TEST_BINARY_DIR}/monolithic/CMakeCache.txt" split_entries
  REGEX "^(GGML_LIBRARY|GGML_BASE_LIBRARY):")
if(split_entries)
  message(FATAL_ERROR "Monolithic mode unexpectedly requires split GGML libraries")
endif()

# This case builds the existing, clearly synthetic llama fixture with no external library options.
configure_case(synthetic -DCHROMELLM_SYNTHETIC_TEST=ON)
file(STRINGS "${CHROMELLM_TEST_BINARY_DIR}/synthetic/CMakeCache.txt" external_entries
  REGEX "^(LLAMA_LIBRARY|GGML_LIBRARY|GGML_BASE_LIBRARY):")
if(external_entries)
  message(FATAL_ERROR "Synthetic mode unexpectedly depends on external libraries")
endif()
execute_process(
  COMMAND "${CMAKE_COMMAND}" --build "${CHROMELLM_TEST_BINARY_DIR}/synthetic"
    --target chromellm_llama_jni --config Release --parallel 2
  RESULT_VARIABLE result OUTPUT_VARIABLE output ERROR_VARIABLE error TIMEOUT 60)
if(NOT result EQUAL 0)
  message(FATAL_ERROR "Isolated synthetic build failed (${result}):\n${output}\n${error}")
endif()
message(STATUS "Passed sibling discovery, explicit overrides, ordinary lookup fallback, monolithic compatibility and isolated synthetic build; no real model or GPU execution")
