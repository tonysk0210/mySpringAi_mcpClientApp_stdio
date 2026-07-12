package com.example.myspringai_mcp_client.controller;

import com.example.myspringai_mcp_client.util.ElicitationSessionStore;
import com.example.myspringai_mcp_client.util.ElicitationSseService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HelpDeskControllerTest {

    @Test
    void cancelElicitationReturnsOkThenNotFound() {
        ElicitationSessionStore sessionStore = new ElicitationSessionStore();
        String sessionId = sessionStore.register(mock(McpSchema.ElicitRequest.class), "Annie");
        HelpDeskController controller = newController(sessionStore);

        ResponseEntity<String> wrongOwner = controller.cancelElicitation(sessionId, "Bob");
        ResponseEntity<String> first = controller.cancelElicitation(sessionId, "Annie");
        ResponseEntity<String> second = controller.cancelElicitation(sessionId, "Annie");

        assertEquals(HttpStatus.NOT_FOUND, wrongOwner.getStatusCode());
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, second.getStatusCode());
    }

    private HelpDeskController newController(ElicitationSessionStore sessionStore) {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_SELF);
        ChatClient.Builder parserClientBuilder = mock(ChatClient.Builder.class, RETURNS_SELF);
        when(chatClientBuilder.build()).thenReturn(mock(ChatClient.class));
        when(parserClientBuilder.build()).thenReturn(mock(ChatClient.class));

        return new HelpDeskController(
                chatClientBuilder,
                parserClientBuilder,
                List.of(),
                sessionStore,
                mock(ElicitationSseService.class));
    }
}
