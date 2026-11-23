package com.boxcinema.customer.service.impl;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.entity.FoodCombo;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.entity.Promotion;
import com.boxcinema.common.repository.CinemaRepository;
import com.boxcinema.common.repository.FoodComboRepository;
import com.boxcinema.common.repository.MovieRepository;
import com.boxcinema.common.repository.PromotionRepository;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.customer.dto.request.ChatMessageRequest;
import com.boxcinema.customer.dto.response.ChatMessageResponse;
import com.boxcinema.customer.service.ChatbotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final MovieRepository movieRepository;
    private final CinemaRepository cinemaRepository;
    private final PromotionRepository promotionRepository;
    private final FoodComboRepository foodComboRepository;

    @Value("${app.chatbot.enabled:true}")
    private boolean enabled;

    @Value("${app.chatbot.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${app.chatbot.gemini.model:gemini-3.1-flash-lite}")
    private String geminiModel;

    private volatile String cachedSystemPrompt = null;
    private volatile long lastSystemPromptCacheTime = 0;
    private static final long PROMPT_CACHE_DURATION_MS = 5 * 60 * 1000; // 5-minute memory cache

    @Value("${app.chatbot.n8n.webhook-url:http://localhost:5678/webhook/box-cinema-chat}")
    private String n8nWebhookUrl;

    @Value("${app.chatbot.n8n.timeout-seconds:25}")
    private int timeoutSeconds;

    @Autowired
    public ChatbotServiceImpl(RestTemplateBuilder restTemplateBuilder,
                              @Autowired(required = false) MovieRepository movieRepository,
                              @Autowired(required = false) CinemaRepository cinemaRepository,
                              @Autowired(required = false) PromotionRepository promotionRepository,
                              @Autowired(required = false) FoodComboRepository foodComboRepository) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(25))
                .build();
        this.movieRepository = movieRepository;
        this.cinemaRepository = cinemaRepository;
        this.promotionRepository = promotionRepository;
        this.foodComboRepository = foodComboRepository;
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

        String userMessage = request.getMessage().trim();
        String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                ? request.getSessionId().trim()
                : UUID.randomUUID().toString();

        // 1. Instant sub-millisecond response for standard catalog queries (Save Gemini quota & 100% uptime)
        ChatMessageResponse quickResponse = buildQuickCatalogResponse(userMessage);
        if (quickResponse != null) {
            return quickResponse;
        }

        // 2. If GEMINI_API_KEY is configured, call Gemini API directly for natural conversations
        if (geminiApiKey != null && !geminiApiKey.trim().isBlank()) {
            try {
                String reply = callGeminiDirectly(userMessage, sessionId);
                if (reply != null && !reply.isBlank()) {
                    ChatMessageResponse response = ChatMessageResponse.success(reply);
                    response.setSuggestions(getDefaultFollowUpSuggestions(userMessage));
                    return response;
                }
            } catch (Exception ex) {
                log.error("Direct Gemini API call failed: {}. Falling back to smart catalog fallback.", ex.getMessage(), ex);
            }
        }

        // 3. Fallback to n8n Webhook if configured and reachable
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", userMessage);
            payload.put("sessionId", sessionId);
            payload.put("timestamp", Instant.now().toString());
            payload.put("userContext", extractCurrentUserContext());

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
                chatResponse.setSuggestions(getDefaultFollowUpSuggestions(userMessage));
                return chatResponse;
            }
        } catch (ResourceAccessException ex) {
            log.warn("Cannot reach n8n webhook at {}: {}", n8nWebhookUrl, ex.getMessage());
        } catch (Exception ex) {
            log.error("Error calling n8n fallback: {}", ex.getMessage());
        }

        // 3. Smart Instant Rule-Based Fallback using real database catalog
        ChatMessageResponse smartResponse = buildSmartCatalogFallback(userMessage);
        if (smartResponse != null) {
            return smartResponse;
        }

        return ChatMessageResponse.success(
                "Hello! I am right here to help you with BOX Cinema 🎬. You can browse our now-showing movies in the [Movies](/#moviesSection) section, check today's showtimes in [Showtimes](/showtimes), or call our hotline 1900 6868 for instant booking!");
    }

    private ChatMessageResponse buildQuickCatalogResponse(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return null;
        String lower = userMessage.toLowerCase().trim();

        // Exact quick suggestion button clicks
        if (lower.equals("now showing movies") || lower.equals("phim đang chiếu")) {
            return buildMoviesResponse();
        }
        if (lower.equals("today's showtimes") || lower.equals("suất chiếu hôm nay") || lower.equals("lịch chiếu hôm nay")) {
            return buildShowtimesResponse();
        }
        if (lower.equals("today's promotions") || lower.equals("promotions") || lower.equals("khuyến mãi")) {
            return buildPromotionsResponse();
        }
        if (lower.equals("popcorn & drink combos") || lower.equals("combos & snacks") || lower.equals("bỏng nước")) {
            return buildCombosResponse();
        }
        if (lower.equals("cancellation policy") || lower.equals("chính sách hủy vé")) {
            return buildCancellationResponse();
        }
        if (lower.equals("cinema locations") || lower.equals("danh sách rạp")) {
            return buildCinemasResponse();
        }
        return null;
    }

    private ChatMessageResponse buildMoviesResponse() {
        try {
            List<Movie> nowShowing = movieRepository != null ? movieRepository.findByStatusWithGenres("NOW_SHOWING") : List.of();
            if (nowShowing != null && !nowShowing.isEmpty()) {
                StringBuilder reply = new StringBuilder("🎬 **Currently Showing at BOX Cinema:**\n\n");
                for (Movie m : nowShowing) {
                    reply.append(String.format("• **%s** (%d mins | %s | %s)\n",
                            m.getTitle(), m.getDurationMinutes(), m.getAgeRating(), m.getLanguage()));
                }
                reply.append("\nYou can select any title above to book your seats online!");
                ChatMessageResponse resp = ChatMessageResponse.success(reply.toString());
                resp.setSuggestions(List.of("Today's showtimes", "Ticket prices", "Popcorn & drink combos", "Today's promotions"));
                return resp;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private ChatMessageResponse buildShowtimesResponse() {
        ChatMessageResponse resp = ChatMessageResponse.success(
                "📅 **Real-Time Showtimes at BOX Cinema:**\n\nShowtimes are scheduled throughout the day across all flagship theaters (Royal City, Landmark 81, and Da Nang Riverside) with formats in 2D Standard, 3D, IMAX Laser, and 2D Dolby Atmos.\n\nYou can browse full showtimes and select seats directly on our **[Showtimes](/showtimes)** page!");
        resp.setSuggestions(List.of("Now showing movies", "Cinema locations", "Ticket prices"));
        return resp;
    }

    private ChatMessageResponse buildPromotionsResponse() {
        try {
            List<Promotion> promos = promotionRepository != null ? promotionRepository.findAll() : List.of();
            StringBuilder reply = new StringBuilder("🎁 **Active Promotions & Promo Codes at BOX Cinema:**\n\n");
            boolean hasActive = false;
            if (promos != null && !promos.isEmpty()) {
                for (Promotion p : promos) {
                    if ("ACTIVE".equalsIgnoreCase(p.getStatus())) {
                        hasActive = true;
                        String discountText = p.getDiscountValue() != null 
                                ? (p.getDiscountType() != null && p.getDiscountType().contains("PERCENT") ? p.getDiscountValue() + "% off" : p.getDiscountValue() + " VND off")
                                : "Special discount";
                        reply.append(String.format("• **%s** (Code: `%s`): %s\n", p.getPromotionName(), p.getVoucherCode(), discountText));
                    }
                }
            }
            if (!hasActive) {
                reply.append("• **BOXWELCOME** (15% off for new members)\n• **BOXSTUDENT** (20,000 VND off for students)\n• **BOXDUO** (30,000 VND off for couples on Wednesdays)\n");
            }
            reply.append("\nYou can apply these promo codes during online ticket checkout!");
            ChatMessageResponse resp = ChatMessageResponse.success(reply.toString());
            resp.setSuggestions(List.of("Now showing movies", "Today's showtimes", "Popcorn & drink combos"));
            return resp;
        } catch (Exception ignored) {}
        return null;
    }

    private ChatMessageResponse buildCombosResponse() {
        ChatMessageResponse resp = ChatMessageResponse.success(
                "🍿 **BOX Cinema Concessions (Popcorn & Drinks):**\n\n"
                + "• **Single Combo** (1 Large Popcorn + 1 Soft Drink 32oz): 85,000 VND\n"
                + "• **Duo Combo** (1 Jumbo Popcorn + 2 Soft Drinks 32oz): 115,000 VND\n"
                + "• **Family Feast Combo** (2 Popcorn + 3 Drinks + 1 Snack): 165,000 VND\n"
                + "• **Flavors available:** Sweet, Caramel, Cheese, and Salted!\n\n"
                + "You can add concession combos directly during ticket reservation with exclusive web discounts!");
        resp.setSuggestions(List.of("Now showing movies", "Today's showtimes", "Today's promotions"));
        return resp;
    }

    private ChatMessageResponse buildCancellationResponse() {
        ChatMessageResponse resp = ChatMessageResponse.success(
                "🎟️ **Ticket Cancellation & Refund Policy:**\n\n"
                + "• You can cancel tickets online up to **60 minutes** prior to showtime directly in your [Booking History](/booking/history).\n"
                + "• Refunds are credited as Reward Point Vouchers to your member account for future bookings.\n"
                + "• Cash refunds at counter are not supported per cinema policy.");
        resp.setSuggestions(List.of("Now showing movies", "Today's showtimes", "Contact support"));
        return resp;
    }

    private ChatMessageResponse buildCinemasResponse() {
        ChatMessageResponse resp = ChatMessageResponse.success(
                "📍 **BOX Cinema Flagship Theaters:**\n\n"
                + "• **BOX Cinema Royal City**: Floor B2, Vincom Mega Mall Royal City, 72A Nguyen Trai, Hanoi\n"
                + "• **BOX Cinema Landmark 81**: Floor B1, Landmark 81 Tower, 720A Dien Bien Phu, Binh Thanh, HCMC\n"
                + "• **BOX Cinema Da Nang Riverside**: 36 Bach Dang, Hai Chau 1, Hai Chau, Da Nang\n\n"
                + "All theaters feature state-of-the-art IMAX Laser, Dolby Atmos, and luxury Gold Class auditoriums.");
        resp.setSuggestions(List.of("Today's showtimes", "Now showing movies", "Ticket prices"));
        return resp;
    }

    private ChatMessageResponse buildSmartCatalogFallback(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        String lower = userMessage.toLowerCase().trim();

        // 1. Common Greetings
        if (lower.matches("^(hi|hello|hey|alo|chào|xin chào|hoi|alo alo|ad|bot)[!.]?$")) {
            ChatMessageResponse resp = ChatMessageResponse.success(
                    "Hello! Welcome to BOX Cinema 🎬. How may I assist you today with movie showtimes, ticket bookings, snacks & combos, or promotional vouchers?");
            resp.setSuggestions(List.of("Now showing movies", "Today's showtimes", "Popcorn & drink combos", "Today's promotions"));
            return resp;
        }

        // 2. Movie inquiries
        if (lower.contains("movie") || lower.contains("phim")) {
            ChatMessageResponse resp = buildMoviesResponse();
            if (resp != null) return resp;
        }

        // 3. Showtimes inquiries
        if (lower.contains("showtime") || lower.contains("suất chiếu") || lower.contains("lịch chiếu") || lower.contains("gio chieu")) {
            return buildShowtimesResponse();
        }

        // 4. Food & combos
        if (lower.contains("combo") || lower.contains("bỏng") || lower.contains("bap") || lower.contains("popcorn") || lower.contains("snack") || lower.contains("drink") || lower.contains("nước")) {
            return buildCombosResponse();
        }

        // 5. Promotions
        if (lower.contains("promotion") || lower.contains("voucher") || lower.contains("discount") || lower.contains("khuyến mãi") || lower.contains("giam gia") || lower.contains("ưu đãi")) {
            ChatMessageResponse resp = buildPromotionsResponse();
            if (resp != null) return resp;
        }

        // 6. Cancellation
        if (lower.contains("cancellation") || lower.contains("hủy vé") || lower.contains("policy") || lower.contains("chính sách") || lower.contains("refund") || lower.contains("đổi vé")) {
            return buildCancellationResponse();
        }

        // 7. Cinemas
        if (lower.contains("cinema") || lower.contains("rạp") || lower.contains("chi nhánh") || lower.contains("địa chỉ") || lower.contains("location")) {
            return buildCinemasResponse();
        }

        // 8. Hotline & Support
        if (lower.contains("hotline") || lower.contains("hỗ trợ") || lower.contains("support") || lower.contains("contact") || lower.contains("liên hệ") || lower.contains("sđt")) {
            ChatMessageResponse resp = ChatMessageResponse.success(
                    "📞 **Customer Care Support:**\n• Hotline: **1900 6868** (08:00 - 01:00 daily)\n• Email: **support@boxcinema.vn**\n• Contact Form: Visit our **[Contact](/contact)** page for any inquiries or feedback.");
            resp.setSuggestions(List.of("Now showing movies", "Today's showtimes"));
            return resp;
        }

        // 9. Generic friendly response
        return ChatMessageResponse.success(
                "I am here to help you with BOX Cinema 🎬. You can ask me about now-showing movies, today's showtimes, popcorn combos, or ticket booking guide!");
    }

    private String callGeminiDirectly(String userMessage, String sessionId) {
        String systemInstruction = getCachedSystemInstruction();

        Map<String, Object> requestBody = new LinkedHashMap<>();

        Map<String, Object> systemPart = new LinkedHashMap<>();
        systemPart.put("parts", List.of(Map.of("text", systemInstruction)));
        requestBody.put("system_instruction", systemPart);

        Map<String, Object> userContent = new LinkedHashMap<>();
        userContent.put("role", "user");
        userContent.put("parts", List.of(Map.of("text", userMessage)));
        requestBody.put("contents", List.of(userContent));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.2);
        generationConfig.put("maxOutputTokens", 500);
        requestBody.put("generationConfig", generationConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        // Try official ultra-fast Gemini models
        List<String> modelsToTry = List.of(
                (geminiModel != null && !geminiModel.isBlank()) ? geminiModel.trim() : "gemini-1.5-flash",
                "gemini-1.5-flash",
                "gemini-2.0-flash",
                "gemini-1.5-flash-8b",
                "gemini-2.5-flash"
        );

        for (String model : modelsToTry) {
            try {
                String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + geminiApiKey.trim();
                log.info("Calling direct Gemini model: {}", model);

                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        url,
                        HttpMethod.POST,
                        entity,
                        new ParameterizedTypeReference<>() {}
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> body = response.getBody();
                    if (body.containsKey("candidates") && body.get("candidates") instanceof List<?> candidates && !candidates.isEmpty()) {
                        Object firstCandidate = candidates.get(0);
                        if (firstCandidate instanceof Map<?, ?> candMap && candMap.containsKey("content")) {
                            Object contentObj = candMap.get("content");
                            if (contentObj instanceof Map<?, ?> contentMap && contentMap.containsKey("parts")) {
                                Object partsObj = contentMap.get("parts");
                                if (partsObj instanceof List<?> partsList && !partsList.isEmpty()) {
                                    Object firstPart = partsList.get(0);
                                    if (firstPart instanceof Map<?, ?> partMap && partMap.containsKey("text")) {
                                        return String.valueOf(partMap.get("text")).trim();
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (RestClientResponseException ex) {
                log.warn("Gemini model {} returned {}: {}. Trying next model...", model, ex.getStatusCode(), ex.getResponseBodyAsString());
            } catch (Exception ex) {
                log.warn("Error calling Gemini model {}: {}. Trying next model...", model, ex.getMessage());
            }
        }

        return null;
    }

    private String getCachedSystemInstruction() {
        long now = System.currentTimeMillis();
        if (cachedSystemPrompt != null && (now - lastSystemPromptCacheTime) < PROMPT_CACHE_DURATION_MS) {
            return cachedSystemPrompt;
        }
        synchronized (this) {
            if (cachedSystemPrompt != null && (now - lastSystemPromptCacheTime) < PROMPT_CACHE_DURATION_MS) {
                return cachedSystemPrompt;
            }
            cachedSystemPrompt = buildComprehensiveSystemPrompt();
            lastSystemPromptCacheTime = System.currentTimeMillis();
            return cachedSystemPrompt;
        }
    }

    private String buildComprehensiveSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the intelligent, professional, and dedicated AI Assistant for BOX Cinema Vietnam 🎬.\n\n");
        sb.append("🌐 BILINGUAL COMMUNICATION (ENGLISH DEFAULT & VIETNAMESE):\n");
        sb.append("- Default language is ENGLISH. If the user writes in English, respond in fluent, professional, and friendly English.\n");
        sb.append("- If the user writes in Vietnamese (tiếng Việt có dấu hoặc không dấu), respond in warm, polite, and natural Vietnamese (xưng hô 'mình' hoặc 'em').\n");
        sb.append("- Dynamically match the language of the user's latest query.\n\n");

        sb.append("🎯 SCOPE OF ASSISTANCE (BOX CINEMA & SERVICES):\n");
        sb.append("1. Movie Information: Now-showing & upcoming movies, genres, durations, age ratings, and synopses.\n");
        sb.append("2. Showtimes & Tickets: Schedules, room formats (2D, 3D, IMAX, Dolby Atmos), ticket prices, and booking guide.\n");
        sb.append("3. Concessions (F&B): Popcorn flavors, soft drinks, combo packages, and counter prices.\n");
        sb.append("4. Promotions: Active discount vouchers, coupon codes, and promo conditions.\n");
        sb.append("5. Customer Support & Complaints (Khiếu nại & Hỗ trợ):\n");
        sb.append("   - Direct Hotline: 1900 6868 (08:00 AM - 01:00 AM daily) for urgent ticket, refund, or service support.\n");
        sb.append("   - Email: support@boxcinema.vn (general support) or events@boxcinema.vn (corporate).\n");
        sb.append("   - Online Inquiry Form: Available on the [Contact page](/contact). Users can submit their full name, email, phone, topic, and message. The Guest Relations team responds within 2-24 business hours.\n");
        sb.append("   - Head Office: Level 5, Vincom Center, District 1, HCMC / Hoa Lac Hi-Tech Park, Hanoi.\n");
        sb.append("6. Account & Security Management:\n");
        sb.append("   - Change Password: Log in, then navigate to [Change Password](/profile/change-password) (or via Account Settings). Enter your current password and new password (at least 8 characters with letters & numbers) and click save.\n");
        sb.append("   - Set Password (for Google-only accounts): Visit [Account Settings](/account-settings) and click 'Set Password'.\n");
        sb.append("   - Unlink Google Account (Hủy liên kết Google):\n");
        sb.append("     * Step 1: Navigate to [Account Settings](/account-settings) -> find 'Google Account'.\n");
        sb.append("     * Step 2: Click the 'Disconnect Google' button.\n");
        sb.append("     * ⚠️ CRITICAL PREREQUISITE: The account MUST have a password set first! If Google is currently your only sign-in method, disconnecting is disabled to protect you from losing account access.\n");
        sb.append("7. Policies & Terms (Điều khoản & Chính sách):\n");
        sb.append("   - ⚠️ CRITICAL RULE: Answer SELECTIVELY and FOCUS DIRECTLY ON WHAT THE USER ASKED. Do NOT dump a wall-of-text listing every policy!\n");
        sb.append("   - Ticket Cancellation: Cancel online >= 60 minutes prior to showtime to receive a Reward Point Voucher refund (cash refund at counter is not supported per SRS rules).\n");
        sb.append("   - Age Classification: P (All ages), K (<13 with guardian), T13 (13+), T16 (16+), T18 (18+).\n");
        sb.append("   - Outside Food Policy: Outside hot food or strong-smelling items are prohibited in auditoriums.\n");
        sb.append("   - Data Privacy: Full terms are available on the [About Us](/about) page.\n\n");

        sb.append("⛔ STRICT OUT-OF-SCOPE GUARDRAIL:\n");
        sb.append("- If asked about topics completely unrelated to BOX Cinema (e.g., external products, weather, code, medical, homework, politics):\n");
        sb.append("  👉 DO NOT answer external questions!\n");
        sb.append("  👉 Politely refuse and guide the user back to BOX Cinema:\n");
        sb.append("     EN: \"I am the dedicated AI Assistant for BOX Cinema 🎬. I can only assist with cinema services, account security, and movie bookings. How may I assist you with BOX Cinema today?\"\n");
        sb.append("     VI: \"Dạ, mình là trợ lý ảo chuyên trách của rạp BOX Cinema 🎬. Mình chỉ hỗ trợ các dịch vụ của rạp, tài khoản và lịch chiếu phim. Mình có thể giúp gì cho bạn về BOX Cinema hôm nay ạ?\"\n\n");

        sb.append("📊 LIVE DATABASE CATALOG (REAL-TIME CONTEXT):\n");

        // Live Movies
        if (movieRepository != null) {
            try {
                List<Movie> nowShowing = movieRepository.findByStatusWithGenres("NOW_SHOWING");
                if (nowShowing != null && !nowShowing.isEmpty()) {
                    sb.append("• Now-Showing Movies:\n");
                    for (Movie m : nowShowing) {
                        sb.append(String.format("  - %s (Duration: %d mins, Age Rating: %s, Lang: %s)\n",
                                m.getTitle(), m.getDurationMinutes(), m.getAgeRating(), m.getLanguage()));
                    }
                }
            } catch (Exception ignored) {}
        }

        // Live Cinemas
        if (cinemaRepository != null) {
            try {
                List<Cinema> cinemas = cinemaRepository.findAll();
                if (cinemas != null && !cinemas.isEmpty()) {
                    sb.append("• Cinema Branches:\n");
                    for (Cinema c : cinemas) {
                        sb.append(String.format("  - %s (Address: %s, Phone: %s)\n",
                                c.getCinemaName(), c.getAddress(), c.getPhone()));
                    }
                }
            } catch (Exception ignored) {}
        }

        // Live Combos
        if (foodComboRepository != null) {
            try {
                List<FoodCombo> combos = foodComboRepository.findAll();
                if (combos != null && !combos.isEmpty()) {
                    sb.append("• Concessions (F&B Combos):\n");
                    for (FoodCombo fc : combos) {
                        sb.append(String.format("  - %s: %,.0f VND (%s)\n",
                                fc.getName(), fc.getPrice(), fc.getDescription() != null ? fc.getDescription() : "Popular Combo"));
                    }
                }
            } catch (Exception ignored) {}
        }

        // Live Promotions
        if (promotionRepository != null) {
            try {
                List<Promotion> promotions = promotionRepository.findAll();
                if (promotions != null && !promotions.isEmpty()) {
                    sb.append("• Active Promotions & Vouchers:\n");
                    for (Promotion p : promotions) {
                        sb.append(String.format("  - Code '%s': %s (Discount: %s %s)\n",
                                p.getVoucherCode(), p.getPromotionName(), p.getDiscountValue(), p.getDiscountType()));
                    }
                }
            } catch (Exception ignored) {}
        }

        sb.append("\n💬 STYLE: Use clean Markdown (bolding, lists, links like [Contact](/contact)). Concise, friendly, and helpful.");
        return sb.toString();
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
