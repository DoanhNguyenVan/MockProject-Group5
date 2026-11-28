export function trailerSource(value) {
  if (!value) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || url.username || url.password || url.port) return null;
    const host = url.hostname.toLowerCase();
    let id;
    if (host === "youtu.be") id = url.pathname.slice(1);
    else if (["youtube.com", "www.youtube.com", "m.youtube.com", "www.youtube-nocookie.com"].includes(host)) {
      id = url.pathname === "/watch" ? url.searchParams.get("v") : url.pathname.match(/^\/(?:embed|shorts)\/([^/]+)$/)?.[1];
    }
    if (id && /^[a-zA-Z0-9_-]{11}$/.test(id)) {
      return { embed: `https://www.youtube-nocookie.com/embed/${id}`, external: `https://www.youtube.com/watch?v=${id}` };
    }
    return { embed: null, external: url.href };
  } catch { return null; }
}

const STAR_SVG = '<svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true"><path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z"/></svg>';
const VERIFIED_BADGE_HTML = '<svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/></svg><span>Verified Viewer</span>';

const STAR_LABELS = {
  1: "1 / 5 · Poor",
  2: "2 / 5 · Fair",
  3: "3 / 5 · Good",
  4: "4 / 5 · Very Good",
  5: "5 / 5 · Masterpiece"
};

function renderStarsHtml(rating) {
  const rounded = Math.round(Number(rating) || 0);
  return [1, 2, 3, 4, 5]
    .map((i) => STAR_SVG.replace("<svg", `<svg class="${i <= rounded ? "" : "dim"}"`))
    .join("");
}

function getCsrfHeaders(doc) {
  const token = doc.querySelector('meta[name="_csrf"]')?.getAttribute("content");
  const header = doc.querySelector('meta[name="_csrf_header"]')?.getAttribute("content") || "X-CSRF-TOKEN";
  return token ? { [header]: token } : {};
}

export function initMovieDetails(doc = document) {
  const synopsis = doc.getElementById("movieSynopsis");
  const toggle = doc.getElementById("movieSynopsisToggle");
  if (synopsis && toggle) {
    const full = synopsis.textContent.trim();
    if (full.length > 320) {
      const excerpt = full.slice(0, 320).replace(/\s+\S*$/, "") + "…";
      synopsis.textContent = excerpt;
      toggle.hidden = false;
      toggle.addEventListener("click", () => {
        const expanded = toggle.getAttribute("aria-expanded") !== "true";
        toggle.setAttribute("aria-expanded", String(expanded));
        toggle.textContent = expanded ? "Show less" : "Read more";
        synopsis.textContent = expanded ? full : excerpt;
      });
    }
  }

  doc.querySelector(".movie-details-poster img")?.addEventListener("error", (event) => { event.target.hidden = true; });
  doc.querySelector(".md-hero-backdrop img")?.addEventListener("error", (event) => { event.target.hidden = true; });
  doc.querySelector(".md-trailer-backdrop-img")?.addEventListener("error", (event) => { event.target.hidden = true; });

  const trailer = doc.getElementById("movieTrailer");
  if (!trailer) return;
  const source = trailerSource(trailer.dataset.trailerUrl);
  if (!source) return;

  const external = doc.getElementById("movieTrailerExternal");
  if (external) {
    external.href = source.external;
    external.hidden = false;
  }

  const message = doc.getElementById("movieTrailerMessage");
  if (message) {
    message.textContent = source.embed
      ? "Play Official Trailer"
      : "Watch this trailer using Open on YouTube above.";
  }

  if (!source.embed) return;
  const play = doc.getElementById("movieTrailerPlay");
  const note = doc.getElementById("movieTrailerNote");
  if (note) note.hidden = false;
  if (!play) return;

  play.hidden = false;
  play.addEventListener("click", () => {
    const frame = doc.createElement("iframe");
    frame.src = source.embed + "?autoplay=1";
    frame.title = `${doc.getElementById("movieTitle")?.textContent || "Movie"} — trailer`;
    frame.allow = "autoplay; encrypted-media; picture-in-picture; fullscreen";
    frame.allowFullscreen = true;
    frame.referrerPolicy = "strict-origin-when-cross-origin";
    doc.getElementById("movieTrailerFrame")?.replaceChildren(frame);
    frame.focus();
  }, { once: true });
}

export function initMovieReviews(doc = document) {
  const section = doc.getElementById("movieReviews");
  const movieId = section?.dataset?.movieId;
  if (!section || !movieId) return;

  const el = (id) => doc.getElementById(id);
  let pickedRating = 0;
  let hoverRating = 0;
  let currentSummary = null;

  const paintStarPicker = () => {
    const target = hoverRating || pickedRating;
    section.querySelectorAll(".star-pick-btn").forEach((btn) => {
      const val = Number(btn.dataset.star);
      btn.classList.toggle("lit", val <= target);
      btn.setAttribute("aria-checked", String(val === pickedRating));
    });
    const hint = el("reviewStarHint");
    if (hint) hint.textContent = target ? STAR_LABELS[target] : "Select a rating";
  };

  const setFeedback = (message, type = "") => {
    const fb = el("reviewFormFeedback");
    if (!fb) return;
    if (!message) {
      fb.hidden = true;
      fb.textContent = "";
      fb.className = "review-form-feedback";
      return;
    }
    fb.hidden = false;
    fb.textContent = message;
    fb.className = `review-form-feedback ${type === "error" ? "is-error" : type === "success" ? "is-success" : ""}`;
  };

  const renderSummary = (summary) => {
    const total = Number(summary?.totalReviews || 0);
    const totalRatings = Number(summary?.totalRatings ?? total);
    const totalDiscussions = Number(summary?.totalDiscussions ?? 0);
    const avg = Number(summary?.averageRating || 0);
    const avgText = totalRatings > 0 ? avg.toFixed(1) : "0.0";

    const headerPill = el("movieHeaderRating");
    if (headerPill) {
      headerPill.hidden = false;
      el("headerAvgText").textContent = avgText;
      el("headerCountText").textContent = `(${totalRatings} ${totalRatings === 1 ? "rating" : "ratings"})`;
    }

    el("avgRatingNum").textContent = avgText;
    el("avgStarsDisplay").innerHTML = renderStarsHtml(avg);

    let summaryLabel = "No reviews yet — be the first to share!";
    if (totalRatings > 0 && totalDiscussions > 0) {
      summaryLabel = `Based on ${totalRatings} verified ${totalRatings === 1 ? "rating" : "ratings"} · ${totalDiscussions} ${totalDiscussions === 1 ? "discussion" : "discussions"}`;
    } else if (totalRatings > 0) {
      summaryLabel = `Based on ${totalRatings} verified ${totalRatings === 1 ? "rating" : "ratings"}`;
    } else if (totalDiscussions > 0) {
      summaryLabel = `${totalDiscussions} community ${totalDiscussions === 1 ? "discussion" : "discussions"} · No star ratings yet`;
    }
    el("totalReviewsLabel").textContent = summaryLabel;

    const bars = el("ratingBars");
    bars.replaceChildren();
    const counts = summary?.ratingCounts || {};
    [5, 4, 3, 2, 1].forEach((star) => {
      const count = Number(counts[star] ?? counts[String(star)] ?? 0);
      const pct = totalRatings > 0 ? Math.round((count / totalRatings) * 100) : 0;
      const row = doc.createElement("div");
      row.className = "rating-bar-row";
      row.innerHTML = `
        <span class="rating-bar-star">${star} ${STAR_SVG}</span>
        <div class="rating-bar-track"><div class="rating-bar-fill" style="width:${pct}%"></div></div>
        <span class="rating-bar-count">${count}</span>
      `;
      bars.append(row);
    });
  };

  const syncForm = (summary) => {
    const guestBox = el("reviewGuestBox");
    const alreadyBox = el("reviewAlreadyBox");
    const form = el("movieReviewForm");
    const starSection = el("starPickerSection");
    const ratedBar = el("ratedSummaryBar");
    const promptBox = el("emptyCommentPrompt");
    if (promptBox) promptBox.hidden = true;

    if (!summary?.authenticated) {
      guestBox.hidden = false;
      if (alreadyBox) alreadyBox.hidden = true;
      form.hidden = true;
      return;
    }

    guestBox.hidden = true;
    const mine = summary.myReview;
    const canAppendComment = Boolean(
      mine && summary.verifiedViewer && mine.rating != null && (!mine.comment || !mine.comment.trim())
    );

    if (mine && !canAppendComment) {
      form.hidden = true;
      if (alreadyBox) alreadyBox.hidden = false;
      return;
    }

    if (alreadyBox) alreadyBox.hidden = true;
    form.hidden = false;
    const commentEl = el("reviewCommentInput");
    commentEl.value = "";
    el("reviewCharCount").textContent = "0";
    pickedRating = 0;

    if (canAppendComment) {
      el("formVerifiedBadge").hidden = false;
      if (starSection) starSection.hidden = true;
      if (ratedBar) {
        ratedBar.hidden = false;
        el("ratedSummaryText").textContent = `You rated this movie ${mine.rating} / 5 ★`;
      }
      el("reviewFormHeading").textContent = "Share Your Thoughts";
      el("submitReviewBtn").textContent = "Add Comment";
      commentEl.placeholder = "Want to tell others what you liked or didn't like? Add your comment here...";
    } else if (!summary.verifiedViewer) {
      el("formVerifiedBadge").hidden = true;
      if (starSection) starSection.hidden = true;
      if (ratedBar) ratedBar.hidden = true;
      el("reviewFormHeading").textContent = "Join the Discussion";
      el("submitReviewBtn").textContent = "Post Comment";
      commentEl.placeholder = "Share your thoughts, expectations, or questions about this film...";
    } else {
      el("formVerifiedBadge").hidden = false;
      if (starSection) starSection.hidden = false;
      if (ratedBar) ratedBar.hidden = true;
      el("reviewFormHeading").textContent = "Rate & Review";
      el("submitReviewBtn").textContent = "Post Review";
      commentEl.placeholder = "Write your thoughts on the direction, performances, sound, and visuals...";
    }
    paintStarPicker();
  };

  const renderReviewsList = (summary) => {
    const container = el("movieReviewsList");
    const verifiedOnly = Boolean(el("verifiedOnlyCheck")?.checked);
    const items = (summary?.reviews || []).filter((r) => !verifiedOnly || r.verifiedViewer);

    container.replaceChildren();
    if (!items.length) {
      const empty = doc.createElement("div");
      empty.className = "review-empty-text";
      empty.textContent = verifiedOnly
        ? "No verified viewer reviews yet."
        : "No audience reviews yet. Share your thoughts above!";
      container.append(empty);
      return;
    }

    items.forEach((r) => {
      const card = doc.createElement("article");
      card.className = `review-item ${r.owner ? "is-mine" : ""}`;

      const top = doc.createElement("div");
      top.className = "review-item-top";

      const authorWrap = doc.createElement("div");
      authorWrap.className = "review-author-wrap";
      const avatar = doc.createElement("div");
      avatar.className = "review-avatar";
      if (r.authorAvatarUrl) {
        const img = doc.createElement("img");
        img.src = r.authorAvatarUrl;
        img.alt = r.authorName || "Reviewer";
        img.addEventListener("error", () => {
          avatar.textContent = r.authorInitial || "?";
        }, { once: true });
        avatar.append(img);
      } else {
        avatar.textContent = r.authorInitial || "?";
      }

      const meta = doc.createElement("div");
      const nameRow = doc.createElement("div");
      nameRow.className = "review-author-name";

      const nameSpan = doc.createElement("span");
      nameSpan.textContent = r.authorName || "Cinema Guest";
      nameRow.append(nameSpan);

      if (r.owner) {
        const you = doc.createElement("span");
        you.className = "review-you-tag";
        you.textContent = "You";
        nameRow.append(you);
      }
      if (r.verifiedViewer) {
        const vb = doc.createElement("span");
        vb.className = "verified-badge";
        vb.innerHTML = VERIFIED_BADGE_HTML;
        nameRow.append(vb);
      } else if (r.rating == null) {
        const db = doc.createElement("span");
        db.className = "review-discussion-tag";
        db.textContent = "Discussion";
        nameRow.append(db);
      }

      const dateDiv = doc.createElement("div");
      dateDiv.className = "review-date";
      dateDiv.textContent = `${r.formattedDate || ""}${r.edited ? " · Edited" : ""}`;
      meta.append(nameRow, dateDiv);
      authorWrap.append(avatar, meta);

      top.append(authorWrap);
      if (r.rating != null) {
        const stars = doc.createElement("div");
        stars.className = "stars-display";
        stars.innerHTML = renderStarsHtml(r.rating);
        top.append(stars);
      }
      card.append(top);

      if (r.comment && r.comment.trim()) {
        const body = doc.createElement("p");
        body.className = "review-body";
        body.textContent = r.comment;
        card.append(body);
      } else if (r.rating != null) {
        const note = doc.createElement("p");
        note.className = "review-rating-only-note";
        note.textContent = `Rated ${r.rating} / 5 stars (no written comment)`;
        card.append(note);
      }

      if (r.adminReply && r.adminReply.trim()) {
        const replyBox = doc.createElement("div");
        replyBox.className = "review-admin-reply";

        const replyHead = doc.createElement("div");
        replyHead.className = "review-admin-reply-head";
        const brandLabel = doc.createElement("strong");
        brandLabel.textContent = "Response from BOX Cinema";
        replyHead.append(brandLabel);
        if (r.formattedAdminRepliedAt) {
          const dateSpan = doc.createElement("span");
          dateSpan.textContent = r.formattedAdminRepliedAt;
          replyHead.append(dateSpan);
        }

        const replyText = doc.createElement("p");
        replyText.className = "review-admin-reply-body";
        replyText.textContent = r.adminReply;

        replyBox.append(replyHead, replyText);
        card.append(replyBox);
      }

      if (r.canDelete) {
        const actions = doc.createElement("div");
        actions.className = "review-actions";
        const delBtn = doc.createElement("button");
        delBtn.type = "button";
        delBtn.className = "review-btn-danger";
        delBtn.textContent = "Delete";
        delBtn.addEventListener("click", () => handleDeleteReview(r.reviewId));
        actions.append(delBtn);
        card.append(actions);
      }

      container.append(card);
    });
  };

  const applySummary = (summary) => {
    currentSummary = summary;
    renderSummary(summary);
    syncForm(summary);
    renderReviewsList(summary);
  };

  const loadReviews = async () => {
    const sort = el("reviewSortSelect")?.value || "newest";
    try {
      const res = await fetch(`/api/movies/${encodeURIComponent(movieId)}/reviews?sort=${encodeURIComponent(sort)}`);
      if (!res.ok) throw new Error("Unable to load reviews.");
      applySummary(await res.json());
    } catch {
      el("totalReviewsLabel").textContent = "Unavailable";
      el("movieReviewsList").innerHTML = '<div class="review-empty-text">Could not load audience reviews right now.</div>';
    }
  };

  const handleDeleteReview = async (reviewId) => {
    if (!reviewId) return;
    setFeedback("");
    try {
      const res = await fetch(`/api/movies/${encodeURIComponent(movieId)}/reviews/${encodeURIComponent(reviewId)}`, {
        method: "DELETE",
        headers: { ...getCsrfHeaders(doc) }
      });
      const data = await res.json();
      if (!res.ok) {
        setFeedback(data.message || "Could not delete review.", "error");
        return;
      }
      applySummary(data);
      setFeedback("Review deleted.", "success");
    } catch {
      setFeedback("Network error while deleting review.", "error");
    }
  };

  section.querySelectorAll(".star-pick-btn").forEach((btn) => {
    const val = Number(btn.dataset.star);
    btn.addEventListener("mouseenter", () => { hoverRating = val; paintStarPicker(); });
    btn.addEventListener("mouseleave", () => { hoverRating = 0; paintStarPicker(); });
    btn.addEventListener("click", () => { pickedRating = val; setFeedback(""); paintStarPicker(); });
  });

  const postReviewPayload = async (ratingValue, commentValue, successMessage) => {
    const submitBtn = el("submitReviewBtn");
    const confirmBtn = el("btnSubmitRatingOnly");
    if (submitBtn) submitBtn.disabled = true;
    if (confirmBtn) confirmBtn.disabled = true;
    setFeedback("");
    try {
      const res = await fetch(`/api/movies/${encodeURIComponent(movieId)}/reviews`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...getCsrfHeaders(doc)
        },
        body: JSON.stringify({ rating: ratingValue, comment: commentValue })
      });
      const data = await res.json();
      if (!res.ok) {
        setFeedback(data.message || "Could not submit review.", "error");
        return;
      }
      applySummary(data);
      setFeedback(successMessage || "Your review has been saved.", "success");
    } catch {
      setFeedback("Network error while saving your review.", "error");
    } finally {
      if (submitBtn) submitBtn.disabled = false;
      if (confirmBtn) confirmBtn.disabled = false;
    }
  };

  el("reviewCommentInput")?.addEventListener("input", () => {
    const val = el("reviewCommentInput").value;
    el("reviewCharCount").textContent = String(val.length);
    if (val.trim()) {
      const promptBox = el("emptyCommentPrompt");
      if (promptBox) promptBox.hidden = true;
    }
  });

  el("movieReviewForm")?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const comment = el("reviewCommentInput")?.value || "";
    const mine = currentSummary?.myReview;
    const canAppendComment = Boolean(
      mine && currentSummary?.verifiedViewer && mine.rating != null && (!mine.comment || !mine.comment.trim())
    );

    if (canAppendComment) {
      if (!comment.trim()) {
        setFeedback("Please write a comment before submitting.", "error");
        return;
      }
      await postReviewPayload(mine.rating, comment, "Your comment has been added.");
      return;
    }

    if (!currentSummary?.verifiedViewer) {
      if (!comment.trim()) {
        setFeedback("Please write a comment before posting.", "error");
        return;
      }
      await postReviewPayload(null, comment, "Your comment has been posted.");
      return;
    }

    if (!pickedRating) {
      setFeedback("Please select 1 to 5 stars before submitting.", "error");
      return;
    }

    if (!comment.trim()) {
      const promptBox = el("emptyCommentPrompt");
      const titleEl = el("emptyCommentPromptTitle");
      if (titleEl) {
        titleEl.textContent = `Submit your ${pickedRating} ★ rating without a comment?`;
      }
      if (promptBox) {
        promptBox.hidden = false;
        setFeedback("");
        return;
      }
    }

    await postReviewPayload(pickedRating, comment, "Your review has been saved.");
  });

  el("btnAddCommentFocus")?.addEventListener("click", () => {
    const promptBox = el("emptyCommentPrompt");
    if (promptBox) promptBox.hidden = true;
    el("reviewCommentInput")?.focus();
  });

  el("btnSubmitRatingOnly")?.addEventListener("click", async () => {
    if (!pickedRating) {
      setFeedback("Please select 1 to 5 stars before submitting.", "error");
      return;
    }
    const promptBox = el("emptyCommentPrompt");
    if (promptBox) promptBox.hidden = true;
    await postReviewPayload(pickedRating, "", "Your star rating has been saved. You can add a comment anytime below.");
  });

  el("reviewSortSelect")?.addEventListener("change", loadReviews);
  el("verifiedOnlyCheck")?.addEventListener("change", () => {
    if (currentSummary) renderReviewsList(currentSummary);
  });

  loadReviews();
}

if (typeof document !== "undefined") {
  initMovieDetails(document);
  initMovieReviews(document);
}
