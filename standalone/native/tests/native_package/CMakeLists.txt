# SPDX-License-Identifier: MIT
# Real shared libraries testing packaging/relocation only, never model inference.
cmake_minimum_required(VERSION 3.20)
foreach(required CHROMELLM_NATIVE_SOURCE_DIR CHROMELLM_TEST_BINARY_DIR CHROMELLM_TEST_GENERATOR)
  if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
    message(FATAL_ERROR "Missing ${required}; run this test through CTest")
  endif()
endforeach()
file(REMOVE_RECURSE "${CHROMELLM_TEST_BINARY_DIR}")
set(source "${CHROMELLM_TEST_BINARY_DIR}/fixture source")
set(build "${CHROMELLM_TEST_BINARY_DIR}/fixture build")
set(stage "${CHROMELLM_TEST_BINARY_DIR}/installed resources")
file(MAKE_DIRECTORY "${source}")
file(WRITE "${source}/export.h" [=[
#if defined(_WIN32)
#define EXPORTED extern "C" __declspec(dllexport)
#define IMPORTED extern "C" __declspec(dllimport)
#else
#define EXPORTED extern "C" __attribute__((visibility("default")))
#define IMPORTED extern "C"
#endif
]=])
file(WRITE "${source}/leaf.cpp" "#include \"export.h\"\nEXPORTED int package_leaf() { return 19; }\n")
file(WRITE "${source}/middle.cpp" "#include \"export.h\"\nIMPORTED int package_leaf();\nEXPORTED int package_middle() { return package_leaf() + 20; }\n")
file(WRITE "${source}/bridge.cpp" "#include \"export.h\"\nIMPORTED int package_middle();\nEXPORTED int package_answer() { return package_middle() + 3; }\n")
file(WRITE "${source}/probe.cpp" [=[
#include <cstdio>
#if defined(_WIN32)
#include <windows.h>
#else
#include <dlfcn.h>
#endif
int main(int argc, char** argv) {
#if defined(_WIN32)
  if (argc != 4) return 2;
  // Resolve only from the relocated DLL's own directory and Windows system
  // directories; never search the caller's CWD or PATH for injected DLLs.
  // Match the manifest's dependency-first loadOrder. Every DLL has already
  // passed native manifest and SHA-256 verification before this relocation probe.
  HMODULE library = nullptr;
  for (int index = 1; index != 4; ++index) {
    library = LoadLibraryExA(argv[index], nullptr,
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!library) {
      std::fprintf(stderr, "LoadLibraryExA failed for %s, Win32 error %lu\n",
                   argv[index], static_cast<unsigned long>(GetLastError()));
      return 3;
    }
  }
  auto answer = reinterpret_cast<int(*)()>(GetProcAddress(library, "package_answer"));
#else
  if (argc != 2) return 2;
  auto library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (!library) { std::fprintf(stderr, "%s\n", dlerror()); return 3; }
  auto answer = reinterpret_cast<int(*)()>(dlsym(library, "package_answer"));
#endif
  if (!answer || answer() != 42) return 4;
  std::puts("relocated three-library closure: 42");
  return 0;
}
]=])
file(WRITE "${source}/CMakeLists.txt" [=[
cmake_minimum_required(VERSION 3.20)
project(chromellm_package_contract LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CHROMELLM_SYNTHETIC_TEST ON)
set(CHROMELLM_INSTALL_NATIVE ON)
include("${CHROMELLM_PACKAGING_MODULE}")
chromellm_configure_native_packaging()
add_library(package_leaf SHARED leaf.cpp)
add_library(package_middle SHARED middle.cpp)
add_library(chromellm_llama_jni SHARED bridge.cpp)
if(UNIX)
  set_target_properties(package_leaf PROPERTIES VERSION 1.2 SOVERSION 1)
  set(leaf_name "$<TARGET_SONAME_FILE_NAME:package_leaf>")
else()
  set(leaf_name "$<TARGET_FILE_NAME:package_leaf>")
endif()
target_link_libraries(package_middle PRIVATE package_leaf)
target_link_libraries(chromellm_llama_jni PRIVATE package_middle)
foreach(target package_leaf package_middle chromellm_llama_jni)
  set_target_properties(${target} PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/${target}"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/${target}")
endforeach()
add_executable(package_probe probe.cpp)
target_link_libraries(package_probe PRIVATE ${CMAKE_DL_LIBS})
chromellm_install_native_runtime(chromellm_llama_jni)
file(GENERATE OUTPUT "${CMAKE_BINARY_DIR}/package-paths-$<CONFIG>.cmake" CONTENT
  "set(probe \"$<TARGET_FILE:package_probe>\")\nset(root_name \"$<TARGET_FILE_NAME:chromellm_llama_jni>\")\nset(leaf_name \"${leaf_name}\")\nset(middle_name \"$<TARGET_FILE_NAME:package_middle>\")\nset(classifier \"${CHROMELLM_NATIVE_CLASSIFIER}\")\n")
]=])
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
set(common ${toolchain} "-DCHROMELLM_PACKAGING_MODULE=${CHROMELLM_NATIVE_SOURCE_DIR}/cmake/NativePackaging.cmake")
file(WRITE "${source}/classifier-refusal.cmake" [=[
cmake_minimum_required(VERSION 3.20)
set(CMAKE_SIZEOF_VOID_P 8)
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR ARM64)
set(CHROMELLM_INSTALL_NATIVE ON)
include("${CHROMELLM_PACKAGING_MODULE}")
chromellm_configure_native_packaging()
]=])

function(run expected)
  execute_process(COMMAND ${ARGN} RESULT_VARIABLE status OUTPUT_VARIABLE output ERROR_VARIABLE error TIMEOUT 120)
  if(expected STREQUAL "PASS" AND NOT status EQUAL 0)
    message(FATAL_ERROR "Command failed (${status}): ${ARGN}\n${output}\n${error}")
  elseif(expected STREQUAL "REFUSE" AND status EQUAL 0)
    message(FATAL_ERROR "Command unexpectedly passed: ${ARGN}")
  endif()
endfunction()
run(REFUSE "${CMAKE_COMMAND}"
  "-DCHROMELLM_PACKAGING_MODULE=${CHROMELLM_NATIVE_SOURCE_DIR}/cmake/NativePackaging.cmake"
  -P "${source}/classifier-refusal.cmake")
run(REFUSE "${CMAKE_COMMAND}" -S "${source}" -B "${build}-unmarked" ${common})
run(REFUSE "${CMAKE_COMMAND}" -S "${source}" -B "${build}-mismatch" ${common}
  -DCHROMELLM_PACKAGE_TEST_FIXTURE=ON -DCHROMELLM_NATIVE_CLASSIFIER=wrong-architecture)
run(PASS "${CMAKE_COMMAND}" -S "${source}" -B "${build}" ${common} -DCHROMELLM_PACKAGE_TEST_FIXTURE=ON)
run(PASS "${CMAKE_COMMAND}" --build "${build}" --config Release --parallel 2)
run(PASS "${CMAKE_COMMAND}" --install "${build}" --config Release --prefix "${stage}" --component ChromeLLMNative)
include("${build}/package-paths-Release.cmake")
set(resource "${stage}/META-INF/native/${classifier}")
file(READ "${resource}/manifest.properties" manifest)
foreach(expected "format=1" "classifier=${classifier}" "library=${root_name}" "synthetic=true"
    "loadOrder=${leaf_name},${middle_name},${root_name}")
  string(FIND "${manifest}" "${expected}\n" found)
  if(found EQUAL -1)
    message(FATAL_ERROR "Native manifest is missing expected entry: ${expected}\n${manifest}")
  endif()
endforeach()
foreach(name "${leaf_name}" "${middle_name}" "${root_name}")
  file(SHA256 "${resource}/${name}" digest)
  string(FIND "${manifest}" "sha256.${name}=${digest}\n" found)
  if(found EQUAL -1 OR IS_SYMLINK "${resource}/${name}")
    message(FATAL_ERROR "Resource hash or regular-file contract failed for ${name}")
  endif()
endforeach()
file(WRITE "${resource}/stale-unlisted-library" "must disappear")
run(PASS "${CMAKE_COMMAND}" --install "${build}" --config Release --prefix "${stage}" --component ChromeLLMNative)
if(EXISTS "${resource}/stale-unlisted-library")
  message(FATAL_ERROR "A repeated native install retained an unlisted resource")
endif()
file(READ "${resource}/manifest.properties" repeated_manifest)
if(NOT manifest STREQUAL repeated_manifest)
  message(FATAL_ERROR "Repeated native install is not deterministic")
endif()
file(RENAME "${build}/package_leaf" "${build}/package_leaf-hidden")
run(REFUSE "${CMAKE_COMMAND}" --install "${build}" --config Release --prefix "${stage}" --component ChromeLLMNative)
file(READ "${resource}/manifest.properties" retained_manifest)
if(NOT manifest STREQUAL retained_manifest)
  message(FATAL_ERROR "Unresolved dependency damaged the previously installed package")
endif()
file(RENAME "${build}/package_leaf-hidden" "${build}/package_leaf")
foreach(directory package_leaf package_middle chromellm_llama_jni)
  file(RENAME "${build}/${directory}" "${build}/${directory}-hidden")
endforeach()
if(WIN32)
  run(PASS "${probe}" "${resource}/${leaf_name}"
    "${resource}/${middle_name}" "${resource}/${root_name}")
else()
  run(PASS "${probe}" "${resource}/${root_name}")
endif()
run(REFUSE "${CMAKE_COMMAND}" --install "${build}" --config Release --prefix "${stage}" --component ChromeLLMNative)
file(READ "${resource}/manifest.properties" retained_manifest)
if(NOT manifest STREQUAL retained_manifest)
  message(FATAL_ERROR "Failed native installation damaged the previously installed package")
endif()
include("${CHROMELLM_NATIVE_SOURCE_DIR}/tests/native_package/Notices.cmake")
message(STATUS "Passed transitive dependency packaging, relocation, exact manifest hashes, deterministic restaging, stale cleanup, classifier refusal, synthetic-package admission and notice collection; no model/GPU execution")
