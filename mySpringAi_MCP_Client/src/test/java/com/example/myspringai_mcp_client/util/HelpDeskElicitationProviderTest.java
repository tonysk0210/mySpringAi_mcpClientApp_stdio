package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HelpDeskElicitationProviderTest {

    private final ElicitationSessionStore sessionStore = mock(ElicitationSessionStore.class);
    private final ElicitationSseService sseService = mock(ElicitationSseService.class);
    private final HelpDeskElicitationProvider provider =
            new HelpDeskElicitationProvider(sessionStore, sseService);

    @Test
    void cancelledFutureReturnsMcpCancel() {
        McpSchema.ElicitRequest request = mockRequest();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        future.cancel(true);
        when(sessionStore.getFuture("session-1")).thenReturn(future);

        McpSchema.ElicitResult result = provider.handleElicitationRequest(request);

        assertEquals(McpSchema.ElicitResult.Action.CANCEL, result.action());
        verify(sseService).push(eq("Annie"), eq("session-1"), eq("請提供工單資料"), any());
    }

    @Test
    void completedFutureReturnsMcpAccept() {
        McpSchema.ElicitRequest request = mockRequest();
        Map<String, Object> data = Map.of("priority", "HIGH", "contactPhone", "0912345678");
        when(sessionStore.getFuture("session-1"))
                .thenReturn(CompletableFuture.completedFuture(data));

        McpSchema.ElicitResult result = provider.handleElicitationRequest(request);

        assertEquals(McpSchema.ElicitResult.Action.ACCEPT, result.action());
        assertEquals(data, result.content());
    }

    @Test
    void missingOwnerMetadataReturnsMcpDecline() {
        McpSchema.ElicitRequest request = mock(McpSchema.ElicitRequest.class);
        when(request.meta()).thenReturn(Map.of());

        McpSchema.ElicitResult result = provider.handleElicitationRequest(request);

        assertEquals(McpSchema.ElicitResult.Action.DECLINE, result.action());
        verifyNoInteractions(sseService);
        verify(sessionStore, never()).register(any(), any());
    }

    private McpSchema.ElicitRequest mockRequest() {
        McpSchema.ElicitRequest request = mock(McpSchema.ElicitRequest.class);
        when(request.message()).thenReturn("請提供工單資料");
        when(request.meta()).thenReturn(Map.of("username", "Annie"));
        when(sessionStore.register(request, "Annie")).thenReturn("session-1");
        return request;
    }
}
