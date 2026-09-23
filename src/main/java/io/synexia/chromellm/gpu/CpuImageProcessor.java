package io.synexia.chromellm.gpu;

public final class CpuImageProcessor implements ImageProcessor {
    @Override
    public RgbaFrame process(RgbaFrame input, PixelOperation operation, PixelParameters p) {
        byte[] src = input.pixels();
        byte[] dst = new byte[src.length];
        int width = input.width();
        int height = input.height();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                switch (operation) {
                    case COPY -> copy(src, dst, i);
                    case INVERT -> invert(src, dst, i);
                    case GRAYSCALE -> grayscale(src, dst, i);
                    case BRIGHTNESS_CONTRAST -> brightnessContrast(src, dst, i, p.p0(), p.p1());
                    case GAMMA -> gamma(src, dst, i, p.p0());
                    case THRESHOLD -> threshold(src, dst, i, p.p0());
                    case CHANNEL_SCALE -> channelScale(src, dst, i, p);
                    case SOBEL_EDGE -> sobel(src, dst, width, height, x, y);
                    case BOX_BLUR -> blur(src, dst, width, height, x, y, Math.max(1, Math.min(8, Math.round(p.p0()))));
                }
            }
        }
        return new RgbaFrame(width, height, dst);
    }

    @Override
    public RgbaFrame blend(RgbaFrame base, RgbaFrame overlay, float opacity) {
        sameDimensions(base, overlay);
        float opacityValue = clamp01(opacity);
        byte[] left = base.pixels();
        byte[] right = overlay.pixels();
        byte[] out = new byte[left.length];
        for (int i = 0; i < out.length; i += 4) {
            float overlayAlpha = (unsigned(right[i + 3]) / 255f) * opacityValue;
            for (int c = 0; c < 3; c++) {
                out[i + c] = (byte)Math.round(unsigned(left[i + c]) * (1f - overlayAlpha)
                        + unsigned(right[i + c]) * overlayAlpha);
            }
            float baseAlpha = unsigned(left[i + 3]) / 255f;
            float outAlpha = overlayAlpha + baseAlpha * (1f - overlayAlpha);
            out[i + 3] = toByte(outAlpha * 255f);
        }
        return new RgbaFrame(base.width(), base.height(), out);
    }

    @Override
    public RgbaFrame generate(int width, int height, PixelGenerator generator, long seed, PixelParameters p) {
        byte[] out = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                generatePixel(out, i, x, y, width, height, generator, seed, p);
            }
        }
        return new RgbaFrame(width, height, out);
    }

    @Override public String backendName() { return "cpu-java"; }
    @Override public boolean hardwareAccelerated() { return false; }

    private static void copy(byte[] s, byte[] d, int i) {
        d[i] = s[i]; d[i + 1] = s[i + 1]; d[i + 2] = s[i + 2]; d[i + 3] = s[i + 3];
    }

    private static void invert(byte[] s, byte[] d, int i) {
        d[i] = (byte)(255 - unsigned(s[i]));
        d[i + 1] = (byte)(255 - unsigned(s[i + 1]));
        d[i + 2] = (byte)(255 - unsigned(s[i + 2]));
        d[i + 3] = s[i + 3];
    }

    private static void grayscale(byte[] s, byte[] d, int i) {
        int gray = Math.round(0.2126f * unsigned(s[i]) + 0.7152f * unsigned(s[i + 1]) + 0.0722f * unsigned(s[i + 2]));
        d[i] = d[i + 1] = d[i + 2] = (byte)gray; d[i + 3] = s[i + 3];
    }

    private static void brightnessContrast(byte[] s, byte[] d, int i, float brightness, float contrast) {
        float c = contrast == 0f ? 1f : contrast;
        for (int ch = 0; ch < 3; ch++) {
            float n = unsigned(s[i + ch]) / 255f;
            n = ((n - 0.5f) * c + 0.5f) + brightness;
            d[i + ch] = toByte(n * 255f);
        }
        d[i + 3] = s[i + 3];
    }

    private static void gamma(byte[] s, byte[] d, int i, float gamma) {
        float g = Math.max(0.01f, gamma);
        for (int ch = 0; ch < 3; ch++) {
            float n = unsigned(s[i + ch]) / 255f;
            d[i + ch] = toByte((float)Math.pow(n, 1f / g) * 255f);
        }
        d[i + 3] = s[i + 3];
    }

    private static void threshold(byte[] s, byte[] d, int i, float threshold) {
        float t = threshold <= 1f ? threshold * 255f : threshold;
        float l = 0.2126f * unsigned(s[i]) + 0.7152f * unsigned(s[i + 1]) + 0.0722f * unsigned(s[i + 2]);
        byte v = (byte)(l >= t ? 255 : 0);
        d[i] = d[i + 1] = d[i + 2] = v; d[i + 3] = s[i + 3];
    }

    private static void channelScale(byte[] s, byte[] d, int i, PixelParameters p) {
        d[i] = toByte(unsigned(s[i]) * p.p0());
        d[i + 1] = toByte(unsigned(s[i + 1]) * p.p1());
        d[i + 2] = toByte(unsigned(s[i + 2]) * p.p2());
        d[i + 3] = toByte(unsigned(s[i + 3]) * (p.p3() == 0f ? 1f : p.p3()));
    }

    private static void sobel(byte[] s, byte[] d, int w, int h, int x, int y) {
        float gx = -luma(s,w,h,x-1,y-1)+luma(s,w,h,x+1,y-1)-2*luma(s,w,h,x-1,y)+2*luma(s,w,h,x+1,y)-luma(s,w,h,x-1,y+1)+luma(s,w,h,x+1,y+1);
        float gy = -luma(s,w,h,x-1,y-1)-2*luma(s,w,h,x,y-1)-luma(s,w,h,x+1,y-1)+luma(s,w,h,x-1,y+1)+2*luma(s,w,h,x,y+1)+luma(s,w,h,x+1,y+1);
        byte v = toByte((float)Math.sqrt(gx * gx + gy * gy));
        int i=(y*w+x)*4; d[i]=d[i+1]=d[i+2]=v; d[i+3]=s[i+3];
    }

    private static void blur(byte[] s, byte[] d, int w, int h, int x, int y, int radius) {
        int[] sum = new int[4]; int count = 0;
        for(int dy=-radius;dy<=radius;dy++) for(int dx=-radius;dx<=radius;dx++) {
            int xx=Math.max(0,Math.min(w-1,x+dx)), yy=Math.max(0,Math.min(h-1,y+dy));
            int i=(yy*w+xx)*4;
            for(int c=0;c<4;c++) sum[c]+=unsigned(s[i+c]);
            count++;
        }
        int o=(y*w+x)*4;
        for(int c=0;c<4;c++) d[o+c]=(byte)(sum[c]/count);
    }

    private static float luma(byte[] s,int w,int h,int x,int y) {
        int xx=Math.max(0,Math.min(w-1,x)), yy=Math.max(0,Math.min(h-1,y)), i=(yy*w+xx)*4;
        return 0.2126f*unsigned(s[i])+0.7152f*unsigned(s[i+1])+0.0722f*unsigned(s[i+2]);
    }

    private static void generatePixel(byte[] out,int i,int x,int y,int w,int h,PixelGenerator g,long seed,PixelParameters p) {
        float fx=w<=1?0f:(float)x/(w-1), fy=h<=1?0f:(float)y/(h-1);
        switch(g) {
            case SOLID -> set(out,i,p.p0(),p.p1(),p.p2(),p.p3()==0f?1f:p.p3());
            case GRADIENT -> set(out,i,fx,fy,0.5f*(fx+fy),1f);
            case NOISE -> {
                int n=hash(x,y,seed); float r=(n&255)/255f, gg=((n>>>8)&255)/255f, b=((n>>>16)&255)/255f;
                set(out,i,r,gg,b,1f);
            }
            case PLASMA -> {
                double t=(seed&0xffffL)*0.0001;
                float r=(float)(0.5+0.5*Math.sin(12*fx+t));
                float gg=(float)(0.5+0.5*Math.sin(12*fy+t+2.094));
                float b=(float)(0.5+0.5*Math.sin(8*(fx+fy)+t+4.188));
                set(out,i,r,gg,b,1f);
            }
            case CHECKERBOARD -> {
                int cell=Math.max(2,Math.round(p.p0()==0f?32f:p.p0()));
                float v=(((x/cell)+(y/cell))&1)==0?0.15f:0.85f;
                set(out,i,v,v,v,1f);
            }
        }
    }

    private static int hash(int x,int y,long seed) {
        long z=seed ^ (x*0x9E3779B97F4A7C15L) ^ (y*0xC2B2AE3D27D4EB4FL);
        z^=z>>>33; z*=0xff51afd7ed558ccdL; z^=z>>>33; z*=0xc4ceb9fe1a85ec53L; z^=z>>>33;
        return (int)z;
    }

    private static void set(byte[] out,int i,float r,float g,float b,float a) {
        out[i]=toByte(r*255f); out[i+1]=toByte(g*255f); out[i+2]=toByte(b*255f); out[i+3]=toByte(a*255f);
    }

    private static void sameDimensions(RgbaFrame a,RgbaFrame b) {
        if(a.width()!=b.width()||a.height()!=b.height()) throw new IllegalArgumentException("frame dimensions differ");
    }
    private static int unsigned(byte b) { return b & 0xff; }
    private static float clamp01(float v) { return Math.max(0f,Math.min(1f,v)); }
    private static byte toByte(float v) { return (byte)Math.round(Math.max(0f,Math.min(255f,v))); }
}
