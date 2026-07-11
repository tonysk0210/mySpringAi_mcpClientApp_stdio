package com.example.myspringai_mcp_server_stdio.config;

import com.example.myspringai_mcp_server_stdio.entity.HelpDeskTicketEntity;
import com.example.myspringai_mcp_server_stdio.repo.HelpDeskTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final HelpDeskTicketRepository repo;

    @Override
    public void run(String... args) {
        if (!repo.findByStatus("CLOSED").isEmpty()) {
            return; // 已有歷史解決案例，跳過 seed
        }

        LocalDateTime now = LocalDateTime.now();

        List<HelpDeskTicketEntity> seedTickets = List.of(
                HelpDeskTicketEntity.builder()
                        .username("Alice")
                        .issue("無法登入系統，輸入密碼後頁面一直轉圈")
                        .status("CLOSED")
                        .priority("HIGH")
                        .contactPhone("0912-345-678")
                        .createdAt(now.minusDays(10))
                        .eta(now.minusDays(8))
                        .resolution("清除瀏覽器快取與 Cookie 後重新登入即可解決。若問題持續，請確認是否啟用了瀏覽器擴充功能，部分廣告阻擋工具會干擾登入流程。")
                        .build(),
                HelpDeskTicketEntity.builder()
                        .username("Bob")
                        .issue("VPN 連線一直斷線，約每隔 10 分鐘就會中斷")
                        .status("CLOSED")
                        .priority("HIGH")
                        .contactPhone("0923-456-789")
                        .createdAt(now.minusDays(7))
                        .eta(now.minusDays(5))
                        .resolution("調整 VPN 用戶端的 Keep-Alive 間隔為 60 秒，並關閉 Windows 的「允許電腦關閉此裝置以節省電源」網路介面卡設定後，連線穩定。")
                        .build(),
                HelpDeskTicketEntity.builder()
                        .username("Carol")
                        .issue("印表機顯示離線，但實體電源正常且連接 USB")
                        .status("CLOSED")
                        .priority("MEDIUM")
                        .contactPhone("N/A")
                        .createdAt(now.minusDays(5))
                        .eta(now.minusDays(3))
                        .resolution("在「裝置和印表機」中刪除舊的印表機項目，重新新增印表機並重新安裝驅動程式。若系統顯示多個相同印表機，請確認選擇狀態為「就緒」的那一個設為預設。")
                        .build(),
                HelpDeskTicketEntity.builder()
                        .username("David")
                        .issue("Outlook 收不到新郵件，但用網頁版可以正常收信")
                        .status("CLOSED")
                        .priority("MEDIUM")
                        .contactPhone("0934-567-890")
                        .createdAt(now.minusDays(4))
                        .eta(now.minusDays(2))
                        .resolution("在 Outlook 帳戶設定中，將同步頻率從「手動」改為「每 15 分鐘」。同時確認 Outlook 未處於「離線工作」模式（傳送/接收索引標籤中）。")
                        .build(),
                HelpDeskTicketEntity.builder()
                        .username("Eve")
                        .issue("電腦開機後桌面只顯示黑畫面，滑鼠可以移動但看不到任何圖示")
                        .status("CLOSED")
                        .priority("URGENT")
                        .contactPhone("0945-678-901")
                        .createdAt(now.minusDays(3))
                        .eta(now.minusDays(1))
                        .resolution("使用 Ctrl+Alt+Del 開啟工作管理員，執行「新增工作」並輸入 explorer.exe 重新啟動桌面程序。若問題每次開機都復發，請在「工作排程器」中檢查是否有第三方程式修改了 Shell 登錄值。")
                        .build(),
                HelpDeskTicketEntity.builder()
                        .username("Frank")
                        .issue("登入系統時顯示「帳號已被鎖定」，無法繼續操作")
                        .status("CLOSED")
                        .priority("HIGH")
                        .contactPhone("0956-111-222")
                        .createdAt(now.minusDays(6))
                        .eta(now.minusDays(5))
                        .resolution("帳號因連續輸入錯誤密碼超過 5 次而自動鎖定。由管理員於後台解除鎖定後，引導使用者透過「忘記密碼」功能重設密碼，並提醒開啟密碼管理工具避免再次發生。")
                        .build(),
                HelpDeskTicketEntity.builder()
                        .username("Grace")
                        .issue("輸入正確帳號密碼後，系統顯示「驗證碼錯誤」導致無法登入")
                        .status("CLOSED")
                        .priority("MEDIUM")
                        .contactPhone("N/A")
                        .createdAt(now.minusDays(9))
                        .eta(now.minusDays(7))
                        .resolution("確認手機時間與網路時間同步（設定 → 一般 → 日期與時間 → 自動設定），時間偏差超過 30 秒會導致 TOTP 驗證碼失效。同步後重新產生驗證碼即可正常登入。")
                        .build(),
                HelpDeskTicketEntity.builder()
                        .username("Henry")
                        .issue("使用公司 SSO 登入時，跳轉後出現 500 錯誤頁面")
                        .status("CLOSED")
                        .priority("HIGH")
                        .contactPhone("0967-333-444")
                        .createdAt(now.minusDays(12))
                        .eta(now.minusDays(10))
                        .resolution("SSO Token 已過期且瀏覽器快取了舊的 Session Cookie。清除所有站點 Cookie 後重新從公司入口網站點選登入連結，不要直接輸入系統網址，讓 SSO 流程完整執行。")
                        .build(),
                HelpDeskTicketEntity.builder()
                        .username("Iris")
                        .issue("更換新電腦後無法登入內部系統，提示「裝置未受信任」")
                        .status("CLOSED")
                        .priority("MEDIUM")
                        .contactPhone("0978-555-666")
                        .createdAt(now.minusDays(8))
                        .eta(now.minusDays(6))
                        .resolution("內部系統啟用了裝置信任驗證（Device Trust）。IT 部門需將新電腦的裝置憑證加入白名單，並安裝公司根憑證（Root CA）。完成後重啟瀏覽器，使用公司帳號重新登入即可。")
                        .build()
        );

        repo.saveAll(seedTickets);
    }
}
