import { httpRequest } from "../core/http-client.js";
import { buildBookingPreview } from "./booking-preview.js?v=1.1";

/** Prefer live inventory. Until that endpoint exists, open the labelled frontend demo. */
export async function loadBookingData(showtimeId, { request = httpRequest, signal, onUnauthenticated = () => {} } = {}) {
  async function read(url) {
    const response = await request(url, { signal });
    if (response.status === 401 || (response.redirected && new URL(response.url).pathname === "/login")) {
      onUnauthenticated();
      const error = new Error("Please sign in to continue."); error.status = 401; throw error;
    }
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      const error = new Error(body.message || "We could not load this screening. Please try again.");
      error.status = response.status; throw error;
    }
    return response.json();
  }
  const id = encodeURIComponent(showtimeId);
  try {
    return await read(`/api/booking/showtimes/${id}`);
  } catch (error) {
    // Never mask authentication failures, server errors or cancelled requests with sample data.
    if (error.status !== 404) throw error;
    const context = await read(`/api/quick-booking/showtimes/${id}`);
    return buildBookingPreview(context);
  }
}
