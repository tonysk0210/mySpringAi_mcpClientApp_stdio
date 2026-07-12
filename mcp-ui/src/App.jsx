import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import Navbar from "./components/Navbar";
import { UsernameProvider } from "./context/UsernameContext";
import HelpdeskChatPage from "./pages/HelpdeskChatPage";
import FileSystemChatPage from "./pages/FileSystemChatPage";
import GithubChatPage from "./pages/GithubChatPage";
import "./App.css";

export default function App() {
  return (
    <UsernameProvider>
      <BrowserRouter>
        <Navbar />
        <main className="main-content">
          <Routes>
            <Route
              path="/"
              element={<Navigate to="/helpdesk-chat" replace />}
            />
            <Route path="/helpdesk-chat" element={<HelpdeskChatPage />} />
            <Route path="/filesystem-chat" element={<FileSystemChatPage />} />
            <Route path="/github-chat" element={<GithubChatPage />} />
          </Routes>
        </main>
      </BrowserRouter>
    </UsernameProvider>
  );
}
