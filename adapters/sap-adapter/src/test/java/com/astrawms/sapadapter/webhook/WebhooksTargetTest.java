package com.astrawms.sapadapter.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.astrawms.common.web.ApiException;
import org.junit.jupiter.api.Test;

/** The webhook target guard (ADR-0025): HTTPS, no credentials, no private, loopback or link-local addresses. */
class WebhooksTargetTest {

    private final Webhooks production = new Webhooks(null, null, null, null, false, false);

    @Test
    void privateLoopbackAndPlainHttpTargetsAreRefused() {
        for (String url : new String[] {"http://partner.example.com/hook", "https://127.0.0.1/hook", "https://10.0.0.5/hook",
                "https://192.168.1.20/hook", "https://169.254.169.254/latest/meta-data", "https://[::1]/hook",
                "https://user:pw@example.com/hook", "ftp://example.com/x", "not a url"}) {
            assertThatThrownBy(() -> production.checkTarget(url)).as(url).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void localTargetsAreAllowedOnlyWhereConfigured() {
        Webhooks local = new Webhooks(null, null, null, null, true, true);
        assertThat(local.checkTarget("http://127.0.0.1:8099/hook").getPort()).isEqualTo(8099);
    }

    @Test
    void signatureIsHmacSha256() {
        // The well-known HMAC-SHA256 test vector.
        assertThat(Webhooks.hmac("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }
}
