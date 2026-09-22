package io.synexia.chromellm.cli;

import io.synexia.chromellm.api.DiffusionCondition;
import io.synexia.chromellm.api.DiffusionRequest;
import io.synexia.chromellm.api.DiffusionResult;
import io.synexia.chromellm.chrome.ChromeAiConditioningClient;
import io.synexia.chromellm.engine.DiffusionEngine;
import io.synexia.chromellm.engine.JavaDiffusionEngine;
import io.synexia.chromellm.nativebridge.JnaDiffusionEngine;
import io.synexia.chromellm.nativebridge.JniDiffusionEngine;
import java.net.URI;
import java.nio.file.Path;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        Arguments o=Arguments.parse(args);
        DiffusionCondition condition=o.prompt==null?DiffusionCondition.none():new ChromeAiConditioningClient(URI.create(o.chromeBase)).plan(o.prompt);
        var request=new DiffusionRequest(o.width,o.height,3,o.steps,o.seed,o.guidance,o.eta,condition);
        try(DiffusionEngine engine=engine(o.engine)) {
            DiffusionResult result=engine.generate(request);
            PpmWriter.write(result,Path.of(o.output));
            System.out.printf("generated %s using %s seed=%d%n",o.output,result.metadata().get("engine"),result.seed());
        }
    }
    private static DiffusionEngine engine(String name) {
        return switch(name) {
            case "java" -> new JavaDiffusionEngine();
            case "jna" -> new JnaDiffusionEngine();
            case "jni" -> new JniDiffusionEngine();
            default -> throw new IllegalArgumentException("unknown engine: "+name);
        };
    }
    private static final class Arguments {
        String engine="java",output="diffusion.ppm",chromeBase="http://127.0.0.1:11435",prompt;
        int width=256,height=256,steps=40; long seed=1L; float guidance=7.5f,eta=0f;
        static Arguments parse(String[] args) {
            Arguments r=new Arguments();
            for(int i=0;i<args.length;i++) {
                String a=args[i],v=i+1<args.length?args[i+1]:null;
                switch(a) {
                    case "--engine" -> { r.engine=require(a,v); i++; }
                    case "--width" -> { r.width=Integer.parseInt(require(a,v)); i++; }
                    case "--height" -> { r.height=Integer.parseInt(require(a,v)); i++; }
                    case "--steps" -> { r.steps=Integer.parseInt(require(a,v)); i++; }
                    case "--seed" -> { r.seed=Long.parseLong(require(a,v)); i++; }
                    case "--guidance" -> { r.guidance=Float.parseFloat(require(a,v)); i++; }
                    case "--eta" -> { r.eta=Float.parseFloat(require(a,v)); i++; }
                    case "--output" -> { r.output=require(a,v); i++; }
                    case "--prompt" -> { r.prompt=require(a,v); i++; }
                    case "--chrome-base" -> { r.chromeBase=require(a,v); i++; }
                    default -> throw new IllegalArgumentException("unknown argument: "+a);
                }
            }
            return r;
        }
        private static String require(String flag,String value) {
            if(value==null) throw new IllegalArgumentException("missing value for "+flag);
            return value;
        }
    }
}
