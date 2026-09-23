package io.synexia.chromellm.gpu.nativebridge;

import com.sun.jna.Pointer;
import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.PixelGenerator;
import io.synexia.chromellm.gpu.PixelOperation;
import io.synexia.chromellm.gpu.PixelParameters;
import io.synexia.chromellm.gpu.RgbaFrame;

public final class JnaOpenClImageProcessor implements ImageProcessor {
    private final NativeGpuLibrary library;
    private Pointer handle;
    private final String deviceName;
    private final boolean accelerated;

    public JnaOpenClImageProcessor(boolean allowCpuFallback) {
        this(NativeGpuLibrary.INSTANCE, allowCpuFallback);
    }

    JnaOpenClImageProcessor(NativeGpuLibrary library, boolean allowCpuFallback) {
        this.library = library;
        this.handle = library.gpu_create(allowCpuFallback ? 1 : 0);
        if (handle == null) throw new IllegalStateException("OpenCL initialization failed: " + library.gpu_last_error());
        this.deviceName = library.gpu_device_name(handle);
        this.accelerated = library.gpu_hardware_accelerated(handle) != 0;
    }

    @Override
    public RgbaFrame process(RgbaFrame input, PixelOperation operation, PixelParameters p) {
        ensureOpen();
        byte[] src=input.pixels(), dst=new byte[src.length];
        int rc=library.gpu_process_rgba8(handle,operation.nativeCode(),src,dst,input.width(),input.height(),p.p0(),p.p1(),p.p2(),p.p3());
        check(rc,"gpu_process_rgba8");
        return new RgbaFrame(input.width(),input.height(),dst);
    }

    @Override
    public RgbaFrame blend(RgbaFrame base, RgbaFrame overlay, float opacity) {
        ensureOpen();
        if(base.width()!=overlay.width()||base.height()!=overlay.height()) throw new IllegalArgumentException("frame dimensions differ");
        byte[] a=base.pixels(), b=overlay.pixels(), out=new byte[a.length];
        check(library.gpu_blend_rgba8(handle,a,b,out,base.width(),base.height(),opacity),"gpu_blend_rgba8");
        return new RgbaFrame(base.width(),base.height(),out);
    }

    @Override
    public RgbaFrame generate(int width,int height,PixelGenerator generator,long seed,PixelParameters p) {
        ensureOpen();
        byte[] out=new byte[Math.multiplyExact(Math.multiplyExact(width,height),4)];
        check(library.gpu_generate_rgba8(handle,generator.nativeCode(),out,width,height,seed,p.p0(),p.p1(),p.p2(),p.p3()),"gpu_generate_rgba8");
        return new RgbaFrame(width,height,out);
    }

    @Override public String backendName() { return "opencl-jna:" + deviceName; }
    @Override public boolean hardwareAccelerated() { return accelerated; }

    @Override
    public void close() {
        if(handle!=null) {
            library.gpu_destroy(handle);
            handle=null;
        }
    }

    private void ensureOpen() { if(handle==null) throw new IllegalStateException("processor is closed"); }
    private void check(int rc,String operation) {
        if(rc!=0) throw new IllegalStateException(operation+" failed ("+rc+"): "+library.gpu_last_error());
    }
}
