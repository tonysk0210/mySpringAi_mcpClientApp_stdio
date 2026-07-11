package com.example.myspringai_mcp_client.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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

    // CopyOnWriteArrayList 在多執行緒環境下安全地管理 SSE 連線清單
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * 前端訂閱時建立一條 SSE 連線，並加入管理清單。
     * 連線斷開時自動從清單移除。
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        log.info("前端 SSE 連線建立，目前連線數：{}", emitters.size());
        return emitter;
    }

    /**
     * 向所有已連線的前端推送 elicitation 事件。
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
    public void push(String sessionId, String prompt, Object schema) {
        Map<String, Object> payload = Map.of(
                "sessionId", sessionId,                     // ① 這次 elicitation 的唯一 ID
                "prompt", prompt,                               // ② MCP server 的說明文字 - MCP server 定義
                "schema", schema != null ? schema : Map.of()        // ③ 需要填哪些欄位的 JSON Schema - 由 MCP server 定義
        );
        List<SseEmitter> dead = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("elicitation") // SSE 事件名稱
                        .data(payload));         // 序列化成 JSON 傳出去
            } catch (IOException e) {
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
        log.info("Elicitation 事件已推送給 {} 個前端連線", emitters.size() - dead.size());
    }
}
