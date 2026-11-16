# SPDX-License-Identifier: MIT
# Stage a single source-pinned CPU JNI library, with the data needed by the
# consumer's digest/size verifier. No model or synthetic test provider is copied.
cmake_minimum_required(VERSION 3.20)
foreach(INPUT RUNTIME_LIBRARY RUNTIME_DESTINATION LLAMA_SOURCE LLAMA_COMMIT
    BRIDGE_SOURCE NATIVE_BUILD_SOURCE CHROMELLM_LICENSE TARGET_SYSTEM TARGET_PROCESSOR
    CXX_COMPILER_ID CXX_COMPILER_VERSION)
  if(NOT DEFINED ${INPUT} OR "${${INPUT}}" STREQUAL "")
    message(FATAL_ERROR "Missing runtime packaging input: ${INPUT}")
  endif()
endforeach()
if(NOT EXISTS "${RUNTIME_LIBRARY}" OR IS_DIRECTORY "${RUNTIME_LIBRARY}")
  message(FATAL_ERROR "The built JNI library is missing")
endif()
file(MAKE_DIRECTORY "${RUNTIME_DESTINATION}")
get_filename_component(LIBRARY_NAME "${RUNTIME_LIBRARY}" NAME)
configure_file("${RUNTIME_LIBRARY}" "${RUNTIME_DESTINATION}/${LIBRARY_NAME}" COPYONLY)
file(SHA256 "${RUNTIME_DESTINATION}/${LIBRARY_NAME}" LIBRARY_SHA256)
file(SIZE "${RUNTIME_DESTINATION}/${LIBRARY_NAME}" LIBRARY_BYTES)
file(SHA256 "${BRIDGE_SOURCE}" BRIDGE_SHA256)
file(SHA256 "${NATIVE_BUILD_SOURCE}" NATIVE_BUILD_SHA256)
file(SHA256 "${CMAKE_CURRENT_LIST_FILE}" PACKAGE_SCRIPT_SHA256)
set(PLATFORM_REQUIREMENTS "")
if(TARGET_SYSTEM STREQUAL "Linux")
  find_program(READELF readelf REQUIRED)
  execute_process(COMMAND "${READELF}" -d "${RUNTIME_LIBRARY}"
    RESULT_VARIABLE AUDIT_STATUS OUTPUT_VARIABLE DEPENDENCIES ERROR_VARIABLE AUDIT_ERROR)
  if(NOT AUDIT_STATUS EQUAL 0)
    message(FATAL_ERROR "Cannot inspect native dependencies: ${AUDIT_ERROR}")
  endif()
  string(REGEX MATCHALL "Shared library: \\[[^]]+\\]" NEEDED "${DEPENDENCIES}")
  foreach(ITEM IN LISTS NEEDED)
    if(NOT ITEM MATCHES "\\[(libc.so.6|libm.so.6|libdl.so.2|libpthread.so.0|librt.so.1|ld-linux-x86-64.so.2|ld-linux-aarch64.so.1)\\]$")
      message(FATAL_ERROR "CPU runtime has a non-system shared dependency: ${ITEM}")
    endif()
  endforeach()
  execute_process(COMMAND "${READELF}" --version-info "${RUNTIME_LIBRARY}"
    RESULT_VARIABLE AUDIT_STATUS OUTPUT_VARIABLE SYMBOL_VERSIONS ERROR_VARIABLE AUDIT_ERROR)
  if(NOT AUDIT_STATUS EQUAL 0)
    message(FATAL_ERROR "Cannot inspect native symbol versions: ${AUDIT_ERROR}")
  endif()
  string(REGEX MATCHALL "GLIBC_[0-9.]+" GLIBC_VERSIONS "${SYMBOL_VERSIONS}")
  if(NOT GLIBC_VERSIONS)
    message(FATAL_ERROR "The Linux CPU distribution requires a glibc ABI record")
  endif()
  list(REMOVE_DUPLICATES GLIBC_VERSIONS)
  list(SORT GLIBC_VERSIONS COMPARE NATURAL)
  list(GET GLIBC_VERSIONS -1 GLIBC_MINIMUM)
  string(REPLACE "GLIBC_" "" GLIBC_MINIMUM "${GLIBC_MINIMUM}")
  set(PLATFORM_REQUIREMENTS "glibc.minimum=${GLIBC_MINIMUM}\n")
elseif(TARGET_SYSTEM STREQUAL "Darwin")
  find_program(OTOOL otool REQUIRED)
  execute_process(COMMAND "${OTOOL}" -L "${RUNTIME_LIBRARY}"
    RESULT_VARIABLE AUDIT_STATUS OUTPUT_VARIABLE DEPENDENCIES ERROR_VARIABLE AUDIT_ERROR)
  if(NOT AUDIT_STATUS EQUAL 0)
    message(FATAL_ERROR "Cannot inspect native dependencies: ${AUDIT_ERROR}")
  endif()
  string(REGEX MATCHALL "\t[^\n]+" NEEDED "${DEPENDENCIES}")
  foreach(ITEM IN LISTS NEEDED)
    if(NOT ITEM MATCHES "^\t(/usr/lib/|/System/Library/Frameworks/|@rpath/${LIBRARY_NAME})")
      message(FATAL_ERROR "CPU runtime has a non-system shared dependency: ${ITEM}")
    endif()
  endforeach()
elseif(TARGET_SYSTEM STREQUAL "Windows" AND CXX_COMPILER_ID STREQUAL "MSVC")
  execute_process(COMMAND "${NATIVE_LINKER}" /DUMP /DEPENDENTS "${RUNTIME_LIBRARY}"
    RESULT_VARIABLE AUDIT_STATUS OUTPUT_VARIABLE DEPENDENCIES ERROR_VARIABLE AUDIT_ERROR)
  if(NOT AUDIT_STATUS EQUAL 0)
    message(FATAL_ERROR "Cannot inspect native dependencies: ${AUDIT_ERROR}")
  endif()
  string(TOLOWER "${DEPENDENCIES}" LOWER_DEPENDENCIES)
  string(REGEX MATCHALL "[a-z0-9_.-]+\\.dll" NEEDED "${LOWER_DEPENDENCIES}")
  string(TOLOWER "${LIBRARY_NAME}" LOWER_LIBRARY_NAME)
  foreach(ITEM IN LISTS NEEDED)
    if(NOT ITEM STREQUAL LOWER_LIBRARY_NAME AND NOT ITEM MATCHES "^(kernel32|advapi32|bcrypt|ws2_32|ucrtbase|ntdll|user32|api-ms-win-[a-z0-9_-]+)\\.dll$")
      message(FATAL_ERROR "CPU runtime has a non-system shared dependency: ${ITEM}")
    endif()
  endforeach()
else()
  message(FATAL_ERROR "No native distribution dependency audit for ${TARGET_SYSTEM}/${CXX_COMPILER_ID}")
endif()
file(WRITE "${RUNTIME_DESTINATION}/dependencies.txt" "${DEPENDENCIES}")
configure_file("${CHROMELLM_LICENSE}" "${RUNTIME_DESTINATION}/LICENSE.chromellm.txt" COPYONLY)
configure_file("${LLAMA_SOURCE}/LICENSE" "${RUNTIME_DESTINATION}/LICENSE.llama.txt" COPYONLY)
if(EXISTS "${LLAMA_SOURCE}/licenses")
  file(COPY "${LLAMA_SOURCE}/licenses/" DESTINATION "${RUNTIME_DESTINATION}/licenses")
endif()
if(EXISTS "${LLAMA_SOURCE}/ggml/src/ggml-cpu/llamafile/sgemm.cpp")
  # Preserve the additional copyright and full MIT terms carried by this source.
  file(STRINGS "${LLAMA_SOURCE}/ggml/src/ggml-cpu/llamafile/sgemm.cpp" LLAMAFILE_NOTICE LIMIT_COUNT 21)
  string(REPLACE ";" "\n" LLAMAFILE_NOTICE "${LLAMAFILE_NOTICE}")
  file(WRITE "${RUNTIME_DESTINATION}/LICENSE.llamafile.txt" "${LLAMAFILE_NOTICE}\n")
endif()
if(TARGET_SYSTEM STREQUAL "Linux" AND CXX_COMPILER_ID STREQUAL "GNU")
  string(REGEX MATCH "^[0-9]+" GCC_MAJOR "${CXX_COMPILER_VERSION}")
  set(GCC_COPYRIGHT "/usr/share/doc/gcc-${GCC_MAJOR}-base/copyright")
  if(NOT EXISTS "${GCC_COPYRIGHT}" OR NOT EXISTS "/usr/share/common-licenses/GPL-3")
    message(FATAL_ERROR "Packaging a static GNU runtime requires the installed GCC copyright/exception and GPL-3 texts")
  endif()
  configure_file("${GCC_COPYRIGHT}" "${RUNTIME_DESTINATION}/LICENSE.gcc-runtime.txt" COPYONLY)
  configure_file("/usr/share/common-licenses/GPL-3" "${RUNTIME_DESTINATION}/LICENSE.GPL-3.txt" COPYONLY)
endif()
file(WRITE "${RUNTIME_DESTINATION}/runtime.properties"
  "schema=1\nfile=${LIBRARY_NAME}\nsha256=${LIBRARY_SHA256}\nbytes=${LIBRARY_BYTES}\n"
  "compute=cpu\nos=${TARGET_SYSTEM}\narch=${TARGET_PROCESSOR}\n"
  "llama.commit=${LLAMA_COMMIT}\nbridge.sha256=${BRIDGE_SHA256}\n"
  "native.build.sha256=${NATIVE_BUILD_SHA256}\n"
  "native.package.sha256=${PACKAGE_SCRIPT_SHA256}\n"
  "${PLATFORM_REQUIREMENTS}"
  "compiler.id=${CXX_COMPILER_ID}\ncompiler.version=${CXX_COMPILER_VERSION}\n")
file(WRITE "${RUNTIME_DESTINATION}/SHA256SUMS" "${LIBRARY_SHA256}  ${LIBRARY_NAME}\n")
file(WRITE "${RUNTIME_DESTINATION}/NOTICE.txt"
  "ChromeLLM CPU JNI runtime.\n"
  "ChromeLLM bridge: MIT; see LICENSE.chromellm.txt.\n"
  "llama.cpp and GGML: MIT; see LICENSE.llama.txt and licenses/.\n"
  "Included llama CPU contributors: Mozilla Foundation (2024); Jeffrey Quesnelle and Bowen Peng (2023), MIT.\n"
  "GNU builds statically link libgcc/libstdc++ under GPL-3 with the GCC Runtime Library Exception; see included texts.\n"
  "Native source commit: ${LLAMA_COMMIT}.\n"
  "This package contains no model weights or browser runtime.\n"
  "It requires Java 21 and the platform OS libraries identified by the CI dependency check.\n"
  "The CPU distribution disables dynamic GGML providers and host-specific instruction flags.\n")
file(WRITE "${RUNTIME_DESTINATION}/README.txt"
  "ChromeLLM native CPU runtime\n\n"
  "Choose the artifact for your operating system and CPU architecture. runtime.properties records\n"
  "the native filename, exact byte count, SHA-256, source commit and compiler. The Java runtime\n"
  "requires Java 21. JNI acquisition accepts a raw shared-library asset, not the ZIP bundle.\n\n"
  "Set jniUrl, jniSha256, jniBytes and jniCacheDir in a Maven smart-build recipe. Use the\n"
  "values from runtime.properties and the HTTPS URL of the matching raw release asset.\n"
  "model.properties describes the separately licensed test model; weights are not in this bundle.\n\n"
  "For Linux, glibc.minimum is the highest version required by the built ELF's imported symbols.\n"
  "Your host must provide that version or newer. dependencies.txt records the actual OS libraries.\n"
  "These glibc builds do not qualify musl distributions. macOS and Windows artifacts require\n"
  "their system libraries; CPU qualification does not imply GPU/WebGPU availability.\n\n"
  "Linux GNU builds include libgcc/libstdc++ under GPL-3 with the GCC Runtime Library Exception.\n"
  "Keep LICENSE.gcc-runtime.txt, LICENSE.GPL-3.txt and all MIT/Apache license and notice files\n"
  "with redistributed bundles. The GCC runtime exception and notices do not change the\n"
  "ChromeLLM/llama.cpp MIT licenses or the separate model's Apache-2.0 license.\n")
message(STATUS "Staged CPU JNI: ${LIBRARY_NAME}, ${LIBRARY_BYTES} bytes, SHA-256 ${LIBRARY_SHA256}")
