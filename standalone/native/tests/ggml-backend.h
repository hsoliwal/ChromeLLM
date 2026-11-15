// SPDX-License-Identifier: MIT
// Test-only GGML device registry subset. Never distribute as a production header.
#pragma once
#include <cstddef>
struct ggml_backend_reg;
struct ggml_backend_device;
typedef ggml_backend_reg* ggml_backend_reg_t;
typedef ggml_backend_device* ggml_backend_dev_t;
enum ggml_backend_dev_type {
  GGML_BACKEND_DEVICE_TYPE_CPU, GGML_BACKEND_DEVICE_TYPE_GPU,
  GGML_BACKEND_DEVICE_TYPE_IGPU, GGML_BACKEND_DEVICE_TYPE_ACCEL
};
extern "C" {
ggml_backend_reg_t ggml_backend_reg_by_name(const char* name);
const char* ggml_backend_reg_name(ggml_backend_reg_t reg);
size_t ggml_backend_dev_count(void);
ggml_backend_dev_t ggml_backend_dev_get(size_t index);
const char* ggml_backend_dev_name(ggml_backend_dev_t device);
const char* ggml_backend_dev_description(ggml_backend_dev_t device);
enum ggml_backend_dev_type ggml_backend_dev_type(ggml_backend_dev_t device);
ggml_backend_reg_t ggml_backend_dev_backend_reg(ggml_backend_dev_t device);
}
