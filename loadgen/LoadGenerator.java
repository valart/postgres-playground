import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class LoadGenerator {
    private record Settings(String scenario, String mode, int workers, int batchSize, int duration,
                            int warmup, int rounds, double overlap, double newMinimumRate, boolean retry,
                            long productId, String baseUrl) {
        static Settings fromEnvironment() {
            String scenario = env("SCENARIO", "reads");
            if (!List.of("reads", "deadlock", "updates").contains(scenario)) {
                throw new IllegalArgumentException("SCENARIO must be reads, deadlock, or updates");
            }
            String mode = env("MODE", scenario.equals("updates") ? "conditional" : "ordered");
            List<String> modes = scenario.equals("updates") ? List.of("naive", "conditional") : List.of("unordered", "ordered");
            if (!modes.contains(mode)) {
                throw new IllegalArgumentException("Invalid MODE for scenario");
            }
            return new Settings(scenario, mode, number("WORKERS", 8, 1, 16), number("BATCH_SIZE", 20, 1, 500),
                    number("DURATION", 60, 1, 1800), number("WARMUP", 10, 0, 120), number("ROUNDS", 0, 0, 100000),
                    fraction("OVERLAP", 0.8), fraction("NEW_MINIMUM_RATE", 0.01),
                    Boolean.parseBoolean(env("RETRY", "false")), Long.parseLong(env("PRODUCT_ID", "777")),
                    env("BASE_URL", "http://app:8080"));
        }
    }

    private static final class Counters {
        final LongAdder submitted = new LongAdder();
        final LongAdder success = new LongAdder();
        final LongAdder failure = new LongAdder();
        final LongAdder submittedItems = new LongAdder();
        final LongAdder completedItems = new LongAdder();
        final LongAdder nanos = new LongAdder();
    }

    private static final class MinimumState {
        final long offerId;
        long minimum;
        MinimumState(long offerId, long minimum) { this.offerId = offerId; this.minimum = minimum; }
    }

    private final Settings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Counters warmup = new Counters();
    private final Counters measure = new Counters();
    private final AtomicInteger active = new AtomicInteger();
    private final List<List<MinimumState>> partitions = new ArrayList<>();
    private final List<Random> randoms = new ArrayList<>();
    private long shopId;

    private LoadGenerator(Settings settings) { this.settings = settings; }

    public static void main(String[] args) throws Exception {
        new LoadGenerator(Settings.fromEnvironment()).run();
    }

    private void run() throws Exception {
        System.out.println(settings);
        prepare();
        var server = HttpServer.create(new InetSocketAddress(9091), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = metrics().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        ExecutorService workers = Executors.newFixedThreadPool(settings.workers());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            workers.shutdownNow();
            server.stop(0);
        }));
        try {
            if (settings.warmup() > 0 && settings.rounds() == 0) phase(workers, warmup, settings.warmup(), 0);
            phase(workers, measure, settings.duration(), settings.rounds());
            System.out.printf("Measured: submitted=%d success=%d failure=%d completedItems=%d%n",
                    measure.submitted.sum(), measure.success.sum(), measure.failure.sum(), measure.completedItems.sum());
            // Leave the final counters available for at least one Prometheus scrape.
            Thread.sleep(10000);
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
            server.stop(0);
            http.close();
        }
        if (measure.success.sum() == 0) {
            throw new IllegalStateException("No measured requests succeeded");
        }
    }

    private void prepare() throws Exception {
        for (int worker = 0; worker < settings.workers(); worker++) {
            partitions.add(new ArrayList<>());
            randoms.add(new Random(42L + worker));
        }
        if (settings.scenario().equals("deadlock")) {
            shopId = Long.parseLong(setupRequest("POST", "/lab/import-shop").trim());
        }
        if (settings.scenario().equals("updates")) {
            String csv = setupRequest("GET", "/lab/update-data?limit=" + settings.workers() * 1000);
            int row = 0;
            for (String line : csv.lines().toList()) {
                String[] cells = line.split(",");
                partitions.get(row++ % settings.workers()).add(new MinimumState(Long.parseLong(cells[0]), Long.parseLong(cells[1])));
            }
            for (var partition : partitions) {
                if (partition.isEmpty()) throw new IllegalStateException("Run make seed before update load");
            }
        }
    }

    private void phase(ExecutorService workers, Counters counters, int seconds, int rounds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        if (settings.scenario().equals("deadlock")) {
            int round = 0;
            while (rounds > 0 ? round++ < rounds : System.nanoTime() < deadline) {
                runImportRound(workers, counters);
            }
            return;
        }
        var tasks = new ArrayList<Callable<Void>>();
        for (int worker = 0; worker < settings.workers(); worker++) {
            int workerIndex = worker;
            tasks.add(() -> {
                int round = 0;
                while (rounds > 0 ? round++ < rounds : System.nanoTime() < deadline) {
                    if (settings.scenario().equals("reads")) {
                        request("GET", "/api/products/" + settings.productId() + "/offers?limit=20", "", 1, counters);
                    } else {
                        request("POST", "/api/price-observations/batch?mode=" + settings.mode(),
                                observations(workerIndex), settings.batchSize(), counters);
                    }
                }
                return null;
            });
        }
        for (var future : workers.invokeAll(tasks)) {
            future.get();
        }
    }

    private void runImportRound(ExecutorService workers, Counters counters) throws Exception {
        int shared = (int) Math.round(settings.batchSize() * settings.overlap());
        int privateKeys = settings.batchSize() - shared;
        int keyCount = shared + privateKeys * settings.workers();
        String json = setupRequest("POST", "/lab/import-products?count=" + keyCount).trim();
        // This endpoint returns only a JSON array of integer IDs.
        String[] ids = json.substring(1, json.length() - 1).split(",");
        var tasks = new ArrayList<Callable<Void>>();
        for (int worker = 0; worker < settings.workers(); worker++) {
            var products = new ArrayList<Long>();
            for (int key = 0; key < shared; key++) products.add(Long.parseLong(ids[key].trim()));
            for (int key = 0; key < privateKeys; key++) {
                products.add(Long.parseLong(ids[shared + worker * privateKeys + key].trim()));
            }
            Collections.shuffle(products, randoms.get(worker));
            String body = offers(products);
            tasks.add(() -> {
                request("POST", "/api/offers/batch?mode=" + settings.mode() + "&retry=" + settings.retry(), body, settings.batchSize(), counters);
                return null;
            });
        }
        for (var future : workers.invokeAll(tasks)) {
            future.get();
        }
    }

    private String offers(List<Long> products) {
        var body = new StringBuilder("{\"offers\":[");
        for (int i = 0; i < products.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"shopId\":").append(shopId).append(",\"productId\":").append(products.get(i))
                    .append(",\"currentPrice\":10000,\"available\":true}");
        }
        return body.append("]}").toString();
    }

    private String observations(int worker) {
        List<MinimumState> data = partitions.get(worker);
        Random random = randoms.get(worker);
        var body = new StringBuilder("{\"observations\":[");
        for (int i = 0; i < settings.batchSize(); i++) {
            MinimumState offer = data.get(random.nextInt(data.size()));
            boolean lower = random.nextDouble() < settings.newMinimumRate() && offer.minimum > 0;
            if (lower) {
                offer.minimum--;
            }
            long price = lower ? offer.minimum : offer.minimum + 100 + random.nextInt(10000);
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"offerId\":").append(offer.offerId).append(",\"observedPrice\":").append(price).append('}');
        }
        return body.append("]}").toString();
    }

    private void request(String method, String path, String body, int items, Counters counters) {
        counters.submitted.increment();
        counters.submittedItems.add(items);
        active.incrementAndGet();
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(httpRequest(method, path, body), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                counters.success.increment();
                counters.completedItems.add(items);
            } else {
                counters.failure.increment();
                if (counters.failure.sum() <= 3) System.err.println(response.statusCode() + " " + response.body());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Load interrupted", exception);
        } catch (Exception exception) {
            counters.failure.increment();
            if (counters.failure.sum() <= 3) {
                System.err.println(exception.getMessage());
            }
        } finally {
            counters.nanos.add(System.nanoTime() - started);
            active.decrementAndGet();
        }
    }

    private String setupRequest(String method, String path) throws Exception {
        var response = http.send(httpRequest(method, path, ""), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Setup failed: " + response.body());
        }
        return response.body();
    }

    private HttpRequest httpRequest(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create(settings.baseUrl() + path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private String metrics() {
        var result = new StringBuilder("# TYPE priceradar_load_active_workers gauge\npriceradar_load_active_workers ").append(active.get()).append('\n');
        for (String name : List.of("requests_submitted_total", "requests_success_total", "requests_failure_total",
                "items_submitted_total", "items_completed_total", "request_seconds_total")) {
            result.append("# TYPE priceradar_load_").append(name).append(" counter\n");
        }
        appendMetrics(result, "warmup", warmup);
        appendMetrics(result, "measure", measure);
        return result.toString();
    }

    private void appendMetrics(StringBuilder result, String phase, Counters counters) {
        String labels = "{phase=\"" + phase + "\",scenario=\"" + settings.scenario() + "\"}";
        metric(result, "requests_submitted_total", labels, counters.submitted.sum());
        metric(result, "requests_success_total", labels, counters.success.sum());
        metric(result, "requests_failure_total", labels, counters.failure.sum());
        metric(result, "items_submitted_total", labels, counters.submittedItems.sum());
        metric(result, "items_completed_total", labels, counters.completedItems.sum());
        metric(result, "request_seconds_total", labels, counters.nanos.sum() / 1e9);
    }

    private void metric(StringBuilder result, String name, String labels, double value) {
        result.append("priceradar_load_").append(name).append(labels).append(' ').append(value).append('\n');
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }

    private static int number(String name, int fallback, int min, int max) {
        int value = Integer.parseInt(env(name, Integer.toString(fallback)));
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be " + min + ".." + max);
        }
        return value;
    }

    private static double fraction(String name, double fallback) {
        double value = Double.parseDouble(env(name, Double.toString(fallback)));
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be 0..1");
        }
        return value;
    }
}
