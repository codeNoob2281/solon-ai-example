package com.example.ai.core;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 在 Spring Boot 启动前读取项目根目录 .env 文件，将键值对注入 System.properties。
 * application.yml / @ConfigurationProperties 中的 ${AI_API_URL:default} 等占位符会从系统属性解析。
 */
@Slf4j
@UtilityClass
public class EnvFileLoader {

    private static final String FILE_NAME = ".env";

    /** .env 文件中的 KEY → 系统属性名 */
    private static final Map<String, String> KEY_TO_SYSTEM = new HashMap<>();

    static {
        KEY_TO_SYSTEM.put("AI_API_URL", "AI_API_URL");
        KEY_TO_SYSTEM.put("AI_API_KEY", "AI_API_KEY");
        KEY_TO_SYSTEM.put("AI_MODEL", "AI_MODEL");
        KEY_TO_SYSTEM.put("SERVER_PORT", "SERVER_PORT");
    }

    /**
     * 在 SpringApplication.run() 之前调用
     */
    public void load() {
        File envFile = findEnvFile();
        if (envFile == null || !envFile.isFile()) {
            log.debug(".env file not found, skip loading");
            return;
        }

        try {
            for (String line : Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8)) {
                line = stripComment(line).trim();
                if (line.isEmpty()) {
                    continue;
                }
                int eqIdx = line.indexOf('=');
                if (eqIdx <= 0) {
                    continue;
                }
                String key = line.substring(0, eqIdx).trim();
                String value = stripQuotes(line.substring(eqIdx + 1).trim());

                String systemKey = KEY_TO_SYSTEM.get(key);
                if (systemKey != null) {
                    System.setProperty(systemKey, value);
                    log.debug("Loaded .env: {}={}", systemKey, systemKey.equals("AI_API_KEY") ? "***" : value);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read .env file: " + envFile.getAbsolutePath(), e);
        }
    }

    private File findEnvFile() {
        // 1. 项目根目录（user.dir）
        File candidate = new File(System.getProperty("user.dir"), FILE_NAME);
        if (candidate.isFile()) {
            return candidate;
        }
        // 2. classpath 根目录
        candidate = new File(System.getProperty("user.dir"), "src/main/resources/" + FILE_NAME);
        if (candidate.isFile()) {
            return candidate;
        }
        // 3. 相对路径（Maven 运行时）
        candidate = new File(FILE_NAME);
        if (candidate.isFile()) {
            return candidate.getAbsoluteFile();
        }
        return null;
    }

    private String stripComment(String line) {
        int idx = line.indexOf('#');
        return idx < 0 ? line : line.substring(0, idx);
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}