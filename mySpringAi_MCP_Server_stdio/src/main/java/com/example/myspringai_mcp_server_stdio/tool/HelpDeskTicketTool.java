package com.example.myspringai_mcp_server_stdio.tool;

import com.example.myspringai_mcp_server_stdio.entity.HelpDeskTicketEntity;
import com.example.myspringai_mcp_server_stdio.payload.HelpDeskTicketPayload;
import com.example.myspringai_mcp_server_stdio.payload.TicketContactInfo;
import com.example.myspringai_mcp_server_stdio.service.HelpDeskTicketService;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class HelpDeskTicketTool {

    private static final String MCP_LOGGER = "MySpringAi_MCP_Server_stdio_logger";

    private static final String DEFAULT_PRIORITY = "MEDIUM";
    private static final String NO_PHONE_PROVIDED = "N/A";

    private final HelpDeskTicketService service;

    // @McpTool 方法不用 public : 「這些方法故意不寫 public，因為它們不是要給其他 Java 程式碼直接呼叫的，而是由 Spring AI MCP framework 透過 reflection 在內部呼叫。」

    /**
     * 建立服務工單。
     * <p>
     * 若 MCP client 支援 elicitation，會在建立前向使用者收集優先等級與聯絡電話；
     * 若不支援或使用者拒絕，則以預設值（MEDIUM 優先級、無電話）繼續建立。
     * 回傳格式化的工單確認訊息，包含工單編號、狀態與預計處理時間。
     */
    @McpTool(name = "createTicket", description = "建立「服務工單」")
    String createTicket(
            @McpToolParam(description = "遇到的問題描述") String issue,
            @McpToolParam(description = "使用者名稱") String username,
            McpSyncRequestContext ctx) {

        /* McpSyncRequestContext 是 Spring AI MCP framework 在每次 tool 被呼叫時動態建立的物件，代表「這一次 MCP 請求的上下文」。
        像 Spring MVC 裡的 HttpServletRequest——每個 HTTP 請求都有一個獨立的 HttpServletRequest，不是 singleton bean */

        HelpDeskTicketPayload payload = new HelpDeskTicketPayload(issue, username);

        log.info("正在為使用者「{}」建立服務工單，問題描述：「{}」", username, issue);
        info(ctx, "正在為使用者「" + username + "」建立服務工單，問題描述：「" + issue + "」");

        String priority = DEFAULT_PRIORITY;
        String contactPhone = NO_PHONE_PROVIDED;

        // 1. Elicitation（核心機制）：請求使用者提供額外的工單細節
        if (ctx.elicitEnabled()) { // ① — 先確認 client 是否支援 elicitation

            log.info("正在透過 elicitation 向 MCP client 請求額外的工單細節...");
            info(ctx, "正在透過 elicitation 向 MCP client 請求額外的工單細節...");

            /*
            Spring AI 會根據 TicketContactInfo record 產生 MCP elicitation 的 requestedSchema，
            MCP client 可依此 schema 向使用者收集 priority/contactPhone。
            若使用者接受，Spring AI 會把 client 回傳的 content 轉回 TicketContactInfo。
            就像 Java 裡的 Scanner.nextLine()——程式會在那一行卡住，等使用者在 terminal 輸入並按 Enter，才繼續往下跑。ctx.elicit() 的概念完全一樣，只是等待的對象換成「MCP client 上的使用者問卷」。
            */

            // 1.1 請求使用者提供「優先等級」及「聯絡電話」
            StructuredElicitResult<TicketContactInfo> elicitResult = ctx.elicit( // ② — 暫停執行，把問題送給 client，等使用者回答
                    spec -> spec.message("在開立服務工單之前，請選擇優先等級（LOW、MEDIUM、HIGH 或 URGENT），並提供聯絡電話，以便我們的團隊與您聯繫。"),
                    TicketContactInfo.class); // ③ — Spring AI 根據這個 record 自動產生問卷 schema 給 client

            log.info("Elicitation 已完成，使用者操作：{}", elicitResult.action());
            info(ctx, "Elicitation 已完成，使用者操作：" + elicitResult.action());

            // 1.2 根據使用者回應設定優先等級及聯絡電話
            if (elicitResult.action() == McpSchema.ElicitResult.Action.ACCEPT
                    && elicitResult.structuredContent() != null) {

                // 使用者提供了額外資訊
                TicketContactInfo info = elicitResult.structuredContent();

                // 使用者填了資料 → 覆蓋預設值
                if (info.priority() != null && !info.priority().isBlank()) {
                    priority = info.priority();
                }
                if (info.contactPhone() != null && !info.contactPhone().isBlank()) {
                    contactPhone = info.contactPhone();
                }

                log.info("感謝！將使用優先等級「{}」及聯絡電話「{}」開立工單。", priority, contactPhone);
                info(ctx, "感謝！將使用優先等級「" + priority + "」及聯絡電話「" + contactPhone + "」開立工單。");

            } else {
                // 使用者選擇拒絕或取消，以預設值繼續。
                log.info("未提供額外資訊，將以預設優先等級「{}」開立工單。", DEFAULT_PRIORITY);
                info(ctx, "未提供額外資訊，將以預設優先等級「" + DEFAULT_PRIORITY + "」開立工單。");
            }
        } else {
            log.warn("已連線的 MCP client 不支援 elicitation，將使用預設工單細節。");
            info(ctx, "已連線的 MCP client 不支援 elicitation，將使用預設工單細節。");
        }

        // 3. 呼叫 Service 層建立「服務工單」
        HelpDeskTicketEntity savedTicket = service.createHelpDeskTicket(payload, priority, contactPhone);

        log.info("成功建立「服務工單」 id#: {}, userName: {}", savedTicket.getId(), savedTicket.getUsername());
        info(ctx, "已為使用者「" + savedTicket.getUsername() + "」建立服務工單，工單編號 #" + savedTicket.getId());

        // 4. 回傳建立「服務工單」的結果；returnDirect=true：模型會直接回傳此字串給使用者，不再追加其他回答
        return String.format("""
                        工單建立成功！
                        - 工單編號：#%d
                        - 使用者：%s
                        - 問題描述：%s
                        - 狀態：%s
                        - 優先等級：%s
                        - 聯絡電話：%s
                        - 建立時間：%s
                        - 預計處理時間：%s
                        """,
                savedTicket.getId(),
                savedTicket.getUsername(),
                savedTicket.getIssue(),
                savedTicket.getStatus(),
                priority,
                contactPhone,
                savedTicket.getCreatedAt(),
                savedTicket.getEta());
    }


    /**
     * 查詢指定使用者的所有服務工單，透過 progress 事件模擬回報查詢進度，完成後回傳工單清單。
     */
    @McpTool(name = "getTicketStatus", description = "取得所有「服務工單」並提供工單相關細節，包括工單編號、問題描述、狀態、建立時間及預計完成時間")
    List<HelpDeskTicketEntity> getTicketStatus(@McpToolParam(description = "用來查詢服務工單狀態的使用者名稱") String username, McpSyncRequestContext ctx) throws InterruptedException {

        log.info("正在為使用者「{}」查詢服務工單", username);
        info(ctx, "正在查詢使用者「" + username + "」的服務工單");

        // 1. 查詢該使用者所有「服務工單」並回傳；模型可用此結果回答進度
        List<HelpDeskTicketEntity> tickets = service.getHelpDeskTicketsByUser(username);

        log.info("已完成使用者「{}」的服務工單查詢，共 {} 張", username, tickets.size());
        info(ctx, "已完成使用者「" + username + "」的服務工單查詢，共 " + tickets.size() + " 張");

        // 模擬一段耗時流程，並每秒向 MCP client 發送一次查詢進度訊息 (這是一個模擬的耗時流程，實際應用中可能需要根據具体情况調整)
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000); // 每次先停 1 秒
            int percent = (i + 1) * 100 / 10; // 計算目前百分比
            // 呼叫 ctx.progress(...) 發送一個結構化進度事件，向 MCP client 送出 ProgressNotification；
            // 若 client 支援，可用來顯示 progress bar 或任務進度。
            ctx.progress(spec -> spec.progress(percent)
                    .message("正在查詢使用者「" + username + "」的服務工單 - 已完成 " + percent + "%"));
        }

        return tickets;
        // throw new RuntimeException("系統發生錯誤-請聯繫人工客服"); // 用來測試 Tool calling 發生錯誤情境
    }
    
    /**
     * 在開立服務工單前，先透過 MCP Sampling 借用 Client LLM 對使用者問題進行自助排障分析。
     * Server 提供問題描述與歷史已解決工單作為知識庫，Client LLM 推理可能原因、排查步驟，
     * 並建議是否需要開立工單。若 client 不支援 sampling，則引導使用者直接呼叫 createTicket。
     */
    @McpTool(name = "troubleshootIssue", description = "在開立服務工單之前，先透過 AI 分析問題並提供自助排障建議，包含可能原因、排查步驟與是否建議開立工單")
    String troubleshootIssue(
            @McpToolParam(description = "遇到的問題描述") String issue,
            @McpToolParam(description = "使用者名稱") String username,
            McpSyncRequestContext ctx) {

        log.info("正在為使用者「{}」分析問題：「{}」", username, issue);
        info(ctx, "正在為使用者「" + username + "」分析問題：「" + issue + "」");

        // 1. 取得歷史已解決工單作為知識庫（只納入有填寫 resolution 的工單）
        List<HelpDeskTicketEntity> resolvedTickets = service.getResolvedTickets();

        // 2. 若 client 不支援 sampling，引導使用者直接開工單
        if (!ctx.sampleEnabled()) {
            log.warn("已連線的 MCP client 不支援 sampling，無法執行 AI 排障。");
            info(ctx, "已連線的 MCP client 不支援 sampling，無法執行 AI 排障。");
            return "很抱歉，目前 AI 自助排障功能無法使用。直接使用「建立服務工單」功能";
        }

        // 3. 格式化歷史解決案例（僅列有 resolution 的工單，確保知識庫有實質內容）
        String rawKnowledgeBase = resolvedTickets.stream()
                .filter(t -> t.getResolution() != null && !t.getResolution().isBlank())
                .map(t -> "問題：" + t.getIssue() + " | 解決方式：" + t.getResolution())
                .collect(Collectors.joining("\n"));

        String knowledgeBase = rawKnowledgeBase.isBlank() ? "（目前無歷史解決案例）" : rawKnowledgeBase;

        String systemPrompt = """
                你是一位專業且親切的 IT 客服排障助理。
                請「僅」根據下方提供的歷史解決案例來回應使用者的問題，並依序提供：
                1. 可能的問題原因（1-3 點）
                2. 建議的自助排查步驟（條列式）

                重要規則：
                - 若歷史解決案例中有相關資訊，請優先引導使用者依照歷史解決方案自行排除；若按步驟操作後仍無法解決，再建議開立服務工單。
                - 若歷史解決案例中找不到與使用者問題相關的資訊，請直接回覆：「目前歷史紀錄中無相關解決案例，建議您開立服務工單，由專人為您處理。」
                - 禁止提供歷史案例以外的通用建議或自行推測解法。
                """;

        log.info("開始透過 ctx.sample() 為使用者「{}」進行 AI 排障分析", username);
        info(ctx, "開始透過 ctx.sample() 為使用者「" + username + "」進行 AI 排障分析");

        // 4. 請 Client LLM 推理排障建議，message 同時提供問題描述與歷史知識庫
        McpSchema.CreateMessageResult result = ctx.sample(spec -> spec
                .systemPrompt(systemPrompt)
                .message("使用者「" + username + "」遇到的問題：" + issue
                        + "\n\n歷史解決案例參考：\n" + knowledgeBase));

        String advice = ((McpSchema.TextContent) result.content()).text();

        log.info("AI 排障分析完成，client 使用模型：{}", result.model());
        info(ctx, "AI 排障分析完成，client 使用模型：" + result.model());

        return advice;
    }

    // Helper 方法：向 MCP client 發送 info 等級的 log
    private void info(McpSyncRequestContext ctx, String message) {
        ctx.log(spec -> spec
                .level(McpSchema.LoggingLevel.INFO)
                .logger(MCP_LOGGER)
                .message(message));
    }
}
