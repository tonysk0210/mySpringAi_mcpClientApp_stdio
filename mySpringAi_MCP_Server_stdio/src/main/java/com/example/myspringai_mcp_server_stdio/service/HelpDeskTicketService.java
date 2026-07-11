package com.example.myspringai_mcp_server_stdio.service;

import com.example.myspringai_mcp_server_stdio.entity.HelpDeskTicketEntity;
import com.example.myspringai_mcp_server_stdio.payload.HelpDeskTicketPayload;
import com.example.myspringai_mcp_server_stdio.repo.HelpDeskTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HelpDeskTicketService {

    private final HelpDeskTicketRepository helpDeskTicketRepository;

    /**
     * 建立並儲存一筆 HelpDeskTicket 狀態為 OPEN。
     */
    public HelpDeskTicketEntity createHelpDeskTicket(HelpDeskTicketPayload payload, String priority, String contactPhone) {
        // 1. 將 tool 層收集到的資料轉成可持久化的 Entity，並補上預設狀態與時間欄位。
        HelpDeskTicketEntity ticket = HelpDeskTicketEntity.builder()
                .issue(payload.issue())
                .username(payload.username())
                .status("OPEN")
                .priority(priority)
                .contactPhone(contactPhone)
                .createdAt(LocalDateTime.now())
                .eta(LocalDateTime.now().plusDays(7))
                .build();
        // 2. 寫入資料庫後回傳已包含 id 等持久化結果的 Entity。
        return helpDeskTicketRepository.save(ticket);
    }

    public List<HelpDeskTicketEntity> getHelpDeskTicketsByUser(String username) {
        return helpDeskTicketRepository.findByUsername(username);
    }

    public List<HelpDeskTicketEntity> getResolvedTickets() {
        return helpDeskTicketRepository.findByStatus("CLOSED");
    }
}
