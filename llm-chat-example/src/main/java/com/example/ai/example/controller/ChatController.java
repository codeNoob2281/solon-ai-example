package com.example.ai.example.controller;

import com.example.ai.example.dto.ChatOptionsRequest;
import com.example.ai.example.dto.OpenAiRequest;
import com.example.ai.example.service.ChatService;
import org.noear.solon.ai.chat.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;

/**
 * AI 对话接口
 * POST /chat/sendMessage              非流式，返回完整文本
 * POST /chat/sendMessageStream        流式，返回 SSE 事件流
 * POST /chat/sendMessageWithOptions   带 ChatOptions 参数，非流式
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private ChatService chatService;

    @PostMapping("/sendMessage")
    public String sendMessage(@RequestBody OpenAiRequest request) {
        return chatService.chat(request.getMessage());
    }

    /**
     * 流式对话：基于 SseEmitter 推送增量文本
     * SSE 事件：
     *   - message: 每个增量文本块
     *   - done:    流结束
     *   - error:   调用出错
     */
    @PostMapping(value = "/sendMessageStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@RequestBody OpenAiRequest request) {
        SseEmitter emitter = new SseEmitter(0L);

        chatService.stream(request.getMessage())
            .subscribe(
                response -> sendMessage(emitter, response),
                error -> sendError(emitter, error),
                () -> complete(emitter)
            );

        return emitter;
    }

    /**
     * 带 ChatOptions 的非流式对话。
     * <p>
     * 请求体支持以下可选参数：
     * <ul>
     *   <li>contextLength - 上下文窗口大小（token 数）</li>
     *   <li>maxTokens - 最大输出 token 数</li>
     *   <li>temperature - 温度（0.0~2.0）</li>
     *   <li>topP - top_p 采样（0.0~1.0）</li>
     *   <li>reasoningEffort - 思考强度（low/medium/high/max）</li>
     *   <li>thinking - 是否开启思考模式（true/false）</li>
     * </ul>
     *
     * @see ChatOptionsRequest
     */
    @PostMapping("/sendMessageWithOptions")
    public String sendMessageWithOptions(@RequestBody ChatOptionsRequest request) {
        return chatService.chatWithOptions(request);
    }

    private void sendMessage(SseEmitter emitter, ChatResponse response) {
        try {
            if (response != null && response.getContent() != null) {
                emitter.send(SseEmitter.event().name("message").data(response.getContent()));
            }
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void sendError(SseEmitter emitter, Throwable error) {
        try {
            String msg = error.getMessage() == null ? "stream error" : error.getMessage();
            emitter.send(SseEmitter.event().name("error").data(msg));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void complete(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data(""));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}