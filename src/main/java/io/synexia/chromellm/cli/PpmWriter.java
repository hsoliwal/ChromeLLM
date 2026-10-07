package io.synexia.chromellm.cli;
import io.synexia.chromellm.api.DiffusionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
final class PpmWriter {
    private PpmWriter() {}
    static void write(DiffusionResult result, Path path) throws IOException {
        byte[] rgb=result.rgb8();
        byte[] header=("P6\n"+result.width()+" "+result.height()+"\n255\n").getBytes(StandardCharsets.US_ASCII);
        byte[] output=new byte[header.length+rgb.length];
        System.arraycopy(header,0,output,0,header.length);
        System.arraycopy(rgb,0,output,header.length,rgb.length);
        Files.write(path,output);
    }
}
