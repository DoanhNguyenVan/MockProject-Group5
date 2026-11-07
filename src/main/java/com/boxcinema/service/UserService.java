package com.boxcinema.service;

import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;

public interface UserService {
    UserSummaryDto registerCustomer(RegisterRequestDto request);
    UserSummaryDto authenticate(String email, String rawPassword);
}

