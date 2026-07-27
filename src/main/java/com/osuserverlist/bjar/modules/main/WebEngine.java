package com.osuserverlist.bjar.modules.main;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;

import ch.qos.logback.classic.Logger;
import io.github.classgraph.ClassGraph;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.RequestLogger;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;

public class WebEngine {
    private static final Logger logger = (Logger) LoggerFactory.getLogger(WebEngine.class);
    private static final String HANDLER_PACKAGE = App.MAIN_PACKAGE + "." + "handlers";

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public static @interface Host {
        String[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public static @interface HttpMethod {
        String[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public static @interface Path {
        String value();
    }

    public static class BanchoWebLogger implements RequestLogger {
        @Override
        public void handle(@NotNull Context ctx, @NotNull Float executionTimeMs) throws Exception {
            logger.info(String.format("%s %s - %s (%.2f ms)", ctx.method().toString(), ctx.url(),
                    ctx.status().toString(), executionTimeMs));
        }
    }

    public static void registerDefaultHandlers(JavalinConfig app) {
        registerAnnotatedHandlers(app, HANDLER_PACKAGE);
    }

    public static void registerAnnotatedHandlers(JavalinConfig app, String packageName) {
        Map<RouteKey, List<HostHandler>> routesByPath = new HashMap<>();

        try (var scan = new ClassGraph()
                .enableClassInfo()
                .enableAnnotationInfo()
                .acceptPackages(packageName)
                .scan()) {
            for (var classInfo : scan.getClassesImplementing(Handler.class.getName())) {
                Class<?> handlerClass = classInfo.loadClass();
                Host hostAnnotation = handlerClass.getAnnotation(Host.class);
                HttpMethod methodAnnotation = handlerClass.getAnnotation(HttpMethod.class);
                Path pathAnnotation = handlerClass.getAnnotation(Path.class);

                if (hostAnnotation == null || pathAnnotation == null) {
                    continue;
                }

                Handler handler = instantiateHandler(handlerClass);
                String path = pathAnnotation.value();
                String[] hosts = normalizeHosts(hostAnnotation.value());
                String[] methods = methodAnnotation == null
                        ? new String[] { "GET" }
                        : methodAnnotation.value();

                for (String method : methods) {
                    String normalized = normalizeMethod(method);
                    routesByPath
                            .computeIfAbsent(new RouteKey(normalized, path), key -> new ArrayList<>())
                            .add(new HostHandler(hosts, handler));
                }
            }
        }

        for (var entry : routesByPath.entrySet()) {
            RouteKey key = entry.getKey();
            String path = key.getPath();
            HostHandler[] handlers = entry.getValue().toArray(new HostHandler[0]);

            registerRoute(app, key.getMethod(), path, handlers);
        }
    }

    private static void registerRoute(JavalinConfig app, String method, String path, HostHandler[] handlers) {
        HostRouter router = HostRouter.build(handlers);

        switch (method) {
            case "POST":
                app.routes.post(path, router::dispatch);
                break;
            case "DELETE":
                app.routes.delete(path, router::dispatch);
                break;
            default:
                app.routes.get(path, router::dispatch);
                break;
        }
    }

    private static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return "GET";
        }

        return method.trim().toUpperCase(Locale.ROOT);
    }

    private static String[] normalizeHosts(String[] hosts) {
        String[] normalized = new String[hosts.length];
        for (int i = 0; i < hosts.length; i++) {
            normalized[i] = hosts[i].toLowerCase(Locale.ROOT);
        }
        return normalized;
    }

    private static Handler instantiateHandler(Class<?> handlerClass) {
        try {
            return (Handler) handlerClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                | NoSuchMethodException ex) {
            throw new IllegalStateException("Failed to instantiate handler: " + handlerClass.getName(), ex);
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class HostHandler {
        private final String[] hosts;
        private final Handler handler;
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class HostRouter {
        private final Map<String, Handler> exactHosts;
        private final Map<String, Handler> subdomainHosts;
        private final List<Map.Entry<String, Handler>> prefixWildcards;

        static HostRouter build(HostHandler[] handlers) {
            Map<String, Handler> exact = new HashMap<>();
            Map<String, Handler> subdomain = new HashMap<>();
            List<Map.Entry<String, Handler>> prefixes = new ArrayList<>();

            for (HostHandler hh : handlers) {
                for (String host : hh.hosts) {
                    if (host.endsWith(".")) {
                        prefixes.add(Map.entry(host, hh.handler));
                    } else {
                        exact.putIfAbsent(host, hh.handler);
                        subdomain.putIfAbsent(host, hh.handler);
                    }
                }
            }

            // Longest prefix first, so a more specific wildcard is preferred
            // over a shorter, more general one.
            prefixes.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

            return new HostRouter(exact, subdomain, prefixes);
        }

        private String extractHost(Context ctx) {
            String hostHeader = ctx.header("Host");
            if (hostHeader == null || hostHeader.isEmpty()) {
                return "";
            }

            int colonIndex = hostHeader.indexOf(':');
            String host = colonIndex >= 0 ? hostHeader.substring(0, colonIndex) : hostHeader;
            return host.toLowerCase(Locale.ROOT);
        }

        private String extractSubdomain(String host) {
            if (host.equals(App.server.enviromentConfig.getDomain())) {
                return "";
            }

            String suffix = "." + App.server.enviromentConfig.getDomain();
            if (host.endsWith(suffix)) {
                String remainder = host.substring(0, host.length() - suffix.length());
                int dotIndex = remainder.indexOf('.');
                return dotIndex > 0 ? remainder.substring(0, dotIndex) : remainder;
            }

            return host;
        }

        void dispatch(Context ctx) throws Exception {
            String host = extractHost(ctx);

            Handler handler = exactHosts.get(host);

            if (handler == null) {
                handler = subdomainHosts.get(extractSubdomain(host));
            }

            if (handler == null) {
                for (Map.Entry<String, Handler> entry : prefixWildcards) {
                    String prefix = entry.getKey();
                    if (host.regionMatches(0, prefix, 0, prefix.length())) {
                        handler = entry.getValue();
                        break;
                    }
                }
            }

            if (handler != null) {
                handler.handle(ctx);
            } else {
                ctx.status(404);
            }
        }
    }

    @Value
    private static class RouteKey {
        String method;
        String path;
    }
}
