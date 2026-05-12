package org.example;

import java.util.ArrayList;
import java.util.List;

public class Main {

    private static final List<TestResult> allResults = new ArrayList<>();

    public static void main(String[] args) {
        // Test 1: Local Test API (kontrollert miljø) - KOMMENTERT BORT
        runTest("Local Test API", "http://localhost:8080/", 3);

        // Test 2: JSONPlaceholder (REST-konvensjoner) - FIKSET URL
        //runTest("JSONPlaceholder", "https://jsonplaceholder.typicode.com/posts", 3);

        // Test 3: Agify API (parameterbasert, ingen lenker)
        //runTest("Agify API", "https://api.agify.io?name=michael", 2);

        // Skriv sammendrag av alle tester
        printSummary();
    }

    /**
     * Kjør en enkelt test og skriv ut resultater
     */
    private static void runTest(String name, String url, int maxDepth) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("TEST: " + name);
        System.out.println("URL: " + url);
        System.out.println("Max depth: " + maxDepth);
        System.out.println("=".repeat(60));

        Crawler crawler = new Crawler(url, maxDepth);
        long startTime = System.currentTimeMillis();
        crawler.start();
        long elapsedMs = System.currentTimeMillis() - startTime;

        // Samle statistikk
        int visited = crawler.getResults().size();
        long successCount = crawler.getResults().stream()
                .filter(r -> r.statusCode() == 200)
                .count();
        long notFoundCount = crawler.getResults().stream()
                .filter(r -> r.statusCode() == 404)
                .count();

        // Skriv resultater
        System.out.println("\n--- RESULTS ---");
        System.out.printf("  Total visited: %d%n", visited);
        System.out.printf("  Successful (200): %d%n", successCount);
        System.out.printf("  Not found (404): %d%n", notFoundCount);
        System.out.printf("  Time: %.2f seconds%n", elapsedMs / 1000.0);

        // Vis oppdagede endepunkter (maks 20 for å unngå for mye output)
        System.out.println("\n  Discovered endpoints (first 20):");
        crawler.getResults().stream()
                .filter(r -> r.statusCode() == 200)
                .limit(20)
                .forEach(r -> System.out.printf("    [depth %d] %s%n", r.depth(), r.url()));

        // Lagre for sammendrag
        allResults.add(new TestResult(name, visited, (int) successCount,
                (int) notFoundCount, elapsedMs / 1000.0));
    }

    /**
     * Skriv sammendrag av alle tester
     */
    private static void printSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("FINAL SUMMARY");
        System.out.println("=".repeat(70));
        System.out.printf("%-25s %10s %10s %10s %12s%n",
                "API", "Visited", "200 OK", "404", "Time (s)");
        System.out.println("-".repeat(70));

        for (TestResult r : allResults) {
            System.out.printf("%-25s %10d %10d %10d %12.2f%n",
                    r.name, r.visited, r.success, r.notFound, r.timeSeconds);
        }
        System.out.println("=".repeat(70));
    }

    /**
     * Hjelpeklasse for å lagre testresultater
     */
    private static class TestResult {
        String name;
        int visited;
        int success;
        int notFound;
        double timeSeconds;

        TestResult(String name, int visited, int success, int notFound, double timeSeconds) {
            this.name = name;
            this.visited = visited;
            this.success = success;
            this.notFound = notFound;
            this.timeSeconds = timeSeconds;
        }
    }
}