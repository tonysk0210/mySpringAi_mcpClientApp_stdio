import { useState } from "react";
import ChatBox from "../components/ChatBox";
import { useUsername } from "../context/UsernameContext";
import client from "../api/client";

export default function GithubChatPage() {
  const { username } = useUsername();
  const [messages, setMessages] = useState(() => [
    {
      role: "assistant",
      content:
        "嗨！我是 GitHub MCP 工具助理 👋 \n\n" +
        "基於測試與安全考量，目前我只支援操作 GitHub 遠端 repository：https://github.com/tonysk0210/mymcp，可協助您查詢檔案、建立 branch、管理 issue 或 pull request。\n\n" +
        "我不能直接讀取您的桌面資料夾，也不能執行本機 git commit / push。",
    },
  ]);
  const [isLoading, setIsLoading] = useState(false);

  async function handleSend(text) {
    if (!username) return;
    setMessages((prev) => [...prev, { role: "user", content: text }]);
    setIsLoading(true);

    try {
      const { data } = await client.post(
        "/github/chat",
        { message: text },
        { headers: { username } },
      );
      const reply = (data ?? "").toString().trim();
      console.log("[GitHub Chat] reply:", reply);
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
      console.error("[GitHub Chat] error:", e);
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
        <h2>GitHub MCP Server - stdio</h2>
        <p>透過 AI 操作 GitHub 遠端 repository 工具。</p>
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
        placeholder="輸入指令，例如：查看 我的 repo 的檔案..."
      />
    </div>
  );
}
