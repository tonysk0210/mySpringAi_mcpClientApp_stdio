package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理所有「等待使用者在聊天框輸入」的 elicitation session。
 * <p>
 * 每當 MCP server 發出 elicitation request，就在這裡建立一個 session，
 * 以 CompletableFuture 暫停並等待使用者回應。
 * 使用者在聊天框輸入後，controller 呼叫 complete() 解除暫停，
 * 讓 handleElicitationRequest() 取得資料繼續執行。
 */
@Component
public class ElicitationSessionStore {

    // 對外暴露 session 資訊，供 controller 取得提示訊息以傳給 LLM 解析
    public record PendingSession(String sessionId, String serverMessage) {
    }

    private record Session(McpSchema.ElicitRequest request, CompletableFuture<Map<String, Object>> future) {
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 建立新 session，回傳唯一 sessionId。
     */
    public String register(McpSchema.ElicitRequest request) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(request, new CompletableFuture<>()));
        return id;
    }

    /**
     * 是否有任何等待中的 session。
     */
    public boolean hasPending() {
        return !sessions.isEmpty();
    }

    /**
     * 取得第一個等待中的 session 資訊（sessionId + server 的提示訊息）。
     * 提示訊息會被傳給 LLM，作為解析使用者輸入的上下文。
     */
    public Optional<PendingSession> firstPending() {
        return sessions.entrySet().stream()
                .findFirst()
                .map(e -> new PendingSession(e.getKey(), e.getValue().request().message()));
    }

    /**
     * 取得指定 session 的 CompletableFuture，供 handleElicitationRequest() 阻塞等待。
     */
    public CompletableFuture<Map<String, Object>> getFuture(String sessionId) {
        Session s = sessions.get(sessionId);
        return s != null ? s.future() : null;
    }

    /**
     * 使用者提交資料 → 完成 future，解除 handleElicitationRequest() 的阻塞。
     */
    public void complete(String sessionId, Map<String, Object> data) {
        Optional.ofNullable(sessions.remove(sessionId))
                .ifPresent(s -> s.future().complete(data));
    }

    /**
     * 取消 session（逾時或例外時使用）。
     */
    public void cancel(String sessionId) {
        Optional.ofNullable(sessions.remove(sessionId))
                .ifPresent(s -> s.future().cancel(true));
    }
}
