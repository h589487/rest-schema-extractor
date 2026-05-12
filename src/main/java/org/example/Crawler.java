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
 * ARCHITECTURE OVERVIEW:
 * This Crawler follows a basic Orchestrator pattern.
 * 1. Data Representation: Uses Records for immutable state.
 * 2. State Management: Tracks visited URLs and the crawling queue (frontier).
 * 3. Networking: Handles HTTP communication and politeness (adaptive delays).
 * 4. Content Parsing: Separates HTML and JSON extraction logic.
 */
public class Crawler {

    // --- DATA MODELS (Concerns: Data Representation) ---
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

    // --- PIPELINE B: Inferred API Model ---
    public static class InferredEndpoint {
        public String path;
        public String method = "GET"; // default assumption
        public Set<String> fields = new LinkedHashSet<>();
    }

    // --- STATE MANAGEMENT (Concerns: Crawl tracking and boundaries) ---
    private final Set<String> visited       = new LinkedHashSet<>();
    private final Queue<UrlEntry> frontier  = new LinkedList<>();
    private final List<CrawlResult> results = new ArrayList<>();
    private final Map<String, InferredEndpoint> inferred = new LinkedHashMap<>();

    // --- NETWORKING CLIENT (Concerns: HTTP configuration) ---
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String baseUrl;
    private final int maxDepth;
    private long delayMs = 300; // Mutable: can be adjusted dynamically based on rate limits

    public Crawler(String baseUrl) {
        this(baseUrl, 3);
    }

    public Crawler(String baseUrl, int maxDepth) {
        this.baseUrl  = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.maxDepth = maxDepth;
    }

    // --- ORCHESTRATION LOGIC (Concerns: Controlling the crawl flow) ---
    public void start() {
        frontier.add(new UrlEntry(baseUrl, 0));

        while (!frontier.isEmpty()) {
            var entry = frontier.poll();

            // Normalize URL to avoid duplicate crawling (trailing slash issue)
            String normalizedUrl = normalize(entry.url());
            if (visited.contains(normalizedUrl)) continue;
            if (entry.depth() > maxDepth) continue;

            visited.add(normalizedUrl);
            System.out.printf("%n[depth %d] Crawling: %s%n", entry.depth(), normalizedUrl);

            // Politeness: Pause execution to respect server rate limits
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            var result = fetch(normalizedUrl, entry.depth());
            if (result == null) continue;

            results.add(result);

            // Expansion: Add new discoveries to the queue
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

    // --- NETWORK LOGIC (Concerns: Request/Response handling with adaptive rate limiting) ---
    private CrawlResult fetch(String url, int depth) {
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "JavaCrawler/1.0 (MasterThesisProject)")
                    .header("Accept", "application/json, text/html")
                    .GET()
                    .build();

            var t0 = Instant.now();
            var res = client.send(req, HttpResponse.BodyHandlers.ofString());
            long elapsed = Duration.between(t0, Instant.now()).toMillis();

            // Adaptive rate limiting: Respect server's rate limit headers
            adjustRateLimit(res);

            var ct = res.headers().firstValue("Content-Type").orElse("unknown");
            System.out.printf("  -> %d (%s) in %dms%n", res.statusCode(), ct, elapsed);

            if (res.statusCode() == 429) {
                // Too Many Requests: Handle Retry-After if present
                System.err.println("  rate limited (429), re-queueing");
                handleRateLimitedResponse(res);
                frontier.add(new UrlEntry(url, depth)); // Re-queue the URL
                return null;
            }

            if (res.statusCode() != 200)
                return new CrawlResult(url, res.statusCode(), ct, null, List.of(), elapsed, depth);

            // Routing: Direct content to the correct parser based on MIME type
            if (ct.contains("text/html"))
                return handleHtml(url, res.body(), res.statusCode(), ct, elapsed, depth);
            else if (ct.toLowerCase().contains("json"))
                return handleJson(url, res.body(), res.statusCode(), ct, elapsed, depth);

            // Fallback: Try to detect JSON structure even if Content-Type is wrong
            String trimmed = res.body().trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                System.out.println("  -> Detected JSON structure despite Content-Type: " + ct);
                return handleJson(url, res.body(), res.statusCode(), ct, elapsed, depth);
            }

            return new CrawlResult(url, res.statusCode(), ct, res.body(), List.of(), elapsed, depth);

        } catch (Exception e) {
            System.err.println("  failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Adjusts the request delay based on rate limit headers returned by the server.
     * Implements adaptive politeness: increases delay when approaching rate limits,
     * and respects explicit Retry-After directives.
     */
    private void adjustRateLimit(HttpResponse<String> response) {
        // Check for remaining rate limit quota
        response.headers().firstValue("X-RateLimit-Remaining").ifPresent(rem -> {
            try {
                int remaining = Integer.parseInt(rem);
                if (remaining < 5) {
                    long newDelay = Math.max(delayMs, 1000); // Minimum 1 second when low on quota
                    if (newDelay != delayMs) {
                        System.out.printf("  rate limit low (%d remaining), increasing delay to %dms%n",
                                remaining, newDelay);
                        delayMs = newDelay;
                    }
                }
            } catch (NumberFormatException ignored) {
                // Malformed header, ignore
            }
        });

        // Respect explicit Retry-After header (common with 429 responses)
        response.headers().firstValue("Retry-After").ifPresent(retry -> {
            try {
                long retrySeconds = Long.parseLong(retry);
                long newDelay = Math.max(delayMs, retrySeconds * 1000);
                if (newDelay != delayMs) {
                    System.out.printf("  retry-after header: delaying %dms%n", newDelay);
                    delayMs = newDelay;
                }
            } catch (NumberFormatException e) {
                // Could be HTTP date format; for simplicity we ignore
            }
        });
    }

    /**
     * Handles 429 Too Many Requests response by respecting Retry-After.
     * Called when the server explicitly indicates we are being rate limited.
     */
    private void handleRateLimitedResponse(HttpResponse<String> response) {
        response.headers().firstValue("Retry-After").ifPresent(retry -> {
            try {
                long retrySeconds = Long.parseLong(retry);
                delayMs = Math.max(delayMs, retrySeconds * 1000);
                System.out.printf("  rate limited, set delay to %dms%n", delayMs);
            } catch (NumberFormatException ignored) {
                delayMs = Math.max(delayMs, 2000); // Fallback to 2 seconds
            }
        });
    }

    // --- HTML PARSING LOGIC (Concerns: DOM Extraction) ---
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

    // --- JSON PARSING LOGIC (Concerns: Schema exploration & link inference) ---
    private CrawlResult handleJson(String url, String body, int status, String ct, long elapsed, int depth) {
        System.out.println("  -> handleJson() called for: " + url);  // DEBUG
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
            System.err.println("  json parse failed: " + e.getMessage());
        }

        return new CrawlResult(url, status, ct, body, discovered, elapsed, depth);
    }

    // --- RECURSIVE EXTRACTION (Concerns: Traversal of unstructured data) ---
    private void extractFromArray(JSONArray arr, String currentUrl, List<String> discovered) {
        // Limit processing to first 10 items to prevent exponential queue growth
        int limit = Math.min(arr.length(), 10);
        for (int i = 0; i < limit; i++) {
            if (arr.get(i) instanceof JSONObject obj)
                extractFromObject(obj, currentUrl, discovered);
        }
    }

    private void extractFromObject(JSONObject obj, String currentUrl, List<String> discovered) {
        System.out.println("  DEBUG: extractFromObject called for URL: " + currentUrl);
        System.out.println("  DEBUG: JSON keys found: " + obj.keySet());
        for (String key : obj.keySet()) {
            var val = obj.get(key);

            // Register endpoint and fields for Pipeline B
            String path = URI.create(currentUrl).getPath();
            if (path.isEmpty()) path = "/";

            // Normalize numeric IDs at the end of the path
            path = path.replaceAll("/\\d+$", "/{id}");

            inferred.putIfAbsent(path, new InferredEndpoint());
            inferred.get(path).path = path;
            inferred.get(path).fields.add(key);

            // Pattern 1: Explicit URLs in string fields
            if (val instanceof String s && looksLikeUrl(key, s)) {
                var resolved = resolveUrl(s);
                if (resolved != null) {
                    discovered.add(resolved);
                }
            }
            // Pattern 2: RESTful ID inference (e.g., /posts -> /posts/1)
            else if ((val instanceof Integer || val instanceof Long) && looksLikeId(key)) {
                String candidate = currentUrl.replaceAll("/$", "") + "/" + val;
                if (candidate.startsWith(baseUrl)) {
                    discovered.add(candidate);
                }
            }
            // Pattern 3: Deep Traversal
            else if (val instanceof JSONObject nested) {
                extractFromObject(nested, currentUrl, discovered);
            } else if (val instanceof JSONArray nested) {
                extractFromArray(nested, currentUrl, discovered);
            }
        }
    }

    // --- UTILITIES (Concerns: String Analysis & Normalization) ---
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

    // --- REPORTING (Concerns: Data Visualization/Output) ---
    private void printReport() {
        long total = results.stream().mapToLong(CrawlResult::responseTimeMs).sum();
        double avg = results.isEmpty() ? 0 : (double) total / results.size();
        int maxDepthReached = results.stream().mapToInt(CrawlResult::depth).max().orElse(0);

        System.out.println("\n=== CRAWL SUMMARY ===");
        System.out.printf("Total visited: %d | Avg Latency: %.0fms | Max Depth: %d | Current delay: %dms%n",
                visited.size(), avg, maxDepthReached, delayMs);

        // --- PIPELINE B OUTPUT ---
        if (inferred.isEmpty()) {
            System.out.println("\n⚠ No JSON endpoints found - skipping pipeline_b_output.json");
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

            System.out.println("\n✓ Pipeline B model written to pipeline_b_output.json");
            System.out.println("  Total endpoints discovered: " + inferred.size());

        } catch (Exception e) {
            System.err.println("Failed to write Pipeline B output: " + e.getMessage());
        }
    }
}