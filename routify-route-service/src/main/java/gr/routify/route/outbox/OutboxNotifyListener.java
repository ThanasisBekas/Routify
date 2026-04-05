package gr.routify.route.outbox;

import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for PostgreSQL {@code NOTIFY} events on the {@code outbox_event_inserted}
 * channel and immediately wakes the {@link OutboxPoller} when new outbox rows
 * are committed.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>On application startup, a dedicated JDBC connection issues
 *       {@code LISTEN outbox_event_inserted}.</li>
 *   <li>A virtual-thread polling loop calls {@code PGConnection.getNotifications(timeout)}
 *       — this blocks cheaply (no CPU) until a notification arrives or the timeout elapses.</li>
 *   <li>When a notification arrives, {@link OutboxPoller#pollAndPublish()} is called
 *       directly — bypassing the scheduled 5-second fallback interval.</li>
 * </ol>
 *
 * <h3>Fault tolerance</h3>
 * <ul>
 *   <li>If the LISTEN connection drops, the loop reconnects with exponential backoff.</li>
 *   <li>The {@code @Scheduled} fallback in {@link OutboxPoller} continues to run as a
 *       safety net — notifications are an optimisation, not a hard dependency.</li>
 *   <li>On graceful shutdown ({@link #stop()}), the dedicated connection is closed
 *       and the loop exits cleanly.</li>
 * </ul>
 *
 * <p><b>Thread model:</b> Uses a single virtual thread (via {@code Thread.ofVirtual()})
 * since the service has {@code spring.threads.virtual.enabled=true}.
 */
@Slf4j
@Component
public class OutboxNotifyListener {

    private static final String CHANNEL = "outbox_event_inserted";

    /** How long to block waiting for a notification before looping (ms). */
    private static final int LISTEN_POLL_TIMEOUT_MS = 500;

    /** Max backoff between reconnect attempts (ms). */
    private static final long MAX_RECONNECT_BACKOFF_MS = 30_000;

    private final DataSource dataSource;
    private final OutboxPoller outboxPoller;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${routify.outbox.notify.enabled:true}")
    private boolean enabled;

    public OutboxNotifyListener(DataSource dataSource, OutboxPoller outboxPoller) {
        this.dataSource = dataSource;
        this.outboxPoller = outboxPoller;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("OutboxNotifyListener: disabled via routify.outbox.notify.enabled=false");
            return;
        }
        if (running.compareAndSet(false, true)) {
            Thread.ofVirtual()
                    .name("outbox-notify-listener")
                    .start(this::listenLoop);
            log.info("OutboxNotifyListener: started on channel '{}'", CHANNEL);
        }
    }

    /**
     * Stops the listener loop. Called automatically on graceful shutdown
     * via Spring's lifecycle management.
     */
    @jakarta.annotation.PreDestroy
    public void stop() {
        running.set(false);
        log.info("OutboxNotifyListener: stopping");
    }

    // ─── Internal ──────────────────────────────────────────────────────────────

    private void listenLoop() {
        long backoff = 1_000;

        while (running.get()) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);
                PGConnection pgConn = conn.unwrap(PGConnection.class);

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("LISTEN " + CHANNEL);
                }
                log.debug("OutboxNotifyListener: LISTEN registered on '{}'", CHANNEL);
                backoff = 1_000; // reset on successful connect

                while (running.get() && !conn.isClosed()) {
                    // getNotifications(timeout) blocks until notification or timeout.
                    // The timeout ensures we periodically check the `running` flag.
                    PGNotification[] notifications = pgConn.getNotifications(LISTEN_POLL_TIMEOUT_MS);

                    if (notifications != null && notifications.length > 0) {
                        log.debug("OutboxNotifyListener: received {} notification(s) — waking poller",
                                notifications.length);
                        try {
                            outboxPoller.pollAndPublish();
                        } catch (Exception e) {
                            // pollAndPublish is @Transactional — errors are logged there.
                            // Don't let a publish failure kill the listener loop.
                            log.warn("OutboxNotifyListener: poller invocation failed: {}", e.getMessage());
                        }
                    }
                }
            } catch (SQLException e) {
                if (!running.get()) break; // shutdown in progress

                log.warn("OutboxNotifyListener: connection lost — reconnecting in {}ms: {}",
                        backoff, e.getMessage());
                sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_RECONNECT_BACKOFF_MS);
            }
        }

        log.info("OutboxNotifyListener: loop exited");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

