package nl.milkrun.msg;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * Transactional outbox. A service mutates its state and records outgoing events in one atomic unit;
 * a relay publishes them afterwards. If the relay crashes after publishing but before marking the row,
 * the event is published again on the next pass: at-least-once by design, so consumers must be idempotent.
 */
public final class Outbox {
    public interface Tx {
        void write(Runnable stateChange);
        void emit(Message m);
    }

    private static final class Row {
        final Message m;
        boolean published;
        Row(Message m) { this.m = m; }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Chaos chaos;
    private final SplittableRandom rnd;
    public long relayCrashes, committed, rolledBack;

    public Outbox(Chaos chaos, long salt) {
        this.chaos = chaos;
        this.rnd = new SplittableRandom(chaos.seed() * 31 + salt);
    }

    /** All-or-nothing: if the body throws, neither state changes nor events are applied. */
    public synchronized void tx(Consumer<Tx> body) {
        List<Runnable> writes = new ArrayList<>();
        List<Message> events = new ArrayList<>();
        try {
            body.accept(new Tx() {
                public void write(Runnable r) { writes.add(r); }
                public void emit(Message m) { events.add(m); }
            });
        } catch (RuntimeException e) {
            rolledBack++;
            throw e;
        }
        writes.forEach(Runnable::run);
        for (Message m : events) rows.add(new Row(m));
        committed++;
    }

    public synchronized int relay(Broker broker) {
        int n = 0;
        for (Row r : rows) {
            if (r.published) continue;
            broker.publish(r.m);
            n++;
            if (chaos.relayCrashP() > 0 && rnd.nextDouble() < chaos.relayCrashP()) {
                relayCrashes++;
                return n;                              // crashed before marking: will be re-sent
            }
            r.published = true;
        }
        return n;
    }

    public synchronized boolean hasPending() {
        for (Row r : rows) if (!r.published) return true;
        return false;
    }
}
