package com.example.myspringai_mcp_client.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理前端的 SSE（Server-Sent Events）連線，負責把 elicitation 事件即時推送到聊天介面。
 * <p>
 * 當 MCP server 發出 elicitation request，這個 service 會透過 SSE
 * 把提示訊息和 sessionId 推送給前端，前端收到後在聊天框顯示一則 bot 訊息，
 * 引導使用者輸入所需的補充資料。
 */
@Slf4j
@Component
public class ElicitationSseService {

    // 每個 owner 維護自己的 SSE 連線，避免把 elicitation 廣播給其他使用者。
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emittersByOwner =
            new ConcurrentHashMap<>();

    /**
     * 前端訂閱時建立一條 SSE 連線，並加入管理清單。
     * 連線斷開時自動從清單移除。
     */
    public SseEmitter subscribe(String owner) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        CopyOnWriteArrayList<SseEmitter> emitters =
                emittersByOwner.computeIfAbsent(owner, ignored -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> removeEmitter(owner, emitter));
        emitter.onTimeout(() -> removeEmitter(owner, emitter));
        emitter.onError(e -> removeEmitter(owner, emitter));

        log.info("前端 SSE 連線建立 owner={}，該使用者連線數：{}", owner, emitters.size());
        return emitter;
    }

    /**
     * 只向指定 owner 的前端連線推送 elicitation 事件。
     * <p>
     * 前端收到後，應在聊天框顯示一則 bot 訊息引導使用者輸入，例如：
     * 「⚠️ {prompt}」，並根據 schema 顯示需要填寫的欄位與合法值。
     *
     * @param sessionId 此次 elicitation 的唯一識別碼，由 server 內部追蹤對應的 CompletableFuture
     * @param prompt    MCP server 的說明文字，描述為何需要補充資料（直接顯示給使用者看）
     * @param schema    MCP server 定義的欄位 schema（JSON Schema 格式），
     *                  描述需要填入哪些欄位、型別、以及合法值範圍，
     *                  前端可用來動態產生提示或表單欄位
     */
    public void push(String owner, String sessionId, String prompt, Object schema) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByOwner.get(owner);
        if (emitters == null || emitters.isEmpty()) {
            log.info("Elicitation 等待前端重連 owner={} sessionId={}", owner, sessionId);
            return;
        }

        List<SseEmitter> dead = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            if (!send(emitter, sessionId, prompt, schema)) {
                dead.add(emitter);
            }
        }
        /*
        前端實際收到的 SSE 訊息長這樣：

          event: elicitation
          data: {
            "sessionId": "a3f2c1d0-...",
            "prompt":    "請提供工單優先等級與聯絡電話，以便完成工單建立",
            "schema": {
              "type": "object",
              "properties": {
                "priority":     { "type": "string", "enum": ["HIGH","MEDIUM","LOW"] },
                "contactPhone": { "type": "string" }
              },
              "required": ["priority", "contactPhone"]
            }
          }
        * */
        emitters.removeAll(dead);
        removeOwnerIfEmpty(owner, emitters);
        log.info("Elicitation 事件已推送 owner={}，成功連線數：{}", owner, emitters.size());
    }

    /**
     * 每 15 秒送一次 SSE comment 心跳。
     * 目的有二：
     * 1. 偵測死連線：send() 對已斷線的 emitter 拋 IOException → 立刻清除，不必等下次 push()
     * 2. 保活：防止 NAT / proxy 因連線閒置過久主動切斷
     */
    @Scheduled(fixedDelay = 15_000)
    public void sendHeartbeat() {
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : emittersByOwner.entrySet()) {
            String owner = entry.getKey();
            CopyOnWriteArrayList<SseEmitter> emitters = entry.getValue();
            List<SseEmitter> dead = new ArrayList<>();

            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException e) {
                    dead.add(emitter);
                }
            }

            emitters.removeAll(dead);
            removeOwnerIfEmpty(owner, emitters);
            if (!dead.isEmpty()) {
                log.info("心跳清除 {} 個 SSE 死連線 owner={}，剩餘：{}", dead.size(), owner, emitters.size());
            }
        }
    }

    private boolean send(SseEmitter emitter, String sessionId, String prompt, Object schema) {
        Map<String, Object> payload = Map.of(
                "sessionId", sessionId,
                "prompt", prompt,
                "schema", schema != null ? schema : Map.of());
        try {
            emitter.send(SseEmitter.event().name("elicitation").data(payload));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void removeEmitter(String owner, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByOwner.get(owner);
        if (emitters == null) return;
        emitters.remove(emitter);
        removeOwnerIfEmpty(owner, emitters);
    }

    private void removeOwnerIfEmpty(String owner, CopyOnWriteArrayList<SseEmitter> emitters) {
        if (emitters.isEmpty()) {
            emittersByOwner.remove(owner, emitters);
        }
    }
}
