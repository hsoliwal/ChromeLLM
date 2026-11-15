// SPDX-License-Identifier: MIT
// Test-only GGML device registry subset. Never distribute as a production header.
#pragma once
#include <cstddef>
struct ggml_backend_reg;
struct ggml_backend_device;
typedef ggml_backend_reg* ggml_backend_reg_t;
typedef ggml_backend_device* ggml_backend_dev_t;
extern "C" {
ggml_backend_reg_t ggml_backend_reg_by_name(const char* name);
size_t ggml_backend_dev_count(void);
ggml_backend_dev_t ggml_backend_dev_get(size_t index);
ggml_backend_reg_t ggml_backend_dev_backend_reg(ggml_backend_dev_t device);
}
