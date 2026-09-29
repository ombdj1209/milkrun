package nl.milkrun.plan;

import nl.milkrun.plan.Model.Eval;
import nl.milkrun.plan.Model.Order;
import nl.milkrun.plan.Model.Params;
import nl.milkrun.plan.Model.Route;
import nl.milkrun.plan.Model.Solution;
import nl.milkrun.plan.Model.Vehicle;
import nl.milkrun.plan.Model.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Vehicle routing with time windows, van capacity and EV range.
 * Construction: nearest neighbour (baseline) or regret-2 insertion. Improvement: large neighbourhood
 * search (random / worst / related removal, regret reinsertion, simulated-annealing acceptance).
 * Iteration-bounded and seeded, so every plan is exactly replayable.
 */
public final class Planner {
    private final Params p;
    private final SplittableRandom rnd;

    public Planner(Params p, long seed) {
        this.p = p;
        this.rnd = new SplittableRandom(seed);
    }

    record Insertion(int route, int pos, double deltaKm) {}

    // ------------------------------------------------------------------ construction
    public Solution nearestNeighbour(List<Vehicle> vehicles, List<Order> orders) {
        List<Order> left = new ArrayList<>(orders);
        List<Route> routes = new ArrayList<>();
        for (Vehicle v : vehicles) {
            Route r = new Route(v);
            while (true) {
                Order best = null;
                double bestD = Double.MAX_VALUE;
                Model.Point at = r.stops.isEmpty() ? p.hub() : r.stops.get(r.stops.size() - 1).loc();
                for (Order o : left) {
                    double d = at.km(o.loc());
                    if (d < bestD && r.evalWith(p, r.stops, r.stops.size(), o).feasible()) {
                        bestD = d;
                        best = o;
                    }
                }
                if (best == null) break;
                r.stops.add(best);
                left.remove(best);
            }
            routes.add(r);
        }
        return new Solution(routes, left);
    }

    public Solution regretInsertion(List<Vehicle> vehicles, List<Order> orders) {
        List<Route> routes = new ArrayList<>();
        for (Vehicle v : vehicles) routes.add(new Route(v));
        Solution s = new Solution(routes, new ArrayList<>());
        List<Order> pool = new ArrayList<>(orders);
        pool.sort(Comparator.comparingInt(Order::winEnd).thenComparing(Order::id));
        reinsert(s, pool);
        return s;
    }

    /** Best feasible insertion of {@code o} into route {@code ri}, or null. */
    Insertion bestIn(Solution s, int ri, Order o) {
        Route r = s.routes.get(ri);
        double base = r.stops.isEmpty() ? 0 : r.eval(p).km();
        double fixed = r.stops.isEmpty() ? p.vehicleFixedKm() : 0;
        Insertion best = null;
        for (int pos = 0; pos <= r.stops.size(); pos++) {
            Eval e = r.evalWith(p, r.stops, pos, o);
            if (!e.feasible()) continue;
            double delta = e.km() - base + fixed;
            if (best == null || delta < best.deltaKm()) best = new Insertion(ri, pos, delta);
        }
        return best;
    }

    /**
     * Regret-2 reinsertion: repeatedly insert the order whose best and second-best options differ most.
     * Insertion options are cached per (order, route); only the route just modified is recomputed.
     */
    void reinsert(Solution s, List<Order> pool) {
        List<Order> todo = new ArrayList<>(pool);
        int nr = s.routes.size();
        List<Insertion[]> cache = new ArrayList<>();
        for (Order o : todo) {
            Insertion[] row = new Insertion[nr];
            for (int ri = 0; ri < nr; ri++) row[ri] = bestIn(s, ri, o);
            cache.add(row);
        }
        while (!todo.isEmpty()) {
            Order pick = null;
            Insertion pickIns = null;
            int pickIdx = -1;
            double bestRegret = -1;
            for (int i = 0; i < todo.size(); i++) {
                Order o = todo.get(i);
                Insertion b1 = null;
                double second = Double.MAX_VALUE;
                for (int ri = 0; ri < nr; ri++) {
                    Insertion ins = cache.get(i)[ri];
                    if (ins == null) continue;
                    if (b1 == null || ins.deltaKm() < b1.deltaKm()) {
                        if (b1 != null) second = b1.deltaKm();
                        b1 = ins;
                    } else if (ins.deltaKm() < second) second = ins.deltaKm();
                }
                if (b1 == null) continue;
                double regret = (second == Double.MAX_VALUE ? 1e6 : second - b1.deltaKm());
                if (regret > bestRegret || (regret == bestRegret && o.id().compareTo(pick.id()) < 0)) {
                    bestRegret = regret;
                    pick = o;
                    pickIns = b1;
                    pickIdx = i;
                }
            }
            if (pick == null) {
                s.unassigned.addAll(todo);
                return;
            }
            int ri = pickIns.route();
            s.routes.get(ri).stops.add(pickIns.pos(), pick);
            todo.remove(pickIdx);
            cache.remove(pickIdx);
            for (int i = 0; i < todo.size(); i++) cache.get(i)[ri] = bestIn(s, ri, todo.get(i));
        }
    }

    // ------------------------------------------------------------------------- LNS
    public record LnsStats(int iterations, int improvements, double startObjective, double endObjective) {}

    public LnsStats improve(Solution s, int iterations) {
        double start = s.objective(p);
        Solution cur = s.copy();
        Solution best = s.copy();
        double curObj = start, bestObj = start, temp = start * 0.002;
        int improvements = 0;
        for (int it = 0; it < iterations; it++) {
            Solution cand = cur.copy();
            int k = Math.max(2, (int) Math.round(cand.planned() * (0.05 + 0.10 * rnd.nextDouble())));
            List<Order> removed = switch (rnd.nextInt(3)) {
                case 0 -> removeRandom(cand, k);
                case 1 -> removeWorst(cand, k);
                default -> removeRelated(cand, k);
            };
            removed.addAll(cand.unassigned);
            cand.unassigned.clear();
            reinsert(cand, removed);
            double obj = cand.objective(p);
            if (obj < curObj || rnd.nextDouble() < Math.exp((curObj - obj) / Math.max(1e-9, temp))) {
                cur = cand;
                curObj = obj;
                if (obj < bestObj - 1e-9) {
                    best = cand.copy();
                    bestObj = obj;
                    improvements++;
                }
            }
            temp *= 0.997;
        }
        s.routes.clear();
        s.routes.addAll(best.routes);
        s.unassigned.clear();
        s.unassigned.addAll(best.unassigned);
        return new LnsStats(iterations, improvements, start, bestObj);
    }

    private List<Order> removeRandom(Solution s, int k) {
        List<Order> out = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            List<Route> nonEmpty = s.routes.stream().filter(r -> !r.stops.isEmpty()).toList();
            if (nonEmpty.isEmpty()) break;
            Route r = nonEmpty.get(rnd.nextInt(nonEmpty.size()));
            out.add(r.stops.remove(rnd.nextInt(r.stops.size())));
        }
        return out;
    }

    private List<Order> removeWorst(Solution s, int k) {
        record Saving(Route r, Order o, double km) {}
        List<Saving> sav = new ArrayList<>();
        for (Route r : s.routes) {
            if (r.stops.isEmpty()) continue;
            double full = r.eval(p).km();
            for (int i = 0; i < r.stops.size(); i++) {
                List<Order> without = new ArrayList<>(r.stops);
                without.remove(i);
                double km = without.isEmpty() ? 0 : new Route(r.vehicle, without).eval(p).km();
                sav.add(new Saving(r, r.stops.get(i), full - km));
            }
        }
        sav.sort(Comparator.comparingDouble(Saving::km).reversed().thenComparing(x -> x.o().id()));
        List<Order> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, sav.size()); i++) {
            int idx = Math.min(sav.size() - 1, (int) (Math.pow(rnd.nextDouble(), 3) * sav.size()));
            Saving x = sav.get(idx);
            if (x.r().stops.remove(x.o())) out.add(x.o());
        }
        return out;
    }

    /** Shaw removal: a seed order plus its nearest neighbours in space and time. */
    private List<Order> removeRelated(Solution s, int k) {
        List<Order> all = new ArrayList<>();
        for (Route r : s.routes) all.addAll(r.stops);
        if (all.isEmpty()) return new ArrayList<>();
        Order seed = all.get(rnd.nextInt(all.size()));
        all.sort(Comparator.comparingDouble((Order o) ->
                seed.loc().km(o.loc()) + 0.1 * Math.abs(seed.winStart() - o.winStart())).thenComparing(Order::id));
        List<Order> out = new ArrayList<>(all.subList(0, Math.min(k, all.size())));
        for (Route r : s.routes) r.stops.removeAll(out);
        return out;
    }

    // ------------------------------------------------------------ live re-planning
    public record LateResult(boolean inserted, String vehicle, double deltaKm, long nanos, String explanation) {}

    /** Inserts an order that arrives after the plan was built: cheapest feasible slot, else a reasoned rejection. */
    public LateResult insertLate(Solution s, Order o) {
        long t0 = System.nanoTime();
        Insertion best = null;
        for (int ri = 0; ri < s.routes.size(); ri++) {
            Insertion ins = bestIn(s, ri, o);
            if (ins != null && (best == null || ins.deltaKm() < best.deltaKm())) best = ins;
        }
        if (best != null) {
            Route r = s.routes.get(best.route());
            r.stops.add(best.pos(), o);
            return new LateResult(true, r.vehicle.id(), best.deltaKm(), System.nanoTime() - t0,
                    String.format("inserted into %s at stop %d (+%.1f km)", r.vehicle.id(), best.pos() + 1, best.deltaKm()));
        }
        s.unassigned.add(o);
        return new LateResult(false, null, 0, System.nanoTime() - t0, explain(s, o));
    }

    /** For each van, the constraint that rules the order out at its least-bad position. */
    public String explain(Solution s, Order o) {
        Map<Violation, Integer> count = new EnumMap<>(Violation.class);
        for (Route r : s.routes) {
            Violation mildest = null;
            for (int pos = 0; pos <= r.stops.size(); pos++) {
                Violation v = r.evalWith(p, r.stops, pos, o).violation();
                if (mildest == null || v.ordinal() < mildest.ordinal()) mildest = v;
            }
            count.merge(mildest, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("no feasible slot on any van: ");
        count.forEach((v, n) -> sb.append(v.name().toLowerCase().replace('_', ' ')).append(" on ").append(n).append(", "));
        return sb.substring(0, sb.length() - 2);
    }
}
