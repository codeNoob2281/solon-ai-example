package com.example.ai.core;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 在 Spring Boot 启动前读取项目根目录 .env 文件，将全部键值对注入 System.properties。
 * application.yml / @ConfigurationProperties 中的 ${KEY:default} 等占位符会从系统属性解析。
 * 无需维护键白名单：.env 中的每一个 KEY=VALUE 都会直接写入系统属性。
 */
@Slf4j
@UtilityClass
public class EnvFileLoader {

    private static final String FILE_NAME = ".env";

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
                if (key.isEmpty()) {
                    continue;
                }
                String value = stripQuotes(line.substring(eqIdx + 1).trim());

                System.setProperty(key, value);
                log.debug("Loaded .env: {}={}", key, isSensitive(key) ? "***" : value);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read .env file: " + envFile.getAbsolutePath(), e);
        }
    }

    /**
     * 敏感键（KEY/TOKEN/PASSWORD/SECRET）日志脱敏
     */
    private boolean isSensitive(String key) {
        String upper = key.toUpperCase();
        return upper.contains("KEY") || upper.contains("TOKEN")
                || upper.contains("PASSWORD") || upper.contains("SECRET");
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