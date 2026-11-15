# SPDX-License-Identifier: MIT
# Notice-content fixtures are test strings, not replacement dependency licenses.
set(notice_work "${CHROMELLM_TEST_BINARY_DIR}/notice fixtures")
set(notice_stage "${notice_work}/stage")
set(notice_script "${CHROMELLM_NATIVE_SOURCE_DIR}/cmake/PackageNotices.cmake")
foreach(name product llama dawn)
  file(MAKE_DIRECTORY "${notice_work}/${name}")
  file(WRITE "${notice_work}/${name}/LICENSE" "${name} license fixture only\n")
endforeach()
file(MAKE_DIRECTORY "${notice_work}/llama/licenses"
  "${notice_work}/llama/ggml/src/ggml-cpu/llamafile"
  "${notice_work}/dawn/third_party/abseil-cpp"
  "${notice_work}/dawn/third_party/spirv-headers/src"
  "${notice_work}/bundle" "${notice_work}/empty")
file(WRITE "${notice_work}/llama/licenses/Apache-2.0.txt" "SPDX-named fixture only\n")
file(WRITE "${notice_work}/llama/ggml/src/ggml-cpu/llamafile/sgemm.cpp"
  "// Copyright 2024 Mozilla Foundation\n// Permission is hereby granted - fixture only\n// SOFTWARE.\n\nint private_kernel_body = 42;\n")
file(WRITE "${notice_work}/llama/ggml/src/ggml-cpu/ops.cpp"
  "int private_ops_body = 42;\n// MIT licensed. Copyright (c) 2023 Jeffrey Quesnelle and Bowen Peng.\n")
file(WRITE "${notice_work}/dawn/third_party/abseil-cpp/LICENSE" "Abseil fixture only\n")
file(WRITE "${notice_work}/dawn/third_party/spirv-headers/src/LICENSE" "SPIRV fixture only\n")
file(WRITE "${notice_work}/dawn/third_party/spirv-headers/README.chromium" "Attribution fixture only\n")
file(WRITE "${notice_work}/dawn/third_party/abseil-cpp/implementation.cpp" "this source must not be packaged\n")
file(WRITE "${notice_work}/bundle/LICENSE.txt" "operator bundle fixture only\n")
file(WRITE "${notice_work}/bundle/NOTICE" "operator notice fixture only\n")
file(WRITE "${notice_work}/bundle/private.cpp" "this source must not be packaged\n")
set(notice_args "-DCHROMELLM_PACKAGE_STAGE=${notice_stage}"
  "-DCHROMELLM_PRODUCT_SOURCE=${notice_work}/product"
  "-DCHROMELLM_LLAMA_SOURCE=${notice_work}/llama")
run(PASS "${CMAKE_COMMAND}" ${notice_args}
  "-DCHROMELLM_DAWN_SOURCE=${notice_work}/dawn" -P "${notice_script}")
set(notice_destination "${notice_stage}/META-INF/licenses")
foreach(relative chromellm/LICENSE llama/LICENSE llama/licenses/Apache-2.0.txt llama/INLINE_NOTICES.txt dawn/LICENSE
    dawn/third_party/abseil-cpp/LICENSE dawn/third_party/spirv-headers/src/LICENSE
    dawn/third_party/spirv-headers/README.chromium NOTICE_FILES.tsv)
  if(NOT EXISTS "${notice_destination}/${relative}")
    message(FATAL_ERROR "Missing expected packaged notice: ${relative}")
  endif()
endforeach()
file(READ "${notice_destination}/llama/INLINE_NOTICES.txt" inline_notices)
if(NOT inline_notices MATCHES "Mozilla Foundation"
    OR NOT inline_notices MATCHES "Jeffrey Quesnelle and Bowen Peng"
    OR inline_notices MATCHES "private_kernel_body|private_ops_body")
  message(FATAL_ERROR "Inline notice extraction omitted attribution or copied source bodies")
endif()
if(EXISTS "${notice_destination}/dawn/third_party/abseil-cpp/implementation.cpp")
  message(FATAL_ERROR "Notice collection included dependency source")
endif()
file(READ "${notice_destination}/NOTICE_FILES.tsv" notice_inventory)
run(PASS "${CMAKE_COMMAND}" ${notice_args}
  "-DCHROMELLM_DAWN_SOURCE=${notice_work}/dawn" -P "${notice_script}")
file(READ "${notice_destination}/NOTICE_FILES.tsv" notice_repeated)
if(NOT notice_inventory STREQUAL notice_repeated)
  message(FATAL_ERROR "Notice inventory is not deterministic")
endif()
file(RENAME "${notice_work}/llama/ggml/src/ggml-cpu/ops.cpp"
  "${notice_work}/llama/ggml/src/ggml-cpu/ops.cpp.saved")
file(WRITE "${notice_work}/llama/ggml/src/ggml-cpu/ops.cpp" "int private_ops_body = 42;\n")
run(REFUSE "${CMAKE_COMMAND}" ${notice_args}
  "-DCHROMELLM_DAWN_SOURCE=${notice_work}/dawn" -P "${notice_script}")
file(REMOVE "${notice_work}/llama/ggml/src/ggml-cpu/ops.cpp")
file(RENAME "${notice_work}/llama/ggml/src/ggml-cpu/ops.cpp.saved"
  "${notice_work}/llama/ggml/src/ggml-cpu/ops.cpp")
file(REMOVE "${notice_work}/dawn/third_party/abseil-cpp/LICENSE")
run(REFUSE "${CMAKE_COMMAND}" ${notice_args}
  "-DCHROMELLM_DAWN_SOURCE=${notice_work}/dawn" -P "${notice_script}")
file(READ "${notice_destination}/NOTICE_FILES.tsv" notice_retained)
if(NOT notice_inventory STREQUAL notice_retained)
  message(FATAL_ERROR "Incomplete dependency notices damaged the previous package")
endif()
run(REFUSE "${CMAKE_COMMAND}" ${notice_args}
  "-DCHROMELLM_DAWN_LICENSE_DIR=${notice_work}/empty" -P "${notice_script}")
run(PASS "${CMAKE_COMMAND}" ${notice_args}
  "-DCHROMELLM_DAWN_PREFIX=${notice_work}/installed-dawn"
  "-DCHROMELLM_DAWN_LICENSE_DIR=${notice_work}/bundle" -P "${notice_script}")
if(NOT EXISTS "${notice_destination}/dawn/LICENSE.txt"
    OR NOT EXISTS "${notice_destination}/dawn/NOTICE"
    OR EXISTS "${notice_destination}/dawn/private.cpp"
    OR EXISTS "${notice_destination}/dawn/third_party")
  message(FATAL_ERROR "Preinstalled Dawn notice override retained stale/source files or omitted notices")
endif()
