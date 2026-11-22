package com.boxcinema.customer.controller;

import com.boxcinema.common.config.SecurityConfig;
import com.boxcinema.customer.dto.request.ChatMessageRequest;
import com.boxcinema.customer.dto.response.ChatMessageResponse;
import com.boxcinema.customer.service.ChatbotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatbotApiController.class)
@Import(SecurityConfig.class)
public class ChatbotApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChatbotService chatbotService;

    @Test
    void testGetConfig() throws Exception {
        Map<String, Object> mockConfig = new LinkedHashMap<>();
        mockConfig.put("enabled", true);
        mockConfig.put("welcomeMessage", "Xin chào! Tôi là Trợ lý AI của BOX Cinema.");
        mockConfig.put("quickSuggestions", List.of("Phim đang chiếu", "Chính sách hủy vé"));

        when(chatbotService.getInitialConfig()).thenReturn(mockConfig);

        mockMvc.perform(get("/api/chat/config"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.welcomeMessage").value("Xin chào! Tôi là Trợ lý AI của BOX Cinema."))
                .andExpect(jsonPath("$.quickSuggestions[0]").value("Phim đang chiếu"));
    }

    @Test
    void testSendMessageSuccess() throws Exception {
        ChatMessageRequest request = new ChatMessageRequest("Tối nay có phim gì?", "sess_123");
        ChatMessageResponse response = ChatMessageResponse.success("Tối nay có phim Joker 2 và Conan đang chiếu.");

        when(chatbotService.processMessage(any(ChatMessageRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.reply").value("Tối nay có phim Joker 2 và Conan đang chiếu."));
    }
}
