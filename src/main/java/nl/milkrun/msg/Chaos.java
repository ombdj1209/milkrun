package nl.milkrun.msg;

/** Failure injection for the broker and outbox relay. All randomness is seeded. */
public record Chaos(double duplicateP, int reorderWindow, double relayCrashP, long seed) {
    public static Chaos none() { return new Chaos(0, 0, 0, 0); }
    public static Chaos heavy(long seed) { return new Chaos(0.30, 8, 0.10, seed); }
}
