# Actual verification results

Environment: macOS arm64, Colima Docker Engine 28.4.0, Compose 2.39.4, PostgreSQL 18.6, Java 25.0.2. Verified on 2026-10-09. These are manual functional checks, not a controlled performance benchmark.

## Build and startup

The complete Gradle 9.2.1 Wrapper generated successfully and downloaded/verified its pinned distribution. `./gradlew --no-daemon --max-workers=2 bootJar` compiled all application sources and packaged the executable JAR. Docker Compose built the app through the same wrapper using the Java 25 build image and compiled the standalone load generator with `javac --release 25`. The migrated Groovy build resolves the pinned Boot 4.1.1 BOM and packages Flyway's PostgreSQL module.

PostgreSQL, application, Prometheus and Grafana all started with healthy Compose status. Flyway applied V1 to an empty PostgreSQL 18.6 database. Actuator reported UP. The initial runtime health check exposed a missing curl utility; the runtime Dockerfile now installs curl, and the corrected image's health check passed.

This host's CLI-only Docker installation lacked Buildx. Verification used the official Buildx 0.29.1 binary and a temporary CLI configuration outside the repository. Container builds and startup completed after that host tooling workaround. A normal Docker Desktop installation includes Buildx. No Java or Gradle installation on the host is required for the documented Compose workflow; direct host compilation was an additional check.

## API and experiments

Created and read shops/products, registered an offer, patched current price/availability, read cheapest offers, submitted observations and read their minimum. Missing shop returned 404; excessive read limit and negative price returned 400.

The deterministic unordered demo observed A's lock wait and returned one worker with 40P01/zero committed rows and one with two committed rows. Ordered mode committed both workers with two inserted rows in total. Retried unordered mode committed both workers: one reported two complete transaction attempts and inserted zero after the competing transaction committed, the other inserted two. These results were checked again after deploying the Gradle-built application. PostgreSQL logs also recorded the deadlock cycle.

Uncoordinated import loads with four workers, batch size 20, ten rounds, full overlap and retry enabled used fresh products each round. Unordered mode completed 34 of 40 logical batches; six exhausted the three-attempt retry bound and returned 409. Ordered mode completed all 40. Each mode committed exactly 200 new distinct offers over its ten rounds; attempts and client completed-item counts were larger and correctly separate from committed inserted rows. Bounded retries can still be exhausted under contention and do not replace consistent ordering.

Small seed created 400,000 unique shop/product offers and initialized minima, then analyzed both tables. None/poor/good index switches succeeded and constraint indexes remained. The [captured real plans](plans/) show:

| Index state | Selected access path | Query buffers |
| --- | --- | --- |
| None | Existing shop/product unique index with repeated searches, then top-N sort | 1,675 shared hits |
| Poor | Poor index scan in price order plus incremental sort | 24 hits + 1,774 reads |
| Good | Good index scan, no sort | 20 hits + 3 reads |

These were initial executions after seeding/index creation, so cache conditions differ and execution times must not be treated as comparable speedups. PostgreSQL 18's repeated index searches in the baseline are a useful example of why "no tailored index" does not mean "no usable index". The poor plan shows product in Index Cond; its broad traversal is revealed by buffers and ordering, not by a rows-removed counter for index entries.

All three variants also completed read-load smoke runs with four workers, ten-second warm-up and sixty-second measurement periods, with no failed requests. Query histograms, query throughput and measured client throughput supplied real Prometheus samples. The baseline run overlapped a container build, so these smoke runs are not recorded as controlled comparative benchmark results.

For a seeded offer, the explicit observations 100 then 200 produced minimum 100 in both modes. Naive reported two rows updated; conditional reported one. Missing minimum initialization was exercised in both modes by deleting just one minimum row and submitting an observation: each reported `processed=1, initialized=1, updated=0`.

Equivalent reset data and identical fixed streams (`WORKERS=4 BATCH_SIZE=20 ROUNDS=100 NEW_MINIMUM_RATE=0.01`) produced 400 successful batches / 8,000 observations in each mode, no failures, and identical full-table checksum:

```text
30136113027ba0163c6c1472ab35ac7e
```

The naive stream performed 8,000 updates; conditional performed 61. The new-minimum setting specifies a probability, so a finite fixed-seed sample need not yield exactly 80 new minima.

The optional controlled snapshot was verified with a 45-second read-only REPEATABLE READ transaction. `pg_stat_activity` showed a backend xmin and PgSleep. A 20-second naive load processed 309,720 observations with no failures and the same number of physical application-counted updates. Autovacuum remained enabled and ran. A manual VACUUM while the snapshot was still held left a dead-tuple estimate of 309,720 and table size around 31.5 MB. These estimates demonstrate cleanup being held back by the additional old snapshot; they are not precise bloat measurements. The snapshot endpoint released its transaction at the configured bound.

After snapshot release, another manual VACUUM reduced the dead-tuple estimate to zero. The table size remained 31,490,048 bytes, illustrating reusable space rather than file shrinkage. No held snapshot sessions remained.

## Monitoring

Prometheus scraped the application successfully and the load target was UP while the CLI ran. Collector availability was 1. Grafana provisioned all four dashboards and its Prometheus data-source proxy returned actual counters and query latency samples. All 45 dashboard query expressions were executed through that proxy with no PromQL errors. Panels for workloads not yet run after an app restart can legitimately have no samples.

Grafana browser rendering was not inspected. Data-source/provisioning/query verification is distinct from visual dashboard review.

An ordinary conditional workload after reset completed a five-second warm-up and thirty-second measurement, with 59,558 successful measured batches / 1,191,160 measured observations and no failures. Grafana's data-source proxy returned observation rates, batch p95 and WAL interval increases. Those database-wide WAL interval values also include reset/setup activity, so they are not recorded as an isolated per-mode WAL comparison. All 45 dashboard queries were checked again with no PromQL errors, and all 16 UPDATE-dashboard queries returned series. Some client/read-rate queries had no current samples after their workloads stopped.

## Limits

The three-million-offer dataset and prolonged resource-exhaustion behavior have not been verified. This report does not claim a universal query plan, exact speedup, exact bloat amount, or indefinite table growth. Autovacuum was confirmed enabled. Ordinary local results are machine/cache/checkpoint dependent; reproduce the documented measurement periods in your own environment.

## Domain refactor verification — 2026-10-10

The controllers are now separate classes for shops, products, offers, minima and price observations. Request/response records each have their own file in a domain package, including lab responses. Shops and products also have separate services and repositories. Simple controller/service constructors use Lombok `@RequiredArgsConstructor`; services configuring transactions and metrics retain explicit constructors.

`clean bootJar` and the Docker build both passed with Lombok 1.18.46 annotation processing. The executable JAR has no Lombok runtime library. The rebuilt application started healthy. Manual calls created/read a shop and product, registered/read/patched an offer, processed observations and retrieved its minimum through the original URLs with the original JSON fields. Unordered, ordered and retried deadlock demonstrations still produced the expected outcomes. Only the temporary rows created by these checks were removed afterward; existing data was preserved. No automated test files were added.

## Dashboard and walkthrough update — 2026-10-10

All four dashboard JSON files parsed successfully, and `docker compose config --quiet` passed. The running Grafana instance loaded the revised file-provisioned definitions: all four expose navigation links, and each of the three scenario dashboards contains a Run this experiment text panel. Provisioned dashboard UIDs match the direct links in the README and experiment guide.

All 45 metric query expressions executed successfully through Grafana's Prometheus data-source proxy, substituting a fifteen-minute interval for the dashboard range variable. Prometheus reported the application target UP and the SQL collector gauge was 1. The optional load target was DOWN because no generator was running, which is expected. Query success does not imply current samples for an inactive workload.

This update did not reseed data, rerun workloads, or inspect browser rendering. Previously verified scenario outcomes remain documented above; the expanded guide provides independent setup, comparison, inspection, and cleanup commands for each case. No automated tests were added.
