package com.yizhaoqi.searchSmart.agent.service;

import com.yizhaoqi.searchSmart.agent.dto.KnowledgeSearchHit;
import com.yizhaoqi.searchSmart.agent.dto.KnowledgeSearchToolRequest;
import com.yizhaoqi.searchSmart.agent.dto.KnowledgeSearchToolResponse;
import com.yizhaoqi.searchSmart.entity.SearchResult;
import com.yizhaoqi.searchSmart.service.HybridSearchService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class KnowledgeSearchTools {

    public static final int DEFAULT_TOP_K = 10;
    private static final int MAX_SNIPPET_LENGTH = 400;

    private final HybridSearchService hybridSearchService;

    public KnowledgeSearchTools(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    @Tool(name = "searchKnowledge", description = "在当前用户有权限访问的知识库中检索最多10条相关片段，返回编号、文件名、片段、分数和引用信息。", returnDirect = false)
    public KnowledgeSearchToolResponse searchKnowledge(KnowledgeSearchToolRequest request) {
        String userId = AgentToolContextHolder.requireUserId();
        String query = request != null ? request.getQuery() : null;
        int topK = normalizeTopK(request != null ? request.getTopK() : null);

        if (!StringUtils.hasText(query)) {
            return new KnowledgeSearchToolResponse(query, topK, "查询内容为空，未执行检索", List.of());
        }

        int searchWindow = Math.max(topK * 3, DEFAULT_TOP_K);
        List<SearchResult> rawResults = hybridSearchService.searchWithPermission(query, userId, searchWindow);

        List<KnowledgeSearchHit> hits = new ArrayList<>();
        for (SearchResult result : rawResults) {
            if (!matchesOptionalFilters(result, request)) {
                continue;
            }
            hits.add(new KnowledgeSearchHit(
                    hits.size() + 1,
                    result.getFileName(),
                    result.getFileMd5(),
                    result.getChunkId(),
                    abbreviate(result.getTextContent()),
                    result.getScore(),
                    result.getOrgTag(),
                    result.getIsPublic()));
            if (hits.size() >= topK) {
                break;
            }
        }

        String message = hits.isEmpty()
                ? "未检索到相关知识片段"
                : "成功检索到 " + hits.size() + " 条相关知识片段";
        return new KnowledgeSearchToolResponse(query, topK, message, hits);
    }

    private boolean matchesOptionalFilters(SearchResult result, KnowledgeSearchToolRequest request) {
        if (request == null) {
            return true;
        }
        if (StringUtils.hasText(request.getFileMd5())
                && !request.getFileMd5().equalsIgnoreCase(result.getFileMd5())) {
            return false;
        }
        if (StringUtils.hasText(request.getOrgTag())) {
            String expectedOrg = request.getOrgTag().toLowerCase(Locale.ROOT);
            String actualOrg = result.getOrgTag() == null ? "" : result.getOrgTag().toLowerCase(Locale.ROOT);
            return expectedOrg.equals(actualOrg);
        }
        return true;
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, DEFAULT_TOP_K);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_SNIPPET_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_SNIPPET_LENGTH) + "…";
    }
}
