package nl.milkrun;

import nl.milkrun.api.ControlTower;
import nl.milkrun.msg.Broker;
import nl.milkrun.msg.Chaos;
import nl.milkrun.msg.Message;
import nl.milkrun.msg.Outbox;
import nl.milkrun.plan.Model.Order;
import nl.milkrun.plan.Model.Params;
import nl.milkrun.plan.Model.Solution;
import nl.milkrun.plan.Planner;
import nl.milkrun.svc.Services.OrderService;
import nl.milkrun.svc.Services.PlanningService;
import nl.milkrun.svc.Services.StockService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class App {

    /** One evening end to end: intake, stock, planning; then restock and late orders on the live plan. */
    public static final class Run {
        public Scenario sc;
        public Params params;
        public Broker broker;
        public Outbox orderOutbox, stockOutbox;
        public OrderService orders;
        public StockService stock;
        public PlanningService planning;
        public Planner.LnsStats lns;
        public long planMillis;
        public final List<String> invariantViolations = new ArrayList<>();

        public long relayCrashes() { return orderOutbox.relayCrashes + stockOutbox.relayCrashes; }

        public long duplicatesIgnored() { return stock.duplicatesIgnored + planning.duplicatesIgnored; }

        public long count(String action) { return planning.log.stream().filter(d -> d.action().equals(action)).count(); }
    }

    public static Run run(long seed, Chaos chaos, int nOrders, int nLate, int nVehicles, int lnsIters) {
        Run r = new Run();
        r.sc = new Scenario(seed, nOrders, nLate, nVehicles);
        r.params = Params.defaults(r.sc.hub);
        r.broker = new Broker(chaos);
        r.orderOutbox = new Outbox(chaos, 1);
        r.stockOutbox = new Outbox(chaos, 2);
        r.orders = new OrderService(r.orderOutbox);
        r.stock = new StockService(r.stockOutbox, r.broker, r.sc.stock);
        Map<String, Order> catalog = new HashMap<>();
        for (Order o : r.sc.orders) catalog.put(o.id(), o);
        for (Order o : r.sc.lateOrders) catalog.put(o.id(), o);
        r.planning = new PlanningService(r.params, seed, r.sc.vehicles, catalog, r.broker);

        for (Order o : r.sc.orders) r.orders.place(o, r.sc.skus.get(o.id()));
        pump(r.broker, r.orderOutbox, r.stockOutbox);

        long t0 = System.nanoTime();
        r.lns = r.planning.build(lnsIters);
        r.planMillis = (System.nanoTime() - t0) / 1_000_000;

        int adj = 0;
        for (String sku : r.sc.stock.keySet().stream().sorted().limit(20).toList())
            r.broker.publish(new Message("adj-" + (adj++), "stock.adjusted", sku, Map.of("sku", sku, "delta", "12")));
        for (Order o : r.sc.lateOrders) r.orders.place(o, r.sc.skus.get(o.id()));
        pump(r.broker, r.orderOutbox, r.stockOutbox);
        checkInvariants(r);
        return r;
    }

    static void pump(Broker b, Outbox... outboxes) {
        for (int guard = 0; guard < 10_000; guard++) {
            boolean work = false;
            for (Outbox o : outboxes) if (o.hasPending()) { o.relay(b); work = true; }
            if (!b.idle()) { b.drain(); work = true; }
            if (!work) return;
        }
        throw new IllegalStateException("pipeline did not quiesce");
    }

    static void checkInvariants(Run r) {
        r.stock.available.forEach((s, n) -> { if (n < 0) r.invariantViolations.add("oversold " + s + " = " + n); });
        Set<String> seen = new HashSet<>();
        Solution plan = r.planning.plan;
        for (var route : plan.routes) for (Order o : route.stops)
            if (!seen.add(o.id())) r.invariantViolations.add("planned twice: " + o.id());
        for (Order o : plan.unassigned) if (!seen.add(o.id())) r.invariantViolations.add("planned and unassigned: " + o.id());
        for (String id : r.stock.reservations.keySet())
            if (!seen.contains(id)) r.invariantViolations.add("reserved but neither planned nor explained: " + id);
        for (String id : seen)
            if (!r.stock.reservations.containsKey(id)) r.invariantViolations.add("planned without reservation: " + id);
        if (!plan.feasible(r.params)) r.invariantViolations.add("infeasible route in plan");
        Set<String> explained = new HashSet<>();
        r.planning.log.forEach(d -> explained.add(d.orderId()));
        for (String id : r.orders.orders.keySet())
            if (!seen.contains(id) && !explained.contains(id)) r.invariantViolations.add("order silently dropped: " + id);
    }

    static double pct(List<Long> xs, double q) {
        if (xs.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(xs);
        s.sort(Long::compare);
        return s.get(Math.min(s.size() - 1, (int) Math.ceil(q * s.size()) - 1)) / 1e6;
    }

    public static void main(String[] args) throws Exception {
        boolean serve = List.of(args).contains("--serve");
        int lnsIters = 1500, nOrders = 200, nLate = 25, nVehicles = 12;

        StringBuilder bench = new StringBuilder("[");
        System.out.println("seed  method               km      vans  unassigned     ms");
        for (long seed = 1; seed <= 5; seed++) {
            Scenario sc = new Scenario(seed, nOrders, 0, nVehicles);
            Params p = Params.defaults(sc.hub);
            record Row(String m, Solution s, long ms) {}
            List<Row> rows = new ArrayList<>();
            long t0 = System.nanoTime();
            Solution nn = new Planner(p, seed).nearestNeighbour(sc.vehicles, sc.orders);
            rows.add(new Row("nearest-neighbour", nn, (System.nanoTime() - t0) / 1_000_000));
            t0 = System.nanoTime();
            Planner pl = new Planner(p, seed);
            Solution rg = pl.regretInsertion(sc.vehicles, sc.orders);
            rows.add(new Row("regret-2 insertion", rg, (System.nanoTime() - t0) / 1_000_000));
            Solution ln = rg.copy();
            t0 = System.nanoTime();
            pl.improve(ln, lnsIters);
            rows.add(new Row("regret-2 + LNS", ln, (System.nanoTime() - t0) / 1_000_000 + rows.get(1).ms));
            for (Row r : rows) {
                if (!r.s.feasible(p)) throw new IllegalStateException("infeasible plan from " + r.m);
                System.out.printf("%-5d %-19s %7.1f  %4d  %10d  %5d%n", seed, r.m, r.s.km(p), r.s.vehiclesUsed(), r.s.unassigned.size(), r.ms);
                bench.append(String.format("{\"seed\":%d,\"method\":\"%s\",\"km\":%.2f,\"vans\":%d,\"unassigned\":%d,\"ms\":%d},",
                        seed, r.m, r.s.km(p), r.s.vehiclesUsed(), r.s.unassigned.size(), r.ms));
            }
        }
        bench.setLength(bench.length() - 1);
        bench.append(']');

        StringBuilder pipe = new StringBuilder("[");
        Run shown = null;
        for (String mode : List.of("none", "heavy")) {
            for (long seed = 1; seed <= 5; seed++) {
                Chaos c = mode.equals("none") ? Chaos.none() : Chaos.heavy(seed);
                Run a = run(seed, c, nOrders, nLate, nVehicles, lnsIters);
                Run b = run(seed, c, nOrders, nLate, nVehicles, lnsIters);   // replay the same evening
                boolean replay = a.planning.plan.fingerprint().equals(b.planning.plan.fingerprint());
                double km = a.planning.plan.km(a.params);
                int planned = a.planning.plan.planned();
                long lateIn = a.count("late-insert"), lateRej = a.count("rejected");
                pipe.append(String.format(
                        "{\"chaos\":\"%s\",\"seed\":%d,\"delivered\":%d,\"duplicatesInjected\":%d,\"reorders\":%d,\"relayCrashes\":%d," +
                        "\"duplicatesIgnored\":%d,\"invariantViolations\":%d,\"replayIdentical\":%b,\"planned\":%d,\"km\":%.1f," +
                        "\"lateInserted\":%d,\"lateRejected\":%d,\"stockShort\":%d,\"unassigned\":%d," +
                        "\"lateInsertP50ms\":%.3f,\"lateInsertP99ms\":%.3f,\"planMs\":%d},",
                        mode, seed, a.broker.delivered, a.broker.duplicatesInjected, a.broker.reorders, a.relayCrashes(),
                        a.duplicatesIgnored(), a.invariantViolations.size(), replay, planned, km, lateIn, lateRej,
                        a.count("not-planned"), a.count("unassigned"),
                        pct(a.planning.lateInsertNanos, 0.5), pct(a.planning.lateInsertNanos, 0.99), a.planMillis));
                System.out.printf("chaos=%-5s seed=%d delivered=%d dupInjected=%d reorders=%d crashes=%d dupIgnored=%d violations=%d replay=%s planned=%d late=%d/%d p99=%.2fms%n",
                        mode, seed, a.broker.delivered, a.broker.duplicatesInjected, a.broker.reorders, a.relayCrashes(),
                        a.duplicatesIgnored(), a.invariantViolations.size(), replay, planned, lateIn, lateIn + lateRej,
                        pct(a.planning.lateInsertNanos, 0.99));
                a.invariantViolations.forEach(v -> System.out.println("  VIOLATION " + v));
                if (mode.equals("heavy") && seed == 1) shown = a;
            }
        }
        pipe.setLength(pipe.length() - 1);
        pipe.append(']');

        String env = String.format("{\"java\":\"%s\",\"os\":\"%s %s\",\"cpus\":%d}", Runtime.version(),
                System.getProperty("os.name"), System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors());
        String kpis = String.format("{\"environment\":%s,\"lnsIterations\":%d,\"orders\":%d,\"lateOrders\":%d,\"vans\":%d,\"planning\":%s,\"pipeline\":%s}",
                env, lnsIters, nOrders, nLate, nVehicles, bench, pipe);
        Path out = Path.of("results");
        Files.createDirectories(out);
        Files.writeString(out.resolve("results.json"), kpis);
        String plan = ControlTower.planJson(shown.planning.plan, shown.params);
        String dec = ControlTower.decisionsJson(shown.planning.log);
        String summary = String.format("Synthetic evening (seed 1, heavy chaos): %d orders planned on %d vans, %.1f km. " +
                        "%d duplicate deliveries and %d relay crashes absorbed, %d invariant violations.",
                shown.planning.plan.planned(), shown.planning.plan.vehiclesUsed(), shown.planning.plan.km(shown.params),
                shown.broker.duplicatesInjected, shown.relayCrashes(), shown.invariantViolations.size());
        Files.writeString(out.resolve("control-tower.html"), ControlTower.html(shown.planning.plan, shown.params, shown.planning.log, summary));
        Files.writeString(out.resolve("plan.json"), plan);
        Files.writeString(out.resolve("decisions.json"), dec);
        System.out.println("wrote results/results.json, results/control-tower.html");
        if (serve) {
            ControlTower.serve(8080, ControlTower.html(shown.planning.plan, shown.params, shown.planning.log, summary), plan, dec, kpis);
            System.out.println("control tower on http://localhost:8080  (Ctrl+C to stop)");
            Thread.currentThread().join();
        }
    }
}
