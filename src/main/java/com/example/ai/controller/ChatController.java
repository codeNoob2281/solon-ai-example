package com.example.ai.controller;

import com.example.ai.dto.OpenAiRequest;
import com.example.ai.service.ChatService;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 对话接口
 * POST /chat/sendMessage
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
}