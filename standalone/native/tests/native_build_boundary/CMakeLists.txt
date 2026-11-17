# SPDX-License-Identifier: MIT
# Configure/build boundary fixtures only; these do not implement native inference.
cmake_minimum_required(VERSION 3.22)
foreach(required CHROMELLM_NATIVE_SOURCE_DIR CHROMELLM_TEST_BINARY_DIR CHROMELLM_TEST_GENERATOR)
  if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
    message(FATAL_ERROR "Missing ${required}; run this test through CTest")
  endif()
endforeach()

file(REMOVE_RECURSE "${CHROMELLM_TEST_BINARY_DIR}")
file(MAKE_DIRECTORY "${CHROMELLM_TEST_BINARY_DIR}")
file(WRITE "${CHROMELLM_TEST_BINARY_DIR}/browser-toolchain.cmake" "set(EMSCRIPTEN TRUE)\n")
set(toolchain -G "${CHROMELLM_TEST_GENERATOR}" -DCMAKE_BUILD_TYPE=Release)
if(CHROMELLM_TEST_GENERATOR_PLATFORM)
  list(APPEND toolchain -A "${CHROMELLM_TEST_GENERATOR_PLATFORM}")
endif()
if(CHROMELLM_TEST_GENERATOR_TOOLSET)
  list(APPEND toolchain -T "${CHROMELLM_TEST_GENERATOR_TOOLSET}")
endif()
foreach(key CXX_COMPILER MAKE_PROGRAM)
  if(CHROMELLM_TEST_${key})
    list(APPEND toolchain "-DCMAKE_${key}:FILEPATH=${CHROMELLM_TEST_${key}}")
  endif()
endforeach()

function(run expected)
  execute_process(COMMAND ${ARGN} RESULT_VARIABLE status OUTPUT_VARIABLE output ERROR_VARIABLE error TIMEOUT 120)
  if(expected STREQUAL "PASS" AND NOT status EQUAL 0)
    message(FATAL_ERROR "Native configure/build failed (${status}): ${ARGN}\n${output}\n${error}")
  elseif(expected STREQUAL "BROWSER_REFUSAL")
    if(status EQUAL 0 OR NOT "${output}\n${error}" MATCHES "Emscripten/browser targets")
      message(FATAL_ERROR "Browser target did not fail at the native boundary: ${ARGN}\n${output}\n${error}")
    endif()
  endif()
endfunction()

# Explicit markers, target-system selections and a loaded browser toolchain
# must all fail before dependency discovery or source acquisition.
foreach(entry native superbuild)
  if(entry STREQUAL "native")
    set(source "${CHROMELLM_NATIVE_SOURCE_DIR}")
  else()
    set(source "${CHROMELLM_NATIVE_SOURCE_DIR}/../native-superbuild")
  endif()
  run(BROWSER_REFUSAL "${CMAKE_COMMAND}" -S "${source}"
    -B "${CHROMELLM_TEST_BINARY_DIR}/${entry}-emscripten" ${toolchain}
    -DEMSCRIPTEN=ON -DCMAKE_DISABLE_FIND_PACKAGE_JNI=TRUE)
  run(BROWSER_REFUSAL "${CMAKE_COMMAND}" -S "${source}"
    -B "${CHROMELLM_TEST_BINARY_DIR}/${entry}-browser-system" ${toolchain}
    -DCMAKE_SYSTEM_NAME=Emscripten -DCMAKE_CXX_COMPILER_WORKS=TRUE
    -DCMAKE_DISABLE_FIND_PACKAGE_JNI=TRUE)
  run(BROWSER_REFUSAL "${CMAKE_COMMAND}" -S "${source}"
    -B "${CHROMELLM_TEST_BINARY_DIR}/${entry}-browser-toolchain" ${toolchain}
    "-DCMAKE_TOOLCHAIN_FILE=${CHROMELLM_TEST_BINARY_DIR}/browser-toolchain.cmake"
    -DCMAKE_DISABLE_FIND_PACKAGE_JNI=TRUE)
endforeach()

# Execute the real superbuild's Dawn stage with operator-supplied, download-free
# fixture sources. The native fork no longer recognizes the Node build option.
set(fixture "${CHROMELLM_TEST_BINARY_DIR}/fixture sources")
file(MAKE_DIRECTORY "${fixture}/llama" "${fixture}/dawn")
file(WRITE "${fixture}/llama/CMakeLists.txt" "cmake_minimum_required(VERSION 3.22)\nproject(llama_fixture LANGUAGES NONE)\n")
file(WRITE "${fixture}/dawn/CMakeLists.txt" [=[
cmake_minimum_required(VERSION 3.22)
project(dawn_native_fixture LANGUAGES NONE)
if(DEFINED DAWN_BUILD_NODE_BINDINGS)
  message(FATAL_ERROR "The removed Node build option reached the native Dawn checkout")
endif()
if(DAWN_FETCH_DEPENDENCIES OR DAWN_BUILD_TESTS OR DAWN_BUILD_SAMPLES OR DAWN_BUILD_PROTOBUF
    OR DAWN_USE_GLFW OR DAWN_USE_X11 OR DAWN_USE_WAYLAND OR TINT_BUILD_TESTS OR TINT_BUILD_CMD_TOOLS)
  message(FATAL_ERROR "Native compute staging attempted optional tools or downloads")
endif()
if(NOT DAWN_ENABLE_INSTALL OR NOT DAWN_BUILD_MONOLITHIC_LIBRARY STREQUAL "STATIC")
  message(FATAL_ERROR "Native compute installation was not selected")
endif()
file(WRITE "${CMAKE_BINARY_DIR}/native-fixture.txt" "native compute stage; no inference\n")
install(FILES "${CMAKE_BINARY_DIR}/native-fixture.txt" DESTINATION share)
]=])
set(build "${CHROMELLM_TEST_BINARY_DIR}/native superbuild")
run(PASS "${CMAKE_COMMAND}" -S "${CHROMELLM_NATIVE_SOURCE_DIR}/../native-superbuild"
  -B "${build}" ${toolchain} -DCHROMELLM_COMPUTE_BACKEND=webgpu
  -DCHROMELLM_FETCH_SOURCES=OFF "-DCHROMELLM_LLAMA_SOURCE_DIR=${fixture}/llama"
  "-DCHROMELLM_DAWN_SOURCE_DIR=${fixture}/dawn")
run(PASS "${CMAKE_COMMAND}" --build "${build}" --target chromellm-dawn --config Release)
if(NOT EXISTS "${build}/dawn-install/share/native-fixture.txt" OR EXISTS "${build}/sources")
  message(FATAL_ERROR "Native Dawn staging did not preserve the download-free source contract")
endif()
message(STATUS "Native build boundary: six browser refusals and native Dawn staging passed")
