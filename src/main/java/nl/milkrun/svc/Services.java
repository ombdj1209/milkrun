package nl.milkrun.svc;

import nl.milkrun.msg.Broker;
import nl.milkrun.msg.Message;
import nl.milkrun.msg.Outbox;
import nl.milkrun.plan.Model.Order;
import nl.milkrun.plan.Model.Params;
import nl.milkrun.plan.Model.Solution;
import nl.milkrun.plan.Model.Vehicle;
import nl.milkrun.plan.Planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Three event-driven services: order intake, stock reservation, route planning. */
public final class Services {
    private Services() {}

    public record Decision(String orderId, String action, String detail) {}

    // ------------------------------------------------------------------ orders
    public static final class OrderService {
        final Outbox outbox;
        public final Map<String, Order> orders = new LinkedHashMap<>();
        public final Map<String, List<String>> lines = new HashMap<>();
        private int seq;

        public OrderService(Outbox outbox) { this.outbox = outbox; }

        public void place(Order o, List<String> skus) {
            outbox.tx(tx -> {
                tx.write(() -> { orders.put(o.id(), o); lines.put(o.id(), skus); });
                tx.emit(new Message("order-" + (++seq), "order.placed", o.id(),
                        Map.of("orderId", o.id(), "skus", String.join(",", skus))));
            });
        }
    }

    // ------------------------------------------------------------------- stock
    public static final class StockService {
        final Outbox outbox;
        public final Map<String, Integer> available = new HashMap<>();
        public final Map<String, List<String>> reservations = new HashMap<>();
        public final Set<String> processed = new HashSet<>();
        public long duplicatesIgnored;
        private int seq;

        public StockService(Outbox outbox, Broker in, Map<String, Integer> initial) {
            this.outbox = outbox;
            available.putAll(initial);
            in.subscribe("order.placed", this::onOrderPlaced);
            in.subscribe("stock.adjusted", this::onAdjusted);
        }

        void onOrderPlaced(Message m) {
            outbox.tx(tx -> {
                if (processed.contains(m.id())) { duplicatesIgnored++; return; }
                String orderId = m.get("orderId");
                tx.write(() -> processed.add(m.id()));
                if (reservations.containsKey(orderId)) return;   // business-key guard on top of message-id dedupe
                List<String> skus = List.of(m.get("skus").split(","));
                Map<String, Integer> need = new HashMap<>();
                for (String s : skus) need.merge(s, 1, Integer::sum);
                List<String> missing = new ArrayList<>();
                need.forEach((s, n) -> { if (available.getOrDefault(s, 0) < n) missing.add(s); });
                if (missing.isEmpty()) {
                    tx.write(() -> {
                        need.forEach((s, n) -> available.merge(s, -n, Integer::sum));
                        reservations.put(orderId, skus);
                    });
                    tx.emit(new Message("stock-" + (++seq), "stock.reserved", orderId, Map.of("orderId", orderId)));
                } else {
                    missing.sort(String::compareTo);
                    tx.emit(new Message("stock-" + (++seq), "stock.short", orderId,
                            Map.of("orderId", orderId, "missing", String.join(",", missing))));
                }
            });
        }

        /** Deltas are commutative, so arrival order does not matter; the message id makes them idempotent. */
        void onAdjusted(Message m) {
            outbox.tx(tx -> {
                if (processed.contains(m.id())) { duplicatesIgnored++; return; }
                tx.write(() -> {
                    processed.add(m.id());
                    available.merge(m.get("sku"), Integer.parseInt(m.get("delta")), Integer::sum);
                });
            });
        }
    }

    // ---------------------------------------------------------------- planning
    public static final class PlanningService {
        final Params params;
        final Planner planner;
        final List<Vehicle> vehicles;
        final Map<String, Order> catalog;
        final Set<String> inPlan = new HashSet<>();
        public final Set<String> processed = new HashSet<>();
        public final List<Order> pending = new ArrayList<>();
        public final List<Decision> log = new ArrayList<>();
        public final List<Long> lateInsertNanos = new ArrayList<>();
        public Solution plan;
        public long duplicatesIgnored;

        public PlanningService(Params p, long seed, List<Vehicle> vehicles, Map<String, Order> catalog, Broker in) {
            this.params = p;
            this.planner = new Planner(p, seed);
            this.vehicles = vehicles;
            this.catalog = catalog;
            in.subscribe("stock.reserved", this::onReserved);
            in.subscribe("stock.short", this::onShort);
        }

        void onReserved(Message m) {
            if (!processed.add(m.id())) { duplicatesIgnored++; return; }
            String id = m.get("orderId");
            if (!inPlan.add(id)) { duplicatesIgnored++; return; }
            Order o = catalog.get(id);
            if (plan == null) {
                pending.add(o);
                return;
            }
            Planner.LateResult r = planner.insertLate(plan, o);
            lateInsertNanos.add(r.nanos());
            log.add(new Decision(id, r.inserted() ? "late-insert" : "rejected", r.explanation()));
        }

        void onShort(Message m) {
            if (!processed.add(m.id())) { duplicatesIgnored++; return; }
            log.add(new Decision(m.get("orderId"), "not-planned", "stock short: " + m.get("missing")));
        }

        public Planner.LnsStats build(int lnsIterations) {
            pending.sort((a, b) -> a.id().compareTo(b.id()));   // arrival order must not change the plan
            plan = planner.regretInsertion(vehicles, pending);
            Planner.LnsStats st = planner.improve(plan, lnsIterations);
            for (Order o : plan.unassigned) log.add(new Decision(o.id(), "unassigned", planner.explain(plan, o)));
            pending.clear();
            return st;
        }
    }
}
