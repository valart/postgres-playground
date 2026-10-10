# Manual verification checklist

No automated test suite is included. Run these steps with only this project's containers and no other lab loads. The destructive reset commands operate on this local educational database.

## Build and start

```sh
make build
make up
docker compose ps
curl -fsS localhost:8080/actuator/health
```

The Docker build compiles the Java application with Gradle Wrapper (`bootJar`) and the CLI with `javac --release 25`. The health response should be UP. Host Java and Gradle are unnecessary. Follow `api.http` before seeding to create/read shop 1 and product 1, insert an offer, patch it, submit observations, and read its minimum. Database constraints should reject negative prices and missing shop/product references. `limit=1000` and a wrong mode should return HTTP 400; a missing shop should return 404.

## Deterministic INSERTs

```sh
make demo-deadlock
make demo-deadlock-ordered
make demo-deadlock RETRY=true
make logs
```

Unordered: `observedWait=true`, one `committed` worker inserting two rows and one `40P01` worker inserting zero. Ordered: both workers commit, total inserted rows two, `observedWait=false`. Retried unordered: both commit, one worker reports two attempts, total inserted rows two. Which worker PostgreSQL chooses as victim is not fixed. The demo uses fresh products each time.

```sh
make load-deadlock MODE=unordered WORKERS=4 BATCH_SIZE=20 ROUNDS=10 OVERLAP=1 RETRY=true
make load-deadlock MODE=ordered WORKERS=4 BATCH_SIZE=20 ROUNDS=10 OVERLAP=1 RETRY=true
```

Load scheduling is intentionally uncontrolled, so deadlock counts vary. On success, a round with full overlap commits 20 distinct offers across all logical batches, not 80. Attempt/retry counters can increase without inflating committed rows.

## Query indexes and plans

```sh
make seed
make index-none
make explain-offers
make load-reads WARMUP=10 DURATION=60
make index-poor
make explain-offers
make load-reads WARMUP=10 DURATION=60
make index-good
make explain-offers
make load-reads WARMUP=10 DURATION=60
docker compose exec -T postgres psql -U priceradar -d priceradar -c '\d offers'
```

Verify that only one experimental index exists at a time, while the primary key and unique shop/product index survive. Record plans and buffer counts. No scan preferences are overridden. Do not prescribe an exact plan shape or speedup.

## Historical minimum correctness and skipped updates

```sh
make reset-minima
curl -fsS -X POST 'localhost:8080/api/price-observations/batch?mode=naive' \
  -H 'Content-Type: application/json' \
  -d '{"observations":[{"offerId":1,"observedPrice":100},{"offerId":1,"observedPrice":200}]}'
curl -fsS localhost:8080/api/offers/1/minimum
make reset-minima
curl -fsS -X POST 'localhost:8080/api/price-observations/batch?mode=conditional' \
  -H 'Content-Type: application/json' \
  -d '{"observations":[{"offerId":1,"observedPrice":100},{"offerId":1,"observedPrice":200}]}'
curl -fsS localhost:8080/api/offers/1/minimum
```

After seed, both minima should be 100. Naive affected updates: 2. Conditional: 1. For a missing minimum, delete one minimum row using psql and send an observation: both modes should return `initialized=1,updated=0` and the same minimum. Do not delete the offer itself.

For an entire repeatable stream, run equal **round counts**, not just equal durations:

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

Checksums should match if all requests succeed. Workers own disjoint offers. This avoids timing-dependent changes to the per-worker random streams. `ROUNDS` turns off warm-up automatically. Check load logs for failures. Both workloads should process `workers × rounds × batch size` observations; conditional should physically update far fewer rows. Dashboard counters are cumulative across resets: use rates/increases or differences from a captured baseline.

## Monitoring

```sh
curl -fsS localhost:8080/actuator/prometheus
curl -fsS 'localhost:9090/api/v1/targets'
curl -fsS 'localhost:9090/api/v1/query?query=up'
curl -fsS -u admin:priceradar 'localhost:3000/api/search?query=PriceRadar'
```

`priceradar` target should be UP. `load` is UP only during a workload and its final ten-second scrape window. Open Grafana at localhost:3000, log in with admin/priceradar, and open the four provisioned dashboards. Run a measured workload for at least 60 seconds to populate 30-second rate windows. No data before the first request is normal. Check collector availability = 1. Validate dashboard expressions through Prometheus's expression browser if a panel is empty.

See `verification-results.md` for the actual environment results, which are separate from these expected outcomes.
