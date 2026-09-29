# Milkrun

**Event-driven order-to-route pipeline for grocery delivery, built to stay correct when messaging misbehaves.** Order intake, stock reservation and van route planning run as three services connected by an at-least-once broker and transactional outboxes. The router plans electric vans with 60-minute customer slots, capacity and battery range, re-plans live as late orders arrive, and explains every decision. A chaos mode injects duplicate deliveries, reordering and relay crashes, and an invariant checker proves nothing was oversold, planned twice or silently dropped.

Java 21 with **zero dependencies**: it compiles and runs with a bare JDK. All orders, stock and geography are synthetic.

## Why this exists

Picnic's Warehouse Systems and delivery teams build in-house Java services on Spring, PostgreSQL, RabbitMQ and Kafka, and route their own electric vans. Two properties matter most in that world, and they are easy to claim and hard to show:

1. **Correctness under at-least-once delivery.** Duplicates and reordering are normal. Effects must still happen exactly once.
2. **Planning quality with a live plan.** Late orders must slot into tonight's routes in milliseconds, or be rejected with a reason a person can act on.

Milkrun makes both measurable.

## Architecture

```
OrderService ──outbox──► order.placed ──► StockService ──outbox──► stock.reserved ──► PlanningService ──► plan + decision log
                                           │  idempotent             stock.short    ──►   (regret-2 + LNS,       │
                                           │  (message id +                                late insertion)        ▼
            stock.adjusted (restock deltas) ┘   business key)                                              ControlTower (HTTP)
                       ▲
                Broker: at-least-once, seeded chaos (duplicates, bounded reordering); Outbox relay: seeded crashes
```

| Piece | File |
|---|---|
| Routes, time windows, capacity, EV range, shift end | `plan/Model.java` |
| Nearest neighbour, regret-2 insertion, large neighbourhood search, live insertion, explanations | `plan/Planner.java` |
| At-least-once broker with duplicates and reordering | `msg/Broker.java` |
| Transactional outbox with atomic commit and crash-and-resend relay | `msg/Outbox.java` |
| Order, stock and planning services with idempotent consumers | `svc/Services.java` |
| Invariant checker and benchmark | `App.java` |
| Control tower: plan, decisions and KPIs as JSON and HTML | `api/ControlTower.java` |

## Results

`./build.sh run` on one CPU core, Java 21. Each evening has 200 orders, 25 late orders and 12 vans (48 totes, 90 km range each); five seeds.

### Route quality

| Method | Mean km | Vans used | Orders left unassigned | Mean time |
|---|---|---|---|---|
| Nearest neighbour | 474.6 | 12.0 | **34.2** | 24 ms |
| Regret-2 insertion | 539.7 | 10.8 | 0 | 110 ms |
| Regret-2 + LNS (1,500 iterations) | **419.4** | 10.8 | 0 | 3.1 s |

Nearest neighbour looks short only because it leaves a sixth of the orders behind. Regret insertion places every order. LNS then cuts distance by **22% on average (16% to 29% across seeds)** with the same fleet. Every plan passed an independent feasibility check on windows, capacity, range and shift end.

### Correctness under chaos

Ten evenings: five clean, five with heavy chaos (30% duplicate deliveries, reordering within an 8-message window, 10% relay crashes after publish).

| | Clean | Heavy chaos (per evening) |
|---|---|---|
| Duplicate deliveries injected | 0 | 158 to 167 |
| Messages reordered | 0 | 291 to 324 |
| Outbox relay crashes (re-sends) | 0 | 42 to 60 |
| Duplicates ignored by consumers | 0 | 203 to 221 |
| **Invariant violations** | **0** | **0** |
| Replay with the same seed gives an identical plan | 5 of 5 | 5 of 5 |

The invariants checked on every run: stock never goes negative; no order is planned twice or both planned and unassigned; every reservation is planned or explained; no order is planned without a reservation; every order ends up planned or with a logged reason; every route is feasible.

### Live re-planning

Late orders arrive after the plan is built, alongside a restock. 114 of 117 late orders were inserted in the clean runs. The other 3 were rejected with a reason that counts, per constraint, how many vans rule the order out (time window, capacity, range or shift end). Insertion takes **0.06 to 0.07 ms at p50 and at most 0.12 ms at p99**, apart from one 3.0 ms JIT warm-up outlier in the first run. Stock-short orders (2% to 15% per evening) are logged as `not-planned` with the missing SKU.

## Run it

```bash
./build.sh test    # 10 tests: constraint checks, feasibility, LNS monotonicity, replay, outbox atomicity,
                   #           crash-and-resend exactly-once effects, chaos invariants, order independence
./build.sh run     # benchmark, about a minute; writes results/results.json and results/control-tower.html
./build.sh serve   # control tower on http://localhost:8080  (/api/plan, /api/decisions, /api/kpis)
```

Only a JDK 21 is needed. The script uses `javac` if present, otherwise the JDK's built-in compiler module.

## Design notes

- **Idempotency at two levels.** Consumers deduplicate on message id (catches broker duplicates and relay re-sends) and on business key (catches the same order arriving under a new message id).
- **Commutative events where possible.** Restocks are deltas, so their arrival order does not matter. Only idempotency is needed, not ordering.
- **The outbox is all-or-nothing.** State changes and outgoing events commit together or not at all (tested with a failing transaction).
- **Replayable planning.** The planner is seeded and iteration-bounded (never time-bounded), and it sorts its input, so the same evening always gives the same plan.
- **Explanations are computed, not written.** A rejection reports, per van, the constraint that rules the order out at its least-bad position.
- **Regret insertion caches options per route.** After each insertion only the modified route is re-evaluated, which keeps reinsertion fast enough to run inside LNS.

## Limitations

- The broker and outbox are in-memory stand-ins for RabbitMQ and a PostgreSQL outbox table. The semantics are modelled; network partitions and consumer rebalancing are not.
- Under chaos, *which* orders win scarce stock can change with arrival order (the invariants still hold). A real system would need an explicit allocation policy for contested stock.
- Travel times are Euclidean distance times a detour factor, at constant speed. There is no road network, traffic or charging stop.
- Each van runs one trip. Multi-trip days, driver breaks and hub loading capacity are not modelled.
- It is framework-free by choice, so it stays runnable anywhere. A Spring Boot shell would wrap the same service classes.

## Next steps

1. Spring Boot adapters for the services, with RabbitMQ and a PostgreSQL outbox, using Testcontainers for the same chaos suite.
2. A road-network travel-time matrix (OSM-based) and charging stops.
3. Run Picnic's open-source error-prone-support checks in CI.
4. A contested-stock policy (earliest slot first, or loyalty-weighted) with fairness metrics.
