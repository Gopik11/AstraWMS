package com.astrawms.common.web;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Clients for synchronous calls between AstraWMS services (ADR-0005, ADR-0029, review H-03/M-02). Every client:
 * <ul>
 *   <li>has connect and read timeouts;</li>
 *   <li>is a bulkhead: at most {@code maxConcurrent} calls are in flight; a further call waits briefly, then fails with
 *       {@link DownstreamBusyException} (a {@link ResourceAccessException}: callers and the problem handler treat it
 *       like a timeout, 503 with Retry-After) instead of tying up request threads and database connections behind a
 *       slow service;</li>
 *   <li>is measured: {@code astra_downstream_call_seconds} by client, method and outcome (2xx, 4xx, 5xx, timeout,
 *       error, busy) and {@code astra_downstream_in_flight} by client.</li>
 * </ul>
 */
public final class ServiceClients {

    /** How long a call waits for a free slot before it is refused as busy. */
    static final Duration SLOT_WAIT = Duration.ofMillis(250);

    private ServiceClients() {
    }

    /** The bulkhead is full: the downstream service is slow and this caller already has its limit of calls open. */
    public static class DownstreamBusyException extends ResourceAccessException {
        public DownstreamBusyException(String client, int limit) {
            super("Too many calls to " + client + " in flight (" + limit + "); not waiting for another");
        }
    }

    public static RestClient build(String name, String baseUrl, Duration timeout, ClientHttpRequestInterceptor auth,
                                   MeterRegistry meters, int maxConcurrent) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(bulkheadAndMetrics(name, meters == null ? new SimpleMeterRegistry() : meters, maxConcurrent));
        if (auth != null) {
            b.requestInterceptor(auth);
        }
        return b.build();
    }

    static ClientHttpRequestInterceptor bulkheadAndMetrics(String name, MeterRegistry meters, int maxConcurrent) {
        Semaphore slots = new Semaphore(maxConcurrent);
        Gauge.builder("astra.downstream.in.flight", slots, s -> maxConcurrent - s.availablePermits()).tag("client", name)
                .description("Calls to another AstraWMS service in flight").register(meters);
        return (request, body, execution) -> {
            long start = System.nanoTime();
            String method = request.getMethod().name();
            boolean acquired;
            try {
                acquired = slots.tryAcquire(SLOT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for a call slot to " + name, e);
            }
            if (!acquired) {
                record(meters, name, method, "busy", start);
                throw new DownstreamBusyException(name, maxConcurrent);
            }
            try {
                ClientHttpResponse response = execution.execute(request, body);
                record(meters, name, method, response.getStatusCode().value() / 100 + "xx", start);
                return response;
            } catch (SocketTimeoutException e) {
                record(meters, name, method, "timeout", start);
                throw e;
            } catch (IOException | RuntimeException e) {
                record(meters, name, method, "error", start);
                throw e;
            } finally {
                slots.release();
            }
        };
    }

    private static void record(MeterRegistry meters, String client, String method, String outcome, long start) {
        Timer.builder("astra.downstream.call").tag("client", client).tag("method", method).tag("outcome", outcome)
                .description("Synchronous call to another AstraWMS service").register(meters)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
}
