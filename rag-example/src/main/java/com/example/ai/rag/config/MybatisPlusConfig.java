package com.example.ai.rag.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置
 * <p>
 * 分页插件：{@code KbDocumentMapper.selectPage} 生效的必要条件——未注册时
 * 分页参数被忽略（返回全量结果）。工程此前未配置任何 MP 插件，知识库管理接口
 * 的文档分页列表依赖本 Bean；位于 com.example.ai.rag 包扫描范围内，
 * 合并启动（ai-starter）经 RagExampleAutoConfiguration 自动装配。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
