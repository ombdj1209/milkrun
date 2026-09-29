package nl.milkrun;

import nl.milkrun.plan.Model.Order;
import nl.milkrun.plan.Model.Point;
import nl.milkrun.plan.Model.Vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Seeded synthetic evening: a 20x20 km city, one hub, 60-minute customer slots, a small EV fleet. */
public final class Scenario {
    public final Point hub = new Point(10, 10);
    public final List<Order> orders = new ArrayList<>();
    public final List<Order> lateOrders = new ArrayList<>();
    public final Map<String, List<String>> skus = new HashMap<>();
    public final Map<String, Integer> stock = new HashMap<>();
    public final List<Vehicle> vehicles = new ArrayList<>();

    public Scenario(long seed, int nOrders, int nLate, int nVehicles) {
        SplittableRandom r = new SplittableRandom(seed);
        for (int v = 0; v < nVehicles; v++) vehicles.add(new Vehicle(String.format("van-%02d", v + 1), 48, 90.0));
        int nSku = 60;
        double[][] hoods = new double[6][2];
        for (double[] h : hoods) { h[0] = 3 + 14 * r.nextDouble(); h[1] = 3 + 14 * r.nextDouble(); }
        for (int i = 0; i < nOrders + nLate; i++) {
            double[] c = hoods[r.nextInt(hoods.length)];               // demand clusters around neighbourhoods
            double x = clamp(c[0] + r.nextGaussian() * 2.0), y = clamp(c[1] + r.nextGaussian() * 2.0);
            int slot = r.nextInt(5);                                    // 16:00..21:00 in 60-minute slots
            boolean late = i >= nOrders;
            String id = late ? String.format("late-%03d", i - nOrders) : String.format("o-%04d", i);
            Order o = new Order(id, new Point(x, y), slot * 60, slot * 60 + 60, 1 + r.nextInt(4));
            (late ? lateOrders : orders).add(o);
            List<String> lines = new ArrayList<>();
            int n = 3 + r.nextInt(6);
            for (int k = 0; k < n; k++)
                lines.add(String.format("sku-%02d", (int) Math.min(nSku - 1, Math.abs(r.nextGaussian()) * nSku / 2.5)));
            skus.put(id, lines);
        }
        for (int s = 0; s < nSku; s++) stock.put(String.format("sku-%02d", s), 20 + r.nextInt(110));
    }

    private static double clamp(double v) { return Math.max(0.2, Math.min(19.8, v)); }
}
