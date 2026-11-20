package com.boxcinema.common.service;

public interface QrCodeService {

    /**
     * Generates a PNG byte array for the specified text payload using ZXing.
     */
    byte[] generateQrPng(String payload, int width, int height);

    /**
     * Generates a Base64-encoded PNG string (data URI compatible) for the specified text payload.
     */
    String generateQrBase64(String payload, int width, int height);
}
