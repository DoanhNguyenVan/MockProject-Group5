package com.boxcinema.service.impl;

import com.boxcinema.service.QrCodeService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

@Service
public class QrCodeServiceImpl implements QrCodeService {

    @Override
    public byte[] generateQrPng(String payload, int width, int height) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("QR payload cannot be empty.");
        }
        int w = width > 0 ? width : 260;
        int h = height > 0 ? height : 260;

        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);

            BitMatrix bitMatrix = qrCodeWriter.encode(payload, BarcodeFormat.QR_CODE, w, h, hints);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate QR code with ZXing", e);
        }
    }

    @Override
    public String generateQrBase64(String payload, int width, int height) {
        byte[] pngBytes = generateQrPng(payload, width, height);
        return Base64.getEncoder().encodeToString(pngBytes);
    }
}
