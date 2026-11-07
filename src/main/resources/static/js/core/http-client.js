/**
 * BOX Cinema - HTTP Client Wrapper with Spring Security CSRF Support
 */
export function getCsrfHeaders() {
  if (typeof document === "undefined") {
    return {};
  }
  const tokenMeta = document.querySelector('meta[name="_csrf"]');
  const headerMeta = document.querySelector('meta[name="_csrf_header"]');
  if (tokenMeta && headerMeta) {
    const headerName = headerMeta.getAttribute("content") || "X-CSRF-TOKEN";
    const tokenValue = tokenMeta.getAttribute("content") || "";
    if (tokenValue) {
      return { [headerName]: tokenValue };
    }
  }
  return {};
}

export async function httpRequest(url, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  const headers = {
    "Content-Type": "application/json",
    ...(options.headers || {})
  };

  if (["POST", "PUT", "PATCH", "DELETE"].includes(method)) {
    Object.assign(headers, getCsrfHeaders());
  }

  const response = await fetch(url, {
    ...options,
    method,
    headers,
    credentials: "same-origin"
  });

  return response;
}
