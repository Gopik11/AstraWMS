package com.astrawms.test;

import com.astrawms.common.config.AstraSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Test classpath only: services trust the {@link TestTokens} issuer instead of a real identity provider. The token
 * validation rules stay the production ones.
 */
@AutoConfiguration(before = AstraSecurityAutoConfiguration.class)
public class TestSecurityAutoConfiguration {

    @Bean
    JwtDecoder testJwtDecoder() {
        return TestTokens.decoder();
    }
}
