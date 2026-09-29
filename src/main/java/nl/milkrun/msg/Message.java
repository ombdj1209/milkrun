package nl.milkrun.msg;

import java.util.Map;

/** Immutable event. {@code id} is the idempotency key consumers deduplicate on. */
public record Message(String id, String topic, String key, Map<String, String> body) {
    public String get(String field) { return body.get(field); }
}
