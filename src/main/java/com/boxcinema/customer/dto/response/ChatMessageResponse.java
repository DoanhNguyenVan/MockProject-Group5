package com.boxcinema.customer.dto.response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChatMessageResponse {
    private boolean success;
    private String reply;
    private String error;
    private List<String> suggestions = new ArrayList<>();
    private List<Map<String, Object>> actionCards = new ArrayList<>();

    public ChatMessageResponse() {
    }

    public ChatMessageResponse(boolean success, String reply) {
        this.success = success;
        this.reply = reply;
    }

    public static ChatMessageResponse success(String reply) {
        ChatMessageResponse res = new ChatMessageResponse(true, reply);
        return res;
    }

    public static ChatMessageResponse fail(String error, String friendlyReply) {
        ChatMessageResponse res = new ChatMessageResponse(false, friendlyReply);
        res.setError(error);
        return res;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public List<Map<String, Object>> getActionCards() {
        return actionCards;
    }

    public void setActionCards(List<Map<String, Object>> actionCards) {
        this.actionCards = actionCards;
    }
}
