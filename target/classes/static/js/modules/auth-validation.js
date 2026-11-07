/**
 * BOX Cinema - Module 01 Form Validation (Login, Sign Up & Contact)
 */

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const PHONE_REGEX = /^\+?[0-9\s-]{9,15}$/;

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
  if (!password || String(password).length < 8) {
    errors.password = "Password must be at least 8 characters long.";
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

export function initAuthAndContactForms(doc = document) {
  initPasswordToggles(doc);

  // 1. Login Form
  const loginForm = doc.getElementById("loginForm");
  if (loginForm) {
    loginForm.addEventListener("submit", (event) => {
      event.preventDefault();
      const email = doc.getElementById("loginEmail")?.value || "";
      const password = doc.getElementById("loginPassword")?.value || "";
      const statusBox = doc.getElementById("loginFormStatus");

      const result = validateLoginFormData({ email, password });
      setFieldFeedback(doc, "loginEmail", "loginEmailError", result.errors.email);
      setFieldFeedback(doc, "loginPassword", "loginPasswordError", result.errors.password);
      loginForm.querySelector('[aria-invalid="true"]')?.focus();

      if (statusBox) {
        if (!result.isValid) {
          statusBox.className = "alert alert-danger py-2 px-3 small mb-3";
          statusBox.textContent = "Please fix the highlighted fields before signing in.";
          statusBox.classList.remove("d-none");
        } else {
          statusBox.className = "alert alert-success py-2 px-3 small mb-3";
          statusBox.textContent = "Your details passed the form check. Sign-in is only a preview in this demo.";
          statusBox.classList.remove("d-none");
        }
      }
    });
  }

  // 2. Register / Sign Up Form
  const registerForm = doc.getElementById("registerForm");
  if (registerForm) {
    registerForm.addEventListener("submit", (event) => {
      event.preventDefault();
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
      registerForm.querySelector('[aria-invalid="true"]')?.focus();

      if (statusBox) {
        if (!result.isValid) {
          statusBox.className = "alert alert-danger py-2 px-3 small mb-3";
          statusBox.textContent = "Please review the required fields to complete your registration.";
          statusBox.classList.remove("d-none");
        } else {
          statusBox.className = "alert alert-success py-2 px-3 small mb-3";
          statusBox.textContent = "Your details passed the form check. Account creation is only a preview in this demo.";
          statusBox.classList.remove("d-none");
        }
      }
    });
  }

  // 3. Contact Form
  const contactForm = doc.getElementById("contactForm");
  if (contactForm) {
    contactForm.addEventListener("submit", (event) => {
      event.preventDefault();
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
      contactForm.querySelector('[aria-invalid="true"]')?.focus();

      if (statusBox) {
        if (!result.isValid) {
          statusBox.className = "alert alert-danger py-2 px-3 small mb-3";
          statusBox.textContent = "Please complete all required fields before sending your message.";
          statusBox.classList.remove("d-none");
        } else {
          statusBox.className = "alert alert-success py-2 px-3 small mb-3";
          statusBox.textContent = "Your message is ready. This demo previews the form. To reach the cinema, use the email or phone link.";
          statusBox.classList.remove("d-none");
        }
      }
    });
  }
}

if (typeof document !== "undefined") {
  document.addEventListener("DOMContentLoaded", () => {
    initAuthAndContactForms(document);
  });
}
