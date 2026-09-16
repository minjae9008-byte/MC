package com.rpgcore.plugin.discord;

import com.rpgcore.plugin.RpgCorePlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Posts game events to a Discord webhook.
 *
 * Three constraints shape all of this:
 *
 * The main thread never waits on Discord. An HTTP call to somebody else's
 * server can take thirty seconds, and a server that pauses for thirty seconds
 * every time a player dies is worse than one with no Discord at all. Every
 * public method here returns immediately; the sending happens on a thread of
 * this class's own.
 *
 * It is not Bukkit's async scheduler, for the reason already learned by
 * {@code SaveQueue}: that scheduler dispatches from the main thread's
 * heartbeat, so an async task cannot run while the main thread is busy or on
 * its way down - which is exactly when the "server stopping" message needs to
 * go out.
 *
 * Discord going away must cost nothing. The queue is bounded and drops the
 * oldest message when full, so an outage or a rate-limit wall cannot grow the
 * heap; retries are bounded; and a broken webhook logs once rather than once
 * per event.
 */
public final class DiscordService {

    /**
     * How many messages may wait. Roughly a minute of a busy server. Past
     * this, notifications are being produced faster than Discord will take
     * them, and the newest ones are the ones worth keeping.
     */
    private static final int QUEUE_CAPACITY = 256;
    /** How long the sender waits for more events before posting what it has. */
    private static final long BATCH_WINDOW_MS = 400L;
    private static final int MAX_ATTEMPTS = 3;

    private final RpgCorePlugin plugin;
    private final BlockingQueue<Outbound> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger dropped = new AtomicInteger();
    /** Set once a URL has failed outright, so a typo logs once, not forever. */
    private final AtomicBoolean muted = new AtomicBoolean();

    private volatile Thread sender;
    private volatile HttpClient http;
    private volatile boolean closing;

    /**
     * One queued message. The webhook URL travels with it rather than being
     * read by the sender thread, so a /rpgcore reload that changes the URL
     * cannot be seen half-applied from another thread.
     */
    private record Outbound(String url, String username, DiscordEmbed embed) { }

    public DiscordService(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** True when a webhook is configured and the feature is on. */
    public boolean active() {
        return plugin.rpgConfig().discordEnabled()
                && !plugin.rpgConfig().discordWebhookUrl().isBlank();
    }

    /**
     * Queues one embed. Safe from any thread; never blocks.
     *
     * Call this only behind the per-event toggle - it is the operator's say
     * over what their channel fills up with.
     */
    public void send(DiscordEmbed embed) {
        if (embed == null || !active() || closing) {
            return;
        }
        Outbound item = new Outbound(plugin.rpgConfig().discordWebhookUrl(),
                plugin.rpgConfig().discordUsername(), embed);
        start();
        while (!queue.offer(item)) {
            // Full: Discord is not keeping up. Drop the oldest, because the
            // newest news is the news worth having, and never block the
            // caller - which may be the main thread mid-tick.
            if (queue.poll() != null) {
                dropped.incrementAndGet();
            }
        }
    }

    /** Lazily starts the sender, so a server with no webhook has no thread. */
    private void start() {
        if (running.compareAndSet(false, true)) {
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            Thread thread = new Thread(this::loop, "RPGCore-discord");
            // Daemon: an unreachable Discord must never be the reason a
            // server cannot exit. Shutdown drains explicitly, with a deadline.
            thread.setDaemon(true);
            this.sender = thread;
            thread.start();
        }
    }

    private void loop() {
        List<Outbound> batch = new ArrayList<>(DiscordEmbed.MAX_EMBEDS_PER_MESSAGE);
        while (true) {
            batch.clear();
            try {
                Outbound first = queue.poll(200L, TimeUnit.MILLISECONDS);
                if (first == null) {
                    // Nothing waiting. Only now is it safe to notice shutdown:
                    // checking the flag before the queue would abandon
                    // messages that are already in hand.
                    if (closing) {
                        return;
                    }
                    continue;
                }
                batch.add(first);
                // Hold the door open briefly. Ten deaths in one fight become
                // one Discord message instead of ten, which both stays inside
                // the rate limit and reads as the single event it was.
                long deadline = System.nanoTime() + BATCH_WINDOW_MS * 1_000_000L;
                while (batch.size() < DiscordEmbed.MAX_EMBEDS_PER_MESSAGE) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    Outbound next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    // A batch is one POST to one webhook under one name, so a
                    // change of either closes it.
                    if (!next.url().equals(first.url())
                            || !String.valueOf(next.username()).equals(String.valueOf(first.username()))) {
                        post(batch);
                        batch.clear();
                        first = next;
                    }
                    batch.add(next);
                }
                post(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // The sender thread outliving a bad message matters more than
                // the message; without this one malformed embed ends the relay
                // for the rest of the session.
                warnOnce("Discord 알림을 보내는 중 오류가 발생했습니다: " + e.getClass().getSimpleName());
            }
        }
    }

    private void post(List<Outbound> batch) throws InterruptedException {
        if (batch.isEmpty()) {
            return;
        }
        List<DiscordEmbed> embeds = new ArrayList<>(batch.size());
        for (Outbound item : batch) {
            embeds.add(item.embed());
        }
        String body = DiscordEmbed.toMessage(embeds, batch.get(0).username());
        String url = batch.get(0).url();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest request;
            try {
                request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        // Discord rejects requests without a real User-Agent.
                        .header("User-Agent", "RPGCore/" + plugin.getPluginMeta().getVersion()
                                + " (Minecraft server notifier)")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
            } catch (IllegalArgumentException e) {
                // Not a usable URL at all. Retrying cannot help.
                warnOnce("discord.webhook-url 이 올바른 주소가 아닙니다. Discord 알림을 보내지 않습니다.");
                return;
            }

            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    reportDrops();
                    return;
                }
                if (status == 429) {
                    // Rate limited. Discord says how long to wait; obey it
                    // rather than guessing, and do not count it as an attempt -
                    // being told to slow down is not a failure.
                    long waitMs = retryAfterMillis(response);
                    Thread.sleep(Math.min(waitMs, 30_000L));
                    attempt--;
                    continue;
                }
                if (status == 401 || status == 403 || status == 404) {
                    // A deleted or mistyped webhook. This will never succeed,
                    // and retrying it on every event would bury the log.
                    warnOnce("Discord 웹훅이 거부되었습니다 (HTTP " + status + "). "
                            + "discord.webhook-url 을 확인하세요. 이번 실행에서는 더 이상 시도하지 않습니다.");
                    return;
                }
                if (status < 500) {
                    // Our own payload is wrong; a retry sends the same bytes.
                    warnOnce("Discord 가 알림을 거부했습니다 (HTTP " + status + ").");
                    return;
                }
            } catch (java.io.IOException e) {
                // Network trouble: worth another try.
                if (attempt == MAX_ATTEMPTS) {
                    warnOnce("Discord 에 연결할 수 없습니다: " + e.getClass().getSimpleName());
                    return;
                }
            }
            Thread.sleep(500L * attempt);
        }
    }

    /** Discord sends the wait as a header and in the body; the header is enough. */
    private static long retryAfterMillis(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return (long) (Double.parseDouble(value.trim()) * 1000.0D);
                    } catch (NumberFormatException e) {
                        return 1000L;
                    }
                })
                .orElse(1000L);
    }

    private void reportDrops() {
        int lost = dropped.getAndSet(0);
        if (lost > 0) {
            plugin.getLogger().warning("Discord 알림 " + lost + "건이 대기열에서 밀려났습니다 "
                    + "(전송 속도보다 알림이 빠릅니다). 필요 없는 알림은 config.yml 의 discord.events 에서 끄세요.");
        }
    }

    private void warnOnce(String message) {
        if (muted.compareAndSet(false, true)) {
            plugin.getLogger().warning(message);
        }
    }

    /** Lets a reload re-open the log after the operator has fixed the URL. */
    public void unmute() {
        muted.set(false);
    }

    /**
     * Sends a last message and waits, briefly, for the queue to empty.
     *
     * This is the whole reason the sender is a thread rather than a scheduled
     * task: at this point the server is on its way down, so a "stopped"
     * message that is merely queued is a message that never arrives. The wait
     * is bounded because a hung Discord must not hold the server open.
     */
    public void shutdown(DiscordEmbed last) {
        if (last != null && active()) {
            send(last);
        }
        closing = true;
        Thread thread = this.sender;
        if (thread == null) {
            return;
        }
        try {
            thread.join(Math.max(0L, plugin.rpgConfig().discordShutdownWaitMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            plugin.getLogger().info("Discord 종료 알림을 제한 시간 안에 보내지 못해 그대로 종료합니다.");
        }
    }
}
