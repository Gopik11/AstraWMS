package com.astrawms.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** The bulkhead and metrics of service-to-service clients (ADR-0029). */
class ServiceClientsTest {

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/slow", ex -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, 2);
            ex.getResponseBody().write("ok".getBytes());
            ex.close();
        });
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void callsBeyondTheLimitFailFastAsBusyAndAreCounted() throws Exception {
        RestClient client = ServiceClients.build("inventory", base(), Duration.ofSeconds(5), null, meters, 2);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        List<Future<String>> slow = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            slow.add(callers.submit(() -> client.get().uri("/slow").retrieve().body(String.class)));
        }
        while (meters.get("astra.downstream.in.flight").tag("client", "inventory").gauge().value() < 2) {
            Thread.sleep(10);
        }
        long t = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/slow").retrieve().body(String.class))
                .isInstanceOf(ServiceClients.DownstreamBusyException.class)
                .isInstanceOf(ResourceAccessException.class);
        // refused after the short slot wait, not after the slow calls
        assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(Duration.ofSeconds(2));
        release.countDown();
        for (Future<String> f : slow) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
        }
        callers.shutdown();
        assertThat(meters.get("astra.downstream.call").tag("client", "inventory").tag("outcome", "busy").timer().count())
                .isEqualTo(1);
        assertThat(meters.get("astra.downstream.call").tag("client", "inventory").tag("outcome", "2xx").timer().count())
                .isEqualTo(2);
        assertThat(meters.get("astra.downstream.in.flight").tag("client", "inventory").gauge().value()).isZero();
    }

    @Test
    void timeoutsAndClientErrorsAreMeasuredByOutcome() {
        RestClient client = ServiceClients.build("master-data", base(), Duration.ofMillis(300), null, meters, 4);
        assertThatThrownBy(() -> client.get().uri("/slow").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> client.get().uri("/missing").retrieve().body(String.class))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
        assertThat(meters.get("astra.downstream.call").tag("outcome", "timeout").timer().count()).isEqualTo(1);
        assertThat(meters.get("astra.downstream.call").tag("outcome", "4xx").timer().count()).isEqualTo(1);
    }
}
