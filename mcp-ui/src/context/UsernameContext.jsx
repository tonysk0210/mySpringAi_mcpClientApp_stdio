import { createContext, useContext, useState } from "react";

const UsernameContext = createContext(null); //  建立 Context 容器

// 提供 child component 來存取 username 和 setUsername 的函數
export function useUsername() {
  return useContext(UsernameContext);
}

export function UsernameProvider({ children }) {
  // 1. 初始狀態從 localStorage 讀取 (lazy initializer)
  const [username, setUsernameState] = useState(
    () => localStorage.getItem("mcp-username") ?? "",
  );

  // 2. 設定使用者名稱的函數
  function setUsername(name) {
    localStorage.setItem("mcp-username", name);
    setUsernameState(name);
  }

  return (
    // 3. 提供 username (目前的使用者名稱) 和 setUsername (設定使用者名稱的函數) 給子組件
    <UsernameContext.Provider value={{ username, setUsername }}>
      {children}
    </UsernameContext.Provider>
  );
}
