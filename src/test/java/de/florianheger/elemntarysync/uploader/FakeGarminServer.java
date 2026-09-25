package de.florianheger.elemntarysync.uploader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Local HTTP server standing in for sso, diauth and connectapi. Responses are queued per path. */
class FakeGarminServer implements AutoCloseable {

    record Request(String method, String path, String query, Headers headers, byte[] body) {
        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String header(String name) {
            return headers.getFirst(name);
        }
    }

    record Response(int status, String body, Map<String, String> headers) {}

    final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Deque<Response>> queued = new HashMap<>();
    private final HttpServer server;

    FakeGarminServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    GarminEndpoints endpoints() {
        String base = "http://localhost:" + server.getAddress().getPort();
        return new GarminEndpoints(base, base, base);
    }

    FakeGarminServer enqueue(String path, int status, String body) {
        return enqueue(path, status, body, Map.of());
    }

    synchronized FakeGarminServer enqueue(String path, int status, String body, Map<String, String> headers) {
        queued.computeIfAbsent(path, key -> new ArrayDeque<>()).add(new Response(status, body, headers));
        return this;
    }

    List<Request> requests(String path) {
        synchronized (requests) {
            return requests.stream().filter(request -> request.path().equals(path)).toList();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders(), exchange.getRequestBody().readAllBytes()));
        Response response;
        synchronized (this) {
            Deque<Response> queue = queued.get(path);
            response = queue == null || queue.isEmpty() ? new Response(404, "no response queued for " + path, Map.of())
                    : queue.poll();
        }
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
