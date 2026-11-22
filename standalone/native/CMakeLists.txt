cmake_minimum_required(VERSION 3.20)
# Reject explicit browser targets before generator/compiler setup, then check
# again after project() has loaded a caller-supplied toolchain.
if(EMSCRIPTEN OR CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  message(FATAL_ERROR "ChromeLLM builds native JNI runtimes; Emscripten/browser targets are unsupported")
endif()
project(chromellm_llama_jni LANGUAGES CXX)
if(EMSCRIPTEN OR CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  message(FATAL_ERROR "ChromeLLM builds native JNI runtimes; Emscripten/browser targets are unsupported")
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# CMake's FindJNI may parse Windows JAVA_HOME backslashes as escape sequences
# (e.g. \h). Normalize the supplied hint before JNI discovery; do not bypass
# the required JVM component, or change the caller's actual source pin.
if(WIN32)
  if(DEFINED JAVA_HOME AND NOT JAVA_HOME STREQUAL "")
    string(REPLACE "\\" "/" JAVA_HOME "${JAVA_HOME}")
  endif()
  if(DEFINED ENV{JAVA_HOME} AND NOT "$ENV{JAVA_HOME}" STREQUAL "")
    string(REPLACE "\\" "/" _chromellm_jdk_home "$ENV{JAVA_HOME}")
    set(ENV{JAVA_HOME} "${_chromellm_jdk_home}")
  endif()
endif()

# The bridge uses JNI headers and the running JVM, never AWT. Older CMake
# versions do not expose FindJNI components and retain full-JDK discovery.
if(CMAKE_VERSION VERSION_LESS "3.24")
  find_package(JNI REQUIRED)
else()
  find_package(JNI REQUIRED COMPONENTS JVM)
endif()
find_package(Threads REQUIRED)
option(CHROMELLM_SYNTHETIC_TEST "Build test-only fake llama API (never for distribution)" OFF)
set(CHROMELLM_LLAMA_SOURCE_DIR "" CACHE PATH "Operator-supplied llama.cpp checkout to build with the JNI bridge")
option(CHROMELLM_WEBGPU "Build the supplied llama.cpp checkout with its native Dawn/WebGPU provider" OFF)
option(CHROMELLM_DISTRIBUTABLE_CPU "Link a standalone CPU JNI artifact for verified lazy acquisition" OFF)
set(CHROMELLM_LLAMA_COMMIT "" CACHE STRING "Exact llama.cpp source commit required for a distributable CPU artifact")
if(CHROMELLM_DISTRIBUTABLE_CPU)
  if(CHROMELLM_SYNTHETIC_TEST OR CHROMELLM_WEBGPU OR NOT CHROMELLM_LLAMA_SOURCE_DIR)
    message(FATAL_ERROR "Distributable CPU JNI requires real source and excludes synthetic or WebGPU providers")
  endif()
  string(LENGTH "${CHROMELLM_LLAMA_COMMIT}" CHROMELLM_LLAMA_COMMIT_LENGTH)
  if(NOT CHROMELLM_LLAMA_COMMIT_LENGTH EQUAL 40 OR NOT CHROMELLM_LLAMA_COMMIT MATCHES "^[0-9a-fA-F]+$")
    message(FATAL_ERROR "CHROMELLM_LLAMA_COMMIT must pin a full 40-hex source commit")
  endif()
  find_package(Git REQUIRED)
  execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${CHROMELLM_LLAMA_SOURCE_DIR}" rev-parse --verify HEAD
    RESULT_VARIABLE CHROMELLM_GIT_RESULT OUTPUT_VARIABLE CHROMELLM_ACTUAL_LLAMA_COMMIT
    OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET TIMEOUT 30)
  string(TOLOWER "${CHROMELLM_LLAMA_COMMIT}" CHROMELLM_LLAMA_COMMIT)
  if(NOT CHROMELLM_GIT_RESULT EQUAL 0 OR NOT CHROMELLM_ACTUAL_LLAMA_COMMIT STREQUAL CHROMELLM_LLAMA_COMMIT)
    message(FATAL_ERROR "Distributable JNI source does not match its exact commit pin")
  endif()
  execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${CHROMELLM_LLAMA_SOURCE_DIR}" status --porcelain --untracked-files=all
    RESULT_VARIABLE CHROMELLM_GIT_RESULT OUTPUT_VARIABLE CHROMELLM_LLAMA_CHANGES
    OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET TIMEOUT 30)
  if(NOT CHROMELLM_GIT_RESULT EQUAL 0 OR NOT CHROMELLM_LLAMA_CHANGES STREQUAL "")
    message(FATAL_ERROR "Distributable JNI requires an unmodified pinned source checkout")
  endif()
  # Keep llama/GGML in one JNI library. GGML_STATIC adds -static to shared links,
  # so static component libraries use BUILD_SHARED_LIBS=OFF instead.
  set(BUILD_SHARED_LIBS OFF CACHE BOOL "Link llama/GGML into the JNI library" FORCE)
  set(LLAMA_USE_SYSTEM_GGML OFF CACHE BOOL "Use the pinned GGML sources" FORCE)
  set(GGML_CPU ON CACHE BOOL "Required CPU backend" FORCE)
  foreach(FEATURE STATIC NATIVE BACKEND_DL CPU_ALL_VARIANTS OPENMP BLAS ACCELERATE
      CUDA HIP MUSA VULKAN WEBGPU METAL RPC SYCL CANN ZDNN OPENCL HEXAGON ZENDNN
      CPU_HBM CPU_KLEIDIAI SSE42 AVX AVX2 BMI2 AVX_VNNI FMA F16C
      AVX512 AVX512_VBMI AVX512_VNNI AVX512_BF16 AMX_TILE AMX_INT8 AMX_BF16)
    set(GGML_${FEATURE} OFF CACHE BOOL "Baseline CPU distribution" FORCE)
  endforeach()
  if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$")
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "Portable ARM baseline" FORCE)
  endif()
  # All static objects and the bridge use the same CRT on Windows.
  if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
  endif()
endif()

option(CHROMELLM_NATIVE_DISTRIBUTION "Stage and attach a real source-built runtime" OFF)
set(CHROMELLM_NATIVE_CLASSIFIER "" CACHE STRING "Explicit host os-architecture classifier")
if(CHROMELLM_NATIVE_DISTRIBUTION)
  if(CMAKE_CROSSCOMPILING OR NOT CMAKE_SYSTEM_NAME STREQUAL CMAKE_HOST_SYSTEM_NAME
      OR CMAKE_CXX_COMPILER_TARGET)
    message(FATAL_ERROR "native distribution requires an unmodified host build; cross targets are unsupported")
  endif()
  if(CHROMELLM_SYNTHETIC_TEST OR NOT CHROMELLM_LLAMA_SOURCE_DIR)
    message(FATAL_ERROR "native distribution requires real source; synthetic/prebuilt mode is not distributable")
  endif()
  string(TOLOWER "${CMAKE_HOST_SYSTEM_NAME}" native_os)
  if(native_os STREQUAL "darwin")
    set(native_os macos)
  endif()
  string(TOLOWER "${CMAKE_HOST_SYSTEM_PROCESSOR}" native_arch)
  if(native_arch MATCHES "^(amd64|x86_64)$")
    set(native_arch x86_64)
  elseif(native_arch MATCHES "^(aarch64|arm64)$")
    set(native_arch arm64)
  else()
    message(FATAL_ERROR "Unsupported native classifier architecture: ${native_arch}")
  endif()
  if(NOT native_os MATCHES "^(linux|macos|windows)$" OR
      NOT CHROMELLM_NATIVE_CLASSIFIER STREQUAL "${native_os}-${native_arch}")
    message(FATAL_ERROR "native classifier must explicitly match host ${native_os}-${native_arch}")
  endif()
  if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "native distribution requires a 64-bit host build")
  endif()
  if(CMAKE_GENERATOR_PLATFORM)
    string(TOLOWER "${CMAKE_GENERATOR_PLATFORM}" generator_arch)
    string(REGEX REPLACE ",.*" "" generator_arch "${generator_arch}")
    if(generator_arch STREQUAL "x64")
      set(generator_arch x86_64)
    endif()
    if(NOT generator_arch STREQUAL native_arch)
      message(FATAL_ERROR "native distribution requires a matching generator host build architecture")
    endif()
  endif()
  if(MSVC AND CMAKE_CXX_COMPILER_ARCHITECTURE_ID)
    string(TOLOWER "${CMAKE_CXX_COMPILER_ARCHITECTURE_ID}" compiler_arch)
    if(compiler_arch STREQUAL "x64")
      set(compiler_arch x86_64)
    endif()
    if(NOT compiler_arch STREQUAL native_arch)
      message(FATAL_ERROR "native distribution requires matching compiler host build architecture")
    endif()
  endif()
  if(CMAKE_OSX_ARCHITECTURES AND NOT CMAKE_OSX_ARCHITECTURES STREQUAL native_arch)
    message(FATAL_ERROR "native distribution requires one matching macOS host build architecture")
  endif()
  set(CHROMELLM_NATIVE_NOTICES "" CACHE PATH "Operator-reviewed complete native dependency notices directory")
  if(NOT IS_DIRECTORY "${CHROMELLM_NATIVE_NOTICES}")
    message(FATAL_ERROR "native distribution requires operator-reviewed notices directory CHROMELLM_NATIVE_NOTICES")
  endif()
  file(GLOB_RECURSE notice_files LIST_DIRECTORIES false "${CHROMELLM_NATIVE_NOTICES}/*")
  if(NOT notice_files)
    message(FATAL_ERROR "native distribution requires nonempty operator-reviewed notices")
  endif()
  # Distribution cannot silently require an unbundled or unlicensed compiler OpenMP runtime.
  # Ordinary native builds retain the donor's normal OpenMP selection.
  set(GGML_OPENMP OFF CACHE BOOL "Distribution uses the source CPU implementation without external OpenMP" FORCE)
  include(${CMAKE_CURRENT_SOURCE_DIR}/NativeDistribution.cmake)
  chromellm_relocatable_libraries()
endif()

include(cmake/NativePackaging.cmake)
chromellm_configure_native_packaging()

if(CHROMELLM_SYNTHETIC_TEST)
  if(CHROMELLM_LLAMA_SOURCE_DIR OR CHROMELLM_WEBGPU)
    message(FATAL_ERROR "The synthetic fixture cannot build a real llama.cpp or WebGPU provider")
  endif()
  add_library(chromellm_llama_stub SHARED tests/llama_stub.cpp)
  set_target_properties(chromellm_llama_stub PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
  target_include_directories(chromellm_llama_stub PRIVATE tests)
  target_link_libraries(chromellm_llama_stub PRIVATE Threads::Threads)
  if(MSVC)
    target_compile_options(chromellm_llama_stub PRIVATE /utf-8)
  endif()
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
  set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "Build llama.cpp tools" FORCE)
  set(LLAMA_CURL OFF CACHE BOOL "Enable llama.cpp downloads" FORCE)
  set(LLAMA_HTTPLIB OFF CACHE BOOL "Enable llama.cpp HTTP downloads" FORCE)
  set(LLAMA_OPENSSL OFF CACHE BOOL "Enable llama.cpp HTTPS downloads" FORCE)
  # Maven's opt-in native-dawn profile stages a pinned release outside donor source.
  # A blank prefix preserves existing operator-managed Dawn discovery.
  if(CHROMELLM_WEBGPU AND CHROMELLM_DAWN_PREFIX AND NOT CHROMELLM_DAWN_PREFIX STREQUAL "NONE")
    if(NOT EXISTS "${CHROMELLM_DAWN_PREFIX}/lib64/cmake/Dawn/DawnConfig.cmake"
        AND NOT EXISTS "${CHROMELLM_DAWN_PREFIX}/lib/cmake/Dawn/DawnConfig.cmake")
      message(FATAL_ERROR "CHROMELLM_DAWN_PREFIX lacks a DawnConfig.cmake: ${CHROMELLM_DAWN_PREFIX}")
    endif()
    # Resolve lib64 explicitly on hosts whose package search otherwise prefers lib/.
    if(NOT Dawn_DIR)
      if(EXISTS "${CHROMELLM_DAWN_PREFIX}/lib64/cmake/Dawn/DawnConfig.cmake")
        set(Dawn_DIR "${CHROMELLM_DAWN_PREFIX}/lib64/cmake/Dawn")
      else()
        set(Dawn_DIR "${CHROMELLM_DAWN_PREFIX}/lib/cmake/Dawn")
      endif()
    endif()
    list(PREPEND CMAKE_PREFIX_PATH "${CHROMELLM_DAWN_PREFIX}")
  endif()
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
    # Prefer matching sibling libraries before ordinary discovery. Explicit cache
    # overrides still take precedence, and installations with separate paths still work.
    get_filename_component(LLAMA_LIBRARY_DIR "${LLAMA_LIBRARY}" DIRECTORY)
    find_library(GGML_LIBRARY NAMES ggml HINTS "${LLAMA_LIBRARY_DIR}" NO_DEFAULT_PATH)
    find_library(GGML_BASE_LIBRARY NAMES ggml-base HINTS "${LLAMA_LIBRARY_DIR}" NO_DEFAULT_PATH)
    find_library(GGML_LIBRARY NAMES ggml REQUIRED)
    find_library(GGML_BASE_LIBRARY NAMES ggml-base REQUIRED)
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
target_link_libraries(chromellm_llama_jni PRIVATE ${LLAMA_LIBRARY} ${CHROMELLM_GGML_LIBRARIES} Threads::Threads)
# Maven consumes the actual target path on both single- and multi-configuration generators.
file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/chromellm-native-$<CONFIG>.txt"
  CONTENT "$<TARGET_FILE:chromellm_llama_jni>\n")
if(MSVC)
  target_compile_options(chromellm_llama_jni PRIVATE /utf-8)
endif()
chromellm_install_native_runtime(chromellm_llama_jni)

if(CHROMELLM_DISTRIBUTABLE_CPU)
  # Retain only OS libraries as dynamic dependencies. No llama/GGML sibling
  # library, backend search directory or download is required at System.load.
  if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
    target_link_options(chromellm_llama_jni PRIVATE -static-libgcc -static-libstdc++ "-Wl,--exclude-libs,ALL")
  endif()
  add_custom_target(chromellm_runtime_package
    COMMAND "${CMAKE_COMMAND}"
      "-DRUNTIME_LIBRARY=$<TARGET_FILE:chromellm_llama_jni>"
      "-DRUNTIME_DESTINATION=${CMAKE_CURRENT_BINARY_DIR}/runtime-package"
      "-DLLAMA_SOURCE=${CHROMELLM_LLAMA_SOURCE_DIR}"
      "-DLLAMA_COMMIT=${CHROMELLM_LLAMA_COMMIT}"
      "-DBRIDGE_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/llama_jni.cpp"
      "-DNATIVE_BUILD_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/CMakeLists.txt"
      "-DCHROMELLM_LICENSE=${CMAKE_CURRENT_SOURCE_DIR}/../../LICENSE"
      "-DTARGET_SYSTEM=${CMAKE_SYSTEM_NAME}"
      "-DTARGET_PROCESSOR=${CMAKE_SYSTEM_PROCESSOR}"
      "-DCXX_COMPILER_ID=${CMAKE_CXX_COMPILER_ID}"
      "-DCXX_COMPILER_VERSION=${CMAKE_CXX_COMPILER_VERSION}"
      "-DNATIVE_LINKER=${CMAKE_LINKER}"
      -P "${CMAKE_CURRENT_SOURCE_DIR}/package_runtime.cmake"
    DEPENDS chromellm_llama_jni
    VERBATIM)
endif()

if(CHROMELLM_SYNTHETIC_TEST)
  include(CTest)
  if(BUILD_TESTING)
    # The independent native entry remains usable with CMake 3.20; the
    # superbuild exercised by this additional regression requires 3.22.
    if(CMAKE_VERSION VERSION_GREATER_EQUAL "3.22")
      add_test(NAME native-build-boundary
        COMMAND ${CMAKE_COMMAND}
          "-DCHROMELLM_NATIVE_SOURCE_DIR=${CMAKE_CURRENT_SOURCE_DIR}"
          "-DCHROMELLM_TEST_BINARY_DIR=${CMAKE_CURRENT_BINARY_DIR}/native-build-boundary-test"
          "-DCHROMELLM_TEST_GENERATOR=${CMAKE_GENERATOR}"
          "-DCHROMELLM_TEST_GENERATOR_PLATFORM=${CMAKE_GENERATOR_PLATFORM}"
          "-DCHROMELLM_TEST_GENERATOR_TOOLSET=${CMAKE_GENERATOR_TOOLSET}"
          "-DCHROMELLM_TEST_CXX_COMPILER=${CMAKE_CXX_COMPILER}"
          "-DCHROMELLM_TEST_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_build_boundary/CMakeLists.txt")
    endif()
    add_test(NAME external-library-discovery
      COMMAND ${CMAKE_COMMAND}
        "-DCHROMELLM_NATIVE_SOURCE_DIR=${CMAKE_CURRENT_SOURCE_DIR}"
        "-DCHROMELLM_TEST_BINARY_DIR=${CMAKE_CURRENT_BINARY_DIR}/library-discovery-test"
        "-DCHROMELLM_TEST_GENERATOR=${CMAKE_GENERATOR}"
        "-DCHROMELLM_TEST_GENERATOR_PLATFORM=${CMAKE_GENERATOR_PLATFORM}"
        "-DCHROMELLM_TEST_GENERATOR_TOOLSET=${CMAKE_GENERATOR_TOOLSET}"
        "-DCHROMELLM_TEST_CXX_COMPILER=${CMAKE_CXX_COMPILER}"
        "-DCHROMELLM_TEST_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        "-DCHROMELLM_TEST_JAVA_INCLUDE_PATH=${JAVA_INCLUDE_PATH}"
        "-DCHROMELLM_TEST_JAVA_INCLUDE_PATH2=${JAVA_INCLUDE_PATH2}"
        "-DCHROMELLM_TEST_JAVA_AWT_INCLUDE_PATH=${JAVA_AWT_INCLUDE_PATH}"
        "-DCHROMELLM_TEST_JAVA_AWT_LIBRARY=${JAVA_AWT_LIBRARY}"
        "-DCHROMELLM_TEST_JAVA_JVM_LIBRARY=${JAVA_JVM_LIBRARY}"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/library_discovery/CMakeLists.txt")
    add_test(NAME native-resource-package
      COMMAND ${CMAKE_COMMAND}
        "-DCHROMELLM_NATIVE_SOURCE_DIR=${CMAKE_CURRENT_SOURCE_DIR}"
        "-DCHROMELLM_TEST_BINARY_DIR=${CMAKE_CURRENT_BINARY_DIR}/native-package-test"
        "-DCHROMELLM_TEST_GENERATOR=${CMAKE_GENERATOR}"
        "-DCHROMELLM_TEST_GENERATOR_PLATFORM=${CMAKE_GENERATOR_PLATFORM}"
        "-DCHROMELLM_TEST_GENERATOR_TOOLSET=${CMAKE_GENERATOR_TOOLSET}"
        "-DCHROMELLM_TEST_CXX_COMPILER=${CMAKE_CXX_COMPILER}"
        "-DCHROMELLM_TEST_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_package/CMakeLists.txt")
  endif()
endif()


if(CHROMELLM_NATIVE_DISTRIBUTION)
  chromellm_runtime_archive(chromellm_llama_jni "${CHROMELLM_NATIVE_CLASSIFIER}"
    "${CMAKE_BINARY_DIR}/chromellm-native-${CHROMELLM_NATIVE_CLASSIFIER}.zip")
endif()
