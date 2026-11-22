package com.boxcinema.customer.service.impl;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.customer.dto.request.ChatMessageRequest;
import com.boxcinema.customer.dto.response.ChatMessageResponse;
import com.boxcinema.customer.service.ChatbotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ChatbotServiceImpl implements ChatbotService {

    private static final Logger log = LoggerFactory.getLogger(ChatbotServiceImpl.class);

    private final RestTemplate restTemplate;

    @Value("${app.chatbot.enabled:true}")
    private boolean enabled;

    @Value("${app.chatbot.n8n.webhook-url:http://localhost:5678/webhook/box-cinema-chat}")
    private String n8nWebhookUrl;

    @Value("${app.chatbot.n8n.timeout-seconds:25}")
    private int timeoutSeconds;

    public ChatbotServiceImpl(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(25))
                .build();
    }

    @Override
    public ChatMessageResponse processMessage(ChatMessageRequest request) {
        if (!enabled) {
            return ChatMessageResponse.fail("CHATBOT_DISABLED",
                    "Tính năng trợ lý ảo hiện đang tạm đóng để bảo trì. Quý khách vui lòng thử lại sau!");
        }

        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return ChatMessageResponse.fail("INVALID_MESSAGE", "Nội dung câu hỏi không được để trống.");
        }

        String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                ? request.getSessionId().trim()
                : UUID.randomUUID().toString();

        // 1. Build Payload and Context Enrichment
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", request.getMessage().trim());
        payload.put("sessionId", sessionId);
        payload.put("timestamp", Instant.now().toString());

        Map<String, Object> userContext = extractCurrentUserContext();
        payload.put("userContext", userContext);

        // 2. Call n8n Webhook
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            log.info("Sending chat message to n8n webhook: {} (sessionId={})", n8nWebhookUrl, sessionId);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    n8nWebhookUrl,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                String reply = extractReplyText(body);

                ChatMessageResponse chatResponse = ChatMessageResponse.success(reply);
                chatResponse.setSuggestions(getDefaultFollowUpSuggestions(request.getMessage()));
                return chatResponse;
            } else {
                log.warn("n8n returned non-2xx status: {}", response.getStatusCode());
                return getFallbackResponse("Hệ thống trợ lý AI đang bận xử lý. Bạn vui lòng thử lại sau ít giây nhé!");
            }

        } catch (ResourceAccessException ex) {
            log.warn("Cannot reach n8n webhook at {}: {}", n8nWebhookUrl, ex.getMessage());
            return getFallbackResponse(
                    "Trợ lý AI hiện chưa sẵn sàng kết nối (n8n offline hoặc đang khởi động). "
                    + "Bạn có thể tra cứu nhanh lịch chiếu tại mục 'Lịch chiếu' hoặc liên hệ hotline 1900 6868 để được hỗ trợ nhé!");
        } catch (RestClientResponseException ex) {
            log.error("n8n webhook error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return getFallbackResponse("Có lỗi xảy ra trong quá trình xử lý câu hỏi. Vui lòng thử lại sau!");
        } catch (Exception ex) {
            log.error("Unexpected error in chatbot processing: {}", ex.getMessage(), ex);
            return getFallbackResponse("Hệ thống gặp sự cố ngoài ý muốn. Vui lòng thử lại sau!");
        }
    }

    @Override
    public Map<String, Object> getInitialConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", enabled);
        config.put("welcomeMessage",
                "Xin chào! Tôi là Trợ lý AI của BOX Cinema 🎬. "
                + "Tôi có thể giúp bạn tra cứu lịch chiếu, giá vé, bắp nước và các chương trình khuyến mãi hôm nay. "
                + "Bạn cần hỗ trợ gì?");
        config.put("quickSuggestions", List.of(
                "Hôm nay có những phim gì đang chiếu?",
                "Chính sách hoàn / hủy vé như thế nào?",
                "Có mã giảm giá voucher nào hôm nay?",
                "Giá các combo bắp nước hiện tại?",
                "Quy định độ tuổi xem phim (P, K, T13, T16, T18)"
        ));
        return config;
    }

    private String extractReplyText(Map<String, Object> body) {
        if (body.containsKey("reply") && body.get("reply") != null) {
            return String.valueOf(body.get("reply"));
        }
        if (body.containsKey("output") && body.get("output") != null) {
            return String.valueOf(body.get("output"));
        }
        if (body.containsKey("text") && body.get("text") != null) {
            return String.valueOf(body.get("text"));
        }
        if (body.containsKey("response") && body.get("response") != null) {
            return String.valueOf(body.get("response"));
        }
        return "Cảm ơn bạn đã liên hệ với BOX Cinema. Bạn có thắc mắc gì thêm không?";
    }

    private Map<String, Object> extractCurrentUserContext() {
        Map<String, Object> userContext = new LinkedHashMap<>();
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                userContext.put("authenticated", true);
                if (auth.getPrincipal() instanceof BoxCinemaUserDetails userDetails) {
                    UserSummaryDto summary = userDetails.getUserSummary();
                    if (summary != null) {
                        userContext.put("userId", summary.getUserId());
                        userContext.put("fullName", summary.getFullName());
                        userContext.put("email", summary.getEmail());
                        userContext.put("phone", summary.getPhone());
                        userContext.put("role", summary.getRole());
                    }
                } else {
                    userContext.put("name", auth.getName());
                }
            } else {
                userContext.put("authenticated", false);
            }
        } catch (Exception ignored) {
            userContext.put("authenticated", false);
        }
        return userContext;
    }

    private ChatMessageResponse getFallbackResponse(String friendlyMessage) {
        ChatMessageResponse res = ChatMessageResponse.success(friendlyMessage);
        res.setSuggestions(List.of(
                "Xem lịch chiếu hôm nay",
                "Xem danh sách phim",
                "Chính sách hủy vé",
                "Hotline hỗ trợ"
        ));
        return res;
    }

    private List<String> getDefaultFollowUpSuggestions(String userMessage) {
        String lower = userMessage.toLowerCase();
        if (lower.contains("phim") || lower.contains("chiếu")) {
            return List.of("Giá vé bao nhiêu?", "Có mã giảm giá không?", "Combo bắp nước có gì?");
        }
        if (lower.contains("vé") || lower.contains("giá")) {
            return List.of("Xem lịch chiếu", "Cách đặt vé online", "Chính sách đổi trả vé");
        }
        return List.of("Phim đang chiếu", "Khuyến mãi hôm nay", "Hotline rạp");
    }
}
