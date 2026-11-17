package pk.ooad.ntd.web;

import pk.ooad.ntd.detection.AlertListener;
import pk.ooad.ntd.domain.Alert;
import pk.ooad.ntd.util.ConfigManager;

import io.javalin.http.sse.SseClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;

public class StreamBroadcaster implements AlertListener {
    private final long networkIntervalNanos;

    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();
    private final AtomicLong frames = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ntd-stream-network");
        thread.setDaemon(true);
        return thread;
    });

    private Function<Alert, String> alertJson = alert -> "";
    private java.util.function.Supplier<String> networkJson = () -> "{}";
    private BiFunction<String, String, String> stateJson = (username, role) -> "{}";
    private long lastNetworkAt;
    private boolean networkPending;

    public StreamBroadcaster() {
        ConfigManager config = ConfigManager.getInstance();
        this.networkIntervalNanos = Math.max(1L, config.getInt("stream.network.intervalMillis", 250)) * 1_000_000L;
    }

    void setEncoders(Function<Alert, String> alertJson, java.util.function.Supplier<String> networkJson,
                     BiFunction<String, String, String> stateJson) {
        this.alertJson = alertJson;
        this.networkJson = networkJson;
        this.stateJson = stateJson;
    }

    @Override
    public void onAlert(Alert a) {
        if (a == null) {
            return;
        }
        emit("alert", alertJson.apply(a));
        requestNetwork();
        broadcastState();
    }

    public void addClient(SseClient c) {
        if (c == null) {
            return;
        }
        clients.add(c);
        emitOne(c, "state", stateJson.apply(c.ctx().sessionAttribute("username"), c.ctx().sessionAttribute("role")));
        emitOne(c, "network", networkJson.get());
    }

    public void removeClient(SseClient c) {
        if (c != null) {
            clients.remove(c);
        }
    }

    public int getClientCount() {
        return clients.size();
    }

    public long getEmittedFrames() {
        return frames.get();
    }

    public void broadcastState() {
        for (SseClient client : List.copyOf(clients)) {
            String username = client.ctx().sessionAttribute("username");
            String role = client.ctx().sessionAttribute("role");
            emitOne(client, "state", stateJson.apply(username, role));
        }
    }

    void requestNetwork() {
        long delay;
        synchronized (this) {
            long now = System.nanoTime();
            long wait = lastNetworkAt + networkIntervalNanos - now;
            if (wait <= 0) {
                lastNetworkAt = now;
                delay = 0L;
            } else if (!networkPending) {
                networkPending = true;
                delay = wait;
            } else {
                return;
            }
        }
        if (delay == 0L) {
            sendNetwork();
            return;
        }
        try {
            scheduler.schedule(this::flushNetwork, delay, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ignored) {
            synchronized (this) {
                networkPending = false;
            }
        }
    }

    void close() {
        scheduler.shutdownNow();
    }

    private void flushNetwork() {
        synchronized (this) {
            networkPending = false;
            lastNetworkAt = System.nanoTime();
        }
        sendNetwork();
    }

    private void sendNetwork() {
        String json;
        try {
            json = networkJson.get();
        } catch (RuntimeException ignored) {
            return;
        }
        emit("network", json);
    }

    private void emit(String event, String data) {
        for (SseClient client : List.copyOf(clients)) {
            emitOne(client, event, data);
        }
    }

    private void emitOne(SseClient client, String event, String data) {
        if (client.terminated()) {
            removeClient(client);
            return;
        }
        try {
            client.sendEvent(event, data);
            frames.incrementAndGet();
        } catch (RuntimeException ignored) {
            removeClient(client);
        }
    }
}
