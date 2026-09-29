package nl.milkrun.plan;

import java.util.ArrayList;
import java.util.List;

/** Domain model for electric-van route planning with customer time windows. Times are minutes from shift start. */
public final class Model {
    private Model() {}

    public record Point(double x, double y) {
        public double km(Point o) {
            return Math.hypot(x - o.x, y - o.y) * 1.3; // Euclid x detour factor for a street grid
        }
    }

    public record Order(String id, Point loc, int winStart, int winEnd, int totes) {}

    public record Vehicle(String id, int capacityTotes, double rangeKm) {}

    public record Params(Point hub, double speedKmPerMin, int serviceMin, int shiftEndMin,
                         double vehicleFixedKm, double unassignedPenaltyKm) {
        public static Params defaults(Point hub) {
            return new Params(hub, 0.5, 3, 330, 15.0, 1000.0);
        }
    }

    /** Why an order does not fit a route. Used for explanations in the decision log. */
    public enum Violation { NONE, WINDOW, CAPACITY, RANGE, SHIFT_END }

    public record Eval(double km, int load, Violation violation, int finishMin) {
        public boolean feasible() { return violation == Violation.NONE; }
    }

    public static final class Route {
        public final Vehicle vehicle;
        public final List<Order> stops;

        public Route(Vehicle v) { this(v, new ArrayList<>()); }

        public Route(Vehicle v, List<Order> stops) { this.vehicle = v; this.stops = stops; }

        public Route copy() { return new Route(vehicle, new ArrayList<>(stops)); }

        public Eval eval(Params p) { return evalWith(p, stops, -1, null); }

        /** Evaluates the route with {@code extra} inserted at {@code pos} (pos = -1: no insertion) without mutating it. */
        public Eval evalWith(Params p, List<Order> seq, int pos, Order extra) {
            double km = 0, t = 0;
            int load = 0;
            Point at = p.hub();
            int n = seq.size() + (extra == null ? 0 : 1);
            Violation v = Violation.NONE;
            for (int i = 0, j = 0; i < n; i++) {
                Order o = (extra != null && i == pos) ? extra : seq.get(j++);
                double d = at.km(o.loc());
                km += d;
                t += d / p.speedKmPerMin();
                if (t < o.winStart()) t = o.winStart();          // wait for the customer's slot
                if (t > o.winEnd() && v == Violation.NONE) v = Violation.WINDOW;
                t += p.serviceMin();
                load += o.totes();
                at = o.loc();
            }
            double back = at.km(p.hub());
            km += back;
            t += back / p.speedKmPerMin();
            if (v == Violation.NONE && load > vehicle.capacityTotes()) v = Violation.CAPACITY;
            if (v == Violation.NONE && km > vehicle.rangeKm()) v = Violation.RANGE;
            if (v == Violation.NONE && t > p.shiftEndMin()) v = Violation.SHIFT_END;
            return new Eval(km, load, v, (int) Math.ceil(t));
        }
    }

    public static final class Solution {
        public final List<Route> routes;
        public final List<Order> unassigned;

        public Solution(List<Route> routes, List<Order> unassigned) {
            this.routes = routes;
            this.unassigned = unassigned;
        }

        public Solution copy() {
            List<Route> r = new ArrayList<>();
            for (Route x : routes) r.add(x.copy());
            return new Solution(r, new ArrayList<>(unassigned));
        }

        public double km(Params p) {
            double s = 0;
            for (Route r : routes) if (!r.stops.isEmpty()) s += r.eval(p).km();
            return s;
        }

        public int vehiclesUsed() {
            int c = 0;
            for (Route r : routes) if (!r.stops.isEmpty()) c++;
            return c;
        }

        public double objective(Params p) {
            return km(p) + vehiclesUsed() * p.vehicleFixedKm() + unassigned.size() * p.unassignedPenaltyKm();
        }

        public boolean feasible(Params p) {
            for (Route r : routes) if (!r.stops.isEmpty() && !r.eval(p).feasible()) return false;
            return true;
        }

        public int planned() {
            int c = 0;
            for (Route r : routes) c += r.stops.size();
            return c;
        }

        /** Fingerprint of the full plan, used to prove deterministic replay. */
        public String fingerprint() {
            StringBuilder sb = new StringBuilder();
            for (Route r : routes) {
                sb.append(r.vehicle.id()).append(':');
                for (Order o : r.stops) sb.append(o.id()).append(',');
                sb.append('|');
            }
            return Integer.toHexString(sb.toString().hashCode());
        }
    }
}
