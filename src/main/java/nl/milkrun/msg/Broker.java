package nl.milkrun.msg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * In-memory stand-in for RabbitMQ with at-least-once semantics: messages can be delivered twice and
 * out of order (bounded window), exactly the conditions consumers must survive in production.
 * A RabbitMQ adapter would implement the same subscribe / publish / drain surface.
 */
public final class Broker {
    private final Map<String, List<Consumer<Message>>> subs = new HashMap<>();
    private final Deque<Message> queue = new ArrayDeque<>();
    private final Chaos chaos;
    private final SplittableRandom rnd;
    public long delivered, duplicatesInjected, reorders;

    public Broker(Chaos chaos) {
        this.chaos = chaos;
        this.rnd = new SplittableRandom(chaos.seed() ^ 0x5DEECE66DL);
    }

    public void subscribe(String topic, Consumer<Message> handler) {
        subs.computeIfAbsent(topic, t -> new ArrayList<>()).add(handler);
    }

    public void publish(Message m) {
        queue.addLast(m);
        if (chaos.duplicateP() > 0 && rnd.nextDouble() < chaos.duplicateP()) {
            queue.addLast(m);
            duplicatesInjected++;
        }
    }

    public boolean idle() { return queue.isEmpty(); }

    /** Delivers everything queued, including anything handlers publish meanwhile. */
    public void drain() {
        while (!queue.isEmpty()) {
            Message m = queue.pollFirst();
            if (chaos.reorderWindow() > 0 && queue.size() > 1 && rnd.nextDouble() < 0.5) {
                int k = 1 + rnd.nextInt(Math.min(chaos.reorderWindow(), queue.size()));
                List<Message> ahead = new ArrayList<>();
                for (int i = 0; i < k; i++) ahead.add(queue.pollFirst());
                queue.addFirst(m);                       // m now arrives after the k later messages
                for (int i = ahead.size() - 1; i >= 0; i--) queue.addFirst(ahead.get(i));
                m = queue.pollFirst();
                reorders++;
            }
            for (Consumer<Message> h : subs.getOrDefault(m.topic(), List.of())) h.accept(m);
            delivered++;
        }
    }
}
