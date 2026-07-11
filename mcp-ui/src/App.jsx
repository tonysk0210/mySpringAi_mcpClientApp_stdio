import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import Navbar from './components/Navbar'
import { UsernameProvider } from './context/UsernameContext'
import HelpdeskPage from './pages/HelpdeskPage'
import McpChatPage from './pages/McpChatPage'
import './App.css'

export default function App() {
  return (
    <UsernameProvider>
      <BrowserRouter>
        <Navbar />
        <main className="main-content">
          <Routes>
            <Route path="/" element={<Navigate to="/helpdesk" replace />} />
            <Route path="/helpdesk" element={<HelpdeskPage />} />
            <Route path="/mcp-chat" element={<McpChatPage />} />
          </Routes>
        </main>
      </BrowserRouter>
    </UsernameProvider>
  )
}
