package com.boxcinema.customer.controller;

import com.boxcinema.customer.dto.request.ChatMessageRequest;
import com.boxcinema.customer.dto.response.ChatMessageResponse;
import com.boxcinema.customer.service.ChatbotService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatbotApiController {

    private final ChatbotService chatbotService;

    public ChatbotApiController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    @PostMapping("/message")
    public ResponseEntity<ChatMessageResponse> sendMessage(@RequestBody ChatMessageRequest request) {
        ChatMessageResponse response = chatbotService.processMessage(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(chatbotService.getInitialConfig());
    }
}
