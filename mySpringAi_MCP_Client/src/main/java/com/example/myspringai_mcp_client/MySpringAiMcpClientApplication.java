package com.example.myspringai_mcp_client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MySpringAiMcpClientApplication {

    public static void main(String[] args) {

        // 分兩步啟動，以便在 run() 之前注入 Profile。
        // 若直接呼叫 SpringApplication.run()，則無法在啟動前動態設定 Profile。
        SpringApplication app = new SpringApplication(MySpringAiMcpClientApplication.class);

        // 根據作業系統自動啟用對應的 Spring Profile，
        // 使 Spring Boot 額外載入 application-{profile}.properties，
        // 讓 MCP server 指令、路徑等平台差異設定可以各自獨立管理，不必寫在共用設定檔裡。
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("windows")) {
            // 載入 application-windows.properties
            // 內含 Windows 專用的 MCP stdio server 啟動指令（cmd / npx / docker 路徑格式）
            app.setAdditionalProfiles("windows");
        } else if (os.contains("mac")) {
            // 載入 application-mac.properties
            // 內含 macOS 專用的 MCP stdio server 啟動指令（unix 路徑格式）
            app.setAdditionalProfiles("mac");
        }

        app.run(args);
    }
}
    /*
      application.properties 啟動順序
    │
    ├─ 1. application.properties 載入（共用設定）
    │
    ├─ 2. application-windows.properties 載入（覆蓋/補充共用設定）
    │      └─ 其中一行指向 JSON：
    │         spring.ai.mcp.client.stdio.servers-configuration=classpath:mcp-servers-windows.json
    */