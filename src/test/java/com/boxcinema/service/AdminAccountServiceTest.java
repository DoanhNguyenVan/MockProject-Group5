package com.boxcinema.service;

import com.boxcinema.entity.User;
import com.boxcinema.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAccountServiceTest {
    private UserRepository repository;
    private SessionManagementService sessions;
    private AdminAccountService service;

    @BeforeEach
    void setup() {
        repository = mock(UserRepository.class);
        sessions = mock(SessionManagementService.class);
        service = new AdminAccountService(repository, sessions);
    }

    private User user(long id, String role, String status) {
        User u = new User("Test User", "test@example.com", "0900012345", "hash", role);
        u.setUserId(id);
        u.setAccountStatus(status);
        return u;
    }

    @Test
    void filtersAndPaginatesWithoutExposingPassword() {
        when(repository.findAdminAccounts(eq("test"), eq("CUSTOMER"), eq("ACTIVE"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user(12L, "CUSTOMER", "ACTIVE"))));
        var result = service.findAccounts(" test ", "customer", "ACTIVE", 0);
        assertEquals(1, result.getTotalElements());
        assertEquals(12L, result.getContent().get(0).userId());
        assertEquals("test@example.com", result.getContent().get(0).email());
    }

    @Test
    void locksActiveAccountAndRevokesSession() {
        User u = user(4L, "CUSTOMER", "ACTIVE");
        when(repository.findByIdForUpdate(4L)).thenReturn(Optional.of(u));
        service.changeLockStatus(4L, true, 1L);
        assertEquals("LOCKED", u.getAccountStatus());
        verify(repository).saveAndFlush(u);
        verify(sessions).invalidateUserSessions(4L);
    }

    @Test
    void unlocksLockedAccountWithoutRevokingSession() {
        User u = user(4L, "CUSTOMER", "LOCKED");
        when(repository.findByIdForUpdate(4L)).thenReturn(Optional.of(u));
        service.changeLockStatus(4L, false, 1L);
        assertEquals("ACTIVE", u.getAccountStatus());
        verify(sessions, never()).invalidateUserSessions(anyLong());
    }

    @Test
    void cannotLockSelf() {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(1L, "ADMIN", "ACTIVE")));
        assertThrows(IllegalStateException.class, () -> service.changeLockStatus(1L, true, 1L));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void cannotLockLastActiveAdmin() {
        when(repository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(2L, "ADMIN", "ACTIVE")));
        when(repository.countByRoleIgnoreCaseAndAccountStatusIgnoreCase("ADMIN", "ACTIVE")).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> service.changeLockStatus(2L, true, 1L));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void cannotUnlockPendingAccount() {
        when(repository.findByIdForUpdate(5L)).thenReturn(Optional.of(user(5L, "CUSTOMER", "PENDING_VERIFICATION")));
        assertThrows(IllegalStateException.class, () -> service.changeLockStatus(5L, false, 1L));
        verify(repository, never()).saveAndFlush(any());
    }
}
