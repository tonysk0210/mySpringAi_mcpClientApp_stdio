import { useState } from "react";
import ChatBox from "../components/ChatBox";
import { useUsername } from "../context/UsernameContext";
import client from "../api/client";

export default function FileSystemChatPage() {
  const { username } = useUsername();
  const [messages, setMessages] = useState(() => [
    {
      role: "assistant",
      content:
        "嗨！我是 FileSystem MCP 工具助理 👋 \n\n" +
        "基於測試與安全考量，目前我只支援存取桌面上的 mymcp 資料夾，可協助您列出目錄、讀取檔案、搜尋檔案或寫入檔案；目前不提供「刪除」功能。\n\n" +
        "請直接輸入要在 桌面 mymcp 資料夾內執行的檔案操作。",
    },
  ]);
  const [isLoading, setIsLoading] = useState(false);

  async function handleSend(text) {
    if (!username) return;
    setMessages((prev) => [...prev, { role: "user", content: text }]);
    setIsLoading(true);

    try {
      const { data } = await client.post(
        "/filesystem/chat",
        { message: text },
        { headers: { username } },
      );
      const reply = (data ?? "").toString().trim();
      console.log("[FileSystem Chat] reply:", reply);
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
      console.error("[FileSystem Chat] error:", e);
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
        <h2>FileSystem MCP Server - stdio</h2>
        <p>透過 AI 操作本機 FileSystem 工具。</p>
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
        placeholder="輸入指令，例如：列出桌面 mymcp 資料夾下的檔案..."
      />
    </div>
  );
}
