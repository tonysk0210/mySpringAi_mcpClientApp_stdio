package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.List;
import java.util.Objects;
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

    // 對外暴露 session 資訊，供 controller 解析回覆及 SSE 重連時恢復提示。
    public record PendingSession(String sessionId, String owner, String serverMessage,
                                 Map<String, Object> schema) {
    }

    private record Session(String owner, McpSchema.ElicitRequest request,
                           CompletableFuture<Map<String, Object>> future) {
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 建立新 session，回傳唯一 sessionId。
     */
    public String register(McpSchema.ElicitRequest request, String owner) {
        Objects.requireNonNull(request, "request 不可為 null");
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner 不可為空白");
        }

        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(owner, request, new CompletableFuture<>()));
        return id;
    }

    /**
     * 是否有任何等待中的 session。
     */
    public boolean hasPending(String owner) {
        return sessions.values().stream().anyMatch(session -> session.owner().equals(owner));
    }

    /**
     * 取得指定 owner 的所有 pending session，供 SSE 重連時重送未完成事件。
     */
    public List<PendingSession> pendingForOwner(String owner) {
        return sessions.entrySet().stream()
                .filter(entry -> entry.getValue().owner().equals(owner))
                .map(entry -> toPendingSession(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 依 sessionId 與 owner 取得等待中的 session，避免跨使用者誤配。
     */
    public Optional<PendingSession> findPending(String sessionId, String owner) {
        Session session = sessions.get(sessionId);
        if (session == null || !session.owner().equals(owner)) {
            return Optional.empty();
        }
        return Optional.of(toPendingSession(sessionId, session));
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
    public boolean complete(String sessionId, String owner, Map<String, Object> data) {
        Session session = sessions.get(sessionId);
        if (session == null || !session.owner().equals(owner) || !sessions.remove(sessionId, session)) {
            return false;
        }
        session.future().complete(data);
        return true;
    }

    /**
     * 取消指定 session，並解除等待中的 elicitation thread。
     *
     * @return 找到並取消 session 時為 true；session 不存在或已完成時為 false
     */
    public boolean cancel(String sessionId, String owner) {
        Session session = sessions.get(sessionId);
        if (session == null || !session.owner().equals(owner) || !sessions.remove(sessionId, session)) {
            return false;
        }

        session.future().cancel(true);
        return true;
    }

    /**
     * 等待逾時時由 provider 清除 session，不經過外部 owner 驗證。
     */
    public boolean expire(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        session.future().cancel(true);
        return true;
    }

    private PendingSession toPendingSession(String sessionId, Session session) {
        Map<String, Object> schema = session.request() instanceof McpSchema.ElicitFormRequest formRequest
                ? formRequest.requestedSchema()
                : Map.of();
        return new PendingSession(sessionId, session.owner(), session.request().message(), schema);
    }
}
