# Experiment walkthrough

Use the small dataset, ten seconds of warm-up, and measurement periods of sixty seconds for reads and 120 seconds for updates. Record hardware, image versions, workload settings, and the Grafana time range with results. Keep one experiment active at a time. Commands and reset requirements are detailed in the [README](../README.md).

## Application and schema

The Flyway migration defines shops, products, offers, and historical minimum prices. The unique shop/product constraint prevents duplicate offers. Prices use integer EUR cents. Current catalog prices and historical minima are separate business operations.

Requests follow Controller → Service → Repository. Start with `ShopController`, `OfferController`, and `api.http`; inspect the SQL in their repositories and the standalone request/response records in the domain model packages.

## INSERT: overlapping transactions

`OfferRepository.insert` uses `INSERT ... ON CONFLICT DO NOTHING`. `OfferService.register` applies ordering, establishes the transaction boundary, catches SQLSTATE 40P01 outside the transaction, and records committed-row counters after commit.

Run `make demo-deadlock`. Transaction A owns key X and waits for Y; transaction B owns Y and attempts X, completing the cycle. Inspect the response and PostgreSQL logs with `make logs`. `DeadlockDemoService` coordinates this using observed lock state rather than timing alone. Run `make demo-deadlock-ordered` and `make demo-deadlock RETRY=true` to compare prevention and recovery. Ordered coordination does not wait for both first inserts: one participant can already be blocked on the first key.

Run unordered and ordered loads using identical overlap, worker count, and batch size. Compare the INSERT dashboard's deadlocks, transaction attempts, retries, successful batches, and committed rows. More INSERT volume alone is insufficient: this cycle needs overlapping keys and inconsistent insertion order. Consistent ordering prevents this cycle, not every possible deadlock.

## Index: filtering and ordering

Inspect `OfferRepository.CHEAPEST_SQL`, `LabRepository.index`, and the seed formula. The query orders by price and ID; product 777 occupies a higher price band to expose the poorly matched index's traversal cost.

Seed once, then compare `make index-none`, `make index-poor`, and `make index-good`. After each switch, run `make explain-offers` and an equivalent read workload. Inspect scan type, sort or incremental sort, index conditions, actual rows, buffers, and execution time. A trailing product index condition can coexist with a broad price-ordered traversal. Describe the actual selected plan rather than assuming one.

Compare SQL latency percentiles, query throughput, measured client throughput, and database read/hit rates in the Composite indexes dashboard. These are warm-cache, closed-loop workloads. Use `PRODUCT_ID=1` for a contrasting distribution. Boolean columns are not inherently bad index candidates, and no exact speedup is guaranteed.

## UPDATE: unchanged values still create work

Inspect `MinimumRepository.initialize` and `update` for naive and conditional SQL. There is no timestamp mutation. `LoadGenerator.observations` constructs the reproducible workload: by default, approximately 1% of observations lower a locally tracked minimum and the rest exceed it.

Use equivalent minimum resets and fixed `ROUNDS` workloads to compare resulting checksums; see [manual verification](manual-verification.md). Then run duration-based comparisons from equivalent resets. The Historical minimum UPDATE dashboard covers processed observations, updated rows, latency, WAL rate and interval bytes, table/index sizes, tuple estimates, HOT updates, and vacuum activity.

MVCC updates create row versions. HOT can reduce index maintenance but does not eliminate row versions or WAL. Vacuum enables space reuse, so stable table size does not mean unnecessary updates are free. Tuple estimates are not precise bloat measurements; generated WAL is not WAL retained on disk.

The optional bounded old-snapshot experiment introduces an additional cleanup constraint. Run vacuum after snapshot release. Do not interpret its accumulation as inevitable under ordinary autovacuum.
