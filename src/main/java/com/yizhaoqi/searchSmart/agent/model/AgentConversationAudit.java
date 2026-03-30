package com.yizhaoqi.searchSmart.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agent_conversation_audit", indexes = {
        @Index(name = "idx_agent_conversation_id", columnList = "conversationId"),
        @Index(name = "idx_agent_user_id", columnList = "userId"),
        @Index(name = "idx_agent_created_at", columnList = "createdAt")
})
public class AgentConversationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String conversationId;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String finalAnswer;

    @Column(columnDefinition = "TEXT")
    private String toolSummaryJson;

    @Column(columnDefinition = "TEXT")
    private String retrievedResultsJson;

    @Column(columnDefinition = "TEXT")
    private String citationJson;

    @Column(nullable = false, length = 64)
    private String modelProfile;

    @Column(nullable = false)
    private Integer iterationCount;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
