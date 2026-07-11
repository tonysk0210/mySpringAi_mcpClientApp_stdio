package com.example.myspringai_mcp_client.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class PrettyLoggerAdvisor implements CallAdvisor {

    private static final String BAR = "═".repeat(50);
    private static final String CONT = "║               ";
    private static final int MAX_LINE = 160; // 超過此寬度強制換行，確保每行都有 ║ 前綴

    private final AtomicInteger callCount = new AtomicInteger(0);

    /** 每次 API 請求開始前呼叫，讓本次 LLM 呼叫序號從 #1 重新計算。 */
    public void reset() {
        callCount.set(0);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        int n = callCount.incrementAndGet();
        logRequest(request, n);
        ChatClientResponse response = chain.nextCall(request);
        logResponse(response, n);
        return response;
    }

    // ────────────────────────────────────────────────────────────
    // 請求
    // ────────────────────────────────────────────────────────────

    private void logRequest(ChatClientRequest request, int n) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══ ► LLM Request #").append(n).append(" ").append(BAR).append("\n");

        for (Message message : request.prompt().getInstructions()) {
            switch (message.getMessageType()) {
                case SYSTEM -> appendSection(sb, "[SYSTEM]", message.getText());
                case USER -> appendSection(sb, "[USER]", message.getText());
                case ASSISTANT -> appendAssistantMessage(sb, (AssistantMessage) message);
                case TOOL -> appendToolResponse(sb, (ToolResponseMessage) message);
                default -> appendSection(sb, "[" + message.getMessageType() + "]", message.getText());
            }
        }

        appendDocs(sb, request.context());
        sb.append("╚").append(BAR).append("══════════════════");
        log.debug(sb.toString());
    }

    private void appendAssistantMessage(StringBuilder sb, AssistantMessage message) {
        if (message.getText() != null && !message.getText().isBlank()) {
            appendSection(sb, "[ASSISTANT]", message.getText());
        }
        for (AssistantMessage.ToolCall tc : message.getToolCalls()) {
            appendSection(sb, "[TOOL_CALL]", tc.name() + "(" + tc.arguments() + ")");
        }
    }

    private void appendToolResponse(StringBuilder sb, ToolResponseMessage message) {
        for (ToolResponseMessage.ToolResponse tr : message.getResponses()) {
            appendSection(sb, "[TOOL_RESP]", tr.name() + " → " + tr.responseData());
        }
    }

    private void appendDocs(StringBuilder sb, Map<String, Object> context) {
        List<Document> docs = getRagDocuments(context);
        if (docs.isEmpty()) return;

        sb.append(String.format("║ %-13s ", "[DOCS]")).append(docs.size()).append(" 筆\n");
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            sb.append(String.format("%s#%d  chunk_index=%-3s  score=%.4f  %s%n",
                    CONT, i + 1,
                    doc.getMetadata().get("chunk_index"),
                    doc.getScore(),
                    doc.getMetadata().get("source")));
        }
    }

    // ────────────────────────────────────────────────────────────
    // 回應
    // ────────────────────────────────────────────────────────────

    private void logResponse(ChatClientResponse response, int n) {
        AssistantMessage output = response.chatResponse().getResult().getOutput();
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══ ◄ LLM Response #").append(n).append(" ").append(BAR).append("\n");

        if (output.getText() != null && !output.getText().isBlank()) {
            appendSection(sb, "[ASSISTANT]", output.getText());
        }
        for (AssistantMessage.ToolCall tc : output.getToolCalls()) {
            appendSection(sb, "[TOOL_CALL]", tc.name() + "(" + tc.arguments() + ")");
        }
        if ((output.getText() == null || output.getText().isBlank()) && output.getToolCalls().isEmpty()) {
            appendSection(sb, "[ASSISTANT]", "（無文字）");
        }

        sb.append("╚").append(BAR).append("══════════════════");
        log.debug(sb.toString());
    }

    // ────────────────────────────────────────────────────────────
    // 輔助方法
    // ────────────────────────────────────────────────────────────

    private void appendSection(StringBuilder sb, String label, String content) {
        String firstPrefix = String.format("║ %-13s ", label);
        String[] lines = content.split("[\\r\\n]+");
        boolean first = true;
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            do {
                String prefix = first ? firstPrefix : CONT;
                int avail = MAX_LINE - prefix.length();
                if (line.length() <= avail) {
                    sb.append(prefix).append(line).append("\n");
                    line = "";
                } else {
                    sb.append(prefix).append(line, 0, avail).append("\n");
                    line = line.substring(avail);
                }
                first = false;
            } while (!line.isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Document> getRagDocuments(Map<String, Object> context) {
        Object docs = context.get("rag_document_context");
        return docs instanceof List<?> list ? (List<Document>) list : List.of();
    }

    @Override
    public String getName() {
        return "PrettyLoggerAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
