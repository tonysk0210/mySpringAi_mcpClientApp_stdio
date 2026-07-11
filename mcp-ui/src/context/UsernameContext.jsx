import { createContext, useContext, useState } from 'react'

const UsernameContext = createContext(null)

export function UsernameProvider({ children }) {
  const [username, setUsernameState] = useState(
    () => localStorage.getItem('mcp-username') ?? ''
  )

  function setUsername(name) {
    localStorage.setItem('mcp-username', name)
    setUsernameState(name)
  }

  return (
    <UsernameContext.Provider value={{ username, setUsername }}>
      {children}
    </UsernameContext.Provider>
  )
}

export function useUsername() {
  return useContext(UsernameContext)
}
