// Deliberately fictional records. Never used as a fallback for a live API response.
export function createAdminPreview() {
  return {
    version: 1,
    movies: [
      { id: 'm1', title: 'Interstellar', durationMinutes: 169, ageRating: 'T13', language: 'English', releaseDate: '2026-11-01', endDate: '', status: 'NOW_SHOWING', genreIds: ['g1', 'g3'], synopsis: 'A team of explorers travels beyond this galaxy to discover whether mankind has a future among the stars.', posterUrl: '', trailerUrl: '' },
      { id: 'm2', title: 'Dune: Part Two', durationMinutes: 166, ageRating: 'T13', language: 'English', releaseDate: '2026-11-06', endDate: '', status: 'NOW_SHOWING', genreIds: ['g1', 'g2'], synopsis: 'Paul Atreides joins the Fremen on a journey of revenge and impossible choices.', posterUrl: '', trailerUrl: '' },
      { id: 'm3', title: 'The Wild Robot', durationMinutes: 102, ageRating: 'P', language: 'English', releaseDate: '2026-11-08', endDate: '', status: 'NOW_SHOWING', genreIds: ['g4'], synopsis: 'A robot learns to adapt to an island and care for an orphaned gosling.', posterUrl: '', trailerUrl: '' },
      { id: 'm4', title: 'Inside Out 2', durationMinutes: 96, ageRating: 'P', language: 'English', releaseDate: '2026-11-20', endDate: '', status: 'COMING_SOON', genreIds: ['g4'], synopsis: 'New emotions arrive as Riley enters her teenage years.', posterUrl: '', trailerUrl: '' },
      { id: 'm5', title: 'The Batman', durationMinutes: 176, ageRating: 'T16', language: 'English', releaseDate: '2026-10-10', endDate: '2026-11-01', status: 'ENDED', genreIds: ['g2'], synopsis: 'A masked detective follows a trail of corruption through Gotham.', posterUrl: '', trailerUrl: '' },
      { id: 'm6', title: 'A Quiet Place', durationMinutes: 90, ageRating: 'T18', language: 'English', releaseDate: '2026-11-05', endDate: '', status: 'NOW_SHOWING', genreIds: ['g5'], synopsis: 'A family must live in silence to survive.', posterUrl: '', trailerUrl: '' },
      { id: 'm7', title: 'Soul', durationMinutes: 100, ageRating: 'P', language: 'English', releaseDate: '2026-11-22', endDate: '', status: 'COMING_SOON', genreIds: ['g4'], synopsis: 'A musician discovers the extraordinary in everyday life.', posterUrl: '', trailerUrl: '' }
    ],
    genres: [{ id: 'g1', genreName: 'Science fiction', description: 'Worlds beyond imagination.' }, { id: 'g2', genreName: 'Action', description: 'Big-screen adventure.' }, { id: 'g3', genreName: 'Drama', description: 'Stories that stay with you.' }, { id: 'g4', genreName: 'Animation', description: 'Imagination for every age.' }, { id: 'g5', genreName: 'Thriller', description: 'Suspense from start to finish.' }],
    cinemas: [
      { id: 'c1', cinemaName: 'BOX Landmark', city: 'Ho Chi Minh City', address: 'Sample location · Binh Thanh', phone: '0900000001', email: 'landmark@example.com', status: 'ACTIVE' },
      { id: 'c2', cinemaName: 'BOX West Lake', city: 'Ha Noi', address: 'Sample location · Tay Ho', phone: '0900000002', email: 'westlake@example.com', status: 'ACTIVE' },
      { id: 'c3', cinemaName: 'BOX Riverside', city: 'Da Nang', address: 'Sample location · Hai Chau', phone: '0900000003', email: 'riverside@example.com', status: 'INACTIVE' }
    ],
    rooms: [{ id: 'r1', cinemaId: 'c1', roomName: 'Screen 01', roomType: 'IMAX', status: 'ACTIVE' }, { id: 'r2', cinemaId: 'c1', roomName: 'Screen 02', roomType: '2D', status: 'ACTIVE' }, { id: 'r3', cinemaId: 'c2', roomName: 'Screen 01', roomType: '3D', status: 'ACTIVE' }],
    showtimes: [
      { id: 's1', movieId: 'm1', roomId: 'r1', startAt: '2026-11-13T18:00', endAt: '2026-11-13T20:49', basePrice: 120000, status: 'ACTIVE' },
      { id: 's2', movieId: 'm2', roomId: 'r2', startAt: '2026-11-13T19:00', endAt: '2026-11-13T21:46', basePrice: 95000, status: 'ACTIVE' },
      { id: 's3', movieId: 'm3', roomId: 'r3', startAt: '2026-11-13T18:30', endAt: '2026-11-13T20:12', basePrice: 90000, status: 'ACTIVE' }
    ],
    pricing: [{ id: 'p1', typeName: 'STANDARD', priceSurcharge: 0, description: 'Standard seat' }, { id: 'p2', typeName: 'VIP', priceSurcharge: 30000, description: 'Premium seating' }],
    promotions: [{ id: 'v1', promotionName: 'Midweek movie night', voucherCode: 'BOXMIDWEEK', discountType: 'PERCENT', discountValue: 15, maxDiscount: 50000, minOrderAmount: 150000, startAt: '2026-11-01T00:00', endAt: '2026-11-30T23:59', usageLimit: 500, perUserLimit: 1, status: 'ACTIVE' }, { id: 'v2', promotionName: 'Double the experience', voucherCode: 'BOXDUO', discountType: 'FIXED', discountValue: 30000, maxDiscount: 30000, minOrderAmount: 200000, startAt: '2026-11-01T00:00', endAt: '2026-11-20T23:59', usageLimit: 200, perUserLimit: 2, status: 'INACTIVE' }],
    combos: [{ id: 'f1', productName: 'Classic combo', productType: 'COMBO', price: 89000, description: 'One popcorn and one soft drink.', status: 'ACTIVE' }, { id: 'f2', productName: 'Movie night for two', productType: 'COMBO', price: 139000, description: 'Large popcorn and two soft drinks.', status: 'ACTIVE' }, { id: 'f3', productName: 'Caramel popcorn', productType: 'FOOD', price: 59000, description: 'Freshly popped, lightly sweet.', status: 'ACTIVE' }],
    staff: [{ id: 'u1', fullName: 'Alex Nguyen', email: 'alex@example.com', phone: '0900000011', cinemaId: 'c1', role: 'MANAGER', status: 'ACTIVE' }, { id: 'u2', fullName: 'Jamie Tran', email: 'jamie@example.com', phone: '0900000012', cinemaId: 'c1', role: 'STAFF', status: 'ACTIVE' }, { id: 'u3', fullName: 'Sam Le', email: 'sam@example.com', phone: '0900000013', cinemaId: 'c2', role: 'ADMIN', status: 'ACTIVE' }],
    layouts: {},
    sales: Array.from({ length: 21 }, (_, index) => ({
      id: `DEMO-${1001 + index}`, date: `2026-11-${String(9 + Math.floor(index / 3)).padStart(2, '0')}`,
      movieId: `m${index % 3 + 1}`, cinemaId: index % 2 ? 'c2' : 'c1',
      tickets: 12 + (index * 7) % 30, revenue: (12 + (index * 7) % 30) * 120000,
      status: index === 4 ? 'CANCELLED' : 'PAID'
    }))
  };
}
