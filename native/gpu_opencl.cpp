#include "gpu_opencl.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#if defined(_WIN32)
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace {

using cl_int = std::int32_t;
using cl_uint = std::uint32_t;
using cl_ulong = std::uint64_t;
using cl_bool = cl_uint;
using cl_device_type = cl_ulong;
using cl_mem_flags = cl_ulong;
using cl_command_queue_properties = cl_ulong;
using cl_context_properties = std::intptr_t;
using cl_device_info = cl_uint;
using cl_program_build_info = cl_uint;

struct _cl_platform_id;
struct _cl_device_id;
struct _cl_context;
struct _cl_command_queue;
struct _cl_mem;
struct _cl_program;
struct _cl_kernel;
struct _cl_event;

using cl_platform_id = _cl_platform_id*;
using cl_device_id = _cl_device_id*;
using cl_context = _cl_context*;
using cl_command_queue = _cl_command_queue*;
using cl_mem = _cl_mem*;
using cl_program = _cl_program*;
using cl_kernel = _cl_kernel*;
using cl_event = _cl_event*;

constexpr cl_int CL_SUCCESS = 0;
constexpr cl_bool CL_TRUE = 1;
constexpr cl_device_type CL_DEVICE_TYPE_CPU = 1ull << 1;
constexpr cl_device_type CL_DEVICE_TYPE_GPU = 1ull << 2;
constexpr cl_device_type CL_DEVICE_TYPE_ALL = ~0ull;
constexpr cl_mem_flags CL_MEM_READ_ONLY = 1ull << 2;
constexpr cl_mem_flags CL_MEM_WRITE_ONLY = 1ull << 1;
constexpr cl_device_info CL_DEVICE_NAME = 0x102B;
constexpr cl_program_build_info CL_PROGRAM_BUILD_LOG = 0x1183;

#if defined(_WIN32)
using LibraryHandle = HMODULE;
LibraryHandle open_library() {
    return LoadLibraryA("OpenCL.dll");
}
void* load_symbol(LibraryHandle library, const char* name) {
    return reinterpret_cast<void*>(GetProcAddress(library, name));
}
#else
using LibraryHandle = void*;
LibraryHandle open_library() {
    LibraryHandle handle = nullptr;
#if defined(__APPLE__)
    handle = dlopen("/System/Library/Frameworks/OpenCL.framework/OpenCL", RTLD_NOW | RTLD_LOCAL);
    if (handle) return handle;
#endif
    handle = dlopen("libOpenCL.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!handle) handle = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    return handle;
}
void* load_symbol(LibraryHandle library, const char* name) {
    return dlsym(library, name);
}
#endif

using ContextCallback = void (*)(const char*, const void*, std::size_t, void*);
using BuildCallback = void (*)(cl_program, void*);

struct OpenClApi {
    LibraryHandle library = nullptr;

    cl_int (*GetPlatformIDs)(cl_uint, cl_platform_id*, cl_uint*) = nullptr;
    cl_int (*GetDeviceIDs)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*) = nullptr;
    cl_int (*GetDeviceInfo)(cl_device_id, cl_device_info, std::size_t, void*, std::size_t*) = nullptr;
    cl_context (*CreateContext)(const cl_context_properties*, cl_uint, const cl_device_id*, ContextCallback, void*, cl_int*) = nullptr;
    cl_command_queue (*CreateCommandQueue)(cl_context, cl_device_id, cl_command_queue_properties, cl_int*) = nullptr;
    cl_program (*CreateProgramWithSource)(cl_context, cl_uint, const char**, const std::size_t*, cl_int*) = nullptr;
    cl_int (*BuildProgram)(cl_program, cl_uint, const cl_device_id*, const char*, BuildCallback, void*) = nullptr;
    cl_int (*GetProgramBuildInfo)(cl_program, cl_device_id, cl_program_build_info, std::size_t, void*, std::size_t*) = nullptr;
    cl_kernel (*CreateKernel)(cl_program, const char*, cl_int*) = nullptr;
    cl_mem (*CreateBuffer)(cl_context, cl_mem_flags, std::size_t, void*, cl_int*) = nullptr;
    cl_int (*SetKernelArg)(cl_kernel, cl_uint, std::size_t, const void*) = nullptr;
    cl_int (*EnqueueWriteBuffer)(cl_command_queue, cl_mem, cl_bool, std::size_t, std::size_t, const void*, cl_uint, const cl_event*, cl_event*) = nullptr;
    cl_int (*EnqueueReadBuffer)(cl_command_queue, cl_mem, cl_bool, std::size_t, std::size_t, void*, cl_uint, const cl_event*, cl_event*) = nullptr;
    cl_int (*EnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const std::size_t*, const std::size_t*, const std::size_t*, cl_uint, const cl_event*, cl_event*) = nullptr;
    cl_int (*Finish)(cl_command_queue) = nullptr;
    cl_int (*ReleaseMemObject)(cl_mem) = nullptr;
    cl_int (*ReleaseKernel)(cl_kernel) = nullptr;
    cl_int (*ReleaseProgram)(cl_program) = nullptr;
    cl_int (*ReleaseCommandQueue)(cl_command_queue) = nullptr;
    cl_int (*ReleaseContext)(cl_context) = nullptr;

    OpenClApi() {
        library = open_library();
        if (!library) return;
#define LOAD_CL(name) name = reinterpret_cast<decltype(name)>(load_symbol(library, "cl" #name))
        LOAD_CL(GetPlatformIDs);
        LOAD_CL(GetDeviceIDs);
        LOAD_CL(GetDeviceInfo);
        LOAD_CL(CreateContext);
        LOAD_CL(CreateCommandQueue);
        LOAD_CL(CreateProgramWithSource);
        LOAD_CL(BuildProgram);
        LOAD_CL(GetProgramBuildInfo);
        LOAD_CL(CreateKernel);
        LOAD_CL(CreateBuffer);
        LOAD_CL(SetKernelArg);
        LOAD_CL(EnqueueWriteBuffer);
        LOAD_CL(EnqueueReadBuffer);
        LOAD_CL(EnqueueNDRangeKernel);
        LOAD_CL(Finish);
        LOAD_CL(ReleaseMemObject);
        LOAD_CL(ReleaseKernel);
        LOAD_CL(ReleaseProgram);
        LOAD_CL(ReleaseCommandQueue);
        LOAD_CL(ReleaseContext);
#undef LOAD_CL
    }

    bool valid() const {
        return library &&
               GetPlatformIDs && GetDeviceIDs && GetDeviceInfo &&
               CreateContext && CreateCommandQueue &&
               CreateProgramWithSource && BuildProgram && GetProgramBuildInfo &&
               CreateKernel && CreateBuffer && SetKernelArg &&
               EnqueueWriteBuffer && EnqueueReadBuffer && EnqueueNDRangeKernel && Finish &&
               ReleaseMemObject && ReleaseKernel && ReleaseProgram &&
               ReleaseCommandQueue && ReleaseContext;
    }
};

OpenClApi& api() {
    static OpenClApi instance;
    return instance;
}

thread_local std::string last_error;

void set_error(const std::string& message, cl_int code = CL_SUCCESS) {
    if (code == CL_SUCCESS) {
        last_error = message;
        return;
    }
    std::ostringstream out;
    out << message << " (OpenCL " << code << ")";
    last_error = out.str();
}

const char* KERNEL_SOURCE = R"CLC(
inline uchar sat255(float v) {
    return convert_uchar_sat_rte(clamp(v, 0.0f, 255.0f));
}

inline float luma(__global const uchar* src, int width, int height, int x, int y) {
    x = clamp(x, 0, width - 1);
    y = clamp(y, 0, height - 1);
    int i = (y * width + x) * 4;
    return 0.2126f * src[i] + 0.7152f * src[i + 1] + 0.0722f * src[i + 2];
}

__kernel void process_rgba(
        __global const uchar* src,
        __global uchar* dst,
        int width,
        int height,
        int op,
        float p0,
        float p1,
        float p2,
        float p3) {
    int gid = (int)get_global_id(0);
    int pixels = width * height;
    if (gid >= pixels) return;

    int x = gid % width;
    int y = gid / width;
    int i = gid * 4;

    if (op == 0) {
        dst[i]=src[i]; dst[i+1]=src[i+1]; dst[i+2]=src[i+2]; dst[i+3]=src[i+3];
        return;
    }
    if (op == 1) {
        dst[i]=255-src[i]; dst[i+1]=255-src[i+1]; dst[i+2]=255-src[i+2]; dst[i+3]=src[i+3];
        return;
    }
    if (op == 2) {
        uchar g=sat255(luma(src,width,height,x,y));
        dst[i]=g; dst[i+1]=g; dst[i+2]=g; dst[i+3]=src[i+3];
        return;
    }
    if (op == 3) {
        float contrast = p1 == 0.0f ? 1.0f : p1;
        for (int c=0;c<3;c++) {
            float n=(float)src[i+c]/255.0f;
            n=((n-0.5f)*contrast+0.5f)+p0;
            dst[i+c]=sat255(n*255.0f);
        }
        dst[i+3]=src[i+3];
        return;
    }
    if (op == 4) {
        float gamma=fmax(0.01f,p0);
        for (int c=0;c<3;c++) {
            float n=(float)src[i+c]/255.0f;
            dst[i+c]=sat255(pow(n,1.0f/gamma)*255.0f);
        }
        dst[i+3]=src[i+3];
        return;
    }
    if (op == 5) {
        float threshold=p0<=1.0f?p0*255.0f:p0;
        uchar v=luma(src,width,height,x,y)>=threshold?(uchar)255:(uchar)0;
        dst[i]=v; dst[i+1]=v; dst[i+2]=v; dst[i+3]=src[i+3];
        return;
    }
    if (op == 6) {
        dst[i]=sat255((float)src[i]*p0);
        dst[i+1]=sat255((float)src[i+1]*p1);
        dst[i+2]=sat255((float)src[i+2]*p2);
        dst[i+3]=sat255((float)src[i+3]*(p3==0.0f?1.0f:p3));
        return;
    }
    if (op == 7) {
        float gx=-luma(src,width,height,x-1,y-1)+luma(src,width,height,x+1,y-1)
                 -2.0f*luma(src,width,height,x-1,y)+2.0f*luma(src,width,height,x+1,y)
                 -luma(src,width,height,x-1,y+1)+luma(src,width,height,x+1,y+1);
        float gy=-luma(src,width,height,x-1,y-1)-2.0f*luma(src,width,height,x,y-1)
                 -luma(src,width,height,x+1,y-1)+luma(src,width,height,x-1,y+1)
                 +2.0f*luma(src,width,height,x,y+1)+luma(src,width,height,x+1,y+1);
        uchar v=sat255(sqrt(gx*gx+gy*gy));
        dst[i]=v; dst[i+1]=v; dst[i+2]=v; dst[i+3]=src[i+3];
        return;
    }
    if (op == 8) {
        int radius=clamp((int)rint(p0),1,8);
        uint4 sum=(uint4)(0,0,0,0);
        int count=0;
        for (int dy=-radius;dy<=radius;dy++) {
            int yy=clamp(y+dy,0,height-1);
            for (int dx=-radius;dx<=radius;dx++) {
                int xx=clamp(x+dx,0,width-1);
                int si=(yy*width+xx)*4;
                sum+=(uint4)(src[si],src[si+1],src[si+2],src[si+3]);
                count++;
            }
        }
        dst[i]=(uchar)(sum.x/count);
        dst[i+1]=(uchar)(sum.y/count);
        dst[i+2]=(uchar)(sum.z/count);
        dst[i+3]=(uchar)(sum.w/count);
        return;
    }

    dst[i]=src[i]; dst[i+1]=src[i+1]; dst[i+2]=src[i+2]; dst[i+3]=src[i+3];
}

__kernel void blend_rgba(
        __global const uchar* base,
        __global const uchar* overlay,
        __global uchar* dst,
        int pixels,
        float opacity) {
    int gid=(int)get_global_id(0);
    if (gid>=pixels) return;
    int i=gid*4;
    float a=clamp(opacity,0.0f,1.0f)*((float)overlay[i+3]/255.0f);
    for (int c=0;c<3;c++) {
        dst[i+c]=sat255((float)base[i+c]*(1.0f-a)+(float)overlay[i+c]*a);
    }
    float base_alpha=(float)base[i+3]/255.0f;
    float out_alpha=a+base_alpha*(1.0f-a);
    dst[i+3]=sat255(out_alpha*255.0f);
}

inline uint hash32(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

__kernel void generate_rgba(
        __global uchar* dst,
        int width,
        int height,
        int mode,
        ulong seed,
        float p0,
        float p1,
        float p2,
        float p3) {
    int gid=(int)get_global_id(0);
    int pixels=width*height;
    if (gid>=pixels) return;

    int x=gid%width;
    int y=gid/width;
    int i=gid*4;
    float fx=width<=1?0.0f:(float)x/(float)(width-1);
    float fy=height<=1?0.0f:(float)y/(float)(height-1);
    float r=0.0f,g=0.0f,b=0.0f,a=1.0f;

    if (mode==0) {
        r=p0; g=p1; b=p2; a=p3==0.0f?1.0f:p3;
    } else if (mode==1) {
        r=fx; g=fy; b=0.5f*(fx+fy);
    } else if (mode==2) {
        uint n=hash32((uint)gid ^ (uint)seed ^ (uint)(seed>>32));
        r=(float)(n&255u)/255.0f;
        g=(float)((n>>8)&255u)/255.0f;
        b=(float)((n>>16)&255u)/255.0f;
    } else if (mode==3) {
        float t=(float)(seed&65535ul)*0.0001f;
        r=0.5f+0.5f*sin(12.0f*fx+t);
        g=0.5f+0.5f*sin(12.0f*fy+t+2.094f);
        b=0.5f+0.5f*sin(8.0f*(fx+fy)+t+4.188f);
    } else {
        int cell=clamp((int)rint(p0==0.0f?32.0f:p0),2,512);
        float v=(((x/cell)+(y/cell))&1)==0?0.15f:0.85f;
        r=v; g=v; b=v;
    }

    dst[i]=sat255(r*255.0f);
    dst[i+1]=sat255(g*255.0f);
    dst[i+2]=sat255(b*255.0f);
    dst[i+3]=sat255(a*255.0f);
}
)CLC";

struct GpuContext {
    OpenClApi* cl = nullptr;
    cl_device_id device = nullptr;
    cl_context context = nullptr;
    cl_command_queue queue = nullptr;
    cl_program program = nullptr;
    cl_kernel process = nullptr;
    cl_kernel blend = nullptr;
    cl_kernel generate = nullptr;
    bool accelerated = false;
    std::string name;

    ~GpuContext() {
        if (!cl) return;
        if (process) cl->ReleaseKernel(process);
        if (blend) cl->ReleaseKernel(blend);
        if (generate) cl->ReleaseKernel(generate);
        if (program) cl->ReleaseProgram(program);
        if (queue) cl->ReleaseCommandQueue(queue);
        if (context) cl->ReleaseContext(context);
    }
};

bool choose_device(OpenClApi& cl, cl_device_type type, cl_device_id& device) {
    cl_uint platform_count=0;
    if (cl.GetPlatformIDs(0,nullptr,&platform_count)!=CL_SUCCESS || platform_count==0) return false;

    std::vector<cl_platform_id> platforms(platform_count);
    if (cl.GetPlatformIDs(platform_count,platforms.data(),nullptr)!=CL_SUCCESS) return false;

    for (cl_platform_id platform : platforms) {
        cl_uint count=0;
        cl_int rc=cl.GetDeviceIDs(platform,type,0,nullptr,&count);
        if (rc!=CL_SUCCESS || count==0) continue;
        std::vector<cl_device_id> devices(count);
        if (cl.GetDeviceIDs(platform,type,count,devices.data(),nullptr)==CL_SUCCESS) {
            device=devices.front();
            return true;
        }
    }
    return false;
}

std::string query_device_name(OpenClApi& cl, cl_device_id device) {
    std::size_t size=0;
    if (cl.GetDeviceInfo(device,CL_DEVICE_NAME,0,nullptr,&size)!=CL_SUCCESS || size==0) return "unknown";
    std::vector<char> chars(size);
    if (cl.GetDeviceInfo(device,CL_DEVICE_NAME,size,chars.data(),nullptr)!=CL_SUCCESS) return "unknown";
    if (!chars.empty() && chars.back()=='\0') chars.pop_back();
    return std::string(chars.begin(),chars.end());
}

std::string query_build_log(OpenClApi& cl, cl_program program, cl_device_id device) {
    std::size_t size=0;
    cl.GetProgramBuildInfo(program,device,CL_PROGRAM_BUILD_LOG,0,nullptr,&size);
    std::vector<char> chars(size+1,'\0');
    if (size) cl.GetProgramBuildInfo(program,device,CL_PROGRAM_BUILD_LOG,size,chars.data(),nullptr);
    return std::string(chars.data());
}

int set_kernel_arg(GpuContext* ctx, cl_kernel kernel, cl_uint index, std::size_t size, const void* value) {
    cl_int rc=ctx->cl->SetKernelArg(kernel,index,size,value);
    if (rc!=CL_SUCCESS) set_error("clSetKernelArg failed",rc);
    return rc;
}

cl_mem make_buffer(GpuContext* ctx, cl_mem_flags flags, std::size_t size, cl_int& rc) {
    cl_mem buffer=ctx->cl->CreateBuffer(ctx->context,flags,size,nullptr,&rc);
    if (!buffer || rc!=CL_SUCCESS) set_error("clCreateBuffer failed",rc);
    return buffer;
}

int write_buffer(GpuContext* ctx, cl_mem buffer, const void* data, std::size_t size) {
    cl_int rc=ctx->cl->EnqueueWriteBuffer(ctx->queue,buffer,CL_TRUE,0,size,data,0,nullptr,nullptr);
    if (rc!=CL_SUCCESS) set_error("clEnqueueWriteBuffer failed",rc);
    return rc;
}

int read_buffer(GpuContext* ctx, cl_mem buffer, void* data, std::size_t size) {
    cl_int rc=ctx->cl->EnqueueReadBuffer(ctx->queue,buffer,CL_TRUE,0,size,data,0,nullptr,nullptr);
    if (rc!=CL_SUCCESS) {
        set_error("clEnqueueReadBuffer failed",rc);
        return rc;
    }
    rc=ctx->cl->Finish(ctx->queue);
    if (rc!=CL_SUCCESS) set_error("clFinish failed",rc);
    return rc;
}

int launch_1d(GpuContext* ctx, cl_kernel kernel, std::size_t items) {
    std::size_t global=items;
    cl_int rc=ctx->cl->EnqueueNDRangeKernel(ctx->queue,kernel,1,nullptr,&global,nullptr,0,nullptr,nullptr);
    if (rc!=CL_SUCCESS) set_error("clEnqueueNDRangeKernel failed",rc);
    return rc;
}

} // namespace

extern "C" {

void* gpu_create(int allow_cpu_fallback) {
    last_error.clear();

    OpenClApi& cl=api();
    if (!cl.valid()) {
        set_error("OpenCL runtime was not found or is missing required symbols");
        return nullptr;
    }

    cl_device_id device=nullptr;
    bool gpu=choose_device(cl,CL_DEVICE_TYPE_GPU,device);
    if (!gpu && allow_cpu_fallback) {
        if (!choose_device(cl,CL_DEVICE_TYPE_CPU,device)) {
            choose_device(cl,CL_DEVICE_TYPE_ALL,device);
        }
    }

    if (!device) {
        set_error(allow_cpu_fallback ? "No OpenCL device found" : "No OpenCL GPU device found");
        return nullptr;
    }

    auto* ctx=new GpuContext();
    ctx->cl=&cl;
    ctx->device=device;
    ctx->accelerated=gpu;
    ctx->name=query_device_name(cl,device);

    cl_int rc=CL_SUCCESS;
    ctx->context=cl.CreateContext(nullptr,1,&device,nullptr,nullptr,&rc);
    if (!ctx->context || rc!=CL_SUCCESS) {
        set_error("clCreateContext failed",rc);
        delete ctx;
        return nullptr;
    }

    ctx->queue=cl.CreateCommandQueue(ctx->context,device,0,&rc);
    if (!ctx->queue || rc!=CL_SUCCESS) {
        set_error("clCreateCommandQueue failed",rc);
        delete ctx;
        return nullptr;
    }

    const char* source=KERNEL_SOURCE;
    std::size_t source_length=std::strlen(source);
    ctx->program=cl.CreateProgramWithSource(ctx->context,1,&source,&source_length,&rc);
    if (!ctx->program || rc!=CL_SUCCESS) {
        set_error("clCreateProgramWithSource failed",rc);
        delete ctx;
        return nullptr;
    }

    rc=cl.BuildProgram(ctx->program,1,&device,nullptr,nullptr,nullptr);
    if (rc!=CL_SUCCESS) {
        set_error("OpenCL kernel build failed: "+query_build_log(cl,ctx->program,device),rc);
        delete ctx;
        return nullptr;
    }

    ctx->process=cl.CreateKernel(ctx->program,"process_rgba",&rc);
    if (!ctx->process || rc!=CL_SUCCESS) {
        set_error("clCreateKernel(process_rgba) failed",rc);
        delete ctx;
        return nullptr;
    }

    ctx->blend=cl.CreateKernel(ctx->program,"blend_rgba",&rc);
    if (!ctx->blend || rc!=CL_SUCCESS) {
        set_error("clCreateKernel(blend_rgba) failed",rc);
        delete ctx;
        return nullptr;
    }

    ctx->generate=cl.CreateKernel(ctx->program,"generate_rgba",&rc);
    if (!ctx->generate || rc!=CL_SUCCESS) {
        set_error("clCreateKernel(generate_rgba) failed",rc);
        delete ctx;
        return nullptr;
    }

    return ctx;
}

int gpu_hardware_accelerated(void* handle) {
    auto* ctx=static_cast<GpuContext*>(handle);
    return ctx && ctx->accelerated ? 1 : 0;
}

const char* gpu_device_name(void* handle) {
    auto* ctx=static_cast<GpuContext*>(handle);
    return ctx ? ctx->name.c_str() : "";
}

int gpu_process_rgba8(
        void* handle,
        int operation,
        const uint8_t* input,
        uint8_t* output,
        int width,
        int height,
        float p0,
        float p1,
        float p2,
        float p3) {
    last_error.clear();
    auto* ctx=static_cast<GpuContext*>(handle);
    if (!ctx || !input || !output || width<=0 || height<=0) {
        set_error("invalid gpu_process_rgba8 arguments");
        return -1;
    }

    std::size_t pixels=static_cast<std::size_t>(width)*static_cast<std::size_t>(height);
    std::size_t bytes=pixels*4u;
    cl_int rc=CL_SUCCESS;
    cl_mem src=make_buffer(ctx,CL_MEM_READ_ONLY,bytes,rc);
    if (!src) return rc;
    cl_mem dst=make_buffer(ctx,CL_MEM_WRITE_ONLY,bytes,rc);
    if (!dst) {
        ctx->cl->ReleaseMemObject(src);
        return rc;
    }

    int result=0;
    if ((rc=write_buffer(ctx,src,input,bytes))!=CL_SUCCESS) result=rc;
    if (!result && set_kernel_arg(ctx,ctx->process,0,sizeof(src),&src)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->process,1,sizeof(dst),&dst)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->process,2,sizeof(width),&width)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->process,3,sizeof(height),&height)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->process,4,sizeof(operation),&operation)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->process,5,sizeof(p0),&p0)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->process,6,sizeof(p1),&p1)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->process,7,sizeof(p2),&p2)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->process,8,sizeof(p3),&p3)!=CL_SUCCESS) result=-2;
    if (!result && (rc=launch_1d(ctx,ctx->process,pixels))!=CL_SUCCESS) result=rc;
    if (!result && (rc=read_buffer(ctx,dst,output,bytes))!=CL_SUCCESS) result=rc;

    ctx->cl->ReleaseMemObject(dst);
    ctx->cl->ReleaseMemObject(src);
    return result;
}

int gpu_blend_rgba8(
        void* handle,
        const uint8_t* base,
        const uint8_t* overlay,
        uint8_t* output,
        int width,
        int height,
        float opacity) {
    last_error.clear();
    auto* ctx=static_cast<GpuContext*>(handle);
    if (!ctx || !base || !overlay || !output || width<=0 || height<=0) {
        set_error("invalid gpu_blend_rgba8 arguments");
        return -1;
    }

    int pixels=width*height;
    std::size_t bytes=static_cast<std::size_t>(pixels)*4u;
    cl_int rc=CL_SUCCESS;
    cl_mem a=make_buffer(ctx,CL_MEM_READ_ONLY,bytes,rc);
    if (!a) return rc;
    cl_mem b=make_buffer(ctx,CL_MEM_READ_ONLY,bytes,rc);
    if (!b) { ctx->cl->ReleaseMemObject(a); return rc; }
    cl_mem dst=make_buffer(ctx,CL_MEM_WRITE_ONLY,bytes,rc);
    if (!dst) { ctx->cl->ReleaseMemObject(b); ctx->cl->ReleaseMemObject(a); return rc; }

    int result=0;
    if ((rc=write_buffer(ctx,a,base,bytes))!=CL_SUCCESS) result=rc;
    if (!result && (rc=write_buffer(ctx,b,overlay,bytes))!=CL_SUCCESS) result=rc;
    if (!result && set_kernel_arg(ctx,ctx->blend,0,sizeof(a),&a)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->blend,1,sizeof(b),&b)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->blend,2,sizeof(dst),&dst)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->blend,3,sizeof(pixels),&pixels)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->blend,4,sizeof(opacity),&opacity)!=CL_SUCCESS) result=-2;
    if (!result && (rc=launch_1d(ctx,ctx->blend,static_cast<std::size_t>(pixels)))!=CL_SUCCESS) result=rc;
    if (!result && (rc=read_buffer(ctx,dst,output,bytes))!=CL_SUCCESS) result=rc;

    ctx->cl->ReleaseMemObject(dst);
    ctx->cl->ReleaseMemObject(b);
    ctx->cl->ReleaseMemObject(a);
    return result;
}

int gpu_generate_rgba8(
        void* handle,
        int generator,
        uint8_t* output,
        int width,
        int height,
        int64_t seed,
        float p0,
        float p1,
        float p2,
        float p3) {
    last_error.clear();
    auto* ctx=static_cast<GpuContext*>(handle);
    if (!ctx || !output || width<=0 || height<=0) {
        set_error("invalid gpu_generate_rgba8 arguments");
        return -1;
    }

    int pixels=width*height;
    std::size_t bytes=static_cast<std::size_t>(pixels)*4u;
    cl_int rc=CL_SUCCESS;
    cl_mem dst=make_buffer(ctx,CL_MEM_WRITE_ONLY,bytes,rc);
    if (!dst) return rc;

    cl_ulong native_seed=static_cast<cl_ulong>(seed);
    int result=0;
    if (set_kernel_arg(ctx,ctx->generate,0,sizeof(dst),&dst)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->generate,1,sizeof(width),&width)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->generate,2,sizeof(height),&height)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->generate,3,sizeof(generator),&generator)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->generate,4,sizeof(native_seed),&native_seed)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->generate,5,sizeof(p0),&p0)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->generate,6,sizeof(p1),&p1)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->generate,7,sizeof(p2),&p2)!=CL_SUCCESS) result=-2;
    if (!result && set_kernel_arg(ctx,ctx->generate,8,sizeof(p3),&p3)!=CL_SUCCESS) result=-2;
    if (!result && (rc=launch_1d(ctx,ctx->generate,static_cast<std::size_t>(pixels)))!=CL_SUCCESS) result=rc;
    if (!result && (rc=read_buffer(ctx,dst,output,bytes))!=CL_SUCCESS) result=rc;

    ctx->cl->ReleaseMemObject(dst);
    return result;
}

void gpu_destroy(void* handle) {
    delete static_cast<GpuContext*>(handle);
}

const char* gpu_last_error(void) {
    return last_error.c_str();
}

} // extern "C"
