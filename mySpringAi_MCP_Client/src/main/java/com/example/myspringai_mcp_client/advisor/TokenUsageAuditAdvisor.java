package com.example.myspringai_mcp_client.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

// Advisor 的運作模式類似 AOP（切面）：
// 每次 ChatClient 發送請求給 AI model，都會先經過所有 advisor 組成的責任鏈（chain）。
// 這個 advisor 的職責是：在 AI 回應回來之後，記錄這次呼叫消耗了多少 token。
// Token 用量攸關 API 費用，透過 log 可以追蹤每次呼叫的成本。
@Slf4j
public class TokenUsageAuditAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {

        // 1. 把 request 往責任鏈的下一個 advisor 傳遞，直到鏈末端真正送出給 AI model。
        //    nextCall() 是阻塞呼叫，會等到 AI 回應之後才繼續往下執行。
        //    因此這個 advisor 的邏輯屬於「後置處理」：request 出去、response 回來後才執行。
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequest);

        // 2. 從回應中取出 ChatResponse。
        //    ChatResponse 包含 AI 的回覆內容以及 metadata（模型名稱、token 用量等附加資訊）。
        ChatResponse chatResponse = chatClientResponse.chatResponse();

        // 3. 從 metadata 取出 token 用量。
        //    Usage 包含三個數字：promptTokens（輸入）、generationTokens（輸出）、totalTokens（總計）。
        //    部分 AI provider 不提供 usage 資訊，因此 usage 可能為 null，必須先做檢查。
        Usage usage = chatResponse.getMetadata().getUsage();

        // 4. 將 token 用量寫進 log，方便後續監控 API 費用。
        if (usage != null) {
            log.info("單次 LLM 呼叫 Token 使用量（本次輸入 + 輸出）: {}", usage.toString());
        }
        return chatClientResponse;
    }

    // Advisor 的識別名稱，Spring AI 用來區分不同 advisor，必須唯一。
    @Override
    public String getName() {
        return "TokenUsageAuditAdvisor";
    }

    // 執行順序：數字越小越早執行（越靠近責任鏈入口）。
    // -1 表示比大多數預設 advisor（order = 0）更早攔截 request，
    // 確保 token 統計涵蓋整條鏈的完整呼叫時間。
    @Override
    public int getOrder() {
        return -1;
    }
}

/*
  Request 進來
      │
      ▼
  ┌─ TokenUsageAuditAdvisor (order = -1)  ← 最外層
  │       │
  │       ▼ chain.nextCall()
  │   ┌─ PrettyLoggerAdvisor (order = 0)  ← 內層，緊鄰 AI
  │   │       │ logRequest()  ← 1. 先印出送給 AI 的內容
  │   │       ▼ chain.nextCall()
  │   │      AI Model
  │   │       │
  │   │       ▼ response 回來
  │   │   logResponse()       ← 2. 印出 AI 的回覆內容
  │   └──────────────────────────────────────
  │       │
  │       ▼ response 傳回外層
  │   log token usage         ← 3. 最後印出 token 用量
  └──────────────────────────────────────────
*/
