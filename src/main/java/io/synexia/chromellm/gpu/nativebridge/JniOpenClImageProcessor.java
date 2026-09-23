package io.synexia.chromellm.gpu.nativebridge;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.PixelGenerator;
import io.synexia.chromellm.gpu.PixelOperation;
import io.synexia.chromellm.gpu.PixelParameters;
import io.synexia.chromellm.gpu.RgbaFrame;

public final class JniOpenClImageProcessor implements ImageProcessor {
    private long handle;
    private final boolean accelerated;
    private final String deviceName;

    public JniOpenClImageProcessor(boolean allowCpuFallback) {
        this.handle=JniGpu.create(allowCpuFallback);
        if(handle==0L) throw new IllegalStateException("OpenCL JNI initialization failed");
        this.accelerated=JniGpu.hardwareAccelerated(handle);
        this.deviceName=JniGpu.deviceName(handle);
    }

    @Override
    public RgbaFrame process(RgbaFrame input, PixelOperation operation, PixelParameters p) {
        ensureOpen();
        return new RgbaFrame(input.width(),input.height(),
                JniGpu.process(handle,operation.nativeCode(),input.pixels(),input.width(),input.height(),p.p0(),p.p1(),p.p2(),p.p3()));
    }

    @Override
    public RgbaFrame blend(RgbaFrame base,RgbaFrame overlay,float opacity) {
        ensureOpen();
        if(base.width()!=overlay.width()||base.height()!=overlay.height()) throw new IllegalArgumentException("frame dimensions differ");
        return new RgbaFrame(base.width(),base.height(),
                JniGpu.blend(handle,base.pixels(),overlay.pixels(),base.width(),base.height(),opacity));
    }

    @Override
    public RgbaFrame generate(int width,int height,PixelGenerator generator,long seed,PixelParameters p) {
        ensureOpen();
        return new RgbaFrame(width,height,JniGpu.generate(handle,generator.nativeCode(),width,height,seed,p.p0(),p.p1(),p.p2(),p.p3()));
    }

    @Override public String backendName() { return "opencl-jni:" + deviceName; }
    @Override public boolean hardwareAccelerated() { return accelerated; }

    @Override
    public void close() {
        if(handle!=0L) {
            JniGpu.destroy(handle);
            handle=0L;
        }
    }

    private void ensureOpen() { if(handle==0L) throw new IllegalStateException("processor is closed"); }
}
