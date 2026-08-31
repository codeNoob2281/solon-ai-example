package com.example.ai.example.controller;

import com.example.ai.example.dto.ChatOptionsRequest;
import com.example.ai.example.dto.CustomAgentRequest;
import com.example.ai.example.dto.OpenAiRequest;
import com.example.ai.example.service.ChatService;
import org.noear.solon.ai.chat.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * POST /chat/presetConversation      预设对话（系统提示词 + 历史消息）
 * POST /chat/promptTemplate           提示词模板（#{key} 变量替换）
 * POST /chat/imageUnderstanding       图像理解（文本 + 图片 URL）
 * POST /chat/audioUnderstanding       音频理解（文本 + 音频 URL，识别动物叫声）
 * POST /chat/videoUnderstanding       视频理解（文本 + 视频 URL，描述视频内容）
 * POST /chat/customAgent              自定义智能体（角色 + 系统指令 + 用户消息），非流式
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
     * - message: 每个增量文本块
     * - done:    流结束
     * - error:   调用出错
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

    /**
     * 预设对话示例：天气查询场景。
     * <p>
     * 系统提示词："你是一个天气查询助手"
     * 预设历史：user"今天杭州天气怎么样？" → assistant"杭州今天天气多云，气温22-28度，微风。"
     * 最终提问："下午要带伞吗？"
     * <p>
     * AI 基于完整上下文回答最终提问。
     */
    @PostMapping("/presetConversation")
    public String presetConversation() {
        return chatService.presetConversation();
    }

    /**
     * 提示词模板示例：讲笑话场景。
     * <p>
     * 系统提示词（硬编码）："你喜欢讲笑话，但是大家并不觉得好笑"
     * 模板（通过 ChatMessage.ofUserTmpl + SnEL）："给我讲一个关于#{subject}的笑话"
     * <p>
     * 用户只需传入主题，如：?subject=程序员
     */
    @PostMapping("/promptTemplate")
    public String promptTemplate(@RequestParam String subject) {
        return chatService.promptTemplate(subject);
    }

    /**
     * 图像理解示例：文本 + 图片 URL。
     * <p>
     * 硬编码："这是什么动物？" + ImageBlock.ofUrl("https://pic.nximg.cn/file/20210531/10621685_024234407129_2.jpg")
     */
    @PostMapping("/imageUnderstanding")
    public String imageUnderstanding() {
        return chatService.imageUnderstanding();
    }

    /**
     * 音频理解示例：识别音频中的动物叫声。
     * <p>
     * 硬编码："请识别这段音频中是什么动物的叫声？"
     * + AudioBlock.ofUrl("https://cdn.pixabay.com/download/audio/2026/01/24/audio_761a22043a.mp3?filename=dragon-studio-cute-cat-meow-472372.mp3")
     */
    @PostMapping("/audioUnderstanding")
    public String audioUnderstanding(@RequestParam(defaultValue = "dots3-note-prev") String modelName) {
        return chatService.audioUnderstanding(modelName);
    }

    /**
     * 视频理解示例：描述视频内容。
     * <p>
     * 硬编码："请描述这段视频的内容？"
     * + VideoBlock.ofUrl("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
     */
    @PostMapping("/videoUnderstanding")
    public String videoUnderstanding(@RequestParam(defaultValue = "dots3-note-prev") String modelName) {
        return chatService.videoUnderstanding(modelName);
    }

    /**
     * 自定义智能体对话（非流式）。
     * <p>
     * 模拟 agent 应用中的"自定义智能体"功能，请求体示例：
     * <pre>{@code
     * {
     *   "role": "财务数据分析师",
     *   "instruction": "你负责分析订单与退款数据。金额单位均为元。",
     *   "message": "请分析本周订单退款率",
     *   // 以下可选，不传则复用全局默认配置
     *   "apiUrl": "https://api.moark.com/v1/chat/completions",
     *   "apiKey": "sk-xxx",
     *   "model": "Qwen3-32B"
     * }
     * }</pre>
     * 对应 Solon AI 构建方式：
     * <pre>{@code
     * ChatModel agent = ChatModel.of(apiUrl)
     *         .apiKey(apiKey)
     *         .model(model)
     *         .role(role)
     *         .instruction(instruction)
     *         .build();
     * }</pre>
     */
    @PostMapping("/customAgent")
    public String customAgent(@RequestBody CustomAgentRequest request) {
        return chatService.chatAsCustomAgent(request);
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