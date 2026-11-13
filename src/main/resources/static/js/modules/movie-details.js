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
  doc.querySelector(".movie-details-poster img")?.addEventListener("error", event => { event.target.hidden = true; });
  const trailer = doc.getElementById("movieTrailer");
  if (!trailer) return;
  const source = trailerSource(trailer.dataset.trailerUrl);
  if (!source) return;
  const external = doc.getElementById("movieTrailerExternal");
  external.href = source.external;
  external.hidden = false;
  const message = doc.getElementById("movieTrailerMessage");
  message.textContent = source.embed ? "Take a first look. Play the trailer when you are ready." : "Watch this trailer using the Open trailer link above.";
  if (!source.embed) return;
  const play = doc.getElementById("movieTrailerPlay");
  play.hidden = false;
  doc.getElementById("movieTrailerNote").hidden = false;
  play.addEventListener("click", () => {
    const frame = doc.createElement("iframe");
    frame.src = source.embed + "?autoplay=1";
    frame.title = `${doc.getElementById("movieTitle").textContent} — trailer`;
    frame.allow = "autoplay; encrypted-media; picture-in-picture; fullscreen";
    frame.allowFullscreen = true;
    frame.referrerPolicy = "strict-origin-when-cross-origin";
    doc.getElementById("movieTrailerFrame").replaceChildren(frame);
    frame.focus();
  }, { once: true });
}

if (typeof document !== "undefined") initMovieDetails(document);
