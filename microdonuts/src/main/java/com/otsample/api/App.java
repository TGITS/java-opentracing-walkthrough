package com.otsample.api;

import brave.Tracing;
import brave.opentracing.BraveTracer;
import com.lightstep.tracer.jre.JRETracer;
import com.lightstep.tracer.shared.Options;
import io.jaegertracing.Configuration;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.Reporter;
import zipkin2.reporter.Sender;
import zipkin2.reporter.okhttp3.OkHttpSender;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Properties;

public class App {
    public static void main(String[] args)
            throws Exception {
        //OpenTracing Configuration File
        Properties config = loadConfig(args);
        //Configuration of the Global Tracer
        if (!configureGlobalTracer(config, "MicroDonuts")) {
            throw new Exception("Could not configure the global tracer");
        }

        ResourceHandler filesHandler = new ResourceHandler();
        filesHandler.setWelcomeFiles(new String[]{"./index.html"});
        filesHandler.setResourceBase(config.getProperty("public_directory"));

        ContextHandler fileCtxHandler = new ContextHandler();
        fileCtxHandler.setHandler(filesHandler);

        ContextHandlerCollection handlers = new ContextHandlerCollection();
        handlers.setHandlers(new Handler[]{
                fileCtxHandler,
                new ApiContextHandler(config),
                new KitchenContextHandler(config),
        });
        Server server = new Server(10001);
        server.setHandler(handlers);

        server.start();
        server.dumpStdErr();
        server.join();
    }

    static Properties loadConfig(String[] args)
            throws IOException {
        String file = "tracer_config.properties";
        if (args.length > 0)
            file = args[0];

        FileInputStream fs = new FileInputStream(file);
        Properties config = new Properties();
        config.load(fs);
        return config;
    }

    static boolean configureGlobalTracer(Properties config, String componentName)
            throws MalformedURLException {
        String tracerName = config.getProperty("tracer");
        if ("jaeger".equals(tracerName)) {
            Configuration jaegerConfiguration =
                    new Configuration("MicroDonuts")
                            .withSampler(new Configuration.SamplerConfiguration()
                                    .withType("const")
                                    .withParam(1))
                            .withReporter(new Configuration.ReporterConfiguration()
                                    .withLogSpans(true)
                                    .withFlushInterval(1000)
                                    .withMaxQueueSize(10000)
                                    .withSender(new Configuration.SenderConfiguration()
                                            .withAgentHost(config.getProperty("jaeger.reporter_host"))
                                            .withAgentPort(Integer.decode(config.getProperty("jaeger.reporter_port")))));
            GlobalTracer.registerIfAbsent(jaegerConfiguration.getTracerBuilder().build());
        } else if ("zipkin".equals(tracerName)) {
            Sender sender = OkHttpSender.create("http://" + config.getProperty("zipkin.reporter_host") + ":" + config.getProperty("zipkin.reporter_port") + "/api/v2/spans");
            Reporter reporter = AsyncReporter.builder(sender).build();
            GlobalTracer.registerIfAbsent(BraveTracer.create(Tracing.newBuilder()
                    .localServiceName(componentName)
                    .spanReporter(reporter)
                    .build()));
        } else if ("lightstep".equals(tracerName)) {
            Options opts = new Options.OptionsBuilder()
                    .withAccessToken(config.getProperty("lightstep.access_token"))
                    .withCollectorHost(config.getProperty("lightstep.collector_host"))
                    .withCollectorPort(Integer.decode(config.getProperty("lightstep.collector_port")))
                    .withComponentName(componentName)
                    .build();
            Tracer tracer = new JRETracer(opts);
            GlobalTracer.registerIfAbsent(tracer);
        } else {
            return false;
        }

        return true;
    }
}
