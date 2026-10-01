package com.astrawms.test;

import jakarta.servlet.Filter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** MockMvc that runs the service's real security filter chain: token validation, tenant binding, role checks. */
public final class AstraMockMvc {

    private AstraMockMvc() {
    }

    public static MockMvc create(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }
}
