import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveActiveRoute } from '../../src/main/resources/static/js/modules/navbar.js';
import {
  isValidEmail,
  isValidPhone,
  validateLoginFormData,
  validateRegisterFormData,
  validateContactFormData
} from '../../src/main/resources/static/js/modules/auth-validation.js';

test('resolves Home, About, Contact, Login, and Register routes accurately', () => {
  assert.equal(resolveActiveRoute('/'), 'home');
  assert.equal(resolveActiveRoute('/home'), 'home');
  assert.equal(resolveActiveRoute('/showtimes'), 'showtimes');
  assert.equal(resolveActiveRoute('/about'), 'about');
  assert.equal(resolveActiveRoute('/contact'), 'contact');
  assert.equal(resolveActiveRoute('/login'), 'login');
  assert.equal(resolveActiveRoute('/register'), 'register');
});

test('validates email and phone formats', () => {
  assert.equal(isValidEmail('alex@boxcinema.com'), true);
  assert.equal(isValidEmail('invalid-email'), false);
  assert.equal(isValidPhone('+84 901 234 567'), true);
  assert.equal(isValidPhone('123'), false);
});

test('validates Login form inputs', () => {
  assert.equal(validateLoginFormData({ email: 'alex@boxcinema.com', password: 'secretPassword' }).isValid, true);
  const invalid = validateLoginFormData({ email: 'bad', password: '' });
  assert.equal(invalid.isValid, false);
  assert.ok(invalid.errors.email);
  assert.ok(invalid.errors.password);
});

test('validates Sign Up / Register form inputs including password match and terms', () => {
  const valid = validateRegisterFormData({
    fullName: 'Alex Nguyen',
    email: 'alex@boxcinema.com',
    phone: '+84901234567',
    password: 'Password123!',
    confirmPassword: 'Password123!',
    termsAccepted: true
  });
  assert.equal(valid.isValid, true);

  const mismatch = validateRegisterFormData({
    fullName: 'Alex Nguyen',
    email: 'alex@boxcinema.com',
    phone: '+84901234567',
    password: 'Password123!',
    confirmPassword: 'DifferentPassword',
    termsAccepted: false
  });
  assert.equal(mismatch.isValid, false);
  assert.ok(mismatch.errors.confirmPassword);
  assert.ok(mismatch.errors.terms);
});

test('validates Contact form inputs', () => {
  const valid = validateContactFormData({
    fullName: 'Alex Nguyen',
    email: 'alex@boxcinema.com',
    topic: 'General Feedback',
    message: 'Great IMAX Laser experience at District 1!'
  });
  assert.equal(valid.isValid, true);
});

test('shared navbar and core templates retain BOX Cinema branding and navigation links', () => {
  const navbarHtml = fs.readFileSync(path.resolve(process.cwd(), 'src/main/resources/templates/fragments/navbar.html'), 'utf8');
  assert.ok(navbarHtml.includes('/assets/img/box-cinema-logo.png'));
  for (const href of ['href="/"', 'href="/about"', 'href="/contact"', 'href="/login"', 'href="/register"']) {
    assert.ok(navbarHtml.includes(href) || navbarHtml.includes(`th:${href}`), `navbar missing ${href}`);
  }
  for (const tpl of ['customer/home.html', 'customer/about.html', 'customer/contact.html', 'auth/login.html', 'auth/register.html']) {
    const html = fs.readFileSync(path.resolve(process.cwd(), 'src/main/resources/templates', tpl), 'utf8');
    assert.ok(html.includes('fragments/navbar') || html.includes('/assets/img/box-cinema-logo.png'), `${tpl} missing navbar/logo`);
  }
});
