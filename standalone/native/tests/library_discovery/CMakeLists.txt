# SPDX-License-Identifier: MIT
# Configure-only external-library fixtures; never link or execute these placeholder libraries.
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
file(MAKE_DIRECTORY "${prefix}/include" "${prefix}/lib" "${overrides}" "${fallback}" "${orphan}")

if(WIN32)
  set(library_prefix "${CHROMELLM_TEST_IMPORT_PREFIX}")
  set(library_suffix "${CHROMELLM_TEST_IMPORT_SUFFIX}")
else()
  set(library_prefix "${CHROMELLM_TEST_SHARED_PREFIX}")
  set(library_suffix "${CHROMELLM_TEST_SHARED_SUFFIX}")
endif()
if(library_suffix STREQUAL "")
  message(FATAL_ERROR "Missing platform library suffix")
endif()
foreach(name llama ggml ggml-base)
  file(WRITE "${prefix}/lib/${library_prefix}${name}${library_suffix}" "configure-only synthetic library fixture\n")
endforeach()
foreach(name ggml ggml-base)
  file(WRITE "${overrides}/${library_prefix}${name}${library_suffix}" "explicit configure-only fixture\n")
  file(WRITE "${fallback}/${library_prefix}${name}${library_suffix}" "fallback configure-only fixture\n")
endforeach()
file(WRITE "${orphan}/${library_prefix}llama${library_suffix}" "configure-only llama fixture\n")
foreach(header llama.h ggml.h ggml-cpu.h ggml-backend.h ggml-alloc.h ggml-opt.h)
  file(WRITE "${prefix}/include/${header}" "/* configure-only header fixture */\n")
endforeach()

set(common_args -G "${CHROMELLM_TEST_GENERATOR}" -DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=Release)
if(NOT "${CHROMELLM_TEST_GENERATOR_PLATFORM}" STREQUAL "")
  list(APPEND common_args -A "${CHROMELLM_TEST_GENERATOR_PLATFORM}")
endif()
if(NOT "${CHROMELLM_TEST_GENERATOR_TOOLSET}" STREQUAL "")
  list(APPEND common_args -T "${CHROMELLM_TEST_GENERATOR_TOOLSET}")
endif()
foreach(pair CXX_COMPILER MAKE_PROGRAM)
  if(CHROMELLM_TEST_${pair})
    list(APPEND common_args "-DCMAKE_${pair}:FILEPATH=${CHROMELLM_TEST_${pair}}")
  endif()
endforeach()
# Reuse the parent build's resolved JNI locations, including explicitly supplied JDK headers.
foreach(name JAVA_INCLUDE_PATH JAVA_INCLUDE_PATH2 JAVA_AWT_INCLUDE_PATH JAVA_AWT_LIBRARY JAVA_JVM_LIBRARY)
  if(CHROMELLM_TEST_${name})
    list(APPEND common_args "-D${name}:PATH=${CHROMELLM_TEST_${name}}")
  endif()
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
set(llama "${prefix}/lib/${library_prefix}llama${library_suffix}")
configure_case(sibling ${external_args} "-DLLAMA_LIBRARY:FILEPATH=${llama}"
  "-DCMAKE_LIBRARY_PATH:PATH=${fallback}")
require_cache(sibling GGML_LIBRARY "${prefix}/lib/${library_prefix}ggml${library_suffix}")
require_cache(sibling GGML_BASE_LIBRARY "${prefix}/lib/${library_prefix}ggml-base${library_suffix}")

configure_case(override ${external_args} "-DLLAMA_LIBRARY:FILEPATH=${llama}"
  "-DGGML_LIBRARY:FILEPATH=${overrides}/${library_prefix}ggml${library_suffix}"
  "-DGGML_BASE_LIBRARY:FILEPATH=${overrides}/${library_prefix}ggml-base${library_suffix}")
require_cache(override GGML_LIBRARY "${overrides}/${library_prefix}ggml${library_suffix}")
require_cache(override GGML_BASE_LIBRARY "${overrides}/${library_prefix}ggml-base${library_suffix}")

configure_case(fallback ${external_args}
  "-DLLAMA_LIBRARY:FILEPATH=${orphan}/${library_prefix}llama${library_suffix}"
  "-DCMAKE_LIBRARY_PATH:PATH=${fallback}")
require_cache(fallback GGML_LIBRARY "${fallback}/${library_prefix}ggml${library_suffix}")
require_cache(fallback GGML_BASE_LIBRARY "${fallback}/${library_prefix}ggml-base${library_suffix}")

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
message(STATUS "Passed sibling discovery, explicit overrides, ordinary lookup fallback and isolated synthetic build; no real model or GPU execution")
