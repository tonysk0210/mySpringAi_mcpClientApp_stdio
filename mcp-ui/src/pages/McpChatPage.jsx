import { useState } from "react";
import ChatBox from "../components/ChatBox";
import { useUsername } from "../context/UsernameContext";
import client from "../api/client";

export default function McpChatPage() {
  const { username } = useUsername();
  const [messages, setMessages] = useState(() => [
    {
      role: "assistant",
      content:
        "嗨！我是 MCP 工具操作助理 👋\n\n" +
        "只要用自然語言告訴我想做的事，我可以幫您：\n" +
        "• 操作檔案系統，例如列出、讀取指定目錄的檔案\n" +
        "• 操作 GitHub，例如查詢 repository、issue、commit 等\n\n" +
        "有任何想試試的動作，直接說出來就可以，我們開始吧！",
    },
  ]);
  const [isLoading, setIsLoading] = useState(false);

  async function handleSend(text) {
    if (!username) return;
    setMessages((prev) => [...prev, { role: "user", content: text }]);
    setIsLoading(true);

    try {
      const { data } = await client.post(
        "/chat",
        { message: text },
        { headers: { username } },
      );
      const reply = (data ?? "").toString().trim();
      console.log("[MCP Chat] reply:", reply);
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
      console.error("[MCP Chat] error:", e);
      const content = e.response
        ? `❌ 伺服器錯誤 (${e.response.status})：${e.response.data || "無詳細資訊"}`
        : "❌ 連線錯誤，請確認後端服務是否啟動。";
      setMessages((prev) => [...prev, { role: "assistant", content }]);
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h2>MCP Chat - stdio</h2>
        <p>透過 AI 操作 FileSystem 與 GitHub 工具。</p>
      </div>
      {!username && (
        <div className="page-warning">
          請先在右上角設定使用者名稱才能傳送訊息。
        </div>
      )}
      <ChatBox
        messages={messages}
        onSend={handleSend}
        isLoading={isLoading}
        disabled={!username}
        placeholder="輸入指令，例如：列出 /tmp 下的檔案..."
      />
    </div>
  );
}
