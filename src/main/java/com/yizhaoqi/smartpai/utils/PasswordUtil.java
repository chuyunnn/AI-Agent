package com.yizhaoqi.smartpai.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

// 使用Spring Security 内置的 BCrypt 加密算法
public class PasswordUtil {
    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 加密密码
     *
     * @param rawPassword 明文密码
     * @return 加密后的密码
     */
    public static String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 验证密码是否匹配
     *
     * @param rawPassword     明文密码
     * @param encodedPassword 加密后的密码
     * @return 是否匹配
     */
    public static boolean matches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }
}
