package com.boxcinema.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Official PayOS Payout (v1/payouts) x-signature HMAC-SHA256 algorithm:
 * 1. Deep-sort all object/map keys lexicographically (preserving array element order).
 * 2. Format top-level sorted entries as encodeURIComponent(k)=encodeURIComponent(v) joined by '&'.
 *    - null -> ""
 *    - List or Map -> compact JSON string (with nested map keys deep-sorted)
 *    - scalar -> String.valueOf(v)
 * 3. Lowercase 64-char hex HMAC-SHA256 using PAYOS_PAYOUT_CHECKSUM_KEY.
 */
public final class PayOsPayoutSignatureUtil {

    private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper();

    private PayOsPayoutSignatureUtil() {
    }

    public static String signPayload(Map<String, Object> payload, String checksumKey) {
        if (checksumKey == null || checksumKey.isBlank()) {
            throw new IllegalStateException("PayOS payout checksum key is required.");
        }
        String dataString = buildDataString(payload);
        return hmacSha256Hex(dataString, checksumKey);
    }

    public static boolean verifySignature(Map<String, Object> dataMap, String receivedSignature, String checksumKey) {
        if (dataMap == null || dataMap.isEmpty() || receivedSignature == null || receivedSignature.isBlank()
                || checksumKey == null || checksumKey.isBlank()) {
            return false;
        }
        String expected = signPayload(dataMap, checksumKey);
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                receivedSignature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static String buildDataString(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        Map<String, Object> sorted = deepSortMap(payload);
        List<String> pairs = new ArrayList<>(sorted.size());
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            String valueStr;
            if (val == null) {
                valueStr = "";
            } else if (val instanceof List<?> || val instanceof Map<?, ?>) {
                try {
                    valueStr = COMPACT_MAPPER.writeValueAsString(val);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to serialize JSON value for PayOS payout signature", e);
                }
            } else {
                valueStr = String.valueOf(val);
            }
            pairs.add(encodeUriComponent(key) + "=" + encodeUriComponent(valueStr));
        }
        return String.join("&", pairs);
    }

    @SuppressWarnings("unchecked")
    public static Object deepSortValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepSortMap((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(deepSortValue(item));
            }
            return out;
        }
        return value;
    }

    public static Map<String, Object> deepSortMap(Map<String, Object> input) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            sorted.put(entry.getKey(), deepSortValue(entry.getValue()));
        }
        return sorted;
    }

    public static String encodeUriComponent(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
    }

    private static String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec spec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(spec);
            return CryptoUtils.bytesToHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Error computing PayOS payout HMAC-SHA256", e);
        }
    }
}
