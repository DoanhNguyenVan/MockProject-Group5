/**
 * BOX Cinema - Module 01 Form Validation & Social OAuth2 Handler (Login, Sign Up & Contact)
 */

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const PHONE_REGEX = /^\+?[0-9\s-]{9,15}$/;

const GOOGLE_SVG = `
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path fill="#EA4335" d="M12 5c1.6 0 3 .6 4.1 1.7l3.1-3.1C17.3 1.8 14.8 1 12 1 7.4 1 3.5 3.6 1.6 7.4l3.7 2.8C6.2 7.2 8.9 5 12 5z"/>
    <path fill="#4285F4" d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.5h6.5c-.3 1.5-1.1 2.8-2.4 3.6l3.7 2.9c2.2-2 3.7-5 3.7-8.7z"/>
    <path fill="#FBBC05" d="M5.3 14.8c-.2-.8-.4-1.6-.4-2.5s.2-1.7.4-2.5L1.6 7C.6 9 0 11.2 0 13.5s.6 4.5 1.6 6.5l3.7-2.9z" transform="translate(0 -1.2)"/>
    <path fill="#34A853" d="M12 23c3.2 0 6-1.1 8-3l-3.7-2.9c-1.1.7-2.5 1.2-4.3 1.2-3.1 0-5.8-2.1-6.7-5l-3.7 2.9C3.5 20.4 7.4 23 12 23z"/>
  </svg>
`;

const FACEBOOK_SVG = `
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path fill="#1877F2" d="M24 12.073c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.99 4.388 10.954 10.125 11.854v-8.385H7.078v-3.47h3.047V9.43c0-3.007 1.792-4.669 4.533-4.669 1.312 0 2.686.235 2.686.235v2.953H15.83c-1.491 0-1.956.925-1.956 1.874v2.25h3.328l-.532 3.47h-2.796v8.385C19.612 23.027 24 18.062 24 12.073z"/>
  </svg>
`;

const SOCIAL_PRESETS = {
  GOOGLE: [
    {
      providerId: "gg_1092837465",
      fullName: "Alex Nguyen",
      email: "alex.nguyen.cinema@gmail.com",
      initials: "AN"
    },
    {
      providerId: "gg_2093847561",
      fullName: "Minh Anh Tran",
      email: "minhanh.box@gmail.com",
      initials: "MA"
    }
  ],
  FACEBOOK: [
    {
      providerId: "fb_8837465912",
      fullName: "Hoang Nam Le",
      email: "hoangnam.fb@facebook.com",
      initials: "HN"
    },
    {
      providerId: "fb_7748291034",
      fullName: "Thao Vy Pham",
      email: "thaovy.fb@facebook.com",
      initials: "TV"
    }
  ]
};

export function isValidEmail(email) {
  return EMAIL_REGEX.test(String(email || "").trim());
}

export function isValidPhone(phone) {
  return PHONE_REGEX.test(String(phone || "").trim());
}

export function validateLoginFormData({ email, password }) {
  const errors = {};
  if (!isValidEmail(email)) {
    errors.email = "Please enter a valid email address (e.g., alex@boxcinema.com).";
  }
  if (!password || String(password).trim().length < 6) {
    errors.password = "Password must be at least 6 characters long.";
  }
  return {
    isValid: Object.keys(errors).length === 0,
    errors
  };
}

export function validateRegisterFormData({
  fullName,
  email,
  phone,
  password,
  confirmPassword,
  termsAccepted
}) {
  const errors = {};
  if (!fullName || String(fullName).trim().length < 2) {
    errors.fullName = "Please enter your full name (at least 2 characters).";
  }
  if (!isValidEmail(email)) {
    errors.email = "Please enter a valid email address.";
  }
  if (!isValidPhone(phone)) {
    errors.phone = "Please enter a valid phone number (9 to 15 digits).";
  }
  if (!password || String(password).length < 6) {
    errors.password = "Password must be at least 6 characters long.";
  }
  if (password !== confirmPassword || !confirmPassword) {
    errors.confirmPassword = "Passwords do not match.";
  }
  if (!termsAccepted) {
    errors.terms = "You must agree to the BOX Cinema Terms of Service & Privacy Policy.";
  }
  return {
    isValid: Object.keys(errors).length === 0,
    errors
  };
}

export function validateContactFormData({ fullName, email, topic, message }) {
  const errors = {};
  if (!fullName || String(fullName).trim().length < 2) {
    errors.fullName = "Please enter your full name.";
  }
  if (!isValidEmail(email)) {
    errors.email = "Please enter a valid email address so we can reply.";
  }
  if (!topic || String(topic).trim() === "") {
    errors.topic = "Please select an inquiry topic.";
  }
  if (!message || String(message).trim().length < 10) {
    errors.message = "Message must contain at least 10 characters.";
  }
  return {
    isValid: Object.keys(errors).length === 0,
    errors
  };
}

function setFieldFeedback(doc, inputId, errorId, message) {
  const inputEl = doc.getElementById(inputId);
  const errorEl = doc.getElementById(errorId);
  if (inputEl) {
    inputEl.setAttribute("aria-describedby", errorId);
    if (message) {
      inputEl.classList.add("is-invalid-dark");
      inputEl.setAttribute("aria-invalid", "true");
    } else {
      inputEl.classList.remove("is-invalid-dark");
      inputEl.removeAttribute("aria-invalid");
    }
  }
  if (errorEl) {
    errorEl.textContent = message || "";
    if (message) {
      errorEl.classList.add("visible");
    } else {
      errorEl.classList.remove("visible");
    }
  }
}

export function initPasswordToggles(doc = document) {
  const toggleButtons = doc.querySelectorAll("[data-toggle-password]");
  toggleButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      const targetId = btn.getAttribute("data-toggle-password");
      const targetInput = targetId ? doc.getElementById(targetId) : null;
      if (!targetInput) {
        return;
      }
      const isPassword = targetInput.getAttribute("type") === "password";
      targetInput.setAttribute("type", isPassword ? "text" : "password");
      btn.textContent = isPassword ? "Hide" : "Show";
    });
  });
}

function initQuickDemoPills(doc = document) {
  const pills = doc.querySelectorAll("[data-demo-email]");
  pills.forEach((pill) => {
    pill.addEventListener("click", () => {
      const emailInput = doc.getElementById("loginEmail");
      const passInput = doc.getElementById("loginPassword");
      if (emailInput && passInput) {
        emailInput.value = pill.getAttribute("data-demo-email") || "";
        passInput.value = pill.getAttribute("data-demo-pass") || "";
        setFieldFeedback(doc, "loginEmail", "loginEmailError", "");
        setFieldFeedback(doc, "loginPassword", "loginPasswordError", "");
        emailInput.focus();
      }
    });
  });
}

function initSocialOAuthModal(doc = document) {
  const modal = doc.getElementById("socialAuthModal");
  const closeBtn = doc.getElementById("closeSocialModalBtn");
  const modalTitle = doc.getElementById("socialModalTitle");
  const modalLogo = doc.getElementById("socialModalLogo");
  const quickList = doc.getElementById("socialQuickAccounts");
  const providerInput = doc.getElementById("socialProviderInput");
  const providerIdInput = doc.getElementById("socialProviderIdInput");
  const fullNameInput = doc.getElementById("socialFullName");
  const emailInput = doc.getElementById("socialEmail");
  const providerBtnLabel = doc.getElementById("socialProviderBtnLabel");
  const customForm = doc.getElementById("socialCustomForm");

  if (!modal || !customForm) {
    return;
  }

  const openModalForProvider = (providerName) => {
    const upper = String(providerName || "GOOGLE").toUpperCase();
    const isFacebook = upper === "FACEBOOK";
    const displayProvider = isFacebook ? "Facebook" : "Google";

    if (providerInput) providerInput.value = upper;
    if (providerIdInput) providerIdInput.value = "";
    if (modalTitle) modalTitle.textContent = `Continue with ${displayProvider}`;
    if (providerBtnLabel) providerBtnLabel.textContent = displayProvider;
    if (modalLogo) modalLogo.innerHTML = isFacebook ? FACEBOOK_SVG : GOOGLE_SVG;
    if (emailInput) {
      emailInput.placeholder = isFacebook ? "yourname@facebook.com" : "yourname@gmail.com";
      emailInput.value = "";
    }
    if (fullNameInput) {
      fullNameInput.value = "";
    }

    if (quickList) {
      quickList.innerHTML = "";
      const presets = SOCIAL_PRESETS[upper] || SOCIAL_PRESETS.GOOGLE;
      presets.forEach((acc) => {
        const btn = doc.createElement("button");
        btn.type = "button";
        btn.className = "social-account-item";
        btn.innerHTML = `
          <span class="social-avatar">${acc.initials}</span>
          <span class="social-account-meta">
            <span class="social-account-name">${acc.fullName}</span>
            <span class="social-account-email">${acc.email}</span>
          </span>
          <span class="small text-glow-red fw-bold">&#8594;</span>
        `;
        btn.addEventListener("click", () => {
          if (providerInput) providerInput.value = upper;
          if (providerIdInput) providerIdInput.value = acc.providerId;
          if (fullNameInput) fullNameInput.value = acc.fullName;
          if (emailInput) emailInput.value = acc.email;
          customForm.submit();
        });
        quickList.appendChild(btn);
      });
    }

    modal.classList.remove("d-none");
  };

  const socialButtons = doc.querySelectorAll("[data-social-provider]");
  socialButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      const provider = btn.getAttribute("data-social-provider") || "GOOGLE";
      const oauthConfigured = btn.getAttribute("data-oauth-configured") === "true";
      if (oauthConfigured && typeof window !== "undefined") {
        window.location.href = `/auth/oauth2/${provider.toLowerCase()}`;
        return;
      }
      openModalForProvider(provider);
    });
  });

  if (closeBtn) {
    closeBtn.addEventListener("click", () => modal.classList.add("d-none"));
  }

  modal.addEventListener("click", (e) => {
    if (e.target === modal) {
      modal.classList.add("d-none");
    }
  });
}

function initForgotPasswordModal(doc = document) {
  const openBtn = doc.getElementById("openForgotModalBtn");
  const modal = doc.getElementById("forgotPasswordModal");
  const closeBtn = doc.getElementById("closeForgotModalBtn");

  if (!openBtn || !modal) {
    return;
  }

  const alertBox = doc.getElementById("forgotModalAlert");
  const step1 = doc.getElementById("forgotStep1");
  const step2 = doc.getElementById("forgotStep2");
  const step3 = doc.getElementById("forgotStep3");
  const step4 = doc.getElementById("forgotStep4");

  const emailInput = doc.getElementById("resetEmail");
  const otpInput = doc.getElementById("resetOtpInput");
  const newPassInput = doc.getElementById("resetNewPassword");
  const confirmPassInput = doc.getElementById("resetConfirmPassword");

  const btnSendOtp = doc.getElementById("btnSendForgotOtp");
  const btnVerifyOtp = doc.getElementById("btnVerifyForgotOtp");
  const btnResendOtp = doc.getElementById("btnResendForgotOtp");
  const btnComplete = doc.getElementById("btnCompleteForgotReset");
  const btnFinish = doc.getElementById("btnFinishForgotSuccess");

  const countdownSpan = doc.getElementById("resendCountdownSpan");
  const countdownSec = doc.getElementById("resendCountdownSec");
  const displayTargetEmail = doc.getElementById("displayTargetEmail");

  let currentEmail = "";
  let currentResetToken = "";
  let cooldownTimer = null;

  function showAlert(type, message) {
    if (!alertBox) return;
    alertBox.className = `auth-alert auth-alert-${type} mb-3`;
    alertBox.textContent = message;
    alertBox.classList.remove("d-none");
  }

  function hideAlert() {
    if (!alertBox) return;
    alertBox.classList.add("d-none");
    alertBox.textContent = "";
  }

  function showStep(stepNum) {
    hideAlert();
    [step1, step2, step3, step4].forEach((s, idx) => {
      if (s) {
        if (idx + 1 === stepNum) {
          s.classList.remove("d-none");
        } else {
          s.classList.add("d-none");
        }
      }
    });
  }

  function startCooldown(seconds = 60) {
    if (cooldownTimer) clearInterval(cooldownTimer);
    if (!btnResendOtp || !countdownSpan || !countdownSec) return;

    btnResendOtp.disabled = true;
    btnResendOtp.classList.add("text-muted");
    btnResendOtp.classList.remove("text-glow-red");
    countdownSpan.classList.remove("d-none");
    countdownSec.textContent = String(seconds);

    let remaining = seconds;
    cooldownTimer = setInterval(() => {
      remaining -= 1;
      if (remaining <= 0) {
        clearInterval(cooldownTimer);
        cooldownTimer = null;
        btnResendOtp.disabled = false;
        btnResendOtp.classList.remove("text-muted");
        btnResendOtp.classList.add("text-glow-red");
        countdownSpan.classList.add("d-none");
      } else {
        countdownSec.textContent = String(remaining);
      }
    }, 1000);
  }

  openBtn.addEventListener("click", () => {
    const loginEmail = doc.getElementById("loginEmail")?.value || "";
    if (emailInput && loginEmail) {
      emailInput.value = loginEmail;
    }
    showStep(1);
    modal.classList.remove("d-none");
  });

  if (closeBtn) {
    closeBtn.addEventListener("click", () => {
      if (cooldownTimer) clearInterval(cooldownTimer);
      modal.classList.add("d-none");
    });
  }

  modal.addEventListener("click", (e) => {
    if (e.target === modal) {
      if (cooldownTimer) clearInterval(cooldownTimer);
      modal.classList.add("d-none");
    }
  });

  // Step 1: Send OTP
  if (btnSendOtp) {
    btnSendOtp.addEventListener("click", async () => {
      hideAlert();
      const email = emailInput?.value?.trim() || "";
      if (!email || !email.includes("@")) {
        showAlert("danger", "Please enter a valid email address.");
        return;
      }

      btnSendOtp.disabled = true;
      const originalText = btnSendOtp.innerHTML;
      btnSendOtp.innerHTML = "<span>Sending code...</span>";

      try {
        const res = await fetch("/api/auth/password-reset/request", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ email })
        });
        const data = await res.json();

        if (res.ok && data.success) {
          currentEmail = email;
          if (displayTargetEmail) displayTargetEmail.textContent = email;
          showStep(2);
          startCooldown(60);
          showAlert("success", data.message || "A verification code has been dispatched.");
        } else {
          showAlert("danger", data.message || "Failed to request verification code. Please try again.");
        }
      } catch (err) {
        showAlert("danger", "Connection error. Please try again.");
      } finally {
        btnSendOtp.disabled = false;
        btnSendOtp.innerHTML = originalText;
      }
    });
  }

  // Step 2: Verify OTP
  if (btnVerifyOtp) {
    btnVerifyOtp.addEventListener("click", async () => {
      hideAlert();
      const otp = otpInput?.value?.trim() || "";
      if (!otp || otp.length !== 6 || !/^\d{6}$/.test(otp)) {
        showAlert("danger", "Please enter the 6-digit numeric verification code.");
        return;
      }

      btnVerifyOtp.disabled = true;
      const originalText = btnVerifyOtp.innerHTML;
      btnVerifyOtp.innerHTML = "<span>Verifying code...</span>";

      try {
        const res = await fetch("/api/auth/password-reset/verify", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ email: currentEmail, otp })
        });
        const data = await res.json();

        if (res.ok && data.success && data.resetToken) {
          currentResetToken = data.resetToken;
          showStep(3);
          showAlert("success", "Code verified! Please create your new password.");
        } else {
          showAlert("danger", data.message || "Invalid or expired verification code.");
        }
      } catch (err) {
        showAlert("danger", "Connection error. Please try again.");
      } finally {
        btnVerifyOtp.disabled = false;
        btnVerifyOtp.innerHTML = originalText;
      }
    });
  }

  // Step 2: Resend OTP
  if (btnResendOtp) {
    btnResendOtp.addEventListener("click", async () => {
      hideAlert();
      btnResendOtp.disabled = true;

      try {
        const res = await fetch("/api/auth/password-reset/resend", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ email: currentEmail })
        });
        const data = await res.json();

        if (res.ok && data.success) {
          startCooldown(60);
          showAlert("success", data.message || "A fresh code has been sent.");
        } else {
          btnResendOtp.disabled = false;
          showAlert("danger", data.message || "Failed to resend code.");
        }
      } catch (err) {
        btnResendOtp.disabled = false;
        showAlert("danger", "Connection error while resending code.");
      }
    });
  }

  // Step 3: Complete Password Reset
  if (btnComplete) {
    btnComplete.addEventListener("click", async () => {
      hideAlert();
      const newPassword = newPassInput?.value || "";
      const confirmPassword = confirmPassInput?.value || "";

      if (newPassword.length < 8) {
        showAlert("danger", "Password must be at least 8 characters long.");
        return;
      }
      if (newPassword !== confirmPassword) {
        showAlert("danger", "New password and confirmation do not match.");
        return;
      }

      btnComplete.disabled = true;
      const originalText = btnComplete.innerHTML;
      btnComplete.innerHTML = "<span>Updating password...</span>";

      try {
        const res = await fetch("/api/auth/password-reset/complete", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            resetToken: currentResetToken,
            newPassword,
            confirmPassword
          })
        });
        const data = await res.json();

        if (res.ok && data.success) {
          showStep(4);
        } else {
          showAlert("danger", data.message || "Failed to reset password. Please try again.");
        }
      } catch (err) {
        showAlert("danger", "Connection error while updating password.");
      } finally {
        btnComplete.disabled = false;
        btnComplete.innerHTML = originalText;
      }
    });
  }

  // Step 4: Finish & Sign In
  if (btnFinish) {
    btnFinish.addEventListener("click", () => {
      modal.classList.add("d-none");
      const loginEmail = doc.getElementById("loginEmail");
      const loginPass = doc.getElementById("loginPassword");
      if (loginEmail && currentEmail) {
        loginEmail.value = currentEmail;
      }
      if (loginPass) {
        loginPass.value = "";
        loginPass.focus();
      }
    });
  }
}

function initOtpVerificationForm(doc = document) {
  const otpForm = doc.getElementById("verifyOtpForm");
  if (!otpForm) {
    return;
  }

  const digitInputs = Array.from(otpForm.querySelectorAll(".otp-digit-input"));
  const hiddenOtpInput = doc.getElementById("otpCodeHidden");
  const autoFillBtn = doc.getElementById("autoFillDemoOtpBtn");

  const syncHiddenOtp = () => {
    const combined = digitInputs.map((el) => (el.value || "").trim()).join("");
    if (hiddenOtpInput) {
      hiddenOtpInput.value = combined;
    }
    return combined;
  };

  const fillDigits = (codeStr) => {
    const clean = String(codeStr || "").replace(/\D/g, "").slice(0, 6);
    digitInputs.forEach((input, idx) => {
      input.value = clean[idx] || "";
    });
    syncHiddenOtp();
    const focusIdx = Math.min(clean.length, digitInputs.length - 1);
    digitInputs[focusIdx]?.focus();
  };

  digitInputs.forEach((input, index) => {
    input.addEventListener("input", (e) => {
      const val = e.target.value.replace(/\D/g, "");
      if (val.length > 1) {
        fillDigits(val);
        return;
      }
      e.target.value = val;
      syncHiddenOtp();
      if (val && index < digitInputs.length - 1) {
        digitInputs[index + 1].focus();
      }
    });

    input.addEventListener("keydown", (e) => {
      if (e.key === "Backspace" && !input.value && index > 0) {
        digitInputs[index - 1].focus();
      }
    });

    input.addEventListener("paste", (e) => {
      e.preventDefault();
      const pasted = (e.clipboardData || window.clipboardData)?.getData("text") || "";
      fillDigits(pasted);
    });
  });

  if (autoFillBtn) {
    autoFillBtn.addEventListener("click", () => {
      const demoOtp = autoFillBtn.getAttribute("data-otp") || "";
      fillDigits(demoOtp);
      setFieldFeedback(doc, "otpCodeHidden", "otpCodeError", "");
    });
  }

  digitInputs[0]?.focus();

  otpForm.addEventListener("submit", (event) => {
    const code = syncHiddenOtp();
    if (code.length !== 6) {
      event.preventDefault();
      setFieldFeedback(doc, "otpCodeHidden", "otpCodeError", "Please enter all 6 digits of the verification code.");
      digitInputs.find((el) => !el.value)?.focus();
      return;
    }
    const submitBtn = doc.getElementById("verifyOtpSubmitBtn");
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.textContent = "Verifying OTP...";
    }
  });
}

function initAuthPosterSlideshow(doc = document) {
  const container = doc.getElementById("authPosterSlideshow");
  if (!container) return;

  const getSlides = () => Array.from(container.querySelectorAll(".auth-poster-slide"));
  let slides = getSlides();
  if (slides.length <= 1) return;

  const requestedStart = Number.parseInt(container.getAttribute("data-start-index") || "0", 10);
  let currentIndex = Number.isNaN(requestedStart) ? 0 : (requestedStart % slides.length);

  slides.forEach((slide, idx) => {
    slide.classList.toggle("active", idx === currentIndex);
  });

  setInterval(() => {
    slides = getSlides();
    if (slides.length <= 1) {
      if (slides.length === 1) {
        slides[0].classList.add("active");
      }
      return;
    }

    if (currentIndex >= slides.length) {
      currentIndex = 0;
    }
    slides[currentIndex].classList.remove("active");
    currentIndex = (currentIndex + 1) % slides.length;
    slides[currentIndex].classList.add("active");
  }, 4000);
}

export function initAuthAndContactForms(doc = document) {
  initAuthPosterSlideshow(doc);
  initPasswordToggles(doc);
  initQuickDemoPills(doc);
  initSocialOAuthModal(doc);
  initForgotPasswordModal(doc);
  initOtpVerificationForm(doc);

  // 1. Login Form
  const loginForm = doc.getElementById("loginForm");
  if (loginForm) {
    loginForm.addEventListener("submit", (event) => {
      const email = doc.getElementById("loginEmail")?.value || "";
      const password = doc.getElementById("loginPassword")?.value || "";
      const statusBox = doc.getElementById("loginFormStatus");

      const result = validateLoginFormData({ email, password });
      setFieldFeedback(doc, "loginEmail", "loginEmailError", result.errors.email);
      setFieldFeedback(doc, "loginPassword", "loginPasswordError", result.errors.password);

      if (!result.isValid) {
        event.preventDefault();
        loginForm.querySelector('[aria-invalid="true"]')?.focus();
        if (statusBox) {
          statusBox.className = "auth-alert auth-alert-danger mb-3";
          statusBox.textContent = "Please fix the highlighted fields before signing in.";
          statusBox.classList.remove("d-none");
        }
        return;
      }

      if (statusBox) {
        statusBox.classList.add("d-none");
      }
      const submitBtn = doc.getElementById("loginSubmitBtn");
      if (submitBtn) {
        submitBtn.textContent = "Signing in...";
        setTimeout(() => { submitBtn.disabled = true; }, 50);
      }
    });
  }

  // 2. Register / Sign Up Form
  const registerForm = doc.getElementById("registerForm");
  if (registerForm) {
    registerForm.addEventListener("submit", (event) => {
      const fullName = doc.getElementById("regFullName")?.value || "";
      const email = doc.getElementById("regEmail")?.value || "";
      const phone = doc.getElementById("regPhone")?.value || "";
      const password = doc.getElementById("regPassword")?.value || "";
      const confirmPassword = doc.getElementById("regConfirmPassword")?.value || "";
      const termsAccepted = Boolean(doc.getElementById("regTerms")?.checked);
      const statusBox = doc.getElementById("registerFormStatus");

      const result = validateRegisterFormData({
        fullName,
        email,
        phone,
        password,
        confirmPassword,
        termsAccepted
      });

      setFieldFeedback(doc, "regFullName", "regFullNameError", result.errors.fullName);
      setFieldFeedback(doc, "regEmail", "regEmailError", result.errors.email);
      setFieldFeedback(doc, "regPhone", "regPhoneError", result.errors.phone);
      setFieldFeedback(doc, "regPassword", "regPasswordError", result.errors.password);
      setFieldFeedback(doc, "regConfirmPassword", "regConfirmPasswordError", result.errors.confirmPassword);
      setFieldFeedback(doc, "regTerms", "regTermsError", result.errors.terms);

      if (!result.isValid) {
        event.preventDefault();
        registerForm.querySelector('[aria-invalid="true"]')?.focus();
        if (statusBox) {
          statusBox.className = "auth-alert auth-alert-danger mb-3";
          statusBox.textContent = "Please review the required fields to complete your registration.";
          statusBox.classList.remove("d-none");
        }
        return;
      }

      if (statusBox) {
        statusBox.classList.add("d-none");
      }
      const submitBtn = doc.getElementById("registerSubmitBtn");
      if (submitBtn) {
        submitBtn.textContent = "Creating account...";
        setTimeout(() => { submitBtn.disabled = true; }, 50);
      }
    });
  }

  // 3. Contact Form
  const contactForm = doc.getElementById("contactForm");
  if (contactForm) {
    contactForm.addEventListener("submit", (event) => {
      const fullName = doc.getElementById("contactName")?.value || "";
      const email = doc.getElementById("contactEmail")?.value || "";
      const topic = doc.getElementById("contactTopic")?.value || "";
      const message = doc.getElementById("contactMessage")?.value || "";
      const statusBox = doc.getElementById("contactFormStatus");

      const result = validateContactFormData({ fullName, email, topic, message });
      setFieldFeedback(doc, "contactName", "contactNameError", result.errors.fullName);
      setFieldFeedback(doc, "contactEmail", "contactEmailError", result.errors.email);
      setFieldFeedback(doc, "contactTopic", "contactTopicError", result.errors.topic);
      setFieldFeedback(doc, "contactMessage", "contactMessageError", result.errors.message);

      if (!result.isValid) {
        event.preventDefault();
        contactForm.querySelector('[aria-invalid="true"]')?.focus();
        if (statusBox) {
          statusBox.className = "alert alert-danger py-2 px-3 small mb-3";
          statusBox.textContent = "Please fill in all required fields marked with an asterisk before submitting.";
          statusBox.classList.remove("d-none");
        }
        return;
      }

      if (statusBox) {
        statusBox.classList.add("d-none");
      }
      const submitBtn = contactForm.querySelector('button[type="submit"]');
      if (submitBtn) {
        submitBtn.textContent = "Sending message...";
        setTimeout(() => { submitBtn.disabled = true; }, 50);
      }
    });
  }
}

if (typeof document !== "undefined") {
  document.addEventListener("DOMContentLoaded", () => {
    initAuthAndContactForms(document);
  });
}
