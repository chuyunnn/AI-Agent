package com.yizhaoqi.searchSmart.agent.service;

public final class AgentToolContextHolder {

    private static final ThreadLocal<String> USER_HOLDER = new ThreadLocal<>();

    private AgentToolContextHolder() {
    }

    public static void setUserId(String userId) {
        USER_HOLDER.set(userId);
    }

    public static String getUserId() {
        return USER_HOLDER.get();
    }

    public static String requireUserId() {
        String userId = USER_HOLDER.get();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("Agent tool context userId is missing");
        }
        return userId;
    }

    public static void clear() {
        USER_HOLDER.remove();
    }
}
