package org.minelogy.watchtower;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


import static org.minelogy.watchtower.Watchtower.LOGGER;
import static org.minelogy.watchtower.SystemMetrics.getSystemMetrics;

public class Network {
    private static final int RECONNECT_BASE_DELAY = 5;
    private static final int RECONNECT_MAX_DELAY = 60;
    private static final int STATUS_DELAY = 60;

    private final String address;
    private final int port;
    private final String password;
    private final boolean ssl;
    private final Gson gson = new Gson();
    private String authToken;

    private WebSocketClient client;
    private volatile boolean running = true;

    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService reconnectScheduler;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile SystemMetrics sys;
    private final SystemMetrics[] metricsHistory = new SystemMetrics[STATUS_DELAY];
    private int metricsIndex = 0;
    private boolean metricsWindowFilled = false;
    private volatile SystemMetrics minuteAverage;

    private final Object lock_client = new Object();
    private final Object lock_schedule = new Object();

    public Network(String address, int port, String password, boolean ssl) {
        this.address = address;
        this.port = port;
        this.password = password;
        this.ssl = ssl;
    }

    public void start() {
        synchronized (lock_client) {
            if (!running) return;
            if (client != null) {
                client.close();
            }
            try {
                String scheme = ssl ? "wss" : "ws";
                String url = String.format("%s://%s:%d/", scheme, address, port);
                URI serverUri = new URI(url);

                client = new WebSocketClient(serverUri) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        running = true;
                        LOGGER.info("WebSocket connection established to {}", serverUri);
                        reconnectAttempts.set(0);
                        JsonObject auth = new JsonObject();
                        auth.addProperty("type", "auth");
                        auth.addProperty("password", password);
                        client.send(gson.toJson(auth));
                    }

                    @Override
                    public void onMessage(String message) {
                        LOGGER.debug("Received message: {}", message);
                        handleCommand(message);
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        synchronized (lock_schedule) {
                            LOGGER.info("WebSocket connection closed, code={}, reason={}, remote={}", code, reason, remote);
                            stopHeartbeat();
                            scheduleReconnect();
                        }
                    }

                    @Override
                    public void onError(Exception ex) {
                        synchronized (lock_schedule) {
                            LOGGER.error("WebSocket error", ex);
                            stopHeartbeat();
                            // onError may fire before onClose for the same disconnection.
                            // scheduleReconnect guards against duplicate scheduling: if
                            // reconnectScheduler is already active, the call is silently
                            // ignored, so at most one reconnect attempt is queued per event.
                            scheduleReconnect();
                        }
                    }
                };

                if (ssl) {
                    client.setSocketFactory(SSLContext.getDefault().getSocketFactory());
                }

                client.setConnectionLostTimeout(30);
                client.connect();
                LOGGER.info("WebSocket connection initiated asynchronously to {}", serverUri);

            } catch (Exception e) {
                LOGGER.error("Failed to initiate WebSocket connection", e);
                scheduleReconnect();
            }
        }
    }
    public void stop() {
        synchronized (lock_schedule) {
            LOGGER.info("Stopping network service...");
            running = false;
            reconnectAttempts.set(0);
            stopHeartbeat();
            if (reconnectScheduler != null) {
                reconnectScheduler.shutdownNow(); // forced: reconnect tasks sleep between attempts
                reconnectScheduler = null;
            }
            synchronized (lock_client) {
                if (client != null) {
                    client.close();
                }
            }
            LOGGER.info("Network service stopped");
        }
    }

    private void scheduleReconnect() {
        synchronized (lock_schedule) {
            if (!running) return;
            if (client != null && client.isOpen()) {
                return;
            }
            if (reconnectScheduler != null && !reconnectScheduler.isShutdown()) {
                return; // already scheduled, skip duplicate
            }
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
            int attempt = reconnectAttempts.incrementAndGet();
            int shift = Math.min(attempt - 1, 30);
            int delay = (int) Math.min((long) RECONNECT_BASE_DELAY << shift, RECONNECT_MAX_DELAY);
            LOGGER.info("Scheduling reconnect in {}s (attempt #{})", delay, attempt);
            reconnectScheduler.schedule(() -> {
                if (!running) return;
                LOGGER.info("Reconnecting...");
                start();
                synchronized (lock_schedule) {
                    if (reconnectScheduler != null) {
                        reconnectScheduler.shutdown();
                        reconnectScheduler = null;
                    }
                }
            }, delay, TimeUnit.SECONDS);
        }
    }

    private void startHeartbeat() {
        synchronized (lock_schedule) {
            if (!running) return;
            stopHeartbeat();
            getSystemMetrics();
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(this::sendStatus, 0, STATUS_DELAY, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(this::updateSystemMetrics, 0, 1, TimeUnit.SECONDS);
        }
    }
    private void stopHeartbeat() {
        synchronized (lock_schedule) {
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown(); // graceful: heartbeat tasks complete quickly
                scheduler = null;
            }
        }
    }

    private void sendStatus() {
        if (sys == null) return;
        ServerStatus.Status snap = ServerStatus.getStatus(minuteAverage);
        if(snap == null) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "status");
        payload.addProperty("token", authToken);
        payload.add("data", gson.toJsonTree(snap));
        synchronized (lock_client) {
            if (client != null && client.isOpen()) {
                client.send(gson.toJson(payload));
            }
        }
    }

    private void handleCommand(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            if (!json.has("type") || !json.get("type").isJsonPrimitive()) {
                LOGGER.warn("Missing or invalid 'type' field in command");
                JsonObject err = new JsonObject();
                err.addProperty("type", "error");
                err.addProperty("token", authToken);
                err.addProperty("data", "Missing or invalid 'type' field");
                synchronized (lock_client) {
                    if (client != null && client.isOpen()) client.send(gson.toJson(err));
                }
                return;
            }
            if(!json.has("id") || json.get("id").isJsonNull()) {
                LOGGER.warn("Missing or invalid 'id' field in command");
                JsonObject err = new JsonObject();
                err.addProperty("type", "error");
                err.addProperty("token", authToken);
                err.addProperty("data", "Missing or invalid 'id' field");
                synchronized (lock_client) {
                    if (client != null && client.isOpen()) client.send(gson.toJson(err));
                }
                return;
            }

            String type = json.get("type").getAsString();
            JsonElement id = json.get("id");
            JsonObject response;
            switch (type) {
                case "get_status":
                    if (sys == null) return;
                    ServerStatus.Status status = ServerStatus.getNowStatus(sys);
                    if(status == null) {
                        JsonObject err = new JsonObject();
                        err.addProperty("type", "error");
                        err.addProperty("token", authToken);
                        err.addProperty("data", "Server metrics not yet available");
                        synchronized (lock_client) {
                            if (client != null && client.isOpen()) client.send(gson.toJson(err));
                        }
                        return;
                    }
                    response = buildStatusPayload(id, status);
                    synchronized (lock_client) {
                        if (client != null && client.isOpen()) client.send(gson.toJson(response));
                    }
                    break;
                case "get_players":
                    if(Watchtower.serverPlayers == null) {
                        JsonObject err = new JsonObject();
                        err.addProperty("type", "error");
                        err.addProperty("token", authToken);
                        err.addProperty("data", "Server not yet available");
                        synchronized (lock_client) {
                            if (client != null && client.isOpen()) client.send(gson.toJson(err));
                        }
                        return;
                    }
                    List<String> players = new ArrayList<>(Watchtower.serverPlayers);
                    response = buildStatusPayload(id, players);
                    synchronized (lock_client) {
                        if (client != null && client.isOpen()) client.send(gson.toJson(response));
                    }
                    break;
                case "auth_success":
                    LOGGER.info("Authentication successful");
                    authToken = json.get("data").getAsString();
                    startHeartbeat();
                    break;
                case "auth_fail":
                    String reason = json.has("reason") ? json.get("reason").getAsString() : "No reason given";
                    LOGGER.error("Authentication failed: {}. Disconnecting.", reason);
                    synchronized (lock_schedule) {
                        running = false;
                        stopHeartbeat();
                        synchronized (lock_client) {
                            if (client != null) {
                                client.close();
                            }
                        }
                    }
                    break;
                default:
                    LOGGER.warn("Unknown command type: {}", type);
                    JsonObject error = new JsonObject();
                    error.addProperty("type", "error");
                    error.addProperty("data", "Unknown command: " + type);
                    synchronized (lock_client) {
                        if (client != null && client.isOpen()) client.send(gson.toJson(error));
                    }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse command: {}", message, e);
        }
    }

    private void updateSystemMetrics() {
        sys = getSystemMetrics();
        metricsHistory[metricsIndex] = sys;
        metricsIndex = (metricsIndex + 1) % 60;
        if (metricsIndex == 0) metricsWindowFilled = true;

        int n = metricsWindowFilled ? 60 : metricsIndex;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += metricsHistory[i].cpuLoad;
        }
        minuteAverage = new SystemMetrics(sum / n, sys.hostMemoryUsage, sys.heapUsed, sys.heapMax, sys.heapCommitted);
    }
    private JsonObject buildStatusPayload(JsonElement replay, Object data) {
        JsonObject payload = new JsonObject();
        payload.add("id", replay);
        payload.addProperty("token", authToken);
        payload.add("data", gson.toJsonTree(data));
        return payload;
    }
}