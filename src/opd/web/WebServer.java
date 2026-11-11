package opd.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import opd.service.ApiException;
import opd.service.OPDService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Built-in HTTP server: serves the web UI from ./web and a small JSON API. */
public class WebServer {

    private final OPDService svc;
    private final Path webDir;
    private HttpServer server;
    private int port;

    public WebServer(OPDService svc, Path webDir) {
        this.svc = svc;
        this.webDir = webDir.toAbsolutePath().normalize();
    }

    public int start(int firstPort) throws IOException {
        IOException last = null;
        for (int p = firstPort; p < firstPort + 20; p++) {
            try {
                server = HttpServer.create(new InetSocketAddress(p), 0);
                port = p;
                break;
            } catch (IOException e) { last = e; }
        }
        if (server == null) throw last;
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(6));
        server.start();
        return port;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, ex.getRequestMethod(), path);
            else staticFile(ex, path);
        } catch (ApiException e) {
            json(ex, e.status, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            json(ex, 400, error("Bad request: " + e.getMessage()));
        } catch (Throwable t) {
            t.printStackTrace();
            json(ex, 500, error("Server error: " + t));
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------- API
    private void api(HttpExchange ex, String method, String path) throws IOException {
        String[] seg = path.substring(5).split("/");        // after "/api/"
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String res = seg[0];

        if (res.equals("state") && method.equals("GET")) { json(ex, 200, svc.state()); return; }
        if (res.equals("log") && method.equals("GET")) { json(ex, 200, svc.logs(100)); return; }
        if (res.equals("history") && method.equals("GET")) { json(ex, 200, svc.history(100)); return; }
        if (res.equals("search") && method.equals("GET")) {
            json(ex, 200, svc.search(query(ex, "q"))); return;
        }
        if (res.equals("demo") && method.equals("POST")) { json(ex, 200, svc.demo()); return; }
        if (res.equals("reset") && method.equals("POST")) { json(ex, 200, svc.reset()); return; }

        if (res.equals("doctors")) {
            if (seg.length == 1 && method.equals("GET")) { json(ex, 200, svc.doctors()); return; }
            if (seg.length == 1 && method.equals("POST")) { json(ex, 201, svc.addDoctor(Json.parseObject(body))); return; }
            if (seg.length == 3 && seg[2].equals("call") && method.equals("POST")) {
                json(ex, 200, svc.callNext(num(seg[1]))); return;
            }
            if (seg.length == 3 && seg[2].equals("next") && method.equals("GET")) {
                json(ex, 200, svc.nextPatient(num(seg[1]))); return;
            }
        }
        if (res.equals("patients")) {
            if (seg.length == 1 && method.equals("POST")) { json(ex, 201, svc.register(Json.parseObject(body))); return; }
            if (seg.length == 2 && method.equals("PUT")) { json(ex, 200, svc.update(num(seg[1]), Json.parseObject(body))); return; }
            if (seg.length == 2 && method.equals("DELETE")) { json(ex, 200, svc.cancel(num(seg[1]))); return; }
        }
        throw new ApiException(404, "Unknown API route: " + method + " " + path);
    }

    private static int num(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { throw new ApiException(400, "Invalid number in URL."); }
    }

    private static String query(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return "";
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            if (k.equals(key)) {
                String v = eq < 0 ? "" : part.substring(eq + 1);
                return URLDecoder.decode(v, StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    private void json(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] b = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    // ---------------------------------------------------------- static files
    private void staticFile(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        Path f = webDir.resolve(path.substring(1)).normalize();
        if (!f.startsWith(webDir) || !Files.isRegularFile(f)) {
            byte[] b = "404 - not found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            return;
        }
        String name = f.getFileName().toString().toLowerCase();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".png") ? "image/png" : "application/octet-stream";
        byte[] b = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    public static Path findWebDir() {
        Path[] candidates = { Paths.get("web"), Paths.get("..", "web"), Paths.get("HospitalOPD-Pro", "web") };
        for (Path c : candidates)
            if (Files.isRegularFile(c.resolve("index.html"))) return c;
        return Paths.get("web");
    }
}
