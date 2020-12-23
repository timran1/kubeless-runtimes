/*
Copyright (c) 2016-2017 Bitnami

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.kubeless;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Headers;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ThreadFactory;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import io.kubeless.Event;
import io.kubeless.Context;
import org.apache.log4j.Logger;
import org.apache.log4j.BasicConfigurator;

public class Handler {

    static String className   = System.getenv("MOD_NAME");
    static String methodName  = System.getenv("FUNC_HANDLER");
    static String timeout     = System.getenv("FUNC_TIMEOUT");
    static String runtime     = System.getenv("FUNC_RUNTIME");
    static String memoryLimit = System.getenv("FUNC_MEMORY_LIMIT");
    static Method method;
    static Object obj;
    static Logger logger = Logger.getLogger(Handler.class.getName());

    static final Counter requests = Counter.build().name("function_calls_total").help("Total function calls.").register();
    static final Counter failures = Counter.build().name("function_failures_total").help("Total function call failuress.").register();
    static final Histogram requestLatency = Histogram.build().name("function_duration_seconds").help("Duration of time user function ran in seconds.").register();

    public static void main(String[] args) {

        BasicConfigurator.configure();

        String funcPort = System.getenv("FUNC_PORT");
        if(funcPort == null || funcPort.isEmpty()) {
            funcPort = "8080";
        }
        int port = Integer.parseInt(funcPort);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new FunctionHandler());
            server.createContext("/healthz", new HealthHandler());
            server.createContext("/metrics", new HTTPMetricHandler());
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(50));
            server.start();

            Class<?> c = Class.forName("io.kubeless."+className);
            obj = c.newInstance();
            method = c.getMethod(methodName, io.kubeless.Event.class, io.kubeless.Context.class);
        } catch (Exception e) {
            failures.inc();
            if (e instanceof ClassNotFoundException) {
                logger.error("Class: " + className + " not found");
            } else if (e instanceof NoSuchMethodException) {
                logger.error("Method: " + methodName + " not found");
            } else if (e instanceof java.io.IOException) {
                logger.error("Failed to starting listener.");
            } else {
                logger.error("An exception occured running Class: " + className + " method: " + methodName);
                e.printStackTrace();
            }
        }
    }

    static class FunctionHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange he) throws IOException {
            Histogram.Timer requestTimer = requestLatency.startTimer();
            try {
                requests.inc();

                InputStreamReader reader = new InputStreamReader(he.getRequestBody(), StandardCharsets.UTF_8.name());
                BufferedReader br = new BufferedReader(reader);
                String requestBody = br.lines().collect(Collectors.joining());
                br.close();
                reader.close();

                Headers headers = he.getRequestHeaders();
                String eventId  = getEventId(headers);
                String eventType = getEventType(headers);
                String eventTime = getEventTime(headers);
                String eventNamespace = getEventNamespace(headers);

                Event event = new Event(requestBody, eventId, eventType, eventTime, eventNamespace);
                Context context = new Context(methodName, timeout, runtime, memoryLimit);

                Object returnValue = Handler.method.invoke(Handler.obj, event, context);
                String response = (String)returnValue;
                logger.info("Response: " + response);
                he.sendResponseHeaders(200, response.getBytes().length);
		OutputStream os = he.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } catch (Exception e) {
                failures.inc();
                if (e instanceof ClassNotFoundException) {
                    logger.error("Class: " + className + " not found");
                } else if (e instanceof NoSuchMethodException) {
                    logger.error("Method: " + methodName + " not found");
                } else if (e instanceof InvocationTargetException) {
                    logger.error("Failed to Invoke Method: " + methodName);
                    logger.error(e.getCause());
                } else if (e instanceof InstantiationException) {
                    logger.error("Failed to instantiate method: " + methodName);
                } else {
                    logger.error("An exception occured running Class: " + className + " method: " + methodName);
                    e.printStackTrace();
                }
                String response = "Error: 500 Internal Server Error";
                logger.info("Response: " + response);
                he.sendResponseHeaders(500, response.length());
                OutputStream os = he.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } finally {
                requestTimer.observeDuration();
            }
        }

        private String getEventType(Headers headers) {
            if (headers.containsKey("event-type")) {
                List<String> values = headers.get("event-type");
                if (values != null) {
                    return values.get(0);
                }
            }
            return "";
        }

        private String getEventTime(Headers headers) {
            if (headers.containsKey("event-time")) {
                List<String> values = headers.get("event-time");
                if (values != null) {
                    return values.get(0);
                }
            }
            return "";
        }

        private String getEventNamespace(Headers headers) {
            if (headers.containsKey("event-namespace")) {
                List<String> values = headers.get("event-namespace");
                if (values != null) {
                    return values.get(0);
                }
            }
            return "";
        }

        private String getEventId(Headers headers) {
            if (headers.containsKey("event-id")) {
                List<String> values = headers.get("event-id");
                if (values != null) {
                    return values.get(0);
                }
            }
            return "";
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = "OK";
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
    private static class LocalByteArray extends ThreadLocal<ByteArrayOutputStream>{
      private LocalByteArray() {}
      protected ByteArrayOutputStream initialValue() { return new ByteArrayOutputStream(1048576); }
    }
    static class HTTPMetricHandler implements HttpHandler {
        private final Handler.LocalByteArray response = new Handler.LocalByteArray();
        private CollectorRegistry registry = CollectorRegistry.defaultRegistry;
        @Override
        public void handle(HttpExchange t) throws IOException {
            String query = t.getRequestURI().getRawQuery();

            ByteArrayOutputStream response = new Handler.LocalByteArray().get();
            response.reset();
            OutputStreamWriter osw = new OutputStreamWriter(response);
            TextFormat.write004(osw, this.registry
               .filteredMetricFamilySamples(Handler.parseQuery(query)));
            
            osw.flush();
            osw.close();
            response.flush();
            response.close();
      
            t.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            
            t.getResponseHeaders().set("Content-Length", 
                String.valueOf(response.size()));
            t.sendResponseHeaders(200, response.size());
            response.writeTo(t.getResponseBody());
             
            t.close();
       }
    }
    protected static Set<String> parseQuery(String query) throws IOException {
      Set<String> names = new HashSet<String>();
      if (query != null) {
        String[] pairs = query.split("&");
        for (String pair : pairs) {
          int idx = pair.indexOf("=");
          if (idx != -1 && URLDecoder.decode(pair.substring(0, idx), "UTF-8").equals("name[]")) {
            names.add(URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
          }
        } 
      } 
      return names;
    }
}

