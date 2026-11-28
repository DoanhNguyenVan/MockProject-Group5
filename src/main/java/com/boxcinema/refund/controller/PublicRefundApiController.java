package com.boxcinema.refund.controller;

import com.boxcinema.common.entity.User;
import com.boxcinema.common.service.BankDataEncryptionService;
import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.refund.dto.OrderLookupResponseDto;
import com.boxcinema.refund.dto.PublicRefundSubmitRequestDto;
import com.boxcinema.refund.dto.RefundAppealResponseDto;
import com.boxcinema.refund.dto.RefundAppealSubmitRequestDto;
import com.boxcinema.refund.dto.RefundBankUpdateRequestDto;
import com.boxcinema.refund.dto.RefundRequestDetailDto;
import com.boxcinema.refund.service.EvidenceStorageService;
import com.boxcinema.refund.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/refunds/public")
public class PublicRefundApiController {

    private final RefundService refundService;
    private final EvidenceStorageService evidenceStorageService;

    public PublicRefundApiController(RefundService refundService, EvidenceStorageService evidenceStorageService) {
        this.refundService = refundService;
        this.evidenceStorageService = evidenceStorageService;
    }

    @GetMapping("/banks")
    public ResponseEntity<Map<String, String>> getSupportedBanks() {
        return ResponseEntity.ok(BankDataEncryptionService.SUPPORTED_VIETQR_BANKS);
    }

    @PostMapping("/lookup-order")
    public ResponseEntity<OrderLookupResponseDto> lookupOrder(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String code = body != null ? body.get("orderCode") : null;
        String email = body != null ? body.get("purchaseEmail") : null;
        return ResponseEntity.ok(refundService.lookupPublicOrder(code, email, request.getRemoteAddr()));
    }

    @PostMapping
    public ResponseEntity<RefundRequestDetailDto> submitRefund(
            @RequestBody PublicRefundSubmitRequestDto body,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        RefundRequestDetailDto created = refundService.submitPublicRefundRequest(body, userId, request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/lookup")
    public ResponseEntity<RefundRequestDetailDto> lookupRefundStatus(
            @RequestParam String refundCode,
            @RequestParam String email,
            HttpServletRequest request) {
        return ResponseEntity.ok(refundService.lookupPublicRefundStatus(refundCode, email, request.getRemoteAddr()));
    }

    @PostMapping("/{refundCode}/respond")
    public ResponseEntity<RefundRequestDetailDto> respondMoreInfo(
            @PathVariable String refundCode,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String email = body != null ? body.get("purchaseEmail") : null;
        String message = body != null ? body.get("message") : null;
        String evidenceUrl = body != null ? body.get("evidenceUrl") : null;
        return ResponseEntity.ok(refundService.replyToRefundRequest(
                refundCode, email, message, evidenceUrl, request.getRemoteAddr()));
    }

    @PutMapping("/{refundCode}/bank-account")
    public ResponseEntity<RefundRequestDetailDto> updateBankAccount(
            @PathVariable String refundCode,
            @RequestBody RefundBankUpdateRequestDto body,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        return ResponseEntity.ok(refundService.updatePublicRefundBankAccount(
                refundCode, body, userId, request.getRemoteAddr()));
    }

    @PostMapping("/evidence-upload")
    public ResponseEntity<Map<String, String>> uploadEvidence(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        refundService.checkPublicUploadRateLimit(userId, request.getRemoteAddr());
        String url = evidenceStorageService.storeImage(file);
        return ResponseEntity.ok(Map.of("url", url));
    }

    @GetMapping("/evidence/{filename}")
    public ResponseEntity<Resource> serveEvidence(
            @PathVariable String filename,
            HttpServletRequest request) {
        User actor = evidenceStorageService.resolveAuthenticatedActor(request);
        Resource resource = evidenceStorageService.authorizeAndLoadEvidence(
                filename, actor, request != null ? request.getRemoteAddr() : null);
        MediaType mediaType = filename.toLowerCase().endsWith(".png") ? MediaType.IMAGE_PNG
                : (filename.toLowerCase().endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_JPEG);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'none'; sandbox")
                .body(resource);
    }

    @PostMapping({"/{idOrCode}/appeals", "/{idOrCode}/appeal"})
    public ResponseEntity<RefundAppealResponseDto> submitCustomerAppeal(
            @PathVariable String idOrCode,
            @RequestBody RefundAppealSubmitRequestDto body,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        RefundAppealResponseDto created = refundService.submitCustomerAppeal(
                idOrCode, body, userId, request != null ? request.getRemoteAddr() : null);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping({"/{idOrCode}/appeals", "/{idOrCode}/appeal"})
    public ResponseEntity<List<RefundAppealResponseDto>> listCustomerAppeals(
            @PathVariable String idOrCode,
            @RequestParam(required = false) String email,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        return ResponseEntity.ok(refundService.getAppealsForRefundRequest(idOrCode, userId, email));
    }
}
