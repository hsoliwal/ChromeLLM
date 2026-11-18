# SPDX-License-Identifier: MIT
cmake_minimum_required(VERSION 3.22)
# Check explicit targets before project(), and toolchain-selected targets after.
if(EMSCRIPTEN OR CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  message(FATAL_ERROR "ChromeLLM builds native JNI runtimes; Emscripten/browser targets are unsupported")
endif()
project(chromellm_native_build LANGUAGES NONE)

if(EMSCRIPTEN OR CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  message(FATAL_ERROR "ChromeLLM builds native JNI runtimes; Emscripten/browser targets are unsupported")
endif()

include(ExternalProject)

option(CHROMELLM_FETCH_SOURCES "Fetch missing fork sources at the pinned revisions" ON)
set(CHROMELLM_COMPUTE_BACKEND "cpu" CACHE STRING "Native artifact backend: cpu or webgpu")
if(CHROMELLM_COMPUTE_BACKEND STREQUAL "cpu")
  set(CHROMELLM_WEBGPU OFF)
elseif(CHROMELLM_COMPUTE_BACKEND STREQUAL "webgpu")
  set(CHROMELLM_WEBGPU ON)
else()
  message(FATAL_ERROR "CHROMELLM_COMPUTE_BACKEND must be cpu or webgpu")
endif()
set(CHROMELLM_LLAMA_SOURCE_DIR "" CACHE PATH "Existing compatible llama.cpp fork checkout")
set(CHROMELLM_DAWN_SOURCE_DIR "" CACHE PATH "Existing compatible Dawn fork checkout")
set(CHROMELLM_DAWN_PREFIX "" CACHE PATH "Existing Dawn installation; excludes a Dawn source build")
set(CHROMELLM_DAWN_LICENSE_DIR "" CACHE PATH "Complete Dawn dependency notice bundle for an installed package")
set(CHROMELLM_NATIVE_CLASSIFIER "" CACHE STRING "Target OS/architecture classifier")
set(CHROMELLM_NATIVE_STAGE "${CMAKE_BINARY_DIR}/stage" CACHE PATH "Native artifact resource staging directory")
set(CHROMELLM_BUILD_CONFIGURATION "Release" CACHE STRING "Native build configuration")
set(CHROMELLM_BUILD_PARALLEL "2" CACHE STRING "Maximum native compile jobs")
set(CHROMELLM_LLAMA_REVISION "650ac3739d993e1d425f2d5db3c4205b3606cc30" CACHE STRING "Pinned llama.cpp fork commit")
set(CHROMELLM_DAWN_REVISION "21c27e6d8d79e047915d309b7363d6a1d3d622e2" CACHE STRING "Pinned native Dawn fork commit")

if(NOT CHROMELLM_BUILD_PARALLEL MATCHES "^[1-9][0-9]*$")
  message(FATAL_ERROR "CHROMELLM_BUILD_PARALLEL must be a positive integer")
endif()
if(NOT CHROMELLM_BUILD_CONFIGURATION MATCHES "^(Release|RelWithDebInfo|MinSizeRel|Debug)$")
  message(FATAL_ERROR "Unsupported native build configuration")
endif()
if(CHROMELLM_DAWN_SOURCE_DIR AND CHROMELLM_DAWN_PREFIX)
  message(FATAL_ERROR "Choose one Dawn source checkout or installed prefix")
endif()
if(NOT CHROMELLM_WEBGPU AND (CHROMELLM_DAWN_SOURCE_DIR OR CHROMELLM_DAWN_PREFIX OR CHROMELLM_DAWN_LICENSE_DIR))
  message(FATAL_ERROR "Dawn inputs require CHROMELLM_COMPUTE_BACKEND=webgpu")
endif()

# A source fetch is an explicit native-build action. It never runs in the ordinary
# Java lifecycle, and never changes an operator-owned source checkout.
function(chromellm_source name repository revision supplied out_path out_dependency)
  string(LENGTH "${revision}" revision_length)
  if(NOT revision_length EQUAL 40 OR NOT revision MATCHES "^[0-9a-f]+$")
    message(FATAL_ERROR "${name} requires an immutable 40-character Git commit")
  endif()
  if(supplied)
    get_filename_component(source "${supplied}" ABSOLUTE)
    if(NOT EXISTS "${source}/CMakeLists.txt")
      message(FATAL_ERROR "${name} source checkout has no CMakeLists.txt: ${source}")
    endif()
    # Verify the supplied checkout without fetching or mutating it.
    find_package(Git REQUIRED)
    execute_process(
      COMMAND "${GIT_EXECUTABLE}" -C "${source}" rev-parse --verify HEAD
      RESULT_VARIABLE source_status
      OUTPUT_VARIABLE source_head
      OUTPUT_STRIP_TRAILING_WHITESPACE
      ERROR_QUIET)
    if(NOT source_status EQUAL 0 OR NOT source_head STREQUAL revision)
      message(FATAL_ERROR "${name} source HEAD does not match pinned revision ${revision} (found '${source_head}')")
    endif()
    set(${out_dependency} "" PARENT_SCOPE)
  else()
    set(source "${CMAKE_BINARY_DIR}/sources/${name}-${revision}")
    if(NOT CHROMELLM_FETCH_SOURCES)
      message(FATAL_ERROR "Missing ${name} checkout; provide its source path or enable CHROMELLM_FETCH_SOURCES")
    endif()
    ExternalProject_Add(${name}-source
      GIT_REPOSITORY "${repository}"
      GIT_TAG "${revision}"
      GIT_SUBMODULES ""
      GIT_PROGRESS TRUE
      UPDATE_DISCONNECTED TRUE
      SOURCE_DIR "${source}"
      CONFIGURE_COMMAND ""
      BUILD_COMMAND ""
      INSTALL_COMMAND "")
    set(${out_dependency} "${name}-source" PARENT_SCOPE)
  endif()
  set(${out_path} "${source}" PARENT_SCOPE)
endfunction()

chromellm_source(llama "https://github.com/hsoliwal/synexia-llmcpp.git"
  "${CHROMELLM_LLAMA_REVISION}" "${CHROMELLM_LLAMA_SOURCE_DIR}" llama_source llama_dependency)

set(common_args
  "-DCMAKE_BUILD_TYPE:STRING=${CHROMELLM_BUILD_CONFIGURATION}"
  "-DCMAKE_POSITION_INDEPENDENT_CODE:BOOL=ON")
foreach(variable CMAKE_TOOLCHAIN_FILE CMAKE_MAKE_PROGRAM CMAKE_C_COMPILER CMAKE_CXX_COMPILER
    CMAKE_OSX_ARCHITECTURES CMAKE_OSX_DEPLOYMENT_TARGET CMAKE_SYSROOT)
  if(DEFINED ${variable} AND NOT "${${variable}}" STREQUAL "")
    list(APPEND common_args "-D${variable}:STRING=${${variable}}")
  endif()
endforeach()

set(native_dependencies ${llama_dependency})
set(dawn_prefix "${CHROMELLM_DAWN_PREFIX}")
if(CHROMELLM_WEBGPU AND NOT dawn_prefix)
  chromellm_source(dawn "https://github.com/hsoliwal/dawn.git"
    "${CHROMELLM_DAWN_REVISION}" "${CHROMELLM_DAWN_SOURCE_DIR}" dawn_source dawn_dependency)
  set(dawn_prefix "${CMAKE_BINARY_DIR}/dawn-install")
  ExternalProject_Add(chromellm-dawn
    SOURCE_DIR "${dawn_source}"
    BINARY_DIR "${CMAKE_BINARY_DIR}/dawn-build"
    DOWNLOAD_COMMAND ""
    DEPENDS ${dawn_dependency}
    CMAKE_ARGS ${common_args}
      "-DCMAKE_INSTALL_PREFIX:PATH=${dawn_prefix}"
      -DDAWN_FETCH_DEPENDENCIES:BOOL=${CHROMELLM_FETCH_SOURCES}
      -DDAWN_ENABLE_INSTALL:BOOL=ON
      -DDAWN_BUILD_MONOLITHIC_LIBRARY:STRING=STATIC
      -DBUILD_SHARED_LIBS:BOOL=OFF
      -DDAWN_BUILD_TESTS:BOOL=OFF
      -DDAWN_BUILD_SAMPLES:BOOL=OFF
      -DDAWN_BUILD_PROTOBUF:BOOL=OFF
      -DDAWN_USE_GLFW:BOOL=OFF
      -DDAWN_USE_X11:BOOL=OFF
      -DDAWN_USE_WAYLAND:BOOL=OFF
      -DTINT_BUILD_TESTS:BOOL=OFF
      -DTINT_BUILD_CMD_TOOLS:BOOL=OFF
    BUILD_COMMAND "${CMAKE_COMMAND}" --build <BINARY_DIR>
      --config "${CHROMELLM_BUILD_CONFIGURATION}" --parallel "${CHROMELLM_BUILD_PARALLEL}"
    INSTALL_COMMAND "${CMAKE_COMMAND}" --install <BINARY_DIR>
      --config "${CHROMELLM_BUILD_CONFIGURATION}"
    BUILD_ALWAYS TRUE)
  list(APPEND native_dependencies chromellm-dawn)
endif()

ExternalProject_Add(chromellm-jni
  SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../native"
  BINARY_DIR "${CMAKE_BINARY_DIR}/jni-build"
  DOWNLOAD_COMMAND ""
  DEPENDS ${native_dependencies}
  CMAKE_ARGS ${common_args}
    "-DCMAKE_INSTALL_PREFIX:PATH=${CHROMELLM_NATIVE_STAGE}"
    "-DCHROMELLM_LLAMA_SOURCE_DIR:PATH=${llama_source}"
    "-DCHROMELLM_WEBGPU:BOOL=${CHROMELLM_WEBGPU}"
    "-DCMAKE_PREFIX_PATH:PATH=${dawn_prefix}"
    "-DCHROMELLM_DAWN_PREFIX:PATH=${dawn_prefix}"
    "-DCHROMELLM_NATIVE_CLASSIFIER:STRING=${CHROMELLM_NATIVE_CLASSIFIER}"
    -DCHROMELLM_INSTALL_NATIVE:BOOL=ON
    -DCHROMELLM_SYNTHETIC_TEST:BOOL=OFF
    -DCHROMELLM_PACKAGE_TEST_FIXTURE:BOOL=OFF
    -DBUILD_SHARED_LIBS:BOOL=OFF
    -DGGML_NATIVE:BOOL=OFF
    -DGGML_METAL:BOOL=OFF
    -DGGML_SSE42:BOOL=OFF
    -DGGML_AVX:BOOL=OFF
    -DGGML_AVX2:BOOL=OFF
    -DGGML_BMI2:BOOL=OFF
    -DGGML_F16C:BOOL=OFF
    -DGGML_FMA:BOOL=OFF
    -DGGML_OPENMP:BOOL=OFF
    -DGGML_BACKEND_DL:BOOL=OFF
    -DGGML_WEBGPU_DEBUG:BOOL=OFF
    -DGGML_WEBGPU_CPU_PROFILE:BOOL=OFF
    -DGGML_WEBGPU_GPU_PROFILE:BOOL=OFF
    "-DJAVA_HOME:PATH=${JAVA_HOME}"
  BUILD_COMMAND "${CMAKE_COMMAND}" --build <BINARY_DIR>
    --config "${CHROMELLM_BUILD_CONFIGURATION}" --parallel "${CHROMELLM_BUILD_PARALLEL}"
  INSTALL_COMMAND "${CMAKE_COMMAND}" --install <BINARY_DIR>
    --config "${CHROMELLM_BUILD_CONFIGURATION}" --component ChromeLLMNative
  BUILD_ALWAYS TRUE)

# License texts accompany native outputs. Private fork source bodies and model
# weights are never copied into the resource staging directory.
ExternalProject_Add_Step(chromellm-jni package-notices
  COMMAND "${CMAKE_COMMAND}"
    "-DCHROMELLM_PACKAGE_STAGE=${CHROMELLM_NATIVE_STAGE}"
    "-DCHROMELLM_PRODUCT_SOURCE=${CMAKE_CURRENT_LIST_DIR}/../.."
    "-DCHROMELLM_LLAMA_SOURCE=${llama_source}"
    "-DCHROMELLM_DAWN_SOURCE=${dawn_source}"
    "-DCHROMELLM_DAWN_PREFIX=${CHROMELLM_DAWN_PREFIX}"
    "-DCHROMELLM_DAWN_LICENSE_DIR=${CHROMELLM_DAWN_LICENSE_DIR}"
    -P "${CMAKE_CURRENT_LIST_DIR}/../native/cmake/PackageNotices.cmake"
  DEPENDEES install
  ALWAYS TRUE)
