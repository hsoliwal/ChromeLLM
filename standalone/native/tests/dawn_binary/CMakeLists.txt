# SPDX-License-Identifier: MIT
cmake_minimum_required(VERSION 3.20)
project(chromellm_dawn_binary_smoke LANGUAGES CXX)

find_package(Threads REQUIRED)
find_package(Dawn REQUIRED)

add_executable(chromellm_dawn_binary_smoke smoke.cpp)
target_compile_features(chromellm_dawn_binary_smoke PRIVATE cxx_std_17)
target_link_libraries(chromellm_dawn_binary_smoke PRIVATE dawn::webgpu_dawn Threads::Threads)

enable_testing()
add_test(NAME dawn-binary-vulkan-compute COMMAND chromellm_dawn_binary_smoke)
set_tests_properties(dawn-binary-vulkan-compute PROPERTIES TIMEOUT 90)
