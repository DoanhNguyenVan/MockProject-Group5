(function () {
  const MAX_BYTES = 5 * 1024 * 1024;
  const ALLOWED_TYPES = new Set(["image/jpeg", "image/jpg", "image/png", "image/webp"]);
  const DEFAULT_HINT = "JPG, PNG, WEBP · Max 5 MB";
  let activeUploads = 0;

  function getCsrfHeaders() {
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute("content") || "";
    const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute("content") || "X-CSRF-TOKEN";
    return token ? { [header]: token } : {};
  }

  function updateSubmitButtons() {
    document.querySelectorAll("[data-movie-media-picker]").forEach((picker) => {
      const form = picker.closest("form");
      if (!form) return;
      const submitBtn = form.querySelector('button[type="submit"]');
      if (submitBtn) {
        submitBtn.disabled = activeUploads > 0;
      }
    });
  }

  function showError(picker, message) {
    const errEl = picker.querySelector("[data-media-error]");
    if (!errEl) return;
    if (message) {
      errEl.textContent = message;
      errEl.classList.add("is-visible");
    } else {
      errEl.textContent = "";
      errEl.classList.remove("is-visible");
    }
  }

  function renderPreview(picker, imageUrl, metaHtml) {
    const urlInput = picker.querySelector("[data-media-url]");
    const imgEl = picker.querySelector("[data-media-preview-img]");
    const placeholderEl = picker.querySelector("[data-media-placeholder]");
    const clearBtn = picker.querySelector("[data-media-clear]");
    const btnLabelEl = picker.querySelector("[data-media-btn-label]");
    const metaEl = picker.querySelector("[data-media-meta]");

    const value = (imageUrl !== undefined ? imageUrl : urlInput?.value || "").trim();
    const hasValue = Boolean(value);

    if (imgEl && placeholderEl) {
      if (hasValue) {
        imgEl.src = value;
        imgEl.style.display = "block";
        placeholderEl.style.display = "none";
      } else {
        imgEl.removeAttribute("src");
        imgEl.style.display = "none";
        placeholderEl.style.display = "flex";
      }
    }
    if (clearBtn) {
      clearBtn.style.display = hasValue ? "inline-flex" : "none";
    }
    if (btnLabelEl) {
      btnLabelEl.textContent = hasValue ? "Change Photo" : "Upload Photo";
    }
    if (metaEl) {
      metaEl.innerHTML = metaHtml || `<span>${DEFAULT_HINT}</span>`;
    }
  }

  async function uploadFile(picker, file) {
    if (!file) return;
    showError(picker, "");

    if (!ALLOWED_TYPES.has((file.type || "").toLowerCase())) {
      showError(picker, "Only JPG, PNG, or WEBP images are supported.");
      return;
    }
    if (file.size > MAX_BYTES) {
      showError(picker, "Movie image must be 5 MB or smaller.");
      return;
    }

    const mediaType = picker.getAttribute("data-media-type") || "poster";
    const uploadUrl = picker.getAttribute("data-upload-url") || "/admin/movies/upload-media";
    const urlInput = picker.querySelector("[data-media-url]");
    const fileInput = picker.querySelector("[data-media-file]");
    const uploadBtn = picker.querySelector("[data-media-upload-btn]");

    const previewBlobUrl = URL.createObjectURL(file);
    activeUploads++;
    updateSubmitButtons();
    if (uploadBtn) uploadBtn.classList.add("is-busy");
    renderPreview(
      picker,
      previewBlobUrl,
      '<span class="admin-media-uploading"><span class="admin-media-spinner"></span> Uploading photo...</span>'
    );

    const formData = new FormData();
    formData.append("file", file);
    formData.append("mediaType", mediaType);

    try {
      const response = await fetch(uploadUrl, {
        method: "POST",
        headers: getCsrfHeaders(),
        body: formData
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success || !payload.url) {
        throw new Error(payload.message || "Unable to upload image. Please try again.");
      }
      if (urlInput) {
        urlInput.value = payload.url;
      }
      renderPreview(picker, payload.url, `<span>${DEFAULT_HINT}</span>`);
    } catch (err) {
      showError(picker, err?.message || "Unable to upload image.");
      renderPreview(picker, urlInput ? urlInput.value : "", `<span>${DEFAULT_HINT}</span>`);
    } finally {
      URL.revokeObjectURL(previewBlobUrl);
      if (fileInput) fileInput.value = "";
      if (uploadBtn) uploadBtn.classList.remove("is-busy");
      activeUploads = Math.max(0, activeUploads - 1);
      updateSubmitButtons();
    }
  }

  function wirePicker(picker) {
    if (picker.dataset.wired === "1") return;
    picker.dataset.wired = "1";

    const fileInput = picker.querySelector("[data-media-file]");
    const urlInput = picker.querySelector("[data-media-url]");
    const previewBox = picker.querySelector("[data-media-preview-box]");
    const clearBtn = picker.querySelector("[data-media-clear]");
    const imgEl = picker.querySelector("[data-media-preview-img]");
    const placeholderEl = picker.querySelector("[data-media-placeholder]");

    if (imgEl && placeholderEl) {
      imgEl.addEventListener("error", () => {
        imgEl.style.display = "none";
        placeholderEl.style.display = "flex";
      });
    }

    if (previewBox && fileInput) {
      previewBox.addEventListener("click", () => fileInput.click());
    }

    if (fileInput) {
      fileInput.addEventListener("change", () => {
        const file = fileInput.files && fileInput.files[0];
        if (file) uploadFile(picker, file);
      });
    }

    if (urlInput) {
      urlInput.addEventListener("input", () => {
        showError(picker, "");
        renderPreview(picker);
      });
    }

    if (clearBtn) {
      clearBtn.addEventListener("click", () => {
        showError(picker, "");
        if (fileInput) fileInput.value = "";
        if (urlInput) urlInput.value = "";
        renderPreview(picker, "", `<span>${DEFAULT_HINT}</span>`);
      });
    }

    picker.addEventListener("dragover", (e) => {
      e.preventDefault();
      picker.classList.add("is-dragover");
    });

    picker.addEventListener("dragleave", () => {
      picker.classList.remove("is-dragover");
    });

    picker.addEventListener("drop", (e) => {
      e.preventDefault();
      picker.classList.remove("is-dragover");
      const file = e.dataTransfer?.files?.[0];
      if (file) uploadFile(picker, file);
    });

    renderPreview(picker);
  }

  function syncAll() {
    document.querySelectorAll("[data-movie-media-picker]").forEach((picker) => {
      wirePicker(picker);
      showError(picker, "");
      const fileInput = picker.querySelector("[data-media-file]");
      if (fileInput) fileInput.value = "";
      renderPreview(picker);
    });
  }

  document.addEventListener("DOMContentLoaded", syncAll);
  window.BoxAdminMovieMedia = { syncAll };
})();
