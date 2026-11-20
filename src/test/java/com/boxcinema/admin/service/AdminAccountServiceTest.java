package com.boxcinema.admin.service;

import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.common.service.SessionManagementService;
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

    @Test
    void updatesRoleAndCinemaAndRevokesSessionWhenRoleChanges() {
        User u = user(8L, "CUSTOMER", "ACTIVE");
        when(repository.findByIdForUpdate(8L)).thenReturn(Optional.of(u));
        when(repository.existsByPhoneAndUserIdNot("0911223344", 8L)).thenReturn(false);

        service.updateAccount(8L, "Updated Staff", "0911223344", "STAFF", 2L, 1L);

        assertEquals("Updated Staff", u.getFullName());
        assertEquals("0911223344", u.getPhone());
        assertEquals("STAFF", u.getRole());
        assertEquals(2L, u.getCinemaId());
        verify(repository).saveAndFlush(u);
        verify(sessions).invalidateUserSessions(8L);
    }

    @Test
    void cannotDemoteOwnAdminRole() {
        User selfAdmin = user(1L, "ADMIN", "ACTIVE");
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(selfAdmin));
        assertThrows(IllegalStateException.class,
                () -> service.updateAccount(1L, "Self Admin", "0900012345", "STAFF", null, 1L));
    }
}
