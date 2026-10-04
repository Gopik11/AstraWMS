package com.astrawms.sapadapter.webhook;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Webhook subscriptions (ADR-0025): integration administrators register partner endpoints for WMS events. */
@RestController
@RequestMapping("/api/v1/integration/webhooks")
@PreAuthorize("hasAnyRole('SOLUTION_ADMIN','ERP_INTEGRATION')")
public class WebhookController {

    private final Webhooks webhooks;

    public WebhookController(Webhooks webhooks) {
        this.webhooks = webhooks;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("events", Webhooks.EVENTS, "subscriptions", webhooks.list());
    }

    /** The response carries the signing secret, once. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Webhooks.SubscriptionRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(webhooks.create(body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        webhooks.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/deliveries")
    public List<Map<String, Object>> deliveries(@PathVariable UUID id) {
        return webhooks.deliveries(id);
    }

    @PostMapping("/{id}/ping")
    public Map<String, Object> ping(@PathVariable UUID id) {
        return webhooks.ping(id);
    }
}
