/**
 * BOX Cinema AI Assistant Chatbot Client Module
 * Handles session storage, API requests to Spring Boot BFF, rendering, and quick chips.
 */

(function () {
  const SESSION_STORAGE_KEY = "box_cinema_chat_session_id";

  // Elements
  let launcherBtn = null;
  let chatContainer = null;
  let closeBtn = null;
  let messagesContainer = null;
  let typingIndicator = null;
  let chatInput = null;
  let sendBtn = null;
  let suggestionsContainer = null;

  let sessionId = null;
  let isSending = false;

  function initSessionId() {
    sessionId = sessionStorage.getItem(SESSION_STORAGE_KEY);
    if (!sessionId) {
      sessionId = "sess_" + Math.random().toString(36).substring(2, 11) + "_" + Date.now();
      sessionStorage.setItem(SESSION_STORAGE_KEY, sessionId);
    }
  }

  function formatTime(date) {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  function appendMessage(text, sender = "bot") {
    if (!messagesContainer) return;

    const msgEl = document.createElement("div");
    msgEl.className = `box-chat-msg ${sender}`;

    const bubbleEl = document.createElement("div");
    bubbleEl.className = "bubble";

    if (sender === "bot") {
      bubbleEl.innerHTML = parseBotText(text);
    } else {
      bubbleEl.textContent = text;
    }

    const timeEl = document.createElement("div");
    timeEl.className = "time";
    timeEl.textContent = formatTime(new Date());

    msgEl.appendChild(bubbleEl);
    msgEl.appendChild(timeEl);

    // Insert before typing indicator
    if (typingIndicator) {
      messagesContainer.insertBefore(msgEl, typingIndicator);
    } else {
      messagesContainer.appendChild(msgEl);
    }

    scrollToBottom();
  }

  function parseBotText(raw) {
    if (!raw) return "";
    // Sanitize basic HTML tags
    let text = raw.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

    // Markdown bold: **text** -> <strong>text</strong>
    text = text.replace(/\*\*(.*?)\*\*/g, "<strong>$1</strong>");

    // Markdown links: [title](url) -> <a href="url" target="_blank">title</a>
    text = text.replace(/\[(.*?)\]\((https?:\/\/[^\s]+|\/[^\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');

    // Line breaks
    text = text.replace(/\n/g, "<br>");

    return text;
  }

  function scrollToBottom() {
    if (messagesContainer) {
      messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }
  }

  function setTyping(visible) {
    if (typingIndicator) {
      if (visible) {
        typingIndicator.classList.add("is-visible");
      } else {
        typingIndicator.classList.remove("is-visible");
      }
      scrollToBottom();
    }
  }

  async function loadInitialConfig() {
    try {
      const res = await fetch("/api/chat/config");
      if (res.ok) {
        const data = await res.json();
        if (data.welcomeMessage) {
          appendMessage(data.welcomeMessage, "bot");
        }
        if (Array.isArray(data.quickSuggestions)) {
          renderSuggestions(data.quickSuggestions);
        }
      } else {
        appendMessage("Xin chào! Tôi là Trợ lý AI của BOX Cinema. Bạn cần hỗ trợ gì hôm nay?", "bot");
      }
    } catch (e) {
      appendMessage("Xin chào! Tôi là Trợ lý AI của BOX Cinema. Bạn cần hỗ trợ gì hôm nay?", "bot");
    }
  }

  function renderSuggestions(list) {
    if (!suggestionsContainer) return;
    suggestionsContainer.innerHTML = "";
    list.forEach((item) => {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "box-chat-chip";
      chip.textContent = item;
      chip.addEventListener("click", () => {
        handleUserSend(item);
      });
      suggestionsContainer.appendChild(chip);
    });
  }

  async function handleUserSend(messageText) {
    const text = (messageText || (chatInput ? chatInput.value : "")).trim();
    if (!text || isSending) return;

    if (chatInput) {
      chatInput.value = "";
    }

    appendMessage(text, "user");

    isSending = true;
    if (sendBtn) sendBtn.disabled = true;
    setTyping(true);

    try {
      const response = await fetch("/api/chat/message", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Accept": "application/json"
        },
        body: JSON.stringify({
          message: text,
          sessionId: sessionId
        })
      });

      setTyping(false);

      if (response.ok) {
        const data = await response.json();
        appendMessage(data.reply || "Tôi đã nhận được thông tin từ bạn.", "bot");
        if (Array.isArray(data.suggestions) && data.suggestions.length > 0) {
          renderSuggestions(data.suggestions);
        }
      } else {
        appendMessage("Rất tiếc, hệ thống đang bận. Bạn vui lòng thử lại sau giây lát!", "bot");
      }
    } catch (err) {
      setTyping(false);
      appendMessage("Không thể kết nối đến máy chủ trợ lý ảo. Vui lòng kiểm tra lại kết nối mạng!", "bot");
    } finally {
      // 2.5 seconds debounce before enabling send button
      setTimeout(() => {
        isSending = false;
        if (sendBtn) sendBtn.disabled = false;
        if (chatInput) chatInput.focus();
      }, 2500);
    }
  }

  function setupEventListeners() {
    launcherBtn = document.getElementById("boxChatLauncher");
    chatContainer = document.getElementById("boxChatContainer");
    closeBtn = document.getElementById("boxChatCloseBtn");
    messagesContainer = document.getElementById("boxChatMessages");
    typingIndicator = document.getElementById("boxChatTyping");
    chatInput = document.getElementById("boxChatInput");
    sendBtn = document.getElementById("boxChatSendBtn");
    suggestionsContainer = document.getElementById("boxChatSuggestions");

    if (launcherBtn && chatContainer) {
      launcherBtn.addEventListener("click", () => {
        const isOpen = chatContainer.classList.contains("is-open");
        if (isOpen) {
          chatContainer.classList.remove("is-open");
          launcherBtn.classList.remove("is-active");
        } else {
          chatContainer.classList.add("is-open");
          launcherBtn.classList.add("is-active");
          if (chatInput) chatInput.focus();
        }
      });
    }

    if (closeBtn && chatContainer) {
      closeBtn.addEventListener("click", () => {
        chatContainer.classList.remove("is-open");
        if (launcherBtn) launcherBtn.classList.remove("is-active");
      });
    }

    if (sendBtn) {
      sendBtn.addEventListener("click", () => handleUserSend());
    }

    if (chatInput) {
      chatInput.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) {
          e.preventDefault();
          handleUserSend();
        }
      });
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    initSessionId();
    setupEventListeners();
    loadInitialConfig();
  });
})();
