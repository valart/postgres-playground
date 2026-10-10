SHELL := /bin/sh
.DEFAULT_GOAL := help
MODE ?= unordered
WORKERS ?= 8
BATCH_SIZE ?= 20
DURATION ?= 60
WARMUP ?= 10
ROUNDS ?= 0
OVERLAP ?= 0.8
RETRY ?= false
NEW_MINIMUM_RATE ?= 0.01
PRODUCT_ID ?= 777
SIZE ?= small
SECONDS ?= 120
API ?= http://localhost:8080
COMPOSE := docker compose
LOAD_ENV = WORKERS=$(WORKERS) BATCH_SIZE=$(BATCH_SIZE) DURATION=$(DURATION) WARMUP=$(WARMUP) ROUNDS=$(ROUNDS) OVERLAP=$(OVERLAP) RETRY=$(RETRY) NEW_MINIMUM_RATE=$(NEW_MINIMUM_RATE) PRODUCT_ID=$(PRODUCT_ID)

.PHONY: help up down seed reset reset-minima reset-imports demo-deadlock demo-deadlock-ordered load-deadlock index-none index-poor index-good explain-offers load-reads load-updates vacuum hold-snapshot stop-load logs build
help:
	@echo 'PriceRadar PostgreSQL laboratory'
	@echo 'up / down              Start stack / stop stack (keeps data)'
	@echo 'build                  Compile app with Gradle Wrapper and CLI with javac in Docker'
	@echo 'seed SIZE=small|large   DESTRUCTIVE: replace all business data'
	@echo 'reset                  DESTRUCTIVE: delete Compose volumes and containers'
	@echo 'reset-minima           DESTRUCTIVE: replace minima from current prices'
	@echo 'reset-imports          DESTRUCTIVE: remove reserved lab import data'
	@echo 'demo-deadlock [RETRY=true] / demo-deadlock-ordered'
	@echo 'load-deadlock MODE=unordered|ordered WORKERS=8 BATCH_SIZE=20 DURATION=60 OVERLAP=0.8 RETRY=false'
	@echo 'index-none / index-poor / index-good / explain-offers PRODUCT_ID=777'
	@echo 'load-reads DURATION=60 WARMUP=10 PRODUCT_ID=777'
	@echo 'load-updates MODE=naive|conditional DURATION=120 NEW_MINIMUM_RATE=0.01'
	@echo 'load targets also accept ROUNDS=100 for repeatable request counts (no warm-up)'
	@echo 'hold-snapshot SECONDS=120  Optional controlled cleanup prevention; use another terminal'
	@echo 'vacuum / stop-load / logs'

up:
	$(COMPOSE) up -d --build --wait
down:
	$(COMPOSE) --profile load down
build:
	$(COMPOSE) --profile load build
seed:
	curl -fsS -X POST '$(API)/lab/reset-and-seed?size=$(SIZE)'
reset: stop-load
	@echo 'DESTRUCTIVE: deleting this project database and monitoring volumes'
	$(COMPOSE) --profile load down -v
reset-minima:
	curl -fsS -X POST '$(API)/lab/reset-minima'
reset-imports:
	curl -fsS -X POST '$(API)/lab/reset-imports'
demo-deadlock:
	curl -fsS -X POST '$(API)/lab/demo-deadlock?mode=unordered&retry=$(RETRY)'
	@echo
demo-deadlock-ordered:
	curl -fsS -X POST '$(API)/lab/demo-deadlock?mode=ordered'
	@echo
index-none index-poor index-good:
	curl -fsS -X POST '$(API)/lab/index/$(patsubst index-%,%,$@)'
explain-offers:
	curl -fsS '$(API)/lab/explain-offers?productId=$(PRODUCT_ID)'
load-deadlock: stop-load
	$(LOAD_ENV) SCENARIO=deadlock MODE=$(MODE) $(COMPOSE) --profile load up --build --no-deps --force-recreate --abort-on-container-exit --exit-code-from load load
load-reads: stop-load
	$(LOAD_ENV) SCENARIO=reads MODE=ordered $(COMPOSE) --profile load up --build --no-deps --force-recreate --abort-on-container-exit --exit-code-from load load
load-updates: MODE = conditional
load-updates: stop-load
	$(LOAD_ENV) SCENARIO=updates MODE=$(MODE) $(COMPOSE) --profile load up --build --no-deps --force-recreate --abort-on-container-exit --exit-code-from load load
vacuum:
	curl -fsS -X POST '$(API)/lab/vacuum'
hold-snapshot:
	curl -fsS -X POST '$(API)/lab/hold-snapshot?seconds=$(SECONDS)'
stop-load:
	$(COMPOSE) --profile load stop load
logs:
	$(COMPOSE) logs --tail=100 app postgres
