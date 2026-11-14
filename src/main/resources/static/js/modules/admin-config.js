// Form definitions mirror database concepts; IDs refer only to isolated preview records.
const text = (key, label, max = 150, required = true) => ({ key, label, type: 'text', max, required });
const select = (key, label, options) => ({ key, label, type: 'select', options, required: true });
const reference = (key, label, source) => ({ key, label, type: 'reference', source, required: true });
const number = (key, label, min = 0, max = 999999999) => ({ key, label, type: 'number', min, max, required: true });
const date = (key, label, type = 'date', required = true) => ({ key, label, type, required });
const status = select('status', 'Status', ['ACTIVE', 'INACTIVE']);

export const ADMIN_SECTIONS = {
  dashboard: { title: 'Overview', group: 'WORKSPACE', icon: '◫', description: 'A clear view of your cinema business.' },
  movies: { title: 'Movies', singular: 'movie', group: 'PROGRAMMING', icon: '▣', description: 'Curate the stories on your screens.', columns: ['title', 'durationMinutes', 'ageRating', 'releaseDate', 'status'], fields: [
    text('title', 'Movie title', 200), number('durationMinutes', 'Runtime (minutes)', 1, 600), select('ageRating', 'Age rating', ['P', 'T13', 'T16', 'T18']), text('language', 'Language', 50),
    date('releaseDate', 'Release date'), date('endDate', 'End date', 'date', false), select('status', 'Status', ['NOW_SHOWING', 'COMING_SOON', 'ENDED']),
    { key: 'genreIds', label: 'Genres', type: 'multireference', source: 'genres', required: true },
    { ...text('synopsis', 'Synopsis', 2000, false), type: 'textarea' },
    { ...text('posterUrl', 'Poster URL', 500, false), type: 'url' }, { ...text('trailerUrl', 'Trailer URL', 500, false), type: 'url' }
  ] },
  genres: { title: 'Genres', singular: 'genre', group: 'PROGRAMMING', icon: '◇', description: 'Organize the collection by genre.', columns: ['genreName', 'description'], fields: [text('genreName', 'Genre name', 50), { ...text('description', 'Description', 500, false), type: 'textarea' }] },
  showtimes: { title: 'Showtimes', singular: 'showtime', group: 'PROGRAMMING', icon: '◷', description: 'Plan the next great night at the movies.', columns: ['movieId', 'roomId', 'startAt', 'basePrice', 'status'], fields: [reference('movieId', 'Movie', 'movies'), reference('roomId', 'Room', 'rooms'), date('startAt', 'Starts at', 'datetime-local'), date('endAt', 'Ends at', 'datetime-local'), number('basePrice', 'Base price (VND)', 1000), status] },
  cinemas: { title: 'Cinemas', singular: 'cinema', group: 'CINEMA OPERATIONS', icon: '⌂', description: 'One workspace for every BOX Cinema location.', columns: ['cinemaName', 'city', 'address', 'status'], fields: [text('cinemaName', 'Cinema name'), text('city', 'City', 100), text('address', 'Address', 255), text('phone', 'Phone', 20), { ...text('email', 'Email', 255), type: 'email' }, status] },
  rooms: { title: 'Rooms', singular: 'room', group: 'CINEMA OPERATIONS', icon: '▤', description: 'Manage screening rooms and their seat layouts.', columns: ['roomName', 'cinemaId', 'roomType', 'status'], fields: [text('roomName', 'Room name', 50), reference('cinemaId', 'Cinema', 'cinemas'), select('roomType', 'Format', ['2D', '3D', 'IMAX']), status] },
  seats: { title: 'Seat layouts', group: 'CINEMA OPERATIONS', icon: '▦', description: 'Make every seat part of the experience.' },
  pricing: { title: 'Seat pricing', singular: 'seat type', group: 'CINEMA OPERATIONS', icon: '₫', description: 'Set surcharges. Base ticket prices are managed in Showtimes.', columns: ['typeName', 'priceSurcharge', 'description'], fields: [text('typeName', 'Seat type', 50), number('priceSurcharge', 'Surcharge (VND)'), text('description', 'Description', 500, false)] },
  promotions: { title: 'Promotions', singular: 'promotion', group: 'BUSINESS', icon: '✧', description: 'Create a little more reason to come back.', columns: ['promotionName', 'voucherCode', 'discountValue', 'endAt', 'status'], fields: [text('promotionName', 'Promotion name'), text('voucherCode', 'Voucher code', 50), select('discountType', 'Discount type', ['PERCENT', 'FIXED']), number('discountValue', 'Discount value', 1), number('maxDiscount', 'Maximum discount (VND)', 1), number('minOrderAmount', 'Minimum order (VND)'), date('startAt', 'Starts at', 'datetime-local'), date('endAt', 'Ends at', 'datetime-local'), number('usageLimit', 'Total redemptions', 1, 1000000), number('perUserLimit', 'Redemptions per customer', 1, 1000), status] },
  combos: { title: 'Snacks & combos', singular: 'product', group: 'BUSINESS', icon: '♧', description: 'The perfect companions to a great film.', columns: ['productName', 'productType', 'price', 'status'], fields: [text('productName', 'Product name'), select('productType', 'Category', ['COMBO', 'FOOD', 'DRINK']), number('price', 'Price (VND)', 1000), text('description', 'Description', 500, false), status] },
  staff: { title: 'Staff & roles', singular: 'staff member', group: 'BUSINESS', icon: '♙', description: 'Bring your cinema team together.', columns: ['fullName', 'email', 'cinemaId', 'role', 'status'], fields: [text('fullName', 'Full name', 100), { ...text('email', 'Email', 255), type: 'email' }, text('phone', 'Phone', 20), reference('cinemaId', 'Cinema', 'cinemas'), select('role', 'Role', ['STAFF', 'MANAGER', 'ADMIN']), status] },
  reports: { title: 'Reports', group: 'BUSINESS', icon: '↗', description: 'Understand what brings your audience back.' }
};

export const RECORD_LABELS = { movies: 'title', genres: 'genreName', cinemas: 'cinemaName', rooms: 'roomName', pricing: 'typeName', promotions: 'promotionName', combos: 'productName', staff: 'fullName' };
