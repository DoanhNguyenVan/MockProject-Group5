import { httpRequest } from "../core/http-client.js";
import { buildBookingPreview } from "./booking-preview.js?v=1.1";

/**
 * Loads live screening and seat availability from the MySQL database.
 * Reflects the exact layout, seat types and pricing configured by Admin.
 */
export async function loadBookingData(showtimeId, { request = httpRequest, signal, onUnauthenticated = () => {} } = {}) {
  async function read(url) {
    const response = await request(url, { signal });
    if (response.status === 401 || (response.redirected && new URL(response.url).pathname === "/login")) {
      onUnauthenticated();
      const error = new Error("Please sign in to continue.");
      error.status = 401;
      throw error;
    }
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      const error = new Error(body.message || "We could not load this screening. Please try again.");
      error.status = response.status;
      throw error;
    }
    return response.json();
  }

  const id = encodeURIComponent(showtimeId);
  try {
    const live = await read(`/api/booking/showtimes/${id}`);
    if (live && Array.isArray(live.seats) && live.seats.length > 0) {
      return live;
    }
  } catch (error) {
    if (error.status === 401) throw error;
    console.warn("Could not load /api/booking/showtimes/" + id + ", attempting context fallback:", error);
  }

  const context = await read(`/api/quick-booking/showtimes/${id}`);
  return buildBookingPreview(context);
}
