package com.example.myspringai_mcp_server_stdio.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "HELP_DESK_TICKETS", schema = "PUBLIC")
public class HelpDeskTicketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;

    private String issue;

    private String status; // e.g., OPEN, IN_PROGRESS, CLOSED

    private String priority; // e.g., LOW, MEDIUM, HIGH, URGENT

    private String contactPhone; // collected from the user via MCP elicitation

    private LocalDateTime createdAt;

    private LocalDateTime eta;

    @Column(length = 1000)
    private String resolution; // 工單解決方式，status 變為 CLOSED 時填入
}