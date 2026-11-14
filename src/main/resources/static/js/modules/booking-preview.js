// Frontend demonstration data only: never use these seats as live availability.
export const bookingPreview = {
  preview: true,
  showtimeId: "preview", movieTitle: "Crimson Horizon: Eclipse Protocol", ageRating: "T16",
  posterUrl: "/assets/img/crimson-horizon.webp", format: "2D · English subtitles",
  cinemaId: "preview", cinemaName: "BOX Flagship District 1", roomName: "Screen 03",
  screeningLabel: "Sample screening · 19:30",
  seats: Array.from({ length: 8 }, (_, row) => Array.from({ length: 10 }, (_, col) => ({
    id: `${String.fromCharCode(65 + row)}${col + 1}`, row: String.fromCharCode(65 + row),
    number: col + 1, type: row >= 3 && row <= 5 ? "VIP" : "STANDARD",
    price: row >= 3 && row <= 5 ? 120000 : 90000,
    status: [3, 4, 15, 16, 33, 34, 35, 44, 45, 66, 67].includes(row * 10 + col) ? "BOOKED" : "AVAILABLE",
    aisleAfter: col === 4
  }))).flat(),
  products: [
    { id: "solo", name: "Solo combo", description: "1 large popcorn + 1 soft drink", price: 89000, imageUrl: "/assets/img/fnb/box-popcorn.jpg" },
    { id: "duo", name: "Movie night for two", description: "1 large popcorn + 2 soft drinks", price: 129000, imageUrl: "/assets/img/fnb/box-combos.jpg" },
    { id: "drink", name: "Refreshing soft drink", description: "1 large soft drink", price: 35000, imageUrl: "/assets/img/fnb/box-beverages.jpg" }
  ]
};

/** Keep the real screening identity while making sample seat data explicit. */
export function buildBookingPreview(context) {
  const price = Number(context?.basePrice);
  if (!context?.showtimeId || !context.movieId || !context.movieTitle || !context.cinemaName
      || !context.roomName || !context.screeningLabel || !Number.isSafeInteger(price) || price < 0) {
    throw new Error("Screening information is incomplete. Please choose another showtime.");
  }
  return {
    ...context,
    preview: true,
    seats: bookingPreview.seats.map(seat => ({ ...seat, price: price + (seat.type === "VIP" ? 30000 : 0) })),
    products: bookingPreview.products.map(product => ({ ...product }))
  };
}
