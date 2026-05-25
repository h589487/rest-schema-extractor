package org.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Web crawler for REST API discovery and mapping.
 * Implements breadth-first crawling with adaptive rate limiting.
 *
 * @author John Chen
 * Part of master thesis on automated API documentation
 */
public class Crawler {

    // Core data structure for storing crawl results
    public record CrawlResult(
            String url,
            int statusCode,
            String contentType,
            Object parsedContent,
            List<String> discoveredUrls,
            long responseTimeMs,
            int depth
    ) {}

    private record UrlEntry(String url, int depth) {}

    // Represents a discovered API endpoint with its schema
    public static class InferredEndpoint {
        public String path;
        public String method = "GET";
        public Set<String> fields = new LinkedHashSet<>();
    }

    private final Set<String> visited = new LinkedHashSet<>();
    private final Queue<UrlEntry> frontier = new LinkedList<>();
    private final List<CrawlResult> results = new ArrayList<>();
    private final Map<String, InferredEndpoint> inferred = new LinkedHashMap<>();

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String baseUrl;
    private final int maxDepth;
    private long delayMs = 300; // adjusted dynamically based on server responses

    public Crawler(String baseUrl) {
        this(baseUrl, 3);
    }

    public Crawler(String baseUrl, int maxDepth) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.maxDepth = maxDepth;
    }

    public void start() {
        frontier.add(new UrlEntry(baseUrl, 0));

        while (!frontier.isEmpty()) {
            var entry = frontier.poll();
            String normalizedUrl = normalize(entry.url());

            if (visited.contains(normalizedUrl)) continue;
            if (entry.depth() > maxDepth) continue;

            visited.add(normalizedUrl);
            System.out.printf("[depth %d] Crawling: %s%n", entry.depth(), normalizedUrl);

            // Politeness delay respect server load
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            var result = fetch(normalizedUrl, entry.depth());
            if (result == null) continue;

            results.add(result);

            // Add discovered URLs to queue
            for (String next : result.discoveredUrls()) {
                if (!visited.contains(normalize(next)))
                    frontier.add(new UrlEntry(next, entry.depth() + 1));
            }
        }

        printReport();
    }

    public List<CrawlResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    private CrawlResult fetch(String url, int depth) {
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 (compatible; API-Discovery-Research/1.0; +mailto:589487@stud.hvl.no)")
                    .header("Accept", "application/json, text/html")
                    .GET()
                    .build();

            var t0 = Instant.now();
            var res = client.send(req, HttpResponse.BodyHandlers.ofString());
            long elapsed = Duration.between(t0, Instant.now()).toMillis();

            adjustRateLimit(res);

            var ct = res.headers().firstValue("Content-Type").orElse("unknown");
            System.out.printf("  -> %d (%s) in %dms%n", res.statusCode(), ct, elapsed);

            // Handle rate limiting
            if (res.statusCode() == 429) {
                System.err.println("Rate limited (429) - re-queueing URL");
                handleRateLimitedResponse(res);
                frontier.add(new UrlEntry(url, depth));
                return null;
            }

            if (res.statusCode() != 200)
                return new CrawlResult(url, res.statusCode(), ct, null, List.of(), elapsed, depth);

            // Route to appropriate handler based on content type
            if (ct.contains("text/html"))
                return handleHtml(url, res.body(), res.statusCode(), ct, elapsed, depth);
            else if (ct.toLowerCase().contains("json"))
                return handleJson(url, res.body(), res.statusCode(), ct, elapsed, depth);

            // Fallback: detect JSON by structure if Content-Type is misleading
            String trimmed = res.body().trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                System.out.println("Detected JSON structure (Content-Type: " + ct + ")");
                return handleJson(url, res.body(), res.statusCode(), ct, elapsed, depth);
            }

            return new CrawlResult(url, res.statusCode(), ct, res.body(), List.of(), elapsed, depth);

        } catch (Exception e) {
            System.err.println("Failed to fetch " + url + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Adjusts crawl delay based on rate limit headers.
     * Implements adaptive politeness as discussed in Chapter 3.2.
     */
    private void adjustRateLimit(HttpResponse<String> response) {
        response.headers().firstValue("X-RateLimit-Remaining").ifPresent(rem -> {
            try {
                int remaining = Integer.parseInt(rem);
                if (remaining < 5) {
                    long newDelay = Math.max(delayMs, 1000);
                    if (newDelay != delayMs) {
                        System.out.printf("Rate limit low (%d remaining), increasing delay to %dms%n",
                                remaining, newDelay);
                        delayMs = newDelay;
                    }
                }
            } catch (NumberFormatException ignored) {}
        });

        response.headers().firstValue("Retry-After").ifPresent(retry -> {
            try {
                long retrySeconds = Long.parseLong(retry);
                long newDelay = Math.max(delayMs, retrySeconds * 1000);
                if (newDelay != delayMs) {
                    System.out.printf("Retry-After header found: delaying %dms%n", newDelay);
                    delayMs = newDelay;
                }
            } catch (NumberFormatException e) {
                // Could be HTTP date format  ignoring for now
            }
        });
    }

    private void handleRateLimitedResponse(HttpResponse<String> response) {
        response.headers().firstValue("Retry-After").ifPresent(retry -> {
            try {
                long retrySeconds = Long.parseLong(retry);
                delayMs = Math.max(delayMs, retrySeconds * 1000);
                System.out.printf("Set delay to %dms based on Retry-After%n", delayMs);
            } catch (NumberFormatException ignored) {
                delayMs = Math.max(delayMs, 2000); // fallback
            }
        });
    }

    private CrawlResult handleHtml(String url, String html, int status, String ct, long elapsed, int depth) {
        var doc = Jsoup.parse(html, url);
        var discovered = new ArrayList<String>();

        for (Element link : doc.select("a[href]")) {
            String next = link.absUrl("href");
            if (next.startsWith(baseUrl)) {
                discovered.add(next);
            }
        }

        return new CrawlResult(url, status, ct, html, discovered, elapsed, depth);
    }

    private CrawlResult handleJson(String url, String body, int status, String ct, long elapsed, int depth) {
        String trimmed = body.trim();
        var discovered = new ArrayList<String>();

        try {
            if (trimmed.startsWith("[")) {
                var arr = new JSONArray(trimmed);
                extractFromArray(arr, url, discovered);
                return new CrawlResult(url, status, ct, arr, discovered, elapsed, depth);

            } else if (trimmed.startsWith("{")) {
                var obj = new JSONObject(trimmed);
                extractFromObject(obj, url, discovered);
                return new CrawlResult(url, status, ct, obj, discovered, elapsed, depth);
            }
        } catch (Exception e) {
            System.err.println("JSON parsing failed for " + url + ": " + e.getMessage());
        }

        return new CrawlResult(url, status, ct, body, discovered, elapsed, depth);
    }

    // Extract links and schema from JSON arrays
    // Limited to first 10 items to prevent queue explosion
    private void extractFromArray(JSONArray arr, String currentUrl, List<String> discovered) {
        int limit = Math.min(arr.length(), 10);
        for (int i = 0; i < limit; i++) {
            if (arr.get(i) instanceof JSONObject obj)
                extractFromObject(obj, currentUrl, discovered);
        }
    }

    /**
     * Recursively extracts URLs and builds endpoint schema from JSON objects.
     * Implements three discovery patterns:
     * 1. Explicit URL fields (href, url, link, etc.)
     * 2. RESTful ID inference (e.g., /posts -> /posts/1)
     * 3. Nested object traversal
     */
    private void extractFromObject(JSONObject obj, String currentUrl, List<String> discovered) {
        for (String key : obj.keySet()) {
            var val = obj.get(key);

            // Build endpoint model for Pipeline B
            String path = URI.create(currentUrl).getPath();
            if (path.isEmpty()) path = "/";
            path = path.replaceAll("/\\d+$", "/{id}"); // normalize IDs

            inferred.putIfAbsent(path, new InferredEndpoint());
            inferred.get(path).path = path;
            inferred.get(path).fields.add(key);

            // Pattern 1: Explicit URL fields
            if (val instanceof String s && looksLikeUrl(key, s)) {
                var resolved = resolveUrl(s);
                if (resolved != null) {
                    discovered.add(resolved);
                }
            }
            // Pattern 2: RESTful ID inference
            else if ((val instanceof Integer || val instanceof Long) && looksLikeId(key)) {
                String candidate = currentUrl.replaceAll("/$", "") + "/" + val;
                if (candidate.startsWith(baseUrl)) {
                    discovered.add(candidate);
                }
            }
            // Pattern 3: Deep traversal
            else if (val instanceof JSONObject nested) {
                extractFromObject(nested, currentUrl, discovered);
            } else if (val instanceof JSONArray nested) {
                extractFromArray(nested, currentUrl, discovered);
            }
        }
    }

    private boolean looksLikeUrl(String key, String value) {
        var urlKeys = Set.of("href", "url", "link", "self", "uri", "endpoint", "location");
        return urlKeys.contains(key.toLowerCase()) || value.startsWith("http") || value.startsWith("/");
    }

    private boolean looksLikeId(String key) {
        return key.equals("id") || key.endsWith("_id") || key.endsWith("Id");
    }

    private String resolveUrl(String url) {
        if (url.startsWith("http"))
            return url.startsWith(baseUrl) ? url : null;
        if (url.startsWith("/"))
            return baseUrl.replaceAll("/$", "") + url;
        return null;
    }

    private String normalize(String url) {
        return url.replaceAll("/$", "");
    }

    private void printReport() {
        long total = results.stream().mapToLong(CrawlResult::responseTimeMs).sum();
        double avg = results.isEmpty() ? 0 : (double) total / results.size();
        int maxDepthReached = results.stream().mapToInt(CrawlResult::depth).max().orElse(0);

        System.out.println("\n=== CRAWL SUMMARY ===");
        System.out.printf("Total visited: %d | Avg Latency: %.0fms | Max Depth: %d | Current delay: %dms%n",
                visited.size(), avg, maxDepthReached, delayMs);

        // Write Pipeline B output (endpoint schema model)
        if (inferred.isEmpty()) {
            System.out.println("\nNo JSON endpoints discovered - skipping pipeline_b_output.json");
            return;
        }

        try {
            JSONObject out = new JSONObject();
            JSONArray arr = new JSONArray();

            for (var ep : inferred.values()) {
                JSONObject o = new JSONObject();
                o.put("path", ep.path);
                o.put("method", ep.method);
                o.put("fields", ep.fields);
                arr.put(o);
            }

            out.put("endpoints", arr);

            try (var fw = new java.io.FileWriter("pipeline_b_output.json")) {
                fw.write(out.toString(2));
            }

            System.out.println("\nPipeline B output written to pipeline_b_output.json");
            System.out.println("Total endpoints discovered: " + inferred.size());

        } catch (Exception e) {
            System.err.println("Failed to write Pipeline B output: " + e.getMessage());
            e.printStackTrace();
        }
    }
}