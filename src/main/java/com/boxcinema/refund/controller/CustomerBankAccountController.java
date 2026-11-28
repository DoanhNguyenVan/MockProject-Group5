package com.boxcinema.refund.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.refund.dto.CustomerBankAccountRequestDto;
import com.boxcinema.refund.dto.CustomerBankAccountResponseDto;
import com.boxcinema.refund.service.CustomerBankAccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customer/bank-accounts")
@PreAuthorize("isAuthenticated()")
public class CustomerBankAccountController {

    private final CustomerBankAccountService bankAccountService;

    public CustomerBankAccountController(CustomerBankAccountService bankAccountService) {
        this.bankAccountService = bankAccountService;
    }

    @GetMapping
    public ResponseEntity<List<CustomerBankAccountResponseDto>> listOwnAccounts(HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        return ResponseEntity.ok(bankAccountService.listOwnAccounts(userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerBankAccountResponseDto> getOwnAccount(
            @PathVariable Long id,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        return ResponseEntity.ok(bankAccountService.getOwnAccount(userId, id));
    }

    @PostMapping
    public ResponseEntity<CustomerBankAccountResponseDto> create(
            @RequestBody CustomerBankAccountRequestDto body,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(bankAccountService.create(userId, body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CustomerBankAccountResponseDto> update(
            @PathVariable Long id,
            @RequestBody CustomerBankAccountRequestDto body,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        return ResponseEntity.ok(bankAccountService.update(userId, id, body));
    }

    @PostMapping("/{id}/default")
    public ResponseEntity<CustomerBankAccountResponseDto> setDefault(
            @PathVariable Long id,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        return ResponseEntity.ok(bankAccountService.setDefault(userId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable Long id,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        bankAccountService.delete(userId, id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
