import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HolosenseGlobalServer {

    static Set<String> cache = new HashSet<>();
    static boolean ONLINE_MODE = true;

    public static void main(String[] args) throws Exception {
        loadCache();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/verifyGlobal", ex -> {
            Map<String, String> q = queryToMap(ex.getRequestURI().getQuery());
            String addr = q.getOrDefault("address", "").trim();

            boolean valid = false;
            String reason = "NO_MATCH";
            double confidence = 0.0;

            if (addr.length() < 6) {
                valid = false;
                reason = "TOO_SHORT";
            } else if (cache.contains(normalize(addr))) {
                valid = true;
                reason = "OFFLINE_CACHE_MATCH";
                confidence = 0.9;
            } else if (ONLINE_MODE) {
                OSMResult r = checkOSM_STRICT(addr);
                valid = r.valid;
                confidence = r.confidence;
                reason = r.reason;
                if (valid) saveToCache(normalize(addr));
            }

            String json = "{"
                    + "\"address\":\"" + escape(addr) + "\","
                    + "\"result\":\"" + (valid ? "VALID" : "INVALID") + "\","
                    + "\"confidence\":" + confidence + ","
                    + "\"reason\":\"" + reason + "\","
                    + "\"L5\":" + valid + ","
                    + "\"L6\":" + valid + ","
                    + "\"L7\":" + valid + ","
                    + "\"L8\":" + valid + ","
                    + "\"L9\":" + valid + ","
                    + "\"L10\":" + valid
                    + "}";

            addCors(ex);
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });

        server.start();
        System.out.println("HOLOSENSE Global v2 STRICT running @ http://localhost:8080");
    }

    // ------------------ OSM STRICT ------------------

    static class OSMResult {
        boolean valid;
        double confidence;
        String reason;
        OSMResult(boolean v, double c, String r) { valid = v; confidence = c; reason = r; }
    }

    static OSMResult checkOSM_STRICT(String addr) {
    try {
        // 1) Full address search
        String urlFull = "https://nominatim.openstreetmap.org/search"
                + "?format=json&limit=1&q="
                + URLEncoder.encode(addr, "UTF-8");

        HttpURLConnection con1 = (HttpURLConnection) new URL(urlFull).openConnection();
        con1.setRequestProperty("User-Agent", "holosense-demo/1.0");

        String body1;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(con1.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            body1 = sb.toString();
        }

        if (!body1.trim().equals("[]")) {
            return new OSMResult(true, 0.95, "FULL_ADDRESS_FOUND");
        }

        // 2) Fallback: Area/City existence check
        String areaOnly = extractCity(addr);
        String urlCity = "https://nominatim.openstreetmap.org/search"
                + "?format=json&limit=1&q="
                + URLEncoder.encode(areaOnly, "UTF-8");

        HttpURLConnection con2 = (HttpURLConnection) new URL(urlCity).openConnection();
        con2.setRequestProperty("User-Agent", "holosense-demo/1.0");

        String body2;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(con2.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            body2 = sb.toString();
        }

        if (!body2.trim().equals("[]")) {
            return new OSMResult(true, 0.75, "AREA_CITY_FOUND");
        }

        return new OSMResult(false, 0.0, "NOT_FOUND_IN_OSM");

    } catch (Exception e) {
        return new OSMResult(false, 0.0, "OSM_ERROR");
    }
}

static String extractCity(String addr) {
    String[] parts = addr.split(",");
    if (parts.length >= 2) {
        return parts[parts.length - 2].trim() + "," + parts[parts.length - 1].trim();
    }
    return addr;
}


    // ------------------ CACHE ------------------

    static void loadCache() throws Exception {
        File f = new File("world_cache.txt");
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = br.readLine()) != null)
                cache.add(l.trim());
        }
    }

    static void saveToCache(String addr) {
        try (FileWriter fw = new FileWriter("world_cache.txt", true)) {
            fw.write(addr + "\n");
        } catch (Exception ignored) {}
    }

    // ------------------ UTILS ------------------

    static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    static double similarity(String a, String b) {
        Set<String> A = new HashSet<>(Arrays.asList(a.split(" ")));
        Set<String> B = new HashSet<>(Arrays.asList(b.split(" ")));
        Set<String> inter = new HashSet<>(A);
        inter.retainAll(B);
        Set<String> union = new HashSet<>(A);
        union.addAll(B);
        return union.size() == 0 ? 0 : (double) inter.size() / union.size();
    }

    static String extract(String body, String key) {
        int i = body.indexOf(key);
        if (i < 0) return null;
        int s = i + key.length();
        int e = body.indexOf("\"", s);
        return e < 0 ? null : body.substring(s, e);
    }

    static Map<String, String> queryToMap(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String p : q.split("&")) {
            String[] s = p.split("=");
            if (s.length > 1) {
                try { m.put(s[0], URLDecoder.decode(s[1], "UTF-8")); }
                catch (Exception e) { m.put(s[0], s[1]); }
            }
        }
        return m;
    }

    static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
