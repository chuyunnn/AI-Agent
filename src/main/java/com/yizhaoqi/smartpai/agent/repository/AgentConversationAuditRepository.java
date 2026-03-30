package com.yizhaoqi.smartpai.agent.repository;

import com.yizhaoqi.smartpai.agent.model.AgentConversationAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentConversationAuditRepository extends JpaRepository<AgentConversationAudit, Long> {
}
