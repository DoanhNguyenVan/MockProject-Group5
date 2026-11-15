/**
 * Box Cinema - Visual Seat SVGs
 * 100% Vector Geometry matching authentic cinema seat blueprint reference
 * Types: STANDARD, VIP, SWEETBOX, IMAX_SEAT, SEAT_4DX, RECLINER, WHEELCHAIR
 */

export function getSeatSvg(shapeType, variant = 'full') {
  const norm = String(shapeType || 'standard').toLowerCase().replace(/[^a-z0-9]+/g, '');

  switch (norm) {
    case 'vip':
      return `
        <svg viewBox="0 0 40 36" fill="none" xmlns="http://www.w3.org/2000/svg" class="seat-svg svg-shape-vip" aria-hidden="true">
          <rect x="1" y="1" width="38" height="34" rx="7" stroke="currentColor" stroke-width="1.2" stroke-opacity="0.3" fill="currentColor" fill-opacity="0.06"/>
          <rect x="4.5" y="11" width="3.5" height="11" rx="1.75" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.25"/>
          <rect x="32" y="11" width="3.5" height="11" rx="1.75" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.25"/>
          <rect x="9.5" y="6" width="21" height="19" rx="4" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.18"/>
          <rect x="11.5" y="8" width="17" height="15" rx="2.5" stroke="currentColor" stroke-width="1" fill="currentColor" fill-opacity="0.35"/>
          <rect x="10.5" y="27" width="19" height="4" rx="2" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.45"/>
        </svg>
      `.trim();

    case 'sweetbox':
      if (variant === 'left') {
        return `
          <svg viewBox="0 0 40 36" fill="none" xmlns="http://www.w3.org/2000/svg" class="seat-svg svg-shape-sweetbox-left" aria-hidden="true">
            <path d="M 39 1 L 7 1 C 3.68 1 1 3.68 1 7 L 1 29 C 1 32.32 3.68 35 7 35 L 39 35" stroke="currentColor" stroke-width="1.2" stroke-opacity="0.3" fill="currentColor" fill-opacity="0.06"/>
            <path d="M 40 6.5 L 14.5 6.5 C 9.8 6.5 6 10.3 6 15 C 6 19.7 9.8 23.5 14.5 23.5 L 40 23.5" stroke="currentColor" stroke-width="1.4" fill="currentColor" fill-opacity="0.35"/>
            <path d="M 40 27 L 12 27 C 10.9 27 10 27.9 10 29 L 10 29 C 10 30.1 10.9 31 12 31 L 40 31" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.45"/>
          </svg>
        `.trim();
      }
      if (variant === 'right') {
        return `
          <svg viewBox="0 0 40 36" fill="none" xmlns="http://www.w3.org/2000/svg" class="seat-svg svg-shape-sweetbox-right" aria-hidden="true">
            <path d="M 1 1 L 33 1 C 36.32 1 39 3.68 39 7 L 39 29 C 39 32.32 36.32 35 33 35 L 1 35" stroke="currentColor" stroke-width="1.2" stroke-opacity="0.3" fill="currentColor" fill-opacity="0.06"/>
            <path d="M 0 6.5 L 25.5 6.5 C 30.2 6.5 34 10.3 34 15 C 34 19.7 30.2 23.5 25.5 23.5 L 0 23.5" stroke="currentColor" stroke-width="1.4" fill="currentColor" fill-opacity="0.35"/>
            <path d="M 0 27 L 28 27 C 29.1 27 30 27.9 30 29 L 30 29 C 30 30.1 29.1 31 28 31 L 0 31" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.45"/>
          </svg>
        `.trim();
      }
      return `
        <svg viewBox="0 0 80 36" fill="none" xmlns="http://www.w3.org/2000/svg" class="seat-svg svg-shape-sweetbox" aria-hidden="true">
          <rect x="1" y="1" width="78" height="34" rx="7" stroke="currentColor" stroke-width="1.2" stroke-opacity="0.3" fill="currentColor" fill-opacity="0.06"/>
          <rect x="5" y="6.5" width="70" height="18" rx="9" stroke="currentColor" stroke-width="1.4" fill="currentColor" fill-opacity="0.35"/>
          <rect x="10" y="27" width="60" height="4.5" rx="2.25" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.45"/>
        </svg>
      `.trim();

    case 'imax':
    case 'imaxseat':
      return `
        <svg viewBox="0 0 40 36" fill="none" xmlns="http://www.w3.org/2000/svg" class="seat-svg svg-shape-imax" aria-hidden="true">
          <rect x="1" y="1" width="38" height="34" rx="7" stroke="currentColor" stroke-width="1.2" stroke-opacity="0.3" fill="currentColor" fill-opacity="0.06"/>
          <rect x="3.5" y="7.5" width="5.5" height="20" rx="2.5" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.25"/>
          <circle cx="6.25" cy="11" r="1.5" stroke="currentColor" stroke-width="0.8" fill="currentColor" fill-opacity="0.5"/>
          <rect x="31" y="7.5" width="5.5" height="20" rx="2.5" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.25"/>
          <circle cx="33.75" cy="11" r="1.5" stroke="currentColor" stroke-width="0.8" fill="currentColor" fill-opacity="0.5"/>
          <rect x="10.5" y="6" width="19" height="19" rx="3.5" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.35"/>
          <path d="M 14.5 7 L 14.5 24 M 25.5 7 L 25.5 24" stroke="currentColor" stroke-width="0.8" stroke-opacity="0.6"/>
          <path d="M 10 25.5 Q 20 28 30 25.5 L 29.5 29.5 Q 20 32 10.5 29.5 Z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round" fill="currentColor" fill-opacity="0.45"/>
        </svg>
      `.trim();

    case '4dx':
    case 'seat4dx':
      return `
        <svg viewBox="0 0 40 36" fill="none" xmlns="http://www.w3.org/2000/svg" class="seat-svg svg-shape-4dx" aria-hidden="true">
          <rect x="1" y="1" width="38" height="34" rx="7" stroke="currentColor" stroke-width="1.2" stroke-opacity="0.3" fill="currentColor" fill-opacity="0.06"/>
          <rect x="13" y="3" width="14" height="3.5" rx="1.5" stroke="currentColor" stroke-width="1" fill="currentColor" fill-opacity="0.35"/>
          <line x1="16" y1="6.5" x2="16" y2="9" stroke="currentColor" stroke-width="1.2"/>
          <line x1="24" y1="6.5" x2="24" y2="9" stroke="currentColor" stroke-width="1.2"/>
          <circle cx="6" cy="10" r="3" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.3"/>
          <circle cx="6" cy="10" r="1" fill="currentColor"/>
          <circle cx="34" cy="10" r="3" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.3"/>
          <circle cx="34" cy="10" r="1" fill="currentColor"/>
          <circle cx="6" cy="27" r="3" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.3"/>
          <circle cx="6" cy="27" r="1" fill="currentColor"/>
          <circle cx="34" cy="27" r="3" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.3"/>
          <circle cx="34" cy="27" r="1" fill="currentColor"/>
          <rect x="9.5" y="9" width="21" height="17" rx="2.5" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.3"/>
          <path d="M 20.5 11 L 18 15.5 L 20.5 15.5 L 19.5 20.5 L 23 15 L 20.5 15 Z" fill="currentColor" fill-opacity="0.75"/>
          <path d="M 3.5 16 Q 2.5 18 3.5 20" stroke="currentColor" stroke-width="0.9" stroke-linecap="round"/>
          <path d="M 36.5 16 Q 37.5 18 36.5 20" stroke="currentColor" stroke-width="0.9" stroke-linecap="round"/>
          <path d="M 11 26 Q 20 28 29 26 L 28.5 30.5 Q 20 32 11.5 30.5 Z" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.45"/>
          <circle cx="16" cy="28.5" r="0.6" fill="currentColor"/>
          <circle cx="17.5" cy="29" r="0.6" fill="currentColor"/>
          <circle cx="22.5" cy="29" r="0.6" fill="currentColor"/>
          <circle cx="24" cy="28.5" r="0.6" fill="currentColor"/>
        </svg>
      `.trim();

    case 'recliner':
      return `
        <svg viewBox="0 0 40 36" fill="none" xmlns="http://www.w3.org/2000/svg" class="seat-svg svg-shape-recliner" aria-hidden="true">
          <rect x="1" y="1" width="38" height="34" rx="7" stroke="currentColor" stroke-width="1.2" stroke-opacity="0.3" fill="currentColor" fill-opacity="0.06"/>
          <rect x="12" y="2.5" width="16" height="4.5" rx="2" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.4"/>
          <line x1="15" y1="7" x2="15" y2="9" stroke="currentColor" stroke-width="1.2"/>
          <line x1="25" y1="7" x2="25" y2="9" stroke="currentColor" stroke-width="1.2"/>
          <rect x="3.5" y="11" width="5" height="12" rx="2.5" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.25"/>
          <circle cx="6" cy="13.5" r="1.3" stroke="currentColor" stroke-width="0.8" fill="currentColor" fill-opacity="0.5"/>
          <rect x="31.5" y="11" width="5" height="12" rx="2.5" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.25"/>
          <path d="M 31.5 15 C 29 15 28 16 28 18.5 C 28 21 29 22 31.5 22 Z" stroke="currentColor" stroke-width="1" fill="currentColor" fill-opacity="0.4"/>
          <rect x="9.5" y="9" width="21" height="16.5" rx="3" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.25"/>
          <path d="M 13 9.5 L 27 23.5 M 17 9.5 L 30 22.5 M 27 9.5 L 13 23.5 M 23 9.5 L 10 22.5" stroke="currentColor" stroke-width="0.6" stroke-opacity="0.5"/>
          <rect x="8.5" y="26.5" width="10.5" height="5.5" rx="2" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.45"/>
          <rect x="21" y="26.5" width="10.5" height="5.5" rx="2" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.45"/>
        </svg>
      `.trim();

    case 'wheelchair':
      return `
        <svg viewBox="0 0 40 36" fill="none" xmlns="http://www.w3.org/2000/svg" class="seat-svg svg-shape-wheelchair" aria-hidden="true">
          <rect x="1" y="1" width="38" height="34" rx="7" stroke="currentColor" stroke-width="1.3" stroke-dasharray="4 2.5" stroke-opacity="0.85" fill="currentColor" fill-opacity="0.06"/>
          <path d="M 34 29 L 34.8 30.8 L 36.6 31.6 L 34.8 32.4 L 34 34.2 L 33.2 32.4 L 31.4 31.6 L 33.2 30.8 Z" fill="currentColor" fill-opacity="0.65"/>
          <rect x="11.5" y="3.5" width="3.5" height="5" rx="1.5" stroke="currentColor" stroke-width="1" fill="currentColor" fill-opacity="0.3"/>
          <rect x="25" y="3.5" width="3.5" height="5" rx="1.5" stroke="currentColor" stroke-width="1" fill="currentColor" fill-opacity="0.3"/>
          <rect x="4.5" y="9" width="3.5" height="17" rx="1.75" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.3"/>
          <rect x="32" y="9" width="3.5" height="17" rx="1.75" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.3"/>
          <rect x="9.5" y="9.5" width="21" height="15.5" rx="3.5" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.35"/>
          <path d="M 9.5 20 Q 20 22 30.5 20" stroke="currentColor" stroke-width="0.8" stroke-opacity="0.6"/>
          <rect x="10.5" y="26" width="7" height="3" rx="1" stroke="currentColor" stroke-width="1" fill="currentColor" fill-opacity="0.3"/>
          <rect x="22.5" y="26" width="7" height="3" rx="1" stroke="currentColor" stroke-width="1" fill="currentColor" fill-opacity="0.3"/>
        </svg>
      `.trim();

    case 'standard':
    default:
      return `
        <svg viewBox="0 0 40 36" fill="none" xmlns="http://www.w3.org/2000/svg" class="seat-svg svg-shape-standard" aria-hidden="true">
          <rect x="1" y="1" width="38" height="34" rx="7" stroke="currentColor" stroke-width="1.2" stroke-opacity="0.3" fill="currentColor" fill-opacity="0.06"/>
          <rect x="5.5" y="10" width="3.5" height="11" rx="1.75" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.25"/>
          <rect x="31" y="10" width="3.5" height="11" rx="1.75" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.25"/>
          <rect x="11" y="7" width="18" height="15" rx="3.5" stroke="currentColor" stroke-width="1.2" fill="currentColor" fill-opacity="0.35"/>
          <path d="M 8.5 24 Q 20 28 31.5 24 L 30.5 28.5 Q 20 32 9.5 28.5 Z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round" fill="currentColor" fill-opacity="0.45"/>
        </svg>
      `.trim();
  }
}
