# Running the three PostgreSQL experiments

Each case can be started independently. Use one terminal for commands, a second for inspection during foreground loads, and a browser for Grafana. Docker Compose, Make, and curl are required; host Java and Gradle are not. Keep only one workload active.

No source edits are needed to switch behavior. Mode parameters and index commands control the comparisons. Code references below identify where to inspect or modify the implementation if experimenting further.

## Start and locate monitoring

```sh
make up
docker compose ps
curl -fsS localhost:8080/actuator/health
```

All four services should be healthy. If building reports a missing `docker-buildx`, fix the host Docker plugin first; see README troubleshooting.

Open Grafana at <http://localhost:3000> and sign in with `admin` / `priceradar`. The Prometheus data source and dashboards are provisioned automatically; no manual import is needed:

| Dashboard | Direct link | Purpose |
| --- | --- | --- |
| Application and workload | [Open](http://localhost:3000/d/priceradar-application) | Request rates, latency, failures, completed work, active workers, collector health |
| INSERT deadlocks | [Open](http://localhost:3000/d/priceradar-deadlocks) | Attempts, retries, deadlocks, committed batches/rows, lock waits |
| Composite indexes | [Open](http://localhost:3000/d/priceradar-indexes) | Query latency/throughput, client throughput, database buffers |
| Historical minimum UPDATEs | [Open](http://localhost:3000/d/priceradar-updates) | Observations, updated rows, WAL, tuples, sizes, HOT updates, vacuum |

Use the dashboard navigation menu to switch cases. Each scenario has a **Run this experiment** panel at the bottom. JSON definitions live in `monitoring/grafana/dashboards/`; provisioning and the data source live in `monitoring/grafana/provisioning/`.

Prometheus stores metrics; Grafana provides the dashboards. In [Prometheus Targets](http://localhost:9090/targets), `priceradar` should be UP. `load` is UP only during a workload and its final ten-second scrape window. Execute `priceradar_db_collector_up` in [the expression browser](http://localhost:9090/graph); expect 1. Scraping is configured in `monitoring/prometheus.yml`.

For live inspection, select **Last 15 minutes** and five-second refresh. Use at least sixty-second loads because panels use 30-second rate windows. After each run, choose an **absolute time range** for its measured phase; record clock times when switching modes/indexes. Exclude setup, resets, and warm-up. Rate windows at the beginning of measurement can include warm-up samples, so focus on the interior for steady-state comparisons.

Defaults are eight workers, batches of twenty, and ten-second warm-up. Total runtime exceeds `DURATION`: setup, warm-up, in-flight requests, and final scraping add time. This is closed-loop traffic, not a fixed requests-per-second target. Application counters survive data resets; load counters restart per process. Use rates/increases or differences, not cumulative totals, for a single run.

## Case 1: concurrent INSERT deadlocks

### Step 1 — Start clean and reproduce

```sh
make up
make stop-load
make reset-imports
make demo-deadlock
make logs
```

`reset-imports` deletes only reserved `__lab-import-*` data. No seed is required. The demo creates fresh keys: A inserts X and waits for Y; B inserts Y and attempts X. In the response, expect `observedWait=true`, one committed worker, and one `40P01` worker. Total committed inserted rows should be two; either transaction may be the victim. Inspect PostgreSQL logs for the cycle.

Open **INSERT deadlocks** and generate sustained traffic:

```sh
make load-deadlock MODE=unordered WORKERS=8 BATCH_SIZE=20 DURATION=60 WARMUP=10 OVERLAP=0.8 RETRY=false
```

Watch **Deadlocks / second**, **Submitted and committed logical batches**, **Transaction attempts and retries**, and **Batch p95**. The shared application dashboard shows request failures; the generator prints totals. Five-second samples may miss short lock waits. A single demo event may not appear reliably in rate/increase panels, especially before the counter's first scrape: its response/logs are the authoritative functional evidence.

Load deadlocks are probabilistic. Each round creates fresh product keys with overlapping shuffled batches. Volume alone does not cause this cycle; it needs overlap, concurrency, and inconsistent order.

### Step 2 — Compare retry and consistent ordering

```sh
make demo-deadlock RETRY=true
make demo-deadlock-ordered
make reset-imports
make load-deadlock MODE=unordered WORKERS=8 BATCH_SIZE=20 DURATION=60 WARMUP=10 OVERLAP=0.8 RETRY=true
make reset-imports
make load-deadlock MODE=ordered WORKERS=8 BATCH_SIZE=20 DURATION=60 WARMUP=10 OVERLAP=0.8 RETRY=false
```

The retried demo should commit both workers, one using two transaction attempts. Ordered demo should commit both without a cycle. Both insert two distinct rows total. Compare each load's own time range with identical overlap, workers, and batch size.

Under unordered load, attempts/retries can exceed successful batches. Retry catches 40P01 outside the aborted transaction and retries the entire batch in a new transaction, bounded to three attempts. Exhaustion still returns 409. **Committed inserted rows** excludes conflicts and rolled-back writes. Application and database deadlock counts overlap: do not add them together.

Ordered load sorts every batch by `(shop_id, product_id)` and should avoid this specific cycle. Compare deadlocks, successful batches, committed rows, and p95. Counts depend on scheduling; no exact improvement is promised. Every participating writer must use the same order, which does not prevent every possible deadlock.

Where to inspect/change:

- `OfferService.register`: sorting, transaction boundary, whole-transaction retry, post-commit counters.
- `OfferRepository.insert`: `INSERT ... ON CONFLICT DO NOTHING`.
- `DeadlockDemoService`: deterministic coordination; ordered mode deliberately has no barrier after both first inserts.
- `LoadGenerator.runImportRound`: fresh keys, overlap, shuffled batches.

Change `MODE`, `RETRY`, `WORKERS`, `BATCH_SIZE`, or `OVERLAP` in commands; no code edit is required.

### Step 3 — Finish and clean up

```sh
make stop-load
make reset-imports
```

The generator normally exits automatically. Save time ranges and response/log evidence before cleanup. Leave the stack running for another case or use `make down` to remove containers while preserving volume data.

## Case 2: a poorly matched composite index

### Step 1 — Seed and measure the baseline

```sh
make up
make stop-load
make seed SIZE=small
make index-none
make explain-offers PRODUCT_ID=777
make load-reads WORKERS=8 WARMUP=10 DURATION=60 PRODUCT_ID=777
```

**Seed replaces all business data.** It creates 400,000 unique shop/product offers using fixed formulas/seed 42 and runs ANALYZE. Product 777 has a higher price band to expose broad price-ordered traversal. `index-none` removes experimental indexes but preserves primary-key and unique-constraint indexes.

Open **Composite indexes**. Record **Offer query p50 / p95 / p99**, **Offer queries / second**, and **Measured client throughput**. Database buffer panels cover the whole database, not one query; a read may come from the OS cache, not disk.

In EXPLAIN, inspect the scan, sort, rows, and buffers. Baseline need not use a sequential scan: the unique constraint index can still be useful. `EXPLAIN (ANALYZE, BUFFERS)` executes the query; its initial execution is not a controlled warm-cache benchmark.

### Step 2 — Compare poor and good indexes

```sh
make index-poor
make explain-offers PRODUCT_ID=777
make load-reads WORKERS=8 WARMUP=10 DURATION=60 PRODUCT_ID=777
make index-good
make explain-offers PRODUCT_ID=777
make load-reads WORKERS=8 WARMUP=10 DURATION=60 PRODUCT_ID=777
docker compose exec -T postgres psql -U priceradar -d priceradar -c '\d offers'
```

The commands install these alternatives one at a time and run ANALYZE:

```sql
-- poor
CREATE INDEX offers_poor_idx ON offers(available, current_price, product_id);
-- good
CREATE INDEX offers_good_idx ON offers(product_id, available, current_price, id);
```

The query filters one product and available offers, then orders by `current_price, id`. The poor index may traverse prices for many other products before finding twenty relevant rows. A trailing product Index Cond does not guarantee a narrow traversal. The good index groups relevant offers in the requested order, including the ID tie-breaker.

Compare separate time ranges: **metrics have no index-variant label**, so one mixed interval cannot separate states. Use EXPLAIN for per-query buffers/plan shape, SQL histograms for retrieval latency, and measured client throughput for warm-up-free request rate. Faster closed-loop replies can produce more requests in the same duration.

No planner settings force scans. PostgreSQL can choose repeated searches, incremental sort, skip scans, or other strategies; inspect the actual plan. Boolean columns are not inherently bad index candidates. Try `PRODUCT_ID=1` as a separate contrasting experiment.

Where to inspect/change:

- `OfferRepository.CHEAPEST_SQL`: filtering, deterministic ordering, limit.
- `LabRepository.index`: index definitions and switching.
- `LabRepository.seed`: reproducible dataset distribution.

Switch with `index-none`, `index-poor`, or `index-good`; no rebuild is needed. If modifying SQL/index code, rebuild with `make up` and repeat equivalent workloads.

### Step 3 — Finish and restore baseline

```sh
make stop-load
make index-none
```

This removes experimental indexes without deleting offers or constraint indexes. Save plans and measurement ranges. Optionally run `make down`.

## Case 3: unnecessary historical-minimum UPDATEs

### Step 1 — Seed and run naive updates

```sh
make up
make stop-load
make seed SIZE=small
make reset-minima
make load-updates MODE=naive WORKERS=8 BATCH_SIZE=20 WARMUP=10 DURATION=120 NEW_MINIMUM_RATE=0.01
```

**Seed replaces business data; reset-minima replaces historical minima from current prices.** Both modes initialize missing rows equivalently. Do not change current offer prices between comparisons.

Open **Historical minimum UPDATEs**. Compare **Observation throughput** with **Minimum rows updated / initialized**: naive mode updates every observation on an existing minimum, even when `LEAST` returns the existing value. Inspect **Observation batch p95**, **WAL generated / second**, **Live and dead row estimates**, **Physical updates and HOT updates**, **Table and index bytes**, and **Vacuum runs**.

Workers own disjoint offers and use fixed random seeds. With probability 0.01 an observation lowers a locally tracked minimum by one cent; otherwise it is 100–10,099 cents higher. Minima stop decreasing at zero. This proportion is probabilistic, not an exact quota. No timestamp mutation obscures the experiment.

### Step 2 — Reset and run conditional updates

```sh
make reset-minima
make load-updates MODE=conditional WORKERS=8 BATCH_SIZE=20 WARMUP=10 DURATION=120 NEW_MINIMUM_RATE=0.01
```

Only `MODE` changes. Conditional SQL updates when the observed price is below the minimum. Expect far fewer actual updates than processed observations. Compare updated rows **relative to processed observations**, latency, WAL, HOT updates, and vacuum using separate measured time ranges. Higher throughput can offset some per-observation WAL savings in bytes/second; raw WAL rate alone does not quantify efficiency.

WAL is cluster-wide generated bytes, not retained files. The interval panel is a scrape-based estimate; exclude reset/setup/warm-up. Tuple estimates are delayed and sizes are not precise bloat measurements. Autovacuum stays enabled: dead tuples and table size need not grow indefinitely. HOT avoids some index maintenance but still creates versions and WAL.

Duration runs process different observation counts. To verify identical business results, run fixed streams and compare the printed checksums:

```sh
make reset-minima
make load-updates MODE=naive WORKERS=4 BATCH_SIZE=20 ROUNDS=100 NEW_MINIMUM_RATE=0.01
docker compose exec -T postgres psql -U priceradar -d priceradar -Atc \
  "SELECT md5(string_agg(offer_id::text || ':' || min_price::text, ',' ORDER BY offer_id)) FROM offer_minimum_prices"
make reset-minima
make load-updates MODE=conditional WORKERS=4 BATCH_SIZE=20 ROUNDS=100 NEW_MINIMUM_RATE=0.01
docker compose exec -T postgres psql -U priceradar -d priceradar -Atc \
  "SELECT md5(string_agg(offer_id::text || ':' || min_price::text, ',' ORDER BY offer_id)) FROM offer_minimum_prices"
```

Expect 8,000 observations per mode, no failures, and identical checksums; conditional updates fewer rows. `ROUNDS` skips warm-up and can finish too quickly for meaningful rates: this is a correctness check, not the timed comparison. [Manual verification](manual-verification.md) also includes a two-observation example with explicit affected-row counts.

Where to inspect/change:

- `MinimumRepository.initialize` / `update`: initialization and alternative SQL.
- `MinimumService.observe`: transaction and committed counters.
- `LoadGenerator.observations`: distribution and random seeds.
- `DatabaseMetrics`: five-second database statistics collection.

Change `MODE` or `NEW_MINIMUM_RATE` in commands; no code edit is needed.

### Step 3 — Finish, vacuum, and restore minima

```sh
make stop-load
make vacuum
# Inspect row estimates and sizes before resetting:
make reset-minima
```

Ordinary VACUUM generally makes space reusable rather than returning it to the OS; stable size is expected. Inspect the dashboard after vacuum and before reset. `reset-minima` then rebuilds the initial minimum data but does not reset database-wide/application counters. Optionally run `make down`.

If ordinary load does not show accumulation, use this **optional controlled cleanup-prevention experiment**:

```sh
# Before either terminal starts:
make stop-load
make reset-minima
# Terminal A: bounded old read-only snapshot
make hold-snapshot SECONDS=120
# Terminal B: while A holds the snapshot
make load-updates MODE=naive WORKERS=8 DURATION=90 WARMUP=0
# After both commands finish:
make stop-load
make vacuum
make reset-minima
```

The old snapshot introduces an additional cause: vacuum cannot reclaim versions needed by that reader, even when autovacuum runs. The endpoint releases its transaction on completion/error and bounds duration to 180 seconds. Do not seed/reset while it holds the snapshot: TRUNCATE requires an incompatible lock. After release, vacuum can reclaim obsolete versions. Do not present controlled accumulation as inevitable under ordinary load.

## Stopping versus deleting

- `make stop-load`: stops only the generator.
- `make down`: removes containers, preserving database/monitoring volumes.
- `make reset`: **deletes this Compose project's database and monitoring volumes**. Use only for a completely fresh environment.

These are qualitative expectations, not promised benchmarks. [Actual verification results](verification-results.md) records previous runs and their limits.
