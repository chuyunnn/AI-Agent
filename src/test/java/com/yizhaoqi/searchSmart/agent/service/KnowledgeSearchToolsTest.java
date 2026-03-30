package com.yizhaoqi.searchSmart.agent.service;

import com.yizhaoqi.searchSmart.agent.dto.KnowledgeSearchToolRequest;
import com.yizhaoqi.searchSmart.agent.dto.KnowledgeSearchToolResponse;
import com.yizhaoqi.searchSmart.entity.SearchResult;
import com.yizhaoqi.searchSmart.service.HybridSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolsTest {

    @Mock
    private HybridSearchService hybridSearchService;

    private KnowledgeSearchTools knowledgeSearchTools;

    @BeforeEach
    void setUp() {
        knowledgeSearchTools = new KnowledgeSearchTools(hybridSearchService);
        AgentToolContextHolder.setUserId("alice");
    }

    @AfterEach
    void tearDown() {
        AgentToolContextHolder.clear();
    }

    @Test
    void shouldReturnAtMostTenHitsByDefault() {
        when(hybridSearchService.searchWithPermission(eq("RAG"), eq("alice"), eq(30)))
                .thenReturn(buildResults(12));

        KnowledgeSearchToolResponse response = knowledgeSearchTools.searchKnowledge(
                new KnowledgeSearchToolRequest("RAG", null, null, null));

        assertEquals(10, response.getHits().size());
        assertEquals(10, response.getTopK());
        assertEquals(1, response.getHits().get(0).getIndex());
        assertEquals(10, response.getHits().get(9).getIndex());
    }

    @Test
    void shouldApplyOptionalFileAndOrgFilters() {
        List<SearchResult> results = List.of(
                new SearchResult("file-a", 1, "alpha", 0.9, "u1", "TECH", true, "a.md"),
                new SearchResult("file-b", 2, "beta", 0.8, "u1", "HR", true, "b.md"),
                new SearchResult("file-a", 3, "gamma", 0.7, "u1", "TECH", false, "a.md")
        );
        when(hybridSearchService.searchWithPermission(eq("query"), eq("alice"), eq(30)))
                .thenReturn(results);

        KnowledgeSearchToolResponse response = knowledgeSearchTools.searchKnowledge(
                new KnowledgeSearchToolRequest("query", 10, "file-a", "TECH"));

        assertEquals(2, response.getHits().size());
        assertTrue(response.getHits().stream().allMatch(hit -> "file-a".equals(hit.getFileMd5())));
        assertTrue(response.getHits().stream().allMatch(hit -> "TECH".equals(hit.getOrgTag())));
    }

    private List<SearchResult> buildResults(int count) {
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            results.add(new SearchResult(
                    "file-" + i,
                    i + 1,
                    "content-" + i,
                    1.0 - (i * 0.01),
                    "u1",
                    "TECH",
                    true,
                    "file-" + i + ".md"));
        }
        return results;
    }
}
