/**
 * BOX Cinema - Module 01 Form Validation & Social OAuth2 Handler (Login, Sign Up & Contact)
 */

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const PHONE_REGEX = /^\+?[0-9\s()-]{8,20}$/;
const PASSWORD_MIN_LENGTH = 6;
const PASSWORD_MAX_LENGTH = 128;
const HAS_UPPERCASE_REGEX = /\p{Lu}/u;
const HAS_NUMBER_REGEX = /\d/;
const HAS_SPECIAL_CHAR_REGEX = /[^\p{L}\p{N}\s]/u;

export function isValidPasswordPolicy(password) {
  const passStr = String(password ?? "");
  if (!passStr || !passStr.trim()) {
    return { valid: false, message: "Password cannot be empty or blank whitespace." };
  }
  const codePointLength = Array.from(passStr).length;
  if (codePointLength < PASSWORD_MIN_LENGTH) {
    return { valid: false, message: "Password must be at least 6 characters long." };
  }
  if (codePointLength > PASSWORD_MAX_LENGTH) {
    return { valid: false, message: "Password must not exceed 128 characters." };
  }
  if (!HAS_UPPERCASE_REGEX.test(passStr) || !HAS_NUMBER_REGEX.test(passStr) || !HAS_SPECIAL_CHAR_REGEX.test(passStr)) {
    return { valid: false, message: "Password must include at least 1 uppercase letter, 1 number, and 1 special character." };
  }
  return { valid: true, message: "" };
}

export function isValidEmail(email) {
  return EMAIL_REGEX.test(String(email || "").trim());
}

export function isValidPhone(phone) {
  const cleaned = String(phone || "").trim();
  const digitsOnly = cleaned.replace(/\D/g, "");
  return PHONE_REGEX.test(cleaned) && digitsOnly.length >= 8 && digitsOnly.length <= 15;
}

export function validateLoginFormData({ email, password }) {
  const errors = {};
  if (!isValidEmail(email)) {
    errors.email = "Please enter a valid email address (e.g., alex@boxcinema.com).";
  }
  if (!password || String(password).length < 1) {
    errors.password = "Please enter your password.";
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
    errors.phone = "Please enter a valid phone number (8 to 15 digits).";
  }
  const passCheck = isValidPasswordPolicy(password);
  if (!passCheck.valid) {
    errors.password = passCheck.message;
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
  const socialButtons = doc.querySelectorAll("[data-social-provider]");
  socialButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      const provider = String(btn.getAttribute("data-social-provider") || "GOOGLE").toUpperCase();
      if (provider !== "GOOGLE") {
        return;
      }
      if (typeof window !== "undefined") {
        window.location.href = "/oauth2/authorization/google";
      }
    });
  });
}

function getJsonHeaders(doc = document) {
  const headers = { "Content-Type": "application/json" };
  const csrfToken = doc.querySelector('meta[name="_csrf"]')?.getAttribute("content");
  const csrfHeader = doc.querySelector('meta[name="_csrf_header"]')?.getAttribute("content") || "X-CSRF-TOKEN";
  if (csrfToken) {
    headers[csrfHeader] = csrfToken;
  }
  return headers;
}

function createAlertIconSvg(doc, type) {
  const svgNs = "http://www.w3.org/2000/svg";
  const svg = doc.createElementNS(svgNs, "svg");
  svg.setAttribute("class", "auth-alert-icon");
  svg.setAttribute("width", "20");
  svg.setAttribute("height", "20");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "2");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");

  if (type === "success") {
    const path = doc.createElementNS(svgNs, "path");
    path.setAttribute("d", "M22 11.08V12a10 10 0 1 1-5.93-9.14");
    const polyline = doc.createElementNS(svgNs, "polyline");
    polyline.setAttribute("points", "22 4 12 14.01 9 11.01");
    svg.appendChild(path);
    svg.appendChild(polyline);
  } else {
    const circle = doc.createElementNS(svgNs, "circle");
    circle.setAttribute("cx", "12");
    circle.setAttribute("cy", "12");
    circle.setAttribute("r", "10");
    const line1 = doc.createElementNS(svgNs, "line");
    line1.setAttribute("x1", "12");
    line1.setAttribute("y1", "8");
    line1.setAttribute("x2", "12");
    line1.setAttribute("y2", "12");
    const line2 = doc.createElementNS(svgNs, "line");
    line2.setAttribute("x1", "12");
    line2.setAttribute("y1", "16");
    line2.setAttribute("x2", "12.01");
    line2.setAttribute("y2", "16");
    svg.appendChild(circle);
    svg.appendChild(line1);
    svg.appendChild(line2);
  }

  return svg;
}

function buildOtpDispatchedMessage(actionLabel, isResend = false) {
  if (isResend) {
    return "New verification code sent — valid for 5 minutes.\nEnter the latest 6-digit code sent to your Gmail.";
  }
  return `Verification code sent — valid for 5 minutes.\nEnter the 6-digit code from your Gmail to ${actionLabel}.`;
}

function startResendCooldownTimer({
  buttonEl,
  countdownEl,
  secondsEl,
  seconds,
  existingTimer = null,
  onFinish
}) {
  if (existingTimer) {
    clearInterval(existingTimer);
  }
  if (!buttonEl || !countdownEl || !secondsEl || !seconds || seconds <= 0) {
    return null;
  }

  let remaining = seconds;
  buttonEl.disabled = true;
  countdownEl.classList.remove("d-none");
  secondsEl.textContent = String(remaining);

  const timer = setInterval(() => {
    remaining -= 1;
    if (remaining <= 0) {
      clearInterval(timer);
      buttonEl.disabled = false;
      countdownEl.classList.add("d-none");
      onFinish?.();
    } else {
      secondsEl.textContent = String(remaining);
    }
  }, 1000);

  return timer;
}

function bindOtpDigitGrid(doc, {
  containerEl,
  hiddenInputEl,
  errorId,
  onEnter
}) {
  const digitInputs = containerEl
    ? Array.from(containerEl.querySelectorAll(".otp-digit-input"))
    : [];

  const clearInvalid = () => {
    digitInputs.forEach((input) => input.classList.remove("is-invalid"));
    if (errorId) {
      setFieldFeedback(doc, hiddenInputEl?.id || "", errorId, "");
    }
  };

  const sync = () => {
    const combined = digitInputs.map((el) => (el.value || "").trim()).join("");
    if (hiddenInputEl) {
      hiddenInputEl.value = combined;
    }
    return combined;
  };

  const focusFirst = () => {
    digitInputs[0]?.focus();
  };

  const focusFirstEmpty = () => {
    const emptyEl = digitInputs.find((el) => !el.value) || digitInputs[digitInputs.length - 1];
    emptyEl?.focus();
  };

  const fillDigits = (codeStr) => {
    const clean = String(codeStr || "").replace(/\D/g, "").slice(0, digitInputs.length);
    digitInputs.forEach((input, idx) => {
      input.value = clean[idx] || "";
    });
    clearInvalid();
    sync();
    if (digitInputs.length > 0) {
      const focusIdx = Math.min(clean.length, digitInputs.length - 1);
      digitInputs[focusIdx]?.focus();
    }
  };

  const clear = () => {
    digitInputs.forEach((input) => {
      input.value = "";
    });
    clearInvalid();
    sync();
  };

  const setInvalid = (message) => {
    digitInputs.forEach((input) => {
      if (!input.value) {
        input.classList.add("is-invalid");
      }
    });
    if (digitInputs.length > 0 && digitInputs.every((el) => Boolean(el.value))) {
      digitInputs.forEach((input) => input.classList.add("is-invalid"));
    }
    if (errorId && message) {
      setFieldFeedback(doc, hiddenInputEl?.id || "", errorId, message);
    }
    focusFirstEmpty();
  };

  digitInputs.forEach((input, index) => {
    input.addEventListener("input", (e) => {
      const val = e.target.value.replace(/\D/g, "");
      if (val.length > 1) {
        fillDigits(val);
        return;
      }
      e.target.value = val;
      clearInvalid();
      sync();
      if (val && index < digitInputs.length - 1) {
        digitInputs[index + 1].focus();
      }
    });

    input.addEventListener("keydown", (e) => {
      if (e.key === "Backspace" && !input.value && index > 0) {
        digitInputs[index - 1].focus();
      } else if (e.key === "ArrowLeft" && index > 0) {
        e.preventDefault();
        digitInputs[index - 1].focus();
      } else if (e.key === "ArrowRight" && index < digitInputs.length - 1) {
        e.preventDefault();
        digitInputs[index + 1].focus();
      } else if (e.key === "Enter" && typeof onEnter === "function") {
        e.preventDefault();
        onEnter();
      }
    });

    input.addEventListener("paste", (e) => {
      e.preventDefault();
      const pasted = (e.clipboardData || window.clipboardData)?.getData("text") || "";
      fillDigits(pasted);
    });
  });

  return {
    digitInputs,
    sync,
    fillDigits,
    clear,
    clearInvalid,
    focusFirst,
    focusFirstEmpty,
    setInvalid
  };
}

function initForgotPasswordModal(doc = document) {
  const openBtn = doc.getElementById("openForgotModalBtn");
  const modal = doc.getElementById("forgotPasswordModal");
  const closeBtn = doc.getElementById("closeForgotModalBtn");

  if (!openBtn || !modal) {
    return;
  }

  const alertBox = doc.getElementById("forgotModalAlert");
  const step2Header = doc.getElementById("forgotStep2Header");
  const step1 = doc.getElementById("forgotStep1");
  const step2 = doc.getElementById("forgotStep2");
  const step3 = doc.getElementById("forgotStep3");
  const step4 = doc.getElementById("forgotStep4");

  const emailInput = doc.getElementById("resetEmail");
  const otpInput = doc.getElementById("resetOtpInput");
  const otpDigitGrid = doc.getElementById("forgotOtpDigitGrid");
  const newPassInput = doc.getElementById("resetNewPassword");
  const confirmPassInput = doc.getElementById("resetConfirmPassword");

  const btnSendOtp = doc.getElementById("btnSendForgotOtp");
  const btnVerifyOtp = doc.getElementById("btnVerifyForgotOtp");
  const btnResendOtp = doc.getElementById("btnResendForgotOtp");
  const btnBackToEmail = doc.getElementById("btnBackToForgotEmail");
  const btnComplete = doc.getElementById("btnCompleteForgotReset");
  const btnFinish = doc.getElementById("btnFinishForgotSuccess");

  const countdownSpan = doc.getElementById("resendCountdownSpan");
  const countdownSec = doc.getElementById("resendCountdownSec");
  const displayTargetEmail = doc.getElementById("displayTargetEmail");

  let currentEmail = "";
  let currentResetToken = "";
  let cooldownTimer = null;

  const otpGridApi = otpDigitGrid
    ? bindOtpDigitGrid(doc, {
        containerEl: otpDigitGrid,
        hiddenInputEl: otpInput,
        errorId: "resetOtpError",
        onEnter: () => btnVerifyOtp?.click()
      })
    : null;

  function showAlert(type, message) {
    if (!alertBox) return;
    alertBox.className = `auth-alert auth-alert-${type} mb-3`;
    const iconBox = doc.createElement("span");
    iconBox.className = "auth-alert-icon-box";
    iconBox.setAttribute("aria-hidden", "true");
    iconBox.appendChild(createAlertIconSvg(doc, type));

    const body = doc.createElement("div");
    body.className = "auth-alert-body";
    const lines = String(message || "").split("\n").filter(Boolean);
    const titleEl = doc.createElement("span");
    titleEl.className = "auth-alert-title";
    titleEl.textContent = lines[0] || "";
    body.appendChild(titleEl);

    if (lines.length > 1) {
      const descEl = doc.createElement("span");
      descEl.className = "auth-alert-desc";
      descEl.textContent = lines.slice(1).join(" ");
      body.appendChild(descEl);
    }

    alertBox.textContent = "";
    alertBox.appendChild(iconBox);
    alertBox.appendChild(body);
    alertBox.classList.remove("d-none");
  }

  function hideAlert() {
    if (!alertBox) return;
    alertBox.classList.add("d-none");
    alertBox.textContent = "";
  }

  function stopCooldown() {
    if (cooldownTimer) {
      clearInterval(cooldownTimer);
      cooldownTimer = null;
    }
    if (btnResendOtp) {
      btnResendOtp.disabled = false;
    }
    countdownSpan?.classList.add("d-none");
  }

  function showStep(stepNum) {
    hideAlert();
    if (step2Header) {
      step2Header.classList.toggle("d-none", stepNum !== 2);
    }
    [step1, step2, step3, step4].forEach((s, idx) => {
      if (s) {
        if (idx + 1 === stepNum) {
          s.classList.remove("d-none");
        } else {
          s.classList.add("d-none");
        }
      }
    });
    if (stepNum === 2 && otpGridApi) {
      otpGridApi.clear();
      otpGridApi.focusFirst();
    }
  }

  function startCooldown(seconds = 60) {
    cooldownTimer = startResendCooldownTimer({
      buttonEl: btnResendOtp,
      countdownEl: countdownSpan,
      secondsEl: countdownSec,
      seconds,
      existingTimer: cooldownTimer,
      onFinish: () => {
        cooldownTimer = null;
      }
    });
  }

  const openForgotModal = () => {
    stopCooldown();
    otpGridApi?.clear();
    const loginEmail = doc.getElementById("loginEmail")?.value || "";
    if (emailInput && loginEmail) {
      emailInput.value = loginEmail;
    }
    showStep(1);
    modal.classList.remove("d-none");
    emailInput?.focus();
  };

  openBtn.addEventListener("click", openForgotModal);
  if (typeof window !== "undefined" && new URLSearchParams(window.location.search).get("forgot") === "true") {
    openForgotModal();
  }

  emailInput?.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      btnSendOtp?.click();
    }
  });
  confirmPassInput?.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      btnComplete?.click();
    }
  });

  if (closeBtn) {
    closeBtn.addEventListener("click", () => {
      stopCooldown();
      modal.classList.add("d-none");
    });
  }

  modal.addEventListener("click", (e) => {
    if (e.target === modal) {
      stopCooldown();
      modal.classList.add("d-none");
    }
  });

  btnBackToEmail?.addEventListener("click", () => {
    stopCooldown();
    otpGridApi?.clear();
    showStep(1);
    emailInput?.focus();
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
        const res = await fetch("/api/auth/forgot-password", {
          method: "POST",
          headers: getJsonHeaders(doc),
          body: JSON.stringify({ email })
        });
        const data = await res.json();

        if (res.ok && data.success) {
          currentEmail = email;
          if (displayTargetEmail) displayTargetEmail.textContent = email;
          showStep(2);
          startCooldown(data.cooldownSeconds || 60);
          showAlert("success", buildOtpDispatchedMessage("reset your password", false));
        } else {
          if (data.cooldownSeconds) {
            currentEmail = email;
            if (displayTargetEmail) displayTargetEmail.textContent = email;
            startCooldown(data.cooldownSeconds);
          }
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
      const otp = otpGridApi ? otpGridApi.sync().trim() : (otpInput?.value?.trim() || "");
      if (!otp || otp.length !== 6 || !/^\d{6}$/.test(otp)) {
        otpGridApi?.setInvalid("Please enter all 6 digits of the verification code.");
        showAlert("danger", "Please enter the 6-digit numeric verification code.");
        return;
      }

      btnVerifyOtp.disabled = true;
      const originalText = btnVerifyOtp.innerHTML;
      btnVerifyOtp.innerHTML = "<span>Verifying code...</span>";

      try {
        const res = await fetch("/api/auth/forgot-password/verify", {
          method: "POST",
          headers: getJsonHeaders(doc),
          body: JSON.stringify({ email: currentEmail, otp })
        });
        const data = await res.json();

        if (res.ok && data.success && data.resetToken) {
          currentResetToken = data.resetToken;
          stopCooldown();
          showStep(3);
          showAlert("success", "Code verified! Please create your new password.");
        } else {
          otpGridApi?.setInvalid(data.message || "Invalid or expired verification code.");
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
        const res = await fetch("/api/auth/forgot-password/resend", {
          method: "POST",
          headers: getJsonHeaders(doc),
          body: JSON.stringify({ email: currentEmail })
        });
        const data = await res.json();

        if (res.ok && data.success) {
          otpGridApi?.clear();
          otpGridApi?.focusFirst();
          startCooldown(data.cooldownSeconds || 60);
          showAlert("success", buildOtpDispatchedMessage("reset your password", true));
        } else {
          if (data.cooldownSeconds) {
            startCooldown(data.cooldownSeconds);
          } else {
            btnResendOtp.disabled = false;
          }
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

      const passCheck = isValidPasswordPolicy(newPassword);
      if (!passCheck.valid) {
        showAlert("danger", passCheck.message);
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
        const res = await fetch("/api/auth/forgot-password/reset", {
          method: "POST",
          headers: getJsonHeaders(doc),
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

  const hiddenOtpInput = doc.getElementById("otpCodeHidden");
  const resendBtn = doc.getElementById("verifyResendBtn");
  const resendCountdown = doc.getElementById("verifyResendCountdown");
  const resendSec = doc.getElementById("verifyResendSec");

  if (resendBtn && resendCountdown && resendSec) {
    const initialCooldown = Number.parseInt(resendBtn.getAttribute("data-cooldown") || "0", 10);
    if (!Number.isNaN(initialCooldown) && initialCooldown > 0) {
      startResendCooldownTimer({
        buttonEl: resendBtn,
        countdownEl: resendCountdown,
        secondsEl: resendSec,
        seconds: initialCooldown
      });
    }
  }

  const otpGridApi = bindOtpDigitGrid(doc, {
    containerEl: otpForm.querySelector(".otp-digit-grid"),
    hiddenInputEl: hiddenOtpInput,
    errorId: "otpCodeError"
  });
  otpGridApi.focusFirst();

  otpForm.addEventListener("submit", (event) => {
    const code = otpGridApi.sync();
    if (code.length !== 6) {
      event.preventDefault();
      otpGridApi.setInvalid("Please enter all 6 digits of the verification code.");
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
    const regPassInput = doc.getElementById("regPassword");
    const regConfirmInput = doc.getElementById("regConfirmPassword");

    const validateRegPasswordField = (showEmptyError = false) => {
      if (!regPassInput) return;
      const val = regPassInput.value || "";
      if (!val && !showEmptyError) {
        setFieldFeedback(doc, "regPassword", "regPasswordError", "");
        return;
      }
      const policy = isValidPasswordPolicy(val);
      setFieldFeedback(doc, "regPassword", "regPasswordError", policy.valid ? "" : policy.message);
    };

    const validateRegConfirmField = (showEmptyError = false) => {
      if (!regConfirmInput) return;
      const confirmVal = regConfirmInput.value || "";
      const passVal = regPassInput?.value || "";
      if (!confirmVal && !showEmptyError) {
        setFieldFeedback(doc, "regConfirmPassword", "regConfirmPasswordError", "");
        return;
      }
      const mismatch = passVal !== confirmVal || !confirmVal;
      setFieldFeedback(doc, "regConfirmPassword", "regConfirmPasswordError", mismatch ? "Passwords do not match." : "");
    };

    regPassInput?.addEventListener("blur", () => {
      if (regPassInput.value) {
        validateRegPasswordField(false);
      }
    });

    regPassInput?.addEventListener("input", () => {
      if (regPassInput.classList.contains("is-invalid-dark")) {
        validateRegPasswordField(true);
      }
      if (regConfirmInput?.classList.contains("is-invalid-dark")) {
        validateRegConfirmField(true);
      }
    });

    regConfirmInput?.addEventListener("blur", () => {
      if (regConfirmInput.value) {
        validateRegConfirmField(false);
      }
    });

    regConfirmInput?.addEventListener("input", () => {
      if (regConfirmInput.classList.contains("is-invalid-dark")) {
        validateRegConfirmField(true);
      }
    });

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

  // 4. Complete Profile Form (Google Registration Onboarding)
  const completeProfileForm = doc.getElementById("completeProfileForm");
  if (completeProfileForm) {
    completeProfileForm.addEventListener("submit", (event) => {
      const fullName = doc.getElementById("completeFullName")?.value || "";
      const phone = doc.getElementById("phoneInput")?.value || "";
      let valid = true;

      if (String(fullName).trim().length < 2) {
        setFieldFeedback(doc, "completeFullName", "completeFullNameError", "Please enter your full name (at least 2 characters).");
        valid = false;
      } else {
        setFieldFeedback(doc, "completeFullName", "completeFullNameError", "");
      }

      if (!isValidPhone(phone)) {
        setFieldFeedback(doc, "phoneInput", "completePhoneError", "Please enter a valid phone number (8 to 15 digits).");
        valid = false;
      } else {
        setFieldFeedback(doc, "phoneInput", "completePhoneError", "");
      }

      if (!valid) {
        event.preventDefault();
        completeProfileForm.querySelector('[aria-invalid="true"]')?.focus();
      }
    });
  }
}

if (typeof document !== "undefined") {
  document.addEventListener("DOMContentLoaded", () => {
    initAuthAndContactForms(document);
  });
}
