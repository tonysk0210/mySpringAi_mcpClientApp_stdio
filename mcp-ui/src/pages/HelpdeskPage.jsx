import { useEffect, useRef, useState } from "react";
import ChatBox from "../components/ChatBox";
import { useUsername } from "../context/UsernameContext";

export default function HelpdeskPage() {
  const { username } = useUsername();
  const [messages, setMessages] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isEliciting, setIsEliciting] = useState(false);
  const esRef = useRef(null);

  useEffect(() => {
    let active = true;
    let retryTimer = null;

    function connect() {
      const es = new EventSource("/api/helpdesk/elicitation/stream");
      esRef.current = es;

      es.onopen = () => console.log("[SSE] connected");

      es.addEventListener("elicitation", (e) => {
        if (!active) return;
        try {
          const { prompt } = JSON.parse(e.data);
          setMessages((prev) => [
            ...prev,
            { role: "assistant", content: "⚠️ " + prompt, variant: "warning" },
          ]);
        } catch {
          setMessages((prev) => [
            ...prev,
            { role: "assistant", content: "⚠️ " + e.data, variant: "warning" },
          ]);
        }
        setIsEliciting(true);
      });

      es.onerror = () => {
        if (!active) return;
        console.warn("[SSE] 連線中斷，2 秒後重連...");
        es.close();
        esRef.current = null;
        retryTimer = setTimeout(connect, 2000);
      };
    }

    connect();

    return () => {
      active = false;
      clearTimeout(retryTimer);
      esRef.current?.close();
      esRef.current = null;
    };
  }, []);

  async function handleCancel() {
    if (!isEliciting) return;
    setIsEliciting(false);
    setMessages((prev) => [
      ...prev,
      {
        role: "user",
        content: "（取消提供補充資料，優先等級使用預設值 MEDIUM）",
      },
    ]);
    try {
      const res = await fetch("/api/helpdesk/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json", username },
        body: JSON.stringify({
          message: "使用者取消，不提供補充資料，請以預設值建立工單。",
        }),
      });
      const rawText = await res.text();
      console.log("[Helpdesk cancel] status:", res.status, "| reply:", rawText);
      const reply = rawText.trim() || "（收到空回應）";
      setMessages((prev) => [
        ...prev,
        {
          role: "assistant",
          content: reply,
          variant: reply.startsWith("✅") ? "success" : undefined,
        },
      ]);
    } catch (e) {
      console.error("[Helpdesk cancel] fetch error:", e);
      setMessages((prev) => [
        ...prev,
        { role: "assistant", content: "❌ 取消時發生錯誤。" },
      ]);
    }
  }

  async function handleSend(text) {
    if (!username) return;
    setMessages((prev) => [...prev, { role: "user", content: text }]);

    if (isEliciting) {
      // elicitation 回應路徑：不動 isLoading（第一次 POST 仍在阻塞中）
      setIsEliciting(false);
      try {
        const res = await fetch("/api/helpdesk/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json", username },
          body: JSON.stringify({ message: text }),
        });
        const rawText = await res.text();
        console.log(
          "[Helpdesk elicitation] status:",
          res.status,
          "| reply:",
          rawText,
        );
        const reply = rawText.trim() || "（收到空回應）";
        setMessages((prev) => [
          ...prev,
          {
            role: "assistant",
            content: reply,
            variant: reply.startsWith("✅") ? "success" : undefined,
          },
        ]);
      } catch (e) {
        console.error("[Helpdesk elicitation] fetch error:", e);
        setMessages((prev) => [
          ...prev,
          { role: "assistant", content: "❌ 傳送補充資料時發生錯誤。" },
        ]);
      }
      // 不在此處 setIsLoading(false)，等第一次 POST 完成後才解鎖
    } else {
      // 正常聊天路徑
      setIsLoading(true);
      try {
        const res = await fetch("/api/helpdesk/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json", username },
          body: JSON.stringify({ message: text }),
        });
        const rawText = await res.text();
        console.log("[Helpdesk] status:", res.status, "| reply:", rawText);

        if (!res.ok) {
          setMessages((prev) => [
            ...prev,
            {
              role: "assistant",
              content: `❌ 伺服器錯誤 (${res.status})：${rawText || "無詳細資訊"}`,
            },
          ]);
          return;
        }

        const reply =
          rawText.trim() ||
          "（伺服器回傳空回應，請確認 Spring AI 設定與 OpenAI API Key）";
        setMessages((prev) => [...prev, { role: "assistant", content: reply }]);
      } catch (e) {
        console.error("[Helpdesk] fetch error:", e);
        setMessages((prev) => [
          ...prev,
          {
            role: "assistant",
            content: "❌ 連線錯誤，請確認後端服務是否啟動。",
          },
        ]);
      } finally {
        setIsLoading(false);
      }
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h2>IT Helpdesk</h2>
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
        disabled={!username}
        placeholder={
          isEliciting ? "請回覆上方補充資料的問題..." : "描述您的技術問題..."
        }
      />
    </div>
  );
}
