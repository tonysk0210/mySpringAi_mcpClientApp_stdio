package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElicitationSessionStoreTest {

    private final ElicitationSessionStore store = new ElicitationSessionStore();

    @Test
    void cancelRemovesSessionAndCancelsWaitingFuture() {
        String sessionId = store.register(mock(McpSchema.ElicitRequest.class), "Annie");
        CompletableFuture<Map<String, Object>> future = store.getFuture(sessionId);

        assertFalse(store.cancel(sessionId, "Bob"));
        assertTrue(store.cancel(sessionId, "Annie"));
        assertThrows(CancellationException.class, future::get);
        assertFalse(store.hasPending("Annie"));
        assertFalse(store.cancel(sessionId, "Annie"));
    }

    @Test
    void completedSessionCannotBeCancelledAgain() throws Exception {
        String sessionId = store.register(mock(McpSchema.ElicitRequest.class), "Annie");
        CompletableFuture<Map<String, Object>> future = store.getFuture(sessionId);
        Map<String, Object> data = Map.of("priority", "HIGH");

        assertFalse(store.complete(sessionId, "Bob", data));
        assertTrue(store.complete(sessionId, "Annie", data));

        assertEquals(data, future.get());
        assertFalse(store.cancel(sessionId, "Annie"));
    }

    @Test
    void pendingSessionsAreIsolatedByOwner() {
        McpSchema.ElicitFormRequest annieRequest = mock(McpSchema.ElicitFormRequest.class);
        when(annieRequest.message()).thenReturn("Annie prompt");
        when(annieRequest.requestedSchema()).thenReturn(Map.of("type", "object"));
        String annieSession = store.register(annieRequest, "Annie");
        String bobSession = store.register(mock(McpSchema.ElicitRequest.class), "Bob");

        assertEquals(1, store.pendingForOwner("Annie").size());
        assertEquals(annieSession, store.pendingForOwner("Annie").getFirst().sessionId());
        assertEquals(Map.of("type", "object"), store.pendingForOwner("Annie").getFirst().schema());
        assertTrue(store.findPending(annieSession, "Annie").isPresent());
        assertTrue(store.findPending(bobSession, "Annie").isEmpty());
    }
}
