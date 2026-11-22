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
                return getFallbackResponse("The AI Assistant is currently busy. Please try again in a few moments!");
            }

        } catch (ResourceAccessException ex) {
            log.warn("Cannot reach n8n webhook at {}: {}", n8nWebhookUrl, ex.getMessage());
            return getFallbackResponse(
                    "The AI Assistant is currently offline or starting up. "
                    + "You can browse showtimes in the 'Showtimes' section or contact hotline 1900 6868 for support!");
        } catch (RestClientResponseException ex) {
            log.error("n8n webhook error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return getFallbackResponse("An error occurred while processing your request. Please try again later!");
        } catch (Exception ex) {
            log.error("Unexpected error in chatbot processing: {}", ex.getMessage(), ex);
            return getFallbackResponse("An unexpected issue occurred. Please try again later!");
        }
    }

    @Override
    public Map<String, Object> getInitialConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", enabled);
        config.put("welcomeMessage",
                "Hello! I am the BOX Cinema AI Assistant 🎬.\n\n"
                + "I can help you check now-showing movies, showtimes, ticket prices, popcorn & drink combos, and promotional vouchers. "
                + "You can chat with me in English or Tiếng Việt!\n\n"
                + "How may I help you today?");
        config.put("quickSuggestions", List.of(
                "What movies are showing today?",
                "Any discount vouchers available?",
                "What snack combos do you have?",
                "Cinema locations & hotlines",
                "Ticket cancellation policy"
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
        return "Thank you for contacting BOX Cinema. Is there anything else I can assist you with?";
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
                "Now showing movies",
                "Today's showtimes",
                "Cancellation policy",
                "Support hotline"
        ));
        return res;
    }

    private List<String> getDefaultFollowUpSuggestions(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return List.of("Now showing movies", "Today's promotions", "Cinema locations");
        }
        String lower = " " + userMessage.toLowerCase().trim() + " ";

        boolean isVietnamese = lower.matches(".*[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ].*")
                || lower.contains(" phim ") || lower.contains(" chieu ") || lower.contains(" chiếu ")
                || lower.contains(" rap ") || lower.contains(" rạp ") || lower.contains(" bap ")
                || lower.contains(" bắp ") || lower.contains(" ve ") || lower.contains(" vé ")
                || lower.contains(" gia ") || lower.contains(" giá ") || lower.contains(" khuyen mai ")
                || lower.contains(" khieu nai ") || lower.contains(" khiếu nại ") || lower.contains(" mat khau ")
                || lower.contains(" mật khẩu ") || lower.contains(" huy ") || lower.contains(" hủy ")
                || lower.contains(" lien ket ") || lower.contains(" liên kết ") || lower.contains(" chinh sach ")
                || lower.contains(" chính sách ") || lower.contains(" dieu khoan ") || lower.contains(" điều khoản ");

        if (isVietnamese) {
            if (lower.contains("khiếu nại") || lower.contains("khieu nai") || lower.contains("liên hệ") || lower.contains("lien he") || lower.contains("hỗ trợ")) {
                return List.of("Hotline rạp 1900 6868", "Gửi email hỗ trợ", "Chính sách hủy vé");
            }
            if (lower.contains("mật khẩu") || lower.contains("mat khau") || lower.contains("google") || lower.contains("liên kết") || lower.contains("lien ket") || lower.contains("tài khoản") || lower.contains("tai khoan")) {
                return List.of("Cách đổi mật khẩu", "Cách hủy liên kết Google", "Cài đặt tài khoản");
            }
            if (lower.contains("chính sách") || lower.contains("chinh sach") || lower.contains("điều khoản") || lower.contains("dieu khoan") || lower.contains("độ tuổi") || lower.contains("do tuoi")) {
                return List.of("Chính sách hủy vé", "Quy định độ tuổi", "Đồ ăn mang vào rạp");
            }
            if (lower.contains("phim") || lower.contains("chiếu") || lower.contains("chieu")) {
                return List.of("Giá vé bao nhiêu?", "Có mã giảm giá không?", "Combo bắp nước có gì?");
            }
            if (lower.contains("vé") || lower.contains("ve") || lower.contains("giá") || lower.contains("gia")) {
                return List.of("Xem lịch chiếu hôm nay", "Chính sách hủy vé", "Danh sách cụm rạp");
            }
            if (lower.contains("bắp") || lower.contains("bap") || lower.contains("nước") || lower.contains("combo")) {
                return List.of("Phim đang chiếu", "Mã giảm giá hôm nay", "Xem lịch chiếu");
            }
            return List.of("Phim đang chiếu", "Khuyến mãi hôm nay", "Hotline rạp");
        } else {
            // Default to English
            if (lower.contains("complaint") || lower.contains("contact") || lower.contains("support") || lower.contains("help")) {
                return List.of("Direct Hotline 1900 6868", "Submit inquiry form", "Cancellation policy");
            }
            if (lower.contains("password") || lower.contains("google") || lower.contains("unlink") || lower.contains("account")) {
                return List.of("Change password guide", "Disconnect Google account", "Account settings");
            }
            if (lower.contains("policy") || lower.contains("term") || lower.contains("age") || lower.contains("rating") || lower.contains("refund")) {
                return List.of("Ticket cancellation policy", "Age rating guidelines", "Outside food policy");
            }
            if (lower.contains("movie") || lower.contains("film") || lower.contains("showtime") || lower.contains("showing")) {
                return List.of("Ticket prices", "Any discount vouchers?", "Popcorn & drink combos");
            }
            if (lower.contains("ticket") || lower.contains("price") || lower.contains("book") || lower.contains("cost")) {
                return List.of("Today's showtimes", "Ticket cancellation policy", "Cinema hotlines");
            }
            if (lower.contains("combo") || lower.contains("popcorn") || lower.contains("drink") || lower.contains("food") || lower.contains("snack")) {
                return List.of("Now showing movies", "Any discount vouchers?", "Cinema locations");
            }
            return List.of("Now showing movies", "Today's promotions", "Cinema locations & hotlines");
        }
    }
}
