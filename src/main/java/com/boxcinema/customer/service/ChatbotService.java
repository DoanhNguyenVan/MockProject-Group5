package com.boxcinema.customer.service;

import com.boxcinema.customer.dto.request.ChatMessageRequest;
import com.boxcinema.customer.dto.response.ChatMessageResponse;

import java.util.Map;

public interface ChatbotService {

    ChatMessageResponse processMessage(ChatMessageRequest request);

    Map<String, Object> getInitialConfig();
}
