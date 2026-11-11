package com.osuserverlist.bjar.modules.util;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window counter of attempts, for slowing down password and code guessing.
 * Keys look like "ip:1.2.3.4" or "user:name". In memory only: a restart clears it.
 */
public final class RateLimiter {
    private final int max;
    private final long windowMs;
    private final Map<String, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int max, long windowMs) {
        this.max = max;
        this.windowMs = windowMs;
    }

    public boolean isBlocked(String key) {
        ArrayDeque<Long> q = hits.get(key);
        if (q == null) return false;
        synchronized (q) {
            prune(q, System.currentTimeMillis());
            return q.size() >= max;
        }
    }

    public void record(String key) {
        if (hits.size() > 100_000) {
            long now = System.currentTimeMillis();
            hits.values().removeIf(q -> {
                synchronized (q) {
                    prune(q, now);
                    return q.isEmpty();
                }
            });
        }
        ArrayDeque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(System.currentTimeMillis());
            prune(q, System.currentTimeMillis());
        }
    }

    public void reset(String key) {
        hits.remove(key);
    }

    private void prune(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
            q.pollFirst();
        }
    }
}
