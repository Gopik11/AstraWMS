package com.astrawms.inbound;

import com.astrawms.common.web.ApiException;
import com.astrawms.inbound.inventory.InventoryClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Idempotent in-memory stand-in for inventory-service, with one-shot error injection. */
public class StubInventoryClient implements InventoryClient {

    public record Call(String siteId, String key, ReceiveCommand command) {
    }

    private final Map<String, UUID> operations = new ConcurrentHashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private volatile ApiException nextError;

    @Override
    public synchronized ReceiveResult receive(String siteId, String idempotencyKey, ReceiveCommand command) {
        if (nextError != null) {
            ApiException e = nextError;
            nextError = null;
            throw e;
        }
        calls.add(new Call(siteId, idempotencyKey, command));
        UUID existing = operations.get(idempotencyKey);
        if (existing != null) {
            return new ReceiveResult(existing, true);
        }
        UUID id = UUID.randomUUID();
        operations.put(idempotencyKey, id);
        return new ReceiveResult(id, false);
    }

    public synchronized List<Call> callsWithKeyPrefix(String prefix) {
        return calls.stream().filter(c -> c.key().startsWith(prefix)).toList();
    }

    public void failNext(ApiException e) {
        nextError = e;
    }
}
