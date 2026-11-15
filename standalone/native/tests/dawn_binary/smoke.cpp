// SPDX-License-Identifier: MIT
#include <webgpu/webgpu_cpp.h>
#include <webgpu/webgpu_cpp_print.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <exception>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

namespace {
constexpr uint64_t kWaitNanoseconds = 20'000'000'000ULL;
std::atomic<bool> deviceFailed{false};

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void await(const wgpu::Instance& instance, wgpu::Future future, const char* operation) {
    if (instance.WaitAny(future, kWaitNanoseconds) != wgpu::WaitStatus::Success) {
        throw std::runtime_error(std::string(operation) + " did not finish within 20 seconds");
    }
}

int smoke() {
    const auto timedWait = wgpu::InstanceFeatureName::TimedWaitAny;
    wgpu::InstanceDescriptor instanceDescriptor{};
    instanceDescriptor.requiredFeatureCount = 1;
    instanceDescriptor.requiredFeatures = &timedWait;
    wgpu::Instance instance = wgpu::CreateInstance(&instanceDescriptor);
    require(static_cast<bool>(instance), "Dawn instance creation failed");

    // The test admits only the explicitly requested Vulkan backend. CI may supply
    // a software Vulkan ICD through its environment; no alternative backend is tried.
    wgpu::RequestAdapterOptions adapterOptions{};
    adapterOptions.backendType = wgpu::BackendType::Vulkan;
    auto adapter = std::make_shared<wgpu::Adapter>();
    auto adapterFuture = instance.RequestAdapter(&adapterOptions, wgpu::CallbackMode::WaitAnyOnly,
            [adapter](wgpu::RequestAdapterStatus status, wgpu::Adapter selected, wgpu::StringView message) {
                if (status == wgpu::RequestAdapterStatus::Success) *adapter = std::move(selected);
                else std::cerr << "Vulkan adapter request failed: " << message << '\n';
            });
    await(instance, adapterFuture, "Vulkan adapter request");
    require(static_cast<bool>(*adapter), "Dawn did not provide the requested Vulkan adapter");
    wgpu::AdapterInfo info{};
    require(adapter->GetInfo(&info) == wgpu::Status::Success, "Cannot inspect selected Vulkan adapter");
    require(info.backendType == wgpu::BackendType::Vulkan, "Dawn selected an unexpected backend");
    std::cout << "Dawn adapter: " << info.device << " backend=" << info.backendType
              << " adapterType=" << info.adapterType << '\n';

    wgpu::DeviceDescriptor deviceDescriptor{};
    deviceDescriptor.SetUncapturedErrorCallback(
            [](const wgpu::Device&, wgpu::ErrorType type, wgpu::StringView message) {
                deviceFailed.store(true);
                std::cerr << "Dawn device error " << type << ": " << message << '\n';
            });
    deviceDescriptor.SetDeviceLostCallback(wgpu::CallbackMode::AllowSpontaneous,
            [](const wgpu::Device&, wgpu::DeviceLostReason reason, wgpu::StringView message) {
                if (reason != wgpu::DeviceLostReason::Destroyed) {
                    deviceFailed.store(true);
                    std::cerr << "Dawn device lost: " << message << '\n';
                }
            });
    auto device = std::make_shared<wgpu::Device>();
    auto deviceFuture = adapter->RequestDevice(&deviceDescriptor, wgpu::CallbackMode::WaitAnyOnly,
            [device](wgpu::RequestDeviceStatus status, wgpu::Device selected, wgpu::StringView message) {
                if (status == wgpu::RequestDeviceStatus::Success) *device = std::move(selected);
                else std::cerr << "Vulkan device request failed: " << message << '\n';
            });
    await(instance, deviceFuture, "Vulkan device request");
    require(static_cast<bool>(*device), "Dawn Vulkan device creation failed");

    constexpr std::array<uint32_t, 4> input{{3, 17, 100, 4095}};
    constexpr uint64_t byteCount = sizeof(input);
    wgpu::BufferDescriptor storageDescriptor{};
    storageDescriptor.size = byteCount;
    storageDescriptor.usage = wgpu::BufferUsage::Storage | wgpu::BufferUsage::CopyDst | wgpu::BufferUsage::CopySrc;
    auto storage = device->CreateBuffer(&storageDescriptor);
    wgpu::BufferDescriptor readbackDescriptor{};
    readbackDescriptor.size = byteCount;
    readbackDescriptor.usage = wgpu::BufferUsage::MapRead | wgpu::BufferUsage::CopyDst;
    auto readback = device->CreateBuffer(&readbackDescriptor);
    require(static_cast<bool>(storage) && static_cast<bool>(readback), "Dawn buffer creation failed");

    wgpu::ShaderSourceWGSL wgsl{};
    wgsl.code = R"(
        @group(0) @binding(0) var<storage, read_write> values: array<u32>;
        @compute @workgroup_size(4)
        fn main(@builtin(global_invocation_id) id: vec3<u32>) {
            if (id.x < 4u) { values[id.x] = values[id.x] + 1u; }
        }
    )";
    wgpu::ShaderModuleDescriptor shaderDescriptor{};
    shaderDescriptor.nextInChain = &wgsl;
    auto shader = device->CreateShaderModule(&shaderDescriptor);
    wgpu::ComputePipelineDescriptor pipelineDescriptor{};
    pipelineDescriptor.compute.module = shader;
    pipelineDescriptor.compute.entryPoint = "main";
    auto pipeline = device->CreateComputePipeline(&pipelineDescriptor);
    require(static_cast<bool>(pipeline), "WGSL compute pipeline creation failed");

    wgpu::BindGroupEntry entry{};
    entry.binding = 0;
    entry.buffer = storage;
    entry.size = byteCount;
    wgpu::BindGroupDescriptor groupDescriptor{};
    groupDescriptor.layout = pipeline.GetBindGroupLayout(0);
    groupDescriptor.entryCount = 1;
    groupDescriptor.entries = &entry;
    auto group = device->CreateBindGroup(&groupDescriptor);
    auto queue = device->GetQueue();
    queue.WriteBuffer(storage, 0, input.data(), byteCount);
    auto encoder = device->CreateCommandEncoder();
    auto pass = encoder.BeginComputePass();
    pass.SetPipeline(pipeline);
    pass.SetBindGroup(0, group);
    pass.DispatchWorkgroups(1);
    pass.End();
    encoder.CopyBufferToBuffer(storage, 0, readback, 0, byteCount);
    auto commands = encoder.Finish();
    queue.Submit(1, &commands);

    auto mapped = std::make_shared<std::atomic<bool>>(false);
    auto mapFuture = readback.MapAsync(wgpu::MapMode::Read, 0, byteCount, wgpu::CallbackMode::WaitAnyOnly,
            [mapped](wgpu::MapAsyncStatus status, wgpu::StringView message) {
                if (status == wgpu::MapAsyncStatus::Success) mapped->store(true);
                else std::cerr << "Dawn result mapping failed: " << message << '\n';
            });
    await(instance, mapFuture, "WGSL dispatch and readback");
    require(mapped->load(), "Dawn compute readback did not map");
    const auto* result = static_cast<const uint32_t*>(readback.GetConstMappedRange(0, byteCount));
    require(result != nullptr, "Dawn compute readback is unavailable");
    bool correct = true;
    for (size_t i = 0; i < input.size(); ++i) {
        if (result[i] != input[i] + 1u) {
            std::cerr << "WGSL result mismatch at " << i << ": got " << result[i]
                      << ", expected " << input[i] + 1u << '\n';
            correct = false;
        }
    }
    readback.Unmap();
    require(correct, "Downloaded Dawn binary produced incorrect WGSL compute results");
    require(!deviceFailed.load(), "Dawn reported a device error during compute");
    device->Destroy();
    std::cout << "PASS: downloaded Dawn Vulkan WGSL compute and four-word readback\n";
    return 0;
}
} // namespace

int main() {
    try { return smoke(); }
    catch (const std::exception& failure) {
        std::cerr << "FAIL: " << failure.what() << '\n';
        return 1;
    }
}
