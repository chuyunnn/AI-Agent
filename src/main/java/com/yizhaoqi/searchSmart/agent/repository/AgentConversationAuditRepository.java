package com.yizhaoqi.searchSmart.agent.repository;

import com.yizhaoqi.searchSmart.agent.model.AgentConversationAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentConversationAuditRepository extends JpaRepository<AgentConversationAudit, Long> {
}
