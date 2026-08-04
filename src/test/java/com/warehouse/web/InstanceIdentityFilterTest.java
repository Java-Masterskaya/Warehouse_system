package com.warehouse.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceIdentityFilterTest {

    @Test
    void shouldExposeInstanceIdOnEveryResponse() throws Exception {
        InstanceIdentityFilter filter = new InstanceIdentityFilter("warehouse-app-2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest(),
                response,
                new MockFilterChain()
        );

        assertThat(response.getHeader(InstanceIdentityFilter.INSTANCE_HEADER))
                .isEqualTo("warehouse-app-2");
    }
}
