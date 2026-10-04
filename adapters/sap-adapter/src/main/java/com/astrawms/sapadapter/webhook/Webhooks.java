package com.astrawms.sapadapter.webhook;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbound webhooks (ADR-0025) next to the SAP IDoc interface.
 * <ul>
 *   <li>Events: {@code transfer.shipped}, {@code transfer.received}, {@code issue.posted}, {@code issue.returned},
 *       {@code count.variance}, and {@code ping} (test).</li>
 *   <li>Each delivery is a JSON POST signed with the subscription secret:
 *       {@code X-AstraWMS-Signature: sha256=HMAC(secret, timestamp + "." + body)} with {@code X-AstraWMS-Timestamp}.</li>
 *   <li>Retries with exponential backoff (30 s × 2^n) up to {@value #MAX_ATTEMPTS} attempts, then FAILED.</li>
 *   <li>Targets must be HTTPS and must not resolve to private, loopback or link-local addresses (server-side request
 *       forgery guard), unless the environment allows them (local development and tests only). No redirects.</li>
 * </ul>
 */
@Service
public class Webhooks {

    public static final List<String> EVENTS = List.of("transfer.shipped", "transfer.received", "issue.posted",
            "issue.returned", "count.variance");
    static final int MAX_ATTEMPTS = 8;
    private static final Logger log = LoggerFactory.getLogger(Webhooks.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final boolean allowPrivate;
    private final boolean allowHttp;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final SecureRandom random = new SecureRandom();

    public Webhooks(JdbcClient jdbc, JsonMapper json, Clock clock, TransactionTemplate tx,
                    @Value("${astra.webhooks.allow-private-targets:false}") boolean allowPrivate,
                    @Value("${astra.webhooks.allow-http:false}") boolean allowHttp) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.tx = tx;
        this.allowPrivate = allowPrivate;
        this.allowHttp = allowHttp;
    }

    // ------------------------------------------------------------------ subscriptions

    public record SubscriptionRequest(String name, String url, List<String> events) {
    }

    /** Creates a subscription; the secret is returned once, here, and never again. */
    @Transactional
    public Map<String, Object> create(SubscriptionRequest r) {
        if (r.name() == null || r.name().isBlank() || r.events() == null || r.events().isEmpty()) {
            throw ApiException.badRequest("INT_WEBHOOK_INVALID", "name, url and events are required");
        }
        List<String> events = r.events().stream().map(e -> e.trim().toLowerCase()).distinct().toList();
        if (!EVENTS.containsAll(events)) {
            throw ApiException.badRequest("INT_WEBHOOK_EVENT", "events are among " + EVENTS);
        }
        URI url = checkTarget(r.url());
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String secret = "whsec_" + HexFormat.of().formatHex(raw);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into webhook_subscription (id, tenant_id, name, url, events, secret, created_by, created_at)
                        values (:id, :t, :name, :url, :events, :secret, :user, :now)""")
                .param("id", id).param("t", TenantContext.tenantId()).param("name", r.name().trim())
                .param("url", url.toString()).param("events", events.toArray(String[]::new)).param("secret", secret)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        Map<String, Object> out = new LinkedHashMap<>(one(id));
        out.put("secret", secret);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return jdbc.sql("""
                        select s.id, s.name, s.url, s.events, s.active, s.created_by, s.created_at,
                               (select count(*) from webhook_delivery d where d.subscription_id = s.id and d.status = 'PENDING') as pending,
                               (select count(*) from webhook_delivery d where d.subscription_id = s.id and d.status = 'FAILED') as failed,
                               (select max(d.delivered_at) from webhook_delivery d where d.subscription_id = s.id) as last_delivered_at
                        from webhook_subscription s order by s.created_at""")
                .query().listOfRows().stream().map(Webhooks::arrays).toList();
    }

    private Map<String, Object> one(UUID id) {
        return list().stream().filter(s -> id.equals(s.get("id"))).findFirst()
                .orElseThrow(() -> ApiException.notFound("INT_WEBHOOK_UNKNOWN", "No webhook " + id));
    }

    @Transactional
    public void delete(UUID id) {
        if (jdbc.sql("delete from webhook_subscription where id = :id").param("id", id).update() == 0) {
            throw ApiException.notFound("INT_WEBHOOK_UNKNOWN", "No webhook " + id);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> deliveries(UUID id) {
        one(id);
        return jdbc.sql("""
                        select id, event, message_id, status, attempts, last_status, last_error, created_at, delivered_at,
                               next_attempt_at
                        from webhook_delivery where tenant_id = :t and subscription_id = :s order by created_at desc limit 100""")
                .param("t", TenantContext.tenantId()).param("s", id).query().listOfRows();
    }

    /** Queues a {@code ping} to one subscription, to test the endpoint and its signature check. */
    @Transactional
    public Map<String, Object> ping(UUID id) {
        one(id);
        String messageId = "ping-" + UUID.randomUUID();
        queue(id, messageId, "ping", Map.of("message", "AstraWMS webhook test"));
        return Map.of("queued", messageId);
    }

    // ------------------------------------------------------------------ queueing (in the tenant's context)

    /** Queues {@code event} to every active subscription of the current tenant that wants it (once per message). */
    public void publish(String event, String messageId, Object data) {
        List<UUID> subs = jdbc.sql("select id from webhook_subscription where active and :e = any(events)")
                .param("e", event).query(UUID.class).list();
        for (UUID s : subs) {
            queue(s, messageId, event, data);
        }
    }

    private void queue(UUID subscription, String messageId, String event, Object data) {
        Map<String, Object> sub = jdbc.sql("select url, secret from webhook_subscription where id = :id").param("id", subscription)
                .query().singleRow();
        Instant now = clock.instant();
        UUID deliveryId = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", deliveryId);
        body.put("event", event);
        body.put("tenant", TenantContext.tenantId());
        body.put("occurredAt", now.toString());
        body.put("data", data);
        String text = json.writeValueAsString(body);
        long ts = now.getEpochSecond();
        jdbc.sql("""
                        insert into webhook_delivery (id, tenant_id, subscription_id, message_id, event, url, body, signature,
                                                      signed_at, status, next_attempt_at, created_at)
                        values (:id, :t, :s, :m, :e, :url, :body, :sig, :ts, 'PENDING', :now, :now)
                        on conflict (subscription_id, message_id) do nothing""")
                .param("id", deliveryId).param("t", TenantContext.tenantId()).param("s", subscription).param("m", messageId)
                .param("e", event).param("url", sub.get("url")).param("body", text)
                .param("sig", "sha256=" + hmac((String) sub.get("secret"), ts + "." + text)).param("ts", ts)
                .param("now", Timestamp.from(now)).update();
    }

    static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ dispatching

    @Scheduled(fixedDelayString = "${astra.webhooks.dispatch-interval-ms:5000}")
    public void dispatchScheduled() {
        try {
            dispatchOnce();
        } catch (RuntimeException e) {
            log.warn("Webhook dispatch failed; will retry", e);
        }
    }

    record Due(UUID id, String url, String body, String signature, long signedAt, String event, int attempts) {
    }

    /** Sends the deliveries that are due; returns how many were attempted. */
    public int dispatchOnce() {
        List<Due> due = tx.execute(s -> {
            List<Due> rows = jdbc.sql("""
                            select id, url, body, signature, signed_at, event, attempts from webhook_delivery
                            where status = 'PENDING' and next_attempt_at <= :now order by next_attempt_at limit 20
                            for update skip locked""")
                    .param("now", Timestamp.from(clock.instant()))
                    .query((rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getString(6), rs.getInt(7))).list();
            // Claim them for a minute so a parallel dispatcher does not send them too.
            rows.forEach(d -> jdbc.sql("update webhook_delivery set next_attempt_at = :later where id = :id")
                    .param("later", Timestamp.from(clock.instant().plusSeconds(60))).param("id", d.id()).update());
            return rows;
        });
        if (due == null) {
            return 0;
        }
        for (Due d : due) {
            Integer status = null;
            String error = null;
            try {
                URI target = checkTarget(d.url());
                HttpResponse<Void> r = http.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "AstraWMS-Webhooks/1")
                        .header("X-AstraWMS-Event", d.event())
                        .header("X-AstraWMS-Delivery", d.id().toString())
                        .header("X-AstraWMS-Timestamp", Long.toString(d.signedAt()))
                        .header("X-AstraWMS-Signature", d.signature())
                        .POST(HttpRequest.BodyPublishers.ofString(d.body())).build(), HttpResponse.BodyHandlers.discarding());
                status = r.statusCode();
                if (status < 200 || status >= 300) {
                    error = "HTTP " + status;
                }
            } catch (ApiException e) {
                error = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return due.size();
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
            record(d, status, error);
        }
        return due.size();
    }

    private void record(Due d, Integer status, String error) {
        Instant now = clock.instant();
        int attempts = d.attempts() + 1;
        String state = error == null ? "DELIVERED" : attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
        tx.executeWithoutResult(s -> jdbc.sql("""
                        update webhook_delivery set status = :state, attempts = :a, last_status = :code, last_error = :err,
                            next_attempt_at = :next, delivered_at = cast(:delivered as timestamptz)
                        where id = :id""")
                .param("state", state).param("a", attempts).param("code", status)
                .param("err", error == null ? null : error.length() > 300 ? error.substring(0, 300) : error)
                .param("next", Timestamp.from(now.plusSeconds(30L << Math.min(attempts, 10))))
                .param("delivered", error == null ? Timestamp.from(now) : null).param("id", d.id()).update());
    }

    // ------------------------------------------------------------------ target guard

    /** HTTPS (HTTP only where allowed), no credentials in the URL, and no private, loopback or link-local address. */
    URI checkTarget(String url) {
        URI u;
        try {
            u = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INT_WEBHOOK_URL", "Not a valid URL");
        }
        if (u.getHost() == null || u.getUserInfo() != null
                || !("https".equalsIgnoreCase(u.getScheme()) || (allowHttp && "http".equalsIgnoreCase(u.getScheme())))) {
            throw ApiException.badRequest("INT_WEBHOOK_URL", "The URL must be https://host/... without credentials");
        }
        if (!allowPrivate) {
            try {
                for (InetAddress a : InetAddress.getAllByName(u.getHost())) {
                    if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                            || a.isMulticastAddress() || isUniqueLocal(a) || isCarrierGrade(a)) {
                        throw ApiException.badRequest("INT_WEBHOOK_URL", "The URL resolves to a private address: not allowed");
                    }
                }
            } catch (UnknownHostException e) {
                throw ApiException.badRequest("INT_WEBHOOK_URL", "Unknown host " + u.getHost());
            }
        }
        return u;
    }

    private static boolean isUniqueLocal(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;           // fc00::/7
    }

    private static boolean isCarrierGrade(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;   // 100.64.0.0/10
    }

    private static Map<String, Object> arrays(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>(r);
        if (r.get("events") instanceof java.sql.Array a) {
            try {
                out.put("events", List.of((Object[]) a.getArray()));
            } catch (java.sql.SQLException e) {
                out.put("events", List.of());
            }
        }
        return out;
    }
}
