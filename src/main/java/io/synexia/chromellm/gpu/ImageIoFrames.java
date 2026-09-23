package io.synexia.chromellm.gpu;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public final class ImageIoFrames {
    private ImageIoFrames() {}

    public static RgbaFrame read(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) throw new IOException("Unsupported image: " + path);
        int w=image.getWidth(), h=image.getHeight();
        byte[] rgba=new byte[w*h*4];
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int argb=image.getRGB(x,y), i=(y*w+x)*4;
            rgba[i]=(byte)((argb>>>16)&255);
            rgba[i+1]=(byte)((argb>>>8)&255);
            rgba[i+2]=(byte)(argb&255);
            rgba[i+3]=(byte)((argb>>>24)&255);
        }
        return new RgbaFrame(w,h,rgba);
    }

    public static void write(RgbaFrame frame, Path path) throws IOException {
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        String format=name.endsWith(".jpg")||name.endsWith(".jpeg")?"jpg":"png";
        int type=format.equals("jpg")?BufferedImage.TYPE_INT_RGB:BufferedImage.TYPE_INT_ARGB;
        BufferedImage image=new BufferedImage(frame.width(),frame.height(),type);
        byte[] rgba=frame.pixels();
        for(int y=0;y<frame.height();y++) for(int x=0;x<frame.width();x++) {
            int i=(y*frame.width()+x)*4;
            int a=format.equals("jpg")?255:(rgba[i+3]&255);
            int argb=(a<<24)|((rgba[i]&255)<<16)|((rgba[i+1]&255)<<8)|(rgba[i+2]&255);
            image.setRGB(x,y,argb);
        }
        if(!ImageIO.write(image,format,path.toFile())) throw new IOException("No ImageIO writer for "+format);
    }
}
