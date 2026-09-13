package com.example.ai.agent.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.simple.SimpleInterceptor;
import org.noear.solon.ai.agent.simple.SimpleTrace;
import org.noear.solon.ai.chat.message.ToolMessage;

/**
 * @author floyd
 */
@Slf4j
public class SimpleLoggingAgentInterceptor implements SimpleInterceptor {

    @Override
    public void onSimpleEnd(SimpleTrace trace) {
        long count = trace.getSession().getMessages().stream()
                .filter(x -> x instanceof ToolMessage)
                .count();
        log.info("进行{}次工具调用", count);
    }

    @Override
    public void onSimpleStart(SimpleTrace trace) {

    }
}
