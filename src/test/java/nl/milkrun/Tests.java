package nl.milkrun;

import nl.milkrun.msg.Broker;
import nl.milkrun.msg.Chaos;
import nl.milkrun.msg.Message;
import nl.milkrun.msg.Outbox;
import nl.milkrun.plan.Model.Order;
import nl.milkrun.plan.Model.Params;
import nl.milkrun.plan.Model.Point;
import nl.milkrun.plan.Model.Route;
import nl.milkrun.plan.Model.Solution;
import nl.milkrun.plan.Model.Vehicle;
import nl.milkrun.plan.Model.Violation;
import nl.milkrun.plan.Planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-free test runner: `./build.sh test`. Each method is one test; a failure exits non-zero. */
public final class Tests {
    static int passed, failed;

    interface T { void run() throws Exception; }

    static void test(String name, T t) {
        try {
            t.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("  FAIL  " + name + ": " + e);
        }
    }

    static void check(boolean c, String msg) { if (!c) throw new AssertionError(msg); }

    public static void main(String[] args) {
        Params p = Params.defaults(new Point(10, 10));

        test("route evaluation flags each constraint", () -> {
            Vehicle v = new Vehicle("v", 5, 30);
            Route r = new Route(v, new ArrayList<>(List.of(new Order("a", new Point(12, 10), 0, 60, 2))));
            check(r.eval(p).feasible(), "simple route should be feasible");
            check(r.evalWith(p, r.stops, 1, new Order("b", new Point(11, 10), 0, 60, 9)).violation() == Violation.CAPACITY, "capacity");
            check(r.evalWith(p, r.stops, 1, new Order("c", new Point(19.9, 19.9), 0, 60, 1)).violation() != Violation.NONE, "range or window");
            check(r.evalWith(p, r.stops, 0, new Order("d", new Point(10.1, 10), 0, 1, 1)).violation() == Violation.NONE, "early customer is fine");
            check(r.evalWith(p, r.stops, 1, new Order("e", new Point(10, 18), 0, 5, 1)).violation() == Violation.WINDOW, "window");
        });

        test("every planner output is feasible and accounts for every order", () -> {
            for (long seed = 1; seed <= 3; seed++) {
                Scenario sc = new Scenario(seed, 150, 0, 10);
                Params pp = Params.defaults(sc.hub);
                Planner pl = new Planner(pp, seed);
                for (Solution s : List.of(pl.nearestNeighbour(sc.vehicles, sc.orders), pl.regretInsertion(sc.vehicles, sc.orders))) {
                    check(s.feasible(pp), "infeasible plan");
                    check(s.planned() + s.unassigned.size() == sc.orders.size(), "orders lost");
                }
            }
        });

        test("LNS never makes the objective worse and keeps feasibility", () -> {
            Scenario sc = new Scenario(4, 150, 0, 10);
            Params pp = Params.defaults(sc.hub);
            Planner pl = new Planner(pp, 4);
            Solution s = pl.regretInsertion(sc.vehicles, sc.orders);
            double before = s.objective(pp);
            Planner.LnsStats st = pl.improve(s, 300);
            check(s.objective(pp) <= before + 1e-9, "objective got worse");
            check(Math.abs(st.endObjective() - s.objective(pp)) < 1e-6, "stats disagree with solution");
            check(s.feasible(pp), "infeasible after LNS");
        });

        test("same seed replays the same plan", () -> {
            Scenario sc = new Scenario(5, 120, 0, 8);
            Params pp = Params.defaults(sc.hub);
            Solution a = new Planner(pp, 9).regretInsertion(sc.vehicles, sc.orders);
            new Planner(pp, 9).improve(a, 200);
            Solution b = new Planner(pp, 9).regretInsertion(sc.vehicles, sc.orders);
            new Planner(pp, 9).improve(b, 200);
            check(a.fingerprint().equals(b.fingerprint()), "plans differ");
        });

        test("late insertion keeps the plan feasible or explains the rejection", () -> {
            Scenario sc = new Scenario(6, 150, 30, 10);
            Params pp = Params.defaults(sc.hub);
            Planner pl = new Planner(pp, 6);
            Solution s = pl.regretInsertion(sc.vehicles, sc.orders);
            for (Order o : sc.lateOrders) {
                Planner.LateResult r = pl.insertLate(s, o);
                check(r.inserted() || r.explanation().startsWith("no feasible slot"), "unexplained rejection");
            }
            check(s.feasible(pp), "late insertion broke feasibility");
        });

        test("outbox is atomic: a failing transaction writes nothing", () -> {
            Outbox ob = new Outbox(Chaos.none(), 1);
            List<String> state = new ArrayList<>();
            try {
                ob.tx(tx -> { tx.write(() -> state.add("x")); tx.emit(new Message("m1", "t", "k", Map.of())); throw new IllegalStateException("boom"); });
            } catch (IllegalStateException expected) { }
            check(state.isEmpty() && !ob.hasPending() && ob.rolledBack == 1, "partial commit");
        });

        test("relay crash re-sends, consumer dedupe keeps effects exactly once", () -> {
            Chaos c = new Chaos(0, 0, 0.5, 1);                      // relay crashes after half the publishes
            Outbox ob = new Outbox(c, 1);
            Broker b = new Broker(Chaos.none());
            Set<String> seen = new HashSet<>();
            int[] effects = {0};
            b.subscribe("t", m -> { if (seen.add(m.id())) effects[0]++; });
            for (int i = 0; i < 5; i++) { int k = i; ob.tx(tx -> tx.emit(new Message("m" + k, "t", "k", Map.of()))); }
            for (int i = 0; i < 200 && ob.hasPending(); i++) { ob.relay(b); b.drain(); }
            check(!ob.hasPending(), "outbox never drained");
            check(ob.relayCrashes > 0 && effects[0] == 5 && b.delivered > 5, "effects=" + effects[0] + " delivered=" + b.delivered);
        });

        test("heavy chaos: no oversell, no double planning, nothing dropped silently", () -> {
            for (long seed = 1; seed <= 3; seed++) {
                App.Run r = App.run(seed, Chaos.heavy(seed), 150, 20, 10, 150);
                check(r.broker.duplicatesInjected > 0 && r.broker.reorders > 0 && r.relayCrashes() > 0, "chaos did not fire");
                check(r.invariantViolations.isEmpty(), r.invariantViolations.toString());
            }
        });

        test("chaos runs replay deterministically", () -> {
            App.Run a = App.run(2, Chaos.heavy(2), 120, 15, 8, 100);
            App.Run b = App.run(2, Chaos.heavy(2), 120, 15, 8, 100);
            check(a.planning.plan.fingerprint().equals(b.planning.plan.fingerprint()), "replay differs");
        });

        test("without chaos, order arrival order does not change the plan", () -> {
            App.Run a = App.run(3, Chaos.none(), 120, 0, 8, 100);
            App.Run b = App.run(3, new Chaos(0, 8, 0, 77), 120, 0, 8, 100);   // reorder only
            boolean shortage = a.count("not-planned") > 0 || b.count("not-planned") > 0;
            check(shortage || a.planning.plan.fingerprint().equals(b.planning.plan.fingerprint()),
                    "reordering changed the plan without a stock shortage");
        });

        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
