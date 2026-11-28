package com.boxcinema.refund.service;

import com.boxcinema.common.entity.CustomerBankAccount;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.CustomerBankAccountRepository;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.common.service.BankDataEncryptionService;
import com.boxcinema.refund.dto.CustomerBankAccountRequestDto;
import com.boxcinema.refund.dto.CustomerBankAccountResponseDto;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service managing customer-owned saved bank accounts for PayOS refunds.
 * Account numbers are encrypted with AES-256-GCM at rest and never returned or logged in plaintext.
 */
@Service
public class CustomerBankAccountService {

    private final CustomerBankAccountRepository bankAccountRepository;
    private final UserRepository userRepository;
    private final BankDataEncryptionService bankEncryptionService;

    public CustomerBankAccountService(
            CustomerBankAccountRepository bankAccountRepository,
            UserRepository userRepository,
            BankDataEncryptionService bankEncryptionService) {
        this.bankAccountRepository = bankAccountRepository;
        this.userRepository = userRepository;
        this.bankEncryptionService = bankEncryptionService;
    }

    public record ResolvedBankAccount(
            Long savedBankAccountId,
            String bankBin,
            String bankName,
            String plainAccountNumber,
            String encryptedAccountNumber,
            String maskedAccountNumber,
            String accountNumberHash,
            String accountHolderName,
            String note
    ) {}

    @Transactional(readOnly = true)
    public List<CustomerBankAccountResponseDto> listOwnAccounts(Long userId) {
        requireAuthenticatedUserId(userId);
        return bankAccountRepository.findByUser_UserIdOrderByIsDefaultDescUpdatedAtDesc(userId).stream()
                .map(this::mapToDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomerBankAccountResponseDto getOwnAccount(Long userId, Long accountId) {
        requireAuthenticatedUserId(userId);
        return mapToDto(requireOwnedAccount(userId, accountId));
    }

    @Transactional
    public CustomerBankAccountResponseDto create(Long userId, CustomerBankAccountRequestDto req) {
        requireAuthenticatedUserId(userId);
        if (req == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Bank account payload is required.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_AUTHENTICATED", "User not found."));

        String bankName = validateBin(req.getBankBin());
        String cleanBin = req.getBankBin().trim();
        String cleanAccount = validateAndConfirmAccount(req.getAccountNumber(), req.getConfirmAccountNumber());
        String normalizedHolder = requireValidHolderName(req.getAccountHolderName());
        String cleanNote = sanitizeNote(req.getNote());

        String encrypted = bankEncryptionService.encryptAccountNumber(cleanAccount);
        String masked = bankEncryptionService.maskAccountNumber(cleanAccount);
        String hash = bankEncryptionService.hashAccountNumber(cleanAccount);

        if (bankAccountRepository.existsByUser_UserIdAndBankBinAndAccountNumberHash(userId, cleanBin, hash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_BANK_ACCOUNT", "This bank account is already saved.");
        }

        boolean makeDefault = Boolean.TRUE.equals(req.getIsDefault());
        if (makeDefault) {
            bankAccountRepository.clearDefaultForUser(userId);
            bankAccountRepository.flush();
        }

        CustomerBankAccount entity = new CustomerBankAccount();
        entity.setUser(user);
        entity.setBankBin(cleanBin);
        entity.setBankName(bankName);
        entity.setAccountNumberEncrypted(encrypted);
        entity.setAccountNumberMasked(masked);
        entity.setAccountNumberHash(hash);
        entity.setAccountHolderName(normalizedHolder);
        entity.setNote(cleanNote);
        entity.setDefault(makeDefault);

        try {
            return mapToDto(bankAccountRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_BANK_ACCOUNT", "This bank account is already saved.");
        }
    }

    @Transactional
    public CustomerBankAccountResponseDto update(Long userId, Long accountId, CustomerBankAccountRequestDto req) {
        requireAuthenticatedUserId(userId);
        if (req == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Bank account payload is required.");
        }
        CustomerBankAccount existing = requireOwnedAccount(userId, accountId);

        String effectiveBin = existing.getBankBin();
        String effectiveBankName = existing.getBankName();
        if (req.getBankBin() != null && !req.getBankBin().isBlank()) {
            effectiveBankName = validateBin(req.getBankBin());
            effectiveBin = req.getBankBin().trim();
        }

        String effectiveEncrypted = existing.getAccountNumberEncrypted();
        String effectiveMasked = existing.getAccountNumberMasked();
        String effectiveHash = existing.getAccountNumberHash();
        if (req.getAccountNumber() != null && !req.getAccountNumber().isBlank()) {
            String cleanAccount = validateAndConfirmAccount(req.getAccountNumber(), req.getConfirmAccountNumber());
            effectiveEncrypted = bankEncryptionService.encryptAccountNumber(cleanAccount);
            effectiveMasked = bankEncryptionService.maskAccountNumber(cleanAccount);
            effectiveHash = bankEncryptionService.hashAccountNumber(cleanAccount);
        } else if (req.getConfirmAccountNumber() != null && !req.getConfirmAccountNumber().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_NUMBER_CONFIRM_MISMATCH",
                    "Account number and confirmation account number do not match.");
        }

        String effectiveHolder = existing.getAccountHolderName();
        if (req.getAccountHolderName() != null) {
            effectiveHolder = requireValidHolderName(req.getAccountHolderName());
        }

        String effectiveNote = existing.getNote();
        if (req.getNote() != null) {
            effectiveNote = sanitizeNote(req.getNote());
        }

        if (bankAccountRepository.existsByUser_UserIdAndBankBinAndAccountNumberHashAndIdNot(
                userId, effectiveBin, effectiveHash, accountId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_BANK_ACCOUNT", "This bank account is already saved.");
        }

        boolean currentDefault = existing.isDefault();
        Boolean requestedDefault = req.getIsDefault();
        if (Boolean.TRUE.equals(requestedDefault)) {
            bankAccountRepository.clearDefaultForUser(userId);
            bankAccountRepository.flush();
            existing = bankAccountRepository.findById(accountId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BANK_ACCOUNT_NOT_FOUND", "Saved bank account not found."));
            currentDefault = true;
        } else if (Boolean.FALSE.equals(requestedDefault)) {
            currentDefault = false;
        }

        existing.setBankBin(effectiveBin);
        existing.setBankName(effectiveBankName);
        existing.setAccountNumberEncrypted(effectiveEncrypted);
        existing.setAccountNumberMasked(effectiveMasked);
        existing.setAccountNumberHash(effectiveHash);
        existing.setAccountHolderName(effectiveHolder);
        existing.setNote(effectiveNote);
        existing.setDefault(currentDefault);

        try {
            return mapToDto(bankAccountRepository.saveAndFlush(existing));
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_BANK_ACCOUNT", "This bank account is already saved.");
        }
    }

    @Transactional
    public CustomerBankAccountResponseDto setDefault(Long userId, Long accountId) {
        requireAuthenticatedUserId(userId);
        requireOwnedAccount(userId, accountId);
        bankAccountRepository.clearDefaultForUser(userId);
        bankAccountRepository.flush();
        CustomerBankAccount refreshed = bankAccountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BANK_ACCOUNT_NOT_FOUND", "Saved bank account not found."));
        refreshed.setDefault(true);
        return mapToDto(bankAccountRepository.saveAndFlush(refreshed));
    }

    @Transactional
    public void delete(Long userId, Long accountId) {
        requireAuthenticatedUserId(userId);
        CustomerBankAccount acc = requireOwnedAccount(userId, accountId);
        bankAccountRepository.delete(acc);
    }

    @Transactional(readOnly = true)
    public ResolvedBankAccount resolveDecryptedForOwner(Long savedId, Long actorUserId) {
        if (savedId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Saved bank account ID is required.");
        }
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "BANK_ACCOUNT_ACCESS_DENIED",
                    "Authentication is required to use a saved bank account.");
        }
        CustomerBankAccount acc = bankAccountRepository.findById(savedId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BANK_ACCOUNT_NOT_FOUND", "Saved bank account not found."));
        if (acc.getUser() == null || !acc.getUser().getUserId().equals(actorUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "BANK_ACCOUNT_ACCESS_DENIED",
                    "Access denied: you do not own this saved bank account.");
        }
        String bankName = validateBin(acc.getBankBin());
        String plainAccount;
        try {
            plainAccount = bankEncryptionService.validateAccountNumber(
                    bankEncryptionService.decryptAccountNumber(acc.getAccountNumberEncrypted()));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BANK_INFO", ex.getMessage());
        }
        String freshEncrypted = bankEncryptionService.encryptAccountNumber(plainAccount);
        String masked = bankEncryptionService.maskAccountNumber(plainAccount);
        String hash = bankEncryptionService.hashAccountNumber(plainAccount);
        return new ResolvedBankAccount(
                acc.getId(),
                acc.getBankBin(),
                bankName,
                plainAccount,
                freshEncrypted,
                masked,
                hash,
                acc.getAccountHolderName(),
                acc.getNote()
        );
    }

    @Transactional
    public Long saveFromRefundIfAbsent(
            Long userId,
            String bankBin,
            String bankName,
            String encryptedAccount,
            String maskedAccount,
            String accountHash,
            String holderName,
            String note) {
        if (userId == null || bankBin == null || accountHash == null) {
            return null;
        }
        var existingOpt = bankAccountRepository.findByUser_UserIdAndBankBinAndAccountNumberHash(userId, bankBin, accountHash);
        if (existingOpt.isPresent()) {
            return existingOpt.get().getId();
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        CustomerBankAccount entity = new CustomerBankAccount();
        entity.setUser(user);
        entity.setBankBin(bankBin);
        entity.setBankName(bankName);
        entity.setAccountNumberEncrypted(encryptedAccount);
        entity.setAccountNumberMasked(maskedAccount);
        entity.setAccountNumberHash(accountHash);
        entity.setAccountHolderName(holderName);
        entity.setNote(sanitizeNote(note));
        entity.setDefault(false);
        try {
            return bankAccountRepository.save(entity).getId();
        } catch (DataIntegrityViolationException ex) {
            return bankAccountRepository.findByUser_UserIdAndBankBinAndAccountNumberHash(userId, bankBin, accountHash)
                    .map(CustomerBankAccount::getId)
                    .orElse(null);
        }
    }

    public String validateBin(String rawBin) {
        try {
            return bankEncryptionService.validateAndResolveBankName(rawBin);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BANK_INFO", ex.getMessage());
        }
    }

    public String validateAndConfirmAccount(String rawAccount, String rawConfirm) {
        String cleanAccount;
        try {
            cleanAccount = bankEncryptionService.validateAccountNumber(rawAccount);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BANK_INFO", ex.getMessage());
        }
        if (rawConfirm == null || rawConfirm.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_NUMBER_CONFIRM_REQUIRED",
                    "Please confirm your bank account number.");
        }
        String cleanConfirm = rawConfirm.replaceAll("\\s+", "");
        if (!cleanAccount.equals(cleanConfirm)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_NUMBER_CONFIRM_MISMATCH",
                    "Account number and confirmation account number do not match.");
        }
        return cleanAccount;
    }

    public String requireValidHolderName(String rawHolder) {
        if (rawHolder == null || rawHolder.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_HOLDER_REQUIRED",
                    "Account holder name is required.");
        }
        String normalized = bankEncryptionService.normalizeAccountHolderName(rawHolder);
        if (normalized == null || normalized.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_HOLDER_REQUIRED",
                    "Account holder name must contain valid alphanumeric characters.");
        }
        return normalized;
    }

    public String sanitizeNote(String rawNote) {
        if (rawNote == null || rawNote.isBlank()) {
            return null;
        }
        String clean = rawNote.trim();
        if (clean.length() > 255) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "BANK_NOTE_TOO_LONG",
                    "Bank note must not exceed 255 characters.");
        }
        return clean;
    }

    private void requireAuthenticatedUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_AUTHENTICATED", "Authentication required.");
        }
    }

    private CustomerBankAccount requireOwnedAccount(Long userId, Long accountId) {
        CustomerBankAccount acc = bankAccountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BANK_ACCOUNT_NOT_FOUND", "Saved bank account not found."));
        if (acc.getUser() == null || !acc.getUser().getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "BANK_ACCOUNT_ACCESS_DENIED",
                    "Access denied: you do not own this saved bank account.");
        }
        return acc;
    }

    private CustomerBankAccountResponseDto mapToDto(CustomerBankAccount acc) {
        CustomerBankAccountResponseDto dto = new CustomerBankAccountResponseDto();
        dto.setId(acc.getId());
        dto.setBankBin(acc.getBankBin());
        dto.setBankName(acc.getBankName());
        dto.setAccountNumberMasked(acc.getAccountNumberMasked());
        dto.setAccountHolderName(acc.getAccountHolderName());
        dto.setNote(acc.getNote());
        dto.setDefault(acc.isDefault());
        dto.setCreatedAt(acc.getCreatedAt());
        dto.setUpdatedAt(acc.getUpdatedAt());
        return dto;
    }
}
