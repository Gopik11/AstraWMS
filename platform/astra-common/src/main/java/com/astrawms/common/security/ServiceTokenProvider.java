package com.astrawms.common.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Obtains and caches this service's own access token (OAuth2 client credentials, RFC 6749 §4.4). The token is renewed
 * a minute before it expires, so a call never carries an expired token.
 */
public class ServiceTokenProvider {

    private static final Duration RENEW_BEFORE = Duration.ofSeconds(60);

    private final RestClient rest;
    private final AstraSecurityProperties.Client client;
    private final Clock clock;
    private String token;
    private Instant renewAt = Instant.MIN;

    public ServiceTokenProvider(RestClient rest, AstraSecurityProperties.Client client, Clock clock) {
        this.rest = rest;
        this.client = client;
        this.clock = clock;
    }

    public synchronized String token() {
        Instant now = clock.instant();
        if (token == null || !now.isBefore(renewAt)) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", client.getClientId());
            form.add("client_secret", client.getClientSecret());
            JsonNode response = rest.post().uri(client.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.hasNonNull("access_token")) {
                throw new IllegalStateException("Token endpoint returned no access_token for " + client.getClientId());
            }
            token = response.get("access_token").asString();
            long expiresIn = response.path("expires_in").asLong(300);
            renewAt = now.plusSeconds(Math.max(expiresIn - RENEW_BEFORE.toSeconds(), expiresIn / 2));
        }
        return token;
    }
}
