package com.example.ai.model;

import com.example.ai.core.EnvFileLoader;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 模型配置示例模块独立启动入口（仅 IDE 运行 main()；打包启动走 ai-starter）
 * <p>
 * 提供「供应商 / 模型配置管理」接口（/api/model/*，前端「系统管理 > 模型配置」页），
 * 以及按配置解析 ChatModel 的能力（实现 llm-core 的 ChatModelResolver），
 * 供 agent-example 的流式对话页切换模型。MySQL 表为首次使用时懒建。
 */
@SpringBootApplication
@MapperScan("com.example.ai.model.mapper")
public class ModelExampleApplication {

    public static void main(String[] args) {
        EnvFileLoader.load();
        SpringApplication.run(ModelExampleApplication.class, args);
    }
}
