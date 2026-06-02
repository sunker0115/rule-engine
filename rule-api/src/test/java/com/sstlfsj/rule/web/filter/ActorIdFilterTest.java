package com.sstlfsj.rule.web.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActorIdFilterTest {

    private final ActorIdFilter filter = new ActorIdFilter();

    @Test
    void setsActorIdFromHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Actor-Id", "user1");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, new MockHttpServletResponse(), (request, response) -> {
            assertEquals("user1", ActorIdFilter.current());
            chain.doFilter(request, response);
        });

        verify(chain).doFilter(any(), any());
    }

    @Test
    void fallsBackToUnknown_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, new MockHttpServletResponse(), (request, response) -> {
            assertEquals("UNKNOWN", ActorIdFilter.current());
            chain.doFilter(request, response);
        });
    }

    @Test
    void clearsThreadLocal_afterRequest() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Actor-Id", "user1");

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        // ThreadLocal 在 finally 块中被清除，请求结束后应为 null
        assertNull(ActorIdFilter.current());
    }
}
