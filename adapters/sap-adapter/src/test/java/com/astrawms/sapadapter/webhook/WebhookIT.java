package com.astrawms.sapadapter.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.GoodsMovement;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.security.Roles;
import com.astrawms.test.AstraContainers;
import com.astrawms.test.AstraMockMvc;
import com.astrawms.test.TestTokens;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0025 webhooks: an issue posted becomes a signed delivery to the subscriber. This test allows a local HTTP target
 * (the production default refuses http and private addresses; see {@link WebhooksTargetTest}).
 */
@SpringBootTest(properties = {"astra.outbox.relay-enabled=false", "astra.webhooks.allow-private-targets=true",
        "astra.webhooks.allow-http=true", "astra.webhooks.dispatch-interval-ms=300"})
class WebhookIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        AstraContainers.register(r);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JsonMapper json;

    MockMvc mvc;
    String tenant;
    HttpServer receiver;
    final List<Map<String, String>> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mvc = AstraMockMvc.create(context);
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(Map.of("body", body,
                    "signature", exchange.getRequestHeaders().getFirst("X-AstraWMS-Signature"),
                    "timestamp", exchange.getRequestHeaders().getFirst("X-AstraWMS-Timestamp"),
                    "event", exchange.getRequestHeaders().getFirst("X-AstraWMS-Event")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void stop() {
        receiver.stop(0);
    }

    @Test
    void anIssuePostedIsDeliveredSignedToTheSubscriber() throws Exception {
        String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
        mvc.perform(post("/api/v1/integration/webhooks").with(TestTokens.as(tenant, "rita", Roles.RECEIVER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/integration/webhooks").with(TestTokens.as(tenant, "int", Roles.ERP_INTEGRATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"partner\",\"url\":\"" + url + "\",\"events\":[\"no.such.event\"]}"))
                .andExpect(jsonPath("$.code", is("INT_WEBHOOK_EVENT")));
        String created = mvc.perform(post("/api/v1/integration/webhooks").with(TestTokens.as(tenant, "int", Roles.ERP_INTEGRATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"partner\",\"url\":\"" + url + "\",\"events\":[\"issue.posted\",\"count.variance\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String secret = JsonPath.read(created, "$.secret");
        assertThat(secret).startsWith("whsec_");

        // A status change is not a subscribed event; an issue to a cost centre is.
        send(new GoodsMovement("W1STATUS00000001", "STATUS_AVL_TO_QI", "QA_HOLD", Instant.now(), null, List.of(item())));
        send(new GoodsMovement("W1ISSUE000000001", "ISSUE_COST_CENTER", null, Instant.now(), null, List.of(item()),
                new GoodsMovement.AccountAssignment("COST_CENTER", "CC100", "Maintenance", "MI000001")));
        await(() -> !received.isEmpty());
        Thread.sleep(1500);
        assertThat(received).hasSize(1);
        Map<String, String> r = received.getFirst();
        assertThat(r.get("event")).isEqualTo("issue.posted");
        assertThat(r.get("body")).contains("\"movementType\":\"ISSUE_COST_CENTER\"", "\"code\":\"CC100\"", "\"issueNo\":\"MI000001\"");
        assertThat(r.get("signature")).isEqualTo("sha256=" + Webhooks.hmac(secret, r.get("timestamp") + "." + r.get("body")));

        mvc.perform(get("/api/v1/integration/webhooks/" + id + "/deliveries").with(TestTokens.as(tenant, "int", Roles.ERP_INTEGRATION)))
                .andExpect(jsonPath("$[0].status", is("DELIVERED"))).andExpect(jsonPath("$[0].last_status", is(204)));
        // Another tenant sees nothing of it.
        mvc.perform(get("/api/v1/integration/webhooks").with(TestTokens.as("other-" + tenant, "int", Roles.ERP_INTEGRATION)))
                .andExpect(jsonPath("$.subscriptions.length()", is(0)));
    }

    private static GoodsMovement.Item item() {
        return new GoodsMovement.Item("SKU-1", new BigDecimal("2"), "EA", "0001", null, null, null, null, "UNRESTRICTED", "t");
    }

    private void send(GoodsMovement m) throws Exception {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), GoodsMovement.TYPE, "2.1", "ASTRAWMS", "SAP", tenant, "DC1",
                "ACME", "DC1:SKU-1", "test", 1, Instant.now(), json.valueToTree(m));
        kafka.send(IntegrationContracts.TOPIC_GOODS_MOVEMENTS, tenant + ":DC1:SKU-1", json.writeValueAsString(e)).get();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Condition not met");
    }
}
