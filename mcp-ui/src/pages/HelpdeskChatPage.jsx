import { useEffect, useRef, useState } from "react";
import ChatBox from "../components/ChatBox";
import { useUsername } from "../context/UsernameContext";
import client from "../api/client";

/**
 * IT Helpdesk 聊天頁，負責三件事：
 * 1. 保持一條 SSE 長連線，等後端通知「需要補充資料」
 * 2. 把使用者訊息送給後端 AI，把回覆顯示在聊天框
 * 3. 管理 elicitation（補充資料）的特殊流程
 */

export default function HelpdeskChatPage() {
  const { username } = useUsername(); // 從 Context 取得目前的使用者名稱
  const [messages, setMessages] = useState(() => [
    {
      role: "assistant",
      content:
        "嗨！我是 IT Helpdesk 智能助理 👋\n\n" +
        "無論是電腦當機、網路異常，還是軟體錯誤，都可以直接告訴我，我會：\n" +
        "• 先協助您排查可能原因\n" +
        "• 查詢您手上有沒有進行中的工單\n" +
        "• 如果問題比較複雜，替您建立新工單追蹤\n\n" +
        "途中我可能會請您補充一些資訊(例如問題緊急程度)，請放心告訴我，隨時開始吧！",
    },
  ]); // 所有對話紀錄
  const [isLoading, setIsLoading] = useState(false); // true = AI 正在思考，聊天框鎖住
  const [isEliciting, setIsEliciting] = useState(false); // true = 後端要求補充資料，輸入框解鎖
  const [elicitationSessionId, setElicitationSessionId] = useState(null); // 目前等待回覆的 elicitation session
  // "idle" 未有 username 或尚未嘗試連線；"connecting" 建立中；"connected" 已 open；"disconnected" 中斷等待重連
  const [sseStatus, setSseStatus] = useState("idle");
  const esRef = useRef(null); // 保存 SSE 連線
  const seenElicitationSessionsRef = useRef(new Set()); // 記錄哪些 sessionId 已處理過

  // ### 1. SSE 長連線 (username 改變時重新建立連線，後端因此知道這條 SSE connection 屬於哪個使用者。)
  useEffect(() => {
    // ### 2. 沒有 username 就不連線
    if (!username) {
      setSseStatus("idle");
      return undefined;
    }

    // ### 3. 標記 component 還存在
    let active = true;
    // ### 4. 保存重連 timer
    let retryTimer = null;
    // ### 5. 清除舊 session 紀錄
    seenElicitationSessionsRef.current.clear(); // 清空已處理的 elicitation session：通常發生在 username 改變時，避免新使用者沿用舊使用者的紀錄

    // ### 6. 宣告建立連線的 function
    function connect() {
      // ### 7. 避免重複連線
      if (!active || esRef.current) return;

      // 進入連線流程，同步狀態給 UI 指示燈
      setSseStatus("connecting");

      // ### 8. 建立 SSE 連線，與後端保持連線，前端提供 username 作為識別，後端用來區分不同使用者的連線
      const es = new EventSource(
        `/api/helpdesk/elicitation/stream?username=${encodeURIComponent(username)}`,
      );

      // ### 9. 保存 SSE connection
      esRef.current = es;

      // ### 10. 連線開啟時 console 輸出
      es.onopen = () => {
        console.log(`[SSE] connected username=${username}`);
        setSseStatus("connected");
      };

      // ### 11. 監聽後端推送的 elicitation 事件
      es.addEventListener("elicitation", (e) => {
        // ### 12. Component 已經 unmount 時，不再處理舊連線傳回的事件
        if (!active) return;

        try {
          // ### 13. 將後端推送的 JSON 字串轉成物件，取出 sessionId 與 prompt
          const { sessionId, prompt } = JSON.parse(e.data);

          // ### 14. 沒有 sessionId 就視為無效的 elicitation 資料
          if (!sessionId) throw new Error("SSE elicitation 缺少 sessionId");

          // ### 15. 記住目前 sessionId，並讓畫面進入「等待使用者補充資料」狀態
          setElicitationSessionId(sessionId ?? null);
          setIsEliciting(true);

          // ### 16. 同一個 sessionId 若因重連被重新推送，不重複顯示 prompt
          if (seenElicitationSessionsRef.current.has(sessionId)) return;
          seenElicitationSessionsRef.current.add(sessionId);

          // ### 17. 把後端要求補充資料的 prompt 加入聊天紀錄
          setMessages((prev) => [
            ...prev,
            { role: "assistant", content: "⚠️ " + prompt, variant: "warning" },
          ]);
        } catch {
          // ### 18. 推送資料無法解析時，清除 sessionId，並顯示後端原始內容
          setElicitationSessionId(null);
          setMessages((prev) => [
            ...prev,
            { role: "assistant", content: "⚠️ " + e.data, variant: "warning" },
          ]);
        }
      });

      // ### 19. SSE 中斷時，處理錯誤並安排自動重連
      es.onerror = () => {
        // ### 20. Component 已卸載，或這條已經不是目前連線時，忽略這次 callback
        if (!active || esRef.current !== es) return;

        console.warn("[SSE] 連線中斷，2 秒後重連...");
        setSseStatus("disconnected");

        // ### 21. 關閉中斷的 EventSource，並清除 ref，讓 connect() 可以建立新連線
        es.close();
        esRef.current = null;

        // ### 22. 只建立一個重連計時器，2 秒後再呼叫 connect()
        if (retryTimer === null) {
          retryTimer = setTimeout(() => {
            retryTimer = null;
            connect();
          }, 2000);
        }
      };
    }

    // ### 23. useEffect 執行後，立即建立第一條 SSE 連線
    connect();

    // ### 24. Username 改變或 component unmount 時，清除舊計時器與 SSE 連線
    return () => {
      active = false;
      clearTimeout(retryTimer);
      esRef.current?.close();
      esRef.current = null;
      setSseStatus("idle");
    };
  }, [username]); // ### 25. Username 改變時，先執行 cleanup，再重新執行這個 useEffect

  // b. 一般聊天共用的 POST 輔助函式，回傳 reply 字串
  async function postChat(message, sessionId = null) {
    const { data } = await client.post(
      "/helpdesk/chat",
      { message, sessionId },
      { headers: { username } },
    );
    return (data ?? "").toString().trim();
  }

  // c. 錯誤處理函式
  function errorMessage(e) {
    if (e.response) {
      return `❌ 伺服器錯誤 (${e.response.status})：${e.response.data || "無詳細資訊"}`;
    }
    return "❌ 連線錯誤，請確認後端服務是否啟動。";
  }

  // d. handleCancel：取消目前等待中的補充資料請求
  async function handleCancel() {
    // ### handleCancel 1. 目前不是 elicitation 狀態時，不執行取消
    if (!isEliciting) return;

    // ### handleCancel 2. 沒有 sessionId 就無法告訴後端要取消哪一個 elicitation
    if (!elicitationSessionId) {
      setMessages((prev) => [
        ...prev,
        { role: "assistant", content: "❌ 找不到可取消的補充資料請求。" },
      ]);
      return;
    }

    // ### handleCancel 3. 先保存本次要取消的 sessionId
    const sessionId = elicitationSessionId;

    // ### handleCancel 4. 離開補充資料狀態，避免使用者重複送出或取消
    setIsEliciting(false);

    // ### handleCancel 5. 將使用者的取消動作顯示在聊天紀錄
    setMessages((prev) => [
      ...prev,
      {
        role: "user",
        content: "**取消提供補充資料，優先等級使用預設值 MEDIUM**",
      },
    ]);

    try {
      // ### handleCancel 6. 呼叫後端取消 API；sessionId 在 URL，username 用來驗證 session 所屬者
      const { data } = await client.post(
        `/helpdesk/elicitation/${encodeURIComponent(sessionId)}/cancel`,
        null, // 取消 API 不需要 request body
        { headers: { username } },
      );

      // ### handleCancel 7. 將後端回應轉成去除前後空白的字串
      const reply = (data ?? "").toString().trim();

      // ### handleCancel 8. 取消成功後，清除前端保存的 elicitation sessionId
      setElicitationSessionId(null);

      console.log("[Helpdesk cancel] reply:", reply);

      // ### handleCancel 9. 將後端的取消結果加入聊天紀錄
      setMessages((prev) => [
        ...prev,
        {
          role: "assistant",
          content: reply || "（收到空回應）",
          variant: reply.startsWith("✅") ? "success" : undefined,
        },
      ]);
    } catch (e) {
      // ### handleCancel 10. 取消 API 發生錯誤時，先記錄到 console
      console.error("[Helpdesk cancel] error:", e);

      // ### handleCancel 11. 404 代表 session 已不存在；其他錯誤則恢復 elicitation 狀態讓使用者重試
      if (e.response?.status === 404) {
        setElicitationSessionId(null);
      } else {
        setIsEliciting(true);
      }

      // ### handleCancel 12. 將錯誤訊息加入聊天紀錄
      setMessages((prev) => [
        ...prev,
        { role: "assistant", content: errorMessage(e) },
      ]);
    }
  }

  // e. handleSend：處理一般聊天訊息與 elicitation 補充資料
  async function handleSend(text) {
    // ### handleSend 1. 沒有 username 時，不傳送訊息
    if (!username) return;

    // ### handleSend 2. 先將使用者輸入的文字加入聊天紀錄
    setMessages((prev) => [...prev, { role: "user", content: text }]);

    // ### handleSend 3. 正在 elicitation 狀態時，這次輸入視為補充資料的回覆
    if (isEliciting) {
      // ### handleSend 4. 沒有 sessionId 就無法將補充資料交給正確的 elicitation
      if (!elicitationSessionId) {
        setMessages((prev) => [
          ...prev,
          { role: "assistant", content: "❌ 找不到等待回覆的補充資料請求。" },
        ]);
        return;
      }

      // ### handleSend 5. 送出補充資料前，先離開 elicitation 狀態，避免重複送出
      setIsEliciting(false);

      try {
        // ### handleSend 6. 傳入 sessionId，讓後端將文字交給對應的 elicitation
        const reply = await postChat(text, elicitationSessionId); // reply 應為 "✅ 資料已收到，正在繼續處理，請稍候..."

        // ### handleSend 7. 後端已接收補充資料，清除前端保存的 sessionId
        setElicitationSessionId(null);

        console.log("[Helpdesk elicitation] reply:", reply);

        // ### handleSend 8. 將後端的回應加入聊天紀錄
        setMessages((prev) => [
          ...prev,
          {
            role: "assistant",
            content: reply || "（收到空回應）",
            variant: reply.startsWith("✅") ? "success" : undefined, // variant 用為 styling
          },
        ]);
      } catch (e) {
        // ### handleSend 9. 補充資料傳送失敗時，恢復 elicitation 狀態讓使用者重試
        console.error("[Helpdesk elicitation] error:", e);
        setIsEliciting(true);
        setMessages((prev) => [
          ...prev,
          { role: "assistant", content: errorMessage(e) },
        ]);
      }

      // ### handleSend 10. 這裡不關閉 isLoading，等原本等待 elicitation 的 POST 完成後才解鎖
    } else {
      // ### handleSend 11. 不是 elicitation 狀態時，執行一般聊天流程
      setIsLoading(true);

      try {
        // ### handleSend 12. 一般聊天不需要 sessionId，因此只傳入 text
        const reply = await postChat(text);

        console.log("[Helpdesk] reply:", reply);

        // ### handleSend 13. 將 AI 回應加入聊天紀錄；空回應時顯示備用訊息
        setMessages((prev) => [
          ...prev,
          {
            role: "assistant",
            content:
              reply ||
              "（伺服器回傳空回應，請確認 Spring AI 設定與 OpenAI API Key）",
          },
        ]);
      } catch (e) {
        // ### handleSend 14. 一般聊天傳送失敗時，將錯誤訊息加入聊天紀錄
        console.error("[Helpdesk] error:", e);
        setMessages((prev) => [
          ...prev,
          { role: "assistant", content: errorMessage(e) },
        ]);
      } finally {
        // ### handleSend 15. 不論一般聊天成功或失敗，最後都結束 loading 狀態
        setIsLoading(false);
      }
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div className="page-header-row">
          <h2>智能工單系統 MCP Server - stdio</h2>
          <SseStatusBadge status={sseStatus} />
        </div>
        <p>描述您遇到的技術問題，AI 將協助排障或建立工單。</p>
      </div>
      {!username && (
        <div className="page-warning">
          請先在右上角設定使用者名稱才能傳送訊息。
        </div>
      )}
      <ChatBox
        messages={messages}
        onSend={handleSend}
        onCancel={handleCancel}
        isLoading={isLoading}
        allowSendWhileLoading={isEliciting}
        disabled={!username || sseStatus !== "connected"}
        placeholder={
          isEliciting ? "請回覆上方補充資料的問題..." : "描述您的技術問題..."
        }
      />
    </div>
  );
}

// SSE 連線狀態指示燈 (dot + label + 簡短說明)，顯示於 page-header 右側
function SseStatusBadge({ status }) {
  const label = {
    idle: "未連線",
    connecting: "連線中...",
    connected: "已連線",
    disconnected: "連線中斷，重試中...",
  }[status];
  const hint = {
    idle: "設定使用者名稱後自動建立 SSE 即時通道",
    connecting: "正在建立 SSE 即時通道，請稍候...",
    connected: "SSE 即時通道已就緒，可傳送訊息",
    disconnected: "無法送出訊息，系統將自動重試 SSE 連線",
  }[status];
  return (
    <div className="sse-status-wrap">
      <span
        className={`sse-status sse-status--${status}`}
        title="AI Agent 需透過即時通道推送補充資料的追問，連線建立後才能開始對話。"
      >
        <span className="sse-status-dot" />
        {label}
      </span>
      <small className="sse-status-hint">{hint}</small>
    </div>
  );
}
