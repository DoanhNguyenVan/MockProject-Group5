import { describe, it, expect } from "vitest";
import { resolveActiveRoute } from "../../src/main/resources/static/js/modules/navbar.js";
import {
  isValidEmail,
  isValidPhone,
  validateLoginFormData,
  validateRegisterFormData,
  validateContactFormData
} from "../../src/main/resources/static/js/modules/auth-validation.js";
import { renderTemplate } from "../../scripts/demo-server.mjs";

describe("Module 01 - Navbar Route Resolution", () => {
  it("resolves Home, About, Contact, Login, and Register routes accurately", () => {
    expect(resolveActiveRoute("/")).toBe("home");
    expect(resolveActiveRoute("/home")).toBe("home");
    expect(resolveActiveRoute("/about")).toBe("about");
    expect(resolveActiveRoute("/contact")).toBe("contact");
    expect(resolveActiveRoute("/login")).toBe("login");
    expect(resolveActiveRoute("/register")).toBe("register");
  });
});

describe("Module 01 - Auth & Contact Form Validation", () => {
  it("validates email and phone formats", () => {
    expect(isValidEmail("alex@boxcinema.com")).toBe(true);
    expect(isValidEmail("invalid-email")).toBe(false);
    expect(isValidPhone("+84 901 234 567")).toBe(true);
    expect(isValidPhone("123")).toBe(false);
  });

  it("validates Login form inputs", () => {
    expect(validateLoginFormData({ email: "alex@boxcinema.com", password: "secretPassword" }).isValid).toBe(true);
    const invalid = validateLoginFormData({ email: "bad", password: "123" });
    expect(invalid.isValid).toBe(false);
    expect(invalid.errors.email).toBeDefined();
    expect(invalid.errors.password).toBeDefined();
  });

  it("validates Sign Up / Register form inputs including password match and terms", () => {
    const valid = validateRegisterFormData({
      fullName: "Alex Nguyen",
      email: "alex@boxcinema.com",
      phone: "+84901234567",
      password: "Password123!",
      confirmPassword: "Password123!",
      termsAccepted: true
    });
    expect(valid.isValid).toBe(true);

    const mismatch = validateRegisterFormData({
      fullName: "Alex Nguyen",
      email: "alex@boxcinema.com",
      phone: "+84901234567",
      password: "Password123!",
      confirmPassword: "DifferentPassword",
      termsAccepted: false
    });
    expect(mismatch.isValid).toBe(false);
    expect(mismatch.errors.confirmPassword).toBeDefined();
    expect(mismatch.errors.terms).toBeDefined();
  });

  it("validates Contact form inputs", () => {
    const valid = validateContactFormData({
      fullName: "Alex Nguyen",
      email: "alex@boxcinema.com",
      topic: "General Feedback",
      message: "Great IMAX Laser experience at District 1!"
    });
    expect(valid.isValid).toBe(true);
  });
});

describe("Module 01 - Core Pages & Shared Brand Logo / Navbar Rendering", () => {
  const templates = [
    "customer/home.html",
    "customer/about.html",
    "customer/contact.html",
    "auth/login.html",
    "auth/register.html"
  ];

  it("renders all 5 core templates with BOX Cinema brand logo and navigation links", () => {
    for (const tpl of templates) {
      const html = renderTemplate(tpl);
      expect(html).toContain("/assets/img/box-cinema-logo.svg");
      expect(html).toContain('href="/"');
      expect(html).toContain('href="/about"');
      expect(html).toContain('href="/contact"');
      expect(html).toContain('href="/login"');
      expect(html).toContain('href="/register"');
    }
  });
});
