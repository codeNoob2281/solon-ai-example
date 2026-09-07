package com.example.ai.example.controller;

import com.example.ai.example.dto.ChatMemoryRequest;
import com.example.ai.example.dto.ChatOptionsRequest;
import com.example.ai.example.dto.CustomAgentRequest;
import com.example.ai.example.dto.OpenAiRequest;
import com.example.ai.example.service.ChatMemoryService;
import com.example.ai.example.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
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
 * POST /chat/sendMessageStream        流式，返回 SSE 事件流（thinking 思考增量 + message 正文增量）
 * POST /chat/memory/send              带记忆的对话（FileChatSession 持久化，窗口大小 20）
 * POST /chat/sendMessageWithOptions   带 ChatOptions 参数，非流式
 * POST /chat/presetConversation      预设对话（系统提示词 + 历史消息）
 * POST /chat/promptTemplate           提示词模板（#{key} 变量替换）
 * POST /chat/imageUnderstanding       图像理解（文本 + 图片 URL）
 * POST /chat/audioUnderstanding       音频理解（文本 + 音频 URL，识别动物叫声）
 * POST /chat/videoUnderstanding       视频理解（文本 + 视频 URL，描述视频内容）
 * POST /chat/customAgent              自定义智能体（角色 + 系统指令 + 用户消息），非流式
 */
@Tag(name = "AI 对话", description = "基于 Solon AI ChatModel 的对话示例接口")
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private ChatService chatService;

    @Resource
    private ChatMemoryService chatMemoryService;

    @Operation(summary = "非流式对话", description = "传入用户消息，等待模型生成后一次性返回完整回复文本")
    @PostMapping("/sendMessage")
    public String sendMessage(@RequestBody OpenAiRequest request) {
        return chatService.chat(request.getMessage());
    }

    /**
     * 带记忆的对话（基于 FileChatSession，非流式）。
     * <p>
     * 传入 sessionId 和消息内容，同一 sessionId 会共享同一份对话记忆：
     * 历史消息作为上下文一起发给模型，AI 能"记住"之前的对话。
     * 持久化文件（temp/chat-memory/{sessionId}.messages.ndjson）保留完整消息历史，
     * 模型交互时只取最近 windowSize（默认 20）条作为上下文。
     * <p>
     * 请求体示例：
     * <pre>{@code
     * {
     *   "sessionId": "session-1",
     *   "message": "我叫小明，请记住我的名字"
     * }
     * }</pre>
     * 第二次用同一 sessionId 提问"我叫什么名字？"时，AI 能回忆起上文。
     */
    @Operation(summary = "带记忆的对话",
            description = "基于 FileChatSession 持久化，同一 sessionId 共享同一份对话记忆，"
                    + "模型交互时取最近 windowSize（默认 20）条作为上下文")
    @PostMapping("/memory/send")
    public String sendMemoryMessage(@RequestBody ChatMemoryRequest request) {
        return chatMemoryService.chatWithMemory(request.getSessionId(), request.getMessage());
    }

    /**
     * 流式对话：基于 SseEmitter 推送思考与正文增量。
     * <p>
     * 4.1 ChatEvent 模型：思考增量按 THINKING_DELTA、正文增量按 TEXT_DELTA 投影
     * （hasText 过滤空负载），完整终态从 RESPONSE_END.getResponse() 读取（此处仅做文本转发）。
     * SSE 事件：
     * - thinking: 每个思考增量块（需模型支持思考；请求体 thinking：true=开启，false=关闭，不传=模型默认）
     * - message:  每个正文增量块
     * - done:     流结束
     * - error:    调用出错（4.1 中 ERROR 事件与 onError 表示同一次失败，这里统一走 onError 通道）
     */
    @Operation(summary = "流式对话（SSE，含思考内容）",
            description = "基于 SseEmitter 推送思考与正文增量。SSE 事件：thinking=思考增量块，"
                    + "message=正文增量块，done=流结束，error=调用出错；"
                    + "请求体 thinking：true=开启思考，false=关闭思考，不传=模型默认（需模型支持思考）")
    @PostMapping(value = "/sendMessageStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@RequestBody OpenAiRequest request) {
        SseEmitter emitter = new SseEmitter(0L);

        chatService.stream(request.getMessage(), request.getThinking())
                .filter(event -> event.is(ChatEventType.TEXT_DELTA, ChatEventType.THINKING_DELTA) && event.hasText())
                .subscribe(
                        event -> {
                            if (event.is(ChatEventType.THINKING_DELTA)) {
                                sendThinking(emitter, event.getText());
                            } else {
                                sendMessage(emitter, event.getText());
                            }
                        },
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
    @Operation(summary = "带 ChatOptions 参数的对话",
            description = "支持 contextLength / maxTokens / temperature / topP / "
                    + "reasoningEffort / thinking 等可选参数，详见 ChatOptionsRequest")
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
    @Operation(summary = "预设对话示例（天气查询场景）",
            description = "硬编码系统提示词 + 预设历史消息（杭州天气），AI 基于完整上下文回答最终提问"
                    + "\"下午要带伞吗？\"，无需传参")
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
    @Operation(summary = "提示词模板示例（讲笑话场景）",
            description = "系统提示词硬编码，模板 \"给我讲一个关于#{subject}的笑话\" "
                    + "（ChatMessage.ofUserTmpl + SnEL 变量替换），subject 由查询参数传入")
    @PostMapping("/promptTemplate")
    public String promptTemplate(@Parameter(description = "笑话主题", example = "程序员")
                                 @RequestParam String subject) {
        return chatService.promptTemplate(subject);
    }

    /**
     * 图像理解示例：文本 + 图片 URL。
     * <p>
     * 硬编码："这是什么动物？" + ImageBlock.ofUrl("https://pic.nximg.cn/file/20210531/10621685_024234407129_2.jpg")
     */
    @Operation(summary = "图像理解示例",
            description = "硬编码提问\"这是什么动物？\"+ 图片 URL（ImageBlock.ofUrl），无需传参")
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
    @Operation(summary = "音频理解示例",
            description = "硬编码提问\"请识别这段音频中是什么动物的叫声？\"+ 音频 URL（AudioBlock.ofUrl），"
                    + "理解模型可通过 modelName 指定")
    @PostMapping("/audioUnderstanding")
    public String audioUnderstanding(@Parameter(description = "音频理解模型名称", example = "dots3-note-prev")
                                     @RequestParam(defaultValue = "dots3-note-prev") String modelName) {
        return chatService.audioUnderstanding(modelName);
    }

    /**
     * 视频理解示例：描述视频内容。
     * <p>
     * 硬编码："请描述这段视频的内容？"
     * + VideoBlock.ofUrl("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
     */
    @Operation(summary = "视频理解示例",
            description = "硬编码提问\"请描述这段视频的内容？\"+ 视频 URL（VideoBlock.ofUrl），"
                    + "理解模型可通过 modelName 指定")
    @PostMapping("/videoUnderstanding")
    public String videoUnderstanding(@Parameter(description = "视频理解模型名称", example = "dots3-note-prev")
                                     @RequestParam(defaultValue = "dots3-note-prev") String modelName) {
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
    @Operation(summary = "自定义智能体对话",
            description = "传入角色 + 系统指令 + 用户消息构建专属智能体（非流式），"
                    + "可选覆盖 apiUrl/apiKey/model，不传则复用全局默认配置")
    @PostMapping("/customAgent")
    public String customAgent(@RequestBody CustomAgentRequest request) {
        return chatService.chatAsCustomAgent(request);
    }

    private void sendThinking(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().name("thinking").data(text));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void sendMessage(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().name("message").data(text));
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
