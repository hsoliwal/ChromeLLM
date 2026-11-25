# Runtime packaging only; no source/model acquisition or Java ABI changes.
macro(chromellm_relocatable_libraries)
  if(APPLE)
    set(CMAKE_INSTALL_RPATH "@loader_path")
    set(CMAKE_INSTALL_NAME_DIR "@rpath")
  elseif(UNIX)
    set(CMAKE_INSTALL_RPATH "$ORIGIN")
  endif()
  set(CMAKE_BUILD_WITH_INSTALL_RPATH ON)
  set(CMAKE_INSTALL_RPATH_USE_LINK_PATH OFF)
endmacro()

function(chromellm_runtime_archive target classifier archive)
  set(stage "${CMAKE_CURRENT_BINARY_DIR}/native-stage-${classifier}")
  set(script "${CMAKE_CURRENT_BINARY_DIR}/native-stage-${classifier}-$<CONFIG>.cmake")
  # Windows DLLs and source-built Unix libraries can occupy different build directories.
  # Derive search directories from target ownership, never the caller's environment.
  set(pending ${target})
  set(visited "")
  set(runtime_directories "")
  while(pending)
    list(POP_FRONT pending current)
    if(NOT TARGET "${current}" OR current IN_LIST visited)
      continue()
    endif()
    list(APPEND visited "${current}")
    get_target_property(kind "${current}" TYPE)
    if(kind STREQUAL "SHARED_LIBRARY" OR kind STREQUAL "MODULE_LIBRARY")
      list(APPEND runtime_directories "$<TARGET_FILE_DIR:${current}>")
    endif()
    get_target_property(links "${current}" LINK_LIBRARIES)
    if(links)
      list(APPEND pending ${links})
    endif()
  endwhile()
  list(REMOVE_DUPLICATES runtime_directories)
  # Evaluate the full linked closure before copying; unresolved dependencies fail closed.
  file(GENERATE OUTPUT "${script}" CONTENT "
cmake_minimum_required(VERSION 3.20)
set(bridge \"$<TARGET_FILE:${target}>\")
set(search_directories \"${runtime_directories}\")
if(NOT EXISTS \"\${bridge}\")
  message(FATAL_ERROR \"Missing native bridge: \${bridge}\")
endif()
set(system_dlls kernel32.dll kernelbase.dll ntdll.dll advapi32.dll bcrypt.dll crypt32.dll
  gdi32.dll user32.dll ole32.dll oleaut32.dll rpcrt4.dll secur32.dll shell32.dll shlwapi.dll
  userenv.dll version.dll winmm.dll ws2_32.dll ucrtbase.dll msvcrt.dll vcruntime140.dll
  vcruntime140_1.dll msvcp140.dll msvcp140_1.dll msvcp140_2.dll msvcp140_atomic_wait.dll
  msvcp140_codecvt_ids.dll combase.dll cfgmgr32.dll imm32.dll powrprof.dll setupapi.dll)
# Approved Windows system DLLs are the platform boundary: they are never packaged, and their
# private OS imports (optional, SKU- and runner-image-specific components) are not walked.
# Direct imports of any other DLL must still resolve and are checked below.
set(system_dll_excludes \"\")
if(WIN32)
  foreach(system_dll IN LISTS system_dlls)
    string(REPLACE \".\" \"[.]\" system_dll_pattern \"\${system_dll}\")
    list(APPEND system_dll_excludes
      \"[^A-Za-z0-9][Ww][Ii][Nn][Dd][Oo][Ww][Ss][^A-Za-z0-9][Ss][Yy][Ss][Tt][Ee][Mm]32[^A-Za-z0-9]\${system_dll_pattern}$\")
  endforeach()
endif()
file(GET_RUNTIME_DEPENDENCIES LIBRARIES \"\${bridge}\"
  DIRECTORIES \${search_directories}
  RESOLVED_DEPENDENCIES_VAR dependencies UNRESOLVED_DEPENDENCIES_VAR unresolved
  PRE_EXCLUDE_REGEXES \"api-ms-.*\" \"ext-ms-.*\"
  POST_EXCLUDE_REGEXES \"^(/lib|/lib64|/usr/lib|/usr/lib64)(/[^/]+)?/(libc|libm|libdl|libpthread|librt|libstdc[+][+]|libgcc_s)[.]so([.][0-9]+)*$\" \"^(/lib|/lib64|/usr/lib|/usr/lib64)(/[^/]+)?/ld-linux[^/]*[.]so([.][0-9]+)*$\"
    \"^/usr/lib/[^/]+[.]dylib$\" \"^/System/Library/.*\" \${system_dll_excludes})
if(unresolved)
  message(FATAL_ERROR \"Unresolved native runtime libraries: \${unresolved}\")
endif()
set(owned_dependencies \"\")
foreach(library IN LISTS dependencies)
  file(REAL_PATH \"\${library}\" resolved_library)
  string(TOLOWER \"\${resolved_library}\" runtime_path)
  get_filename_component(runtime_name \"\${runtime_path}\" NAME)
  if(WIN32 AND runtime_path MATCHES \"/windows/system32/\" AND runtime_name IN_LIST system_dlls)
    continue()
  endif()
  # file(REAL_PATH) canonicalizes the library (Windows 8.3 names such as RUNNER~1, macOS
  # /var -> /private/var); canonicalize the producer root the same way before comparing.
  file(REAL_PATH \"${CMAKE_BINARY_DIR}\" producer_root)
  cmake_path(IS_PREFIX producer_root \"\${resolved_library}\" NORMALIZE owned_library)
  if(NOT owned_library)
    message(FATAL_ERROR \"Unsupported external native runtime dependency: \${library}; supply a separately qualified source-linked runtime\")
  endif()
  if(library MATCHES \"[Ss][Tt][Uu][Bb]\")
    message(FATAL_ERROR \"Test stub cannot enter native distribution: \${library}\")
  endif()
  list(APPEND owned_dependencies \"\${library}\")
endforeach()
file(REMOVE_RECURSE \"${stage}\")
file(MAKE_DIRECTORY \"${stage}/lib\" \"${stage}/licenses\")
file(COPY \"\${bridge}\" \${owned_dependencies} DESTINATION \"${stage}/lib\" FOLLOW_SYMLINK_CHAIN)
")
  install(SCRIPT "${script}" COMPONENT chromellm-runtime)
  if(CHROMELLM_LLAMA_SOURCE_DIR)
    install(DIRECTORY "${CHROMELLM_NATIVE_NOTICES}/"
      DESTINATION "${stage}/licenses/reviewed" COMPONENT chromellm-runtime)
    install(FILES "${CHROMELLM_LLAMA_SOURCE_DIR}/LICENSE"
      DESTINATION "${stage}/licenses/llama.cpp" COMPONENT chromellm-runtime)
    install(FILES "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../LICENSE"
      "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../NOTICE"
      "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../THIRD_PARTY.md"
      DESTINATION "${stage}/licenses/ChromeLLM" COMPONENT chromellm-runtime)
    install(FILES "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../NOTICE.md"
      DESTINATION "${stage}/licenses/ChromeLLM/standalone" COMPONENT chromellm-runtime)
    install(DIRECTORY "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../LICENSES/"
      DESTINATION "${stage}/licenses/ChromeLLM/LICENSES" COMPONENT chromellm-runtime)
  endif()
  add_custom_target(native-bundle
    COMMAND "${CMAKE_COMMAND}" --install "${CMAKE_BINARY_DIR}" --config "$<CONFIG>" --component chromellm-runtime
    COMMAND "${CMAKE_COMMAND}" -E chdir "${stage}" "${CMAKE_COMMAND}" -E tar cf "${archive}" --format=zip -- lib licenses
    WORKING_DIRECTORY "${CMAKE_BINARY_DIR}"
    DEPENDS ${target}
    VERBATIM)
endfunction()
