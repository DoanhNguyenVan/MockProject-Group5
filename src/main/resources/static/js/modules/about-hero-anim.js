/**
 * BOX Cinema - Hero Brand Typography Cinematic Sequence (Infinite Loop)
 * Stage 0: "WELLCOME TO" (red) and "BOX CINEMA" (white) split horizontally outward from center.
 * Stage 1: Pause on full "WELLCOME TO BOX CINEMA" hero frame.
 * Stage 2: Buttery smooth handover: "WELLCOME TO" dissolves away, while "BOX CINEMA" smoothly centers.
 * Stage 3: "CINEMA" collapses / tucks into "BOX".
 * Stage 4: "B", "O", "X" glide from horizontal to vertical stack and illuminate neon red.
 * Stage 5: "EYOND", "RDINARY", "-PERIENCE" unfold horizontally out of B, O, X.
 * Stage 6: Hold completed acronym.
 * Stage 7: Smooth cinematic dissolve before seamlessly looping back to Stage 0.
 *
 * Description and Action buttons remain permanently visible with ZERO delay.
 */

function initAboutHeroAnim() {
  const stage = document.getElementById('heroBrandStage');
  if (!stage) return;

  const welcomeIntro = document.getElementById('brandWelcomeIntro');
  const charB = document.getElementById('charB');
  const charO = document.getElementById('charO');
  const charX = document.getElementById('charX');
  const tailB = document.getElementById('tailEyond');
  const tailO = document.getElementById('tailOrdinary');
  const tailX = document.getElementById('tailXperience');
  const cinemaIntro = document.getElementById('brandCinemaIntro');
  const heroDesc = document.getElementById('heroDesc');
  const heroActions = document.getElementById('heroActions');

  // Description and buttons appear immediately without animation per user request
  if (heroDesc) {
    heroDesc.style.opacity = '1';
    heroDesc.style.transform = 'none';
  }
  if (heroActions) {
    heroActions.style.opacity = '1';
    heroActions.style.transform = 'none';
  }

  if (!charB || !charO || !charX || !tailB || !tailO || !tailX || !cinemaIntro) {
    stage.classList.add('anim-completed');
    return;
  }

  // Accessibility: Respect reduced motion preference
  const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (prefersReduced || typeof gsap === 'undefined') {
    if (welcomeIntro) welcomeIntro.style.display = 'none';
    stage.classList.add('anim-completed');
    return;
  }

  // 1. Measure natural geometry while in final layout
  const bRect = charB.getBoundingClientRect();
  const oRect = charO.getBoundingClientRect();
  const xRect = charX.getBoundingClientRect();

  const tailBWidth = tailB.scrollWidth;
  const tailOWidth = tailO.scrollWidth;
  const tailXWidth = tailX.scrollWidth;

  const bWidth = charB.offsetWidth;
  const oWidth = charO.offsetWidth;
  const xWidth = charX.offsetWidth;

  const letterGap = Math.max(4, Math.round(bWidth * 0.08));
  const wordGap = Math.max(16, Math.round(bWidth * 0.35));

  const deltaX_O = bWidth + letterGap;
  const deltaY_O = -(oRect.top - bRect.top);

  const deltaX_X = bWidth + letterGap + oWidth + letterGap;
  const deltaY_X = -(xRect.top - bRect.top);

  const cinemaX = deltaX_X + xWidth + wordGap;

  // Measure welcomeIntro and cinemaIntro
  let welcomeWidth = 0;
  let welcomeX = 0;
  let stageCenterShift = 0;

  if (welcomeIntro) {
    welcomeIntro.style.display = 'block';
    welcomeWidth = welcomeIntro.offsetWidth;
    welcomeX = -(welcomeWidth + wordGap);

    const cinemaWidth = cinemaIntro.offsetWidth || (bWidth * 3.5);
    const rightEdge = cinemaX + cinemaWidth;
    const visualCenter = (welcomeX + rightEdge) / 2;
    stageCenterShift = (bWidth / 2) - visualCenter;
  }

  const splitDistance = Math.max(45, Math.round((welcomeWidth || 200) * 0.18));

  stage.style.visibility = 'visible';

  // 2. Build looping master cinematic timeline
  const tl = gsap.timeline({
    repeat: -1,
    defaults: { ease: 'power3.inOut' }
  });

  // Reset function executed at start of each loop
  function setInitialFrame() {
    gsap.set([tailB, tailO, tailX], { width: 0, opacity: 0 });

    if (stageCenterShift !== 0) {
      gsap.set(stage, { x: stageCenterShift, opacity: 1, filter: 'blur(0px)' });
    } else {
      gsap.set(stage, { opacity: 1, filter: 'blur(0px)' });
    }

    if (welcomeIntro) {
      gsap.set(welcomeIntro, {
        x: welcomeX + splitDistance,
        y: 0,
        opacity: 0,
        filter: 'blur(8px)',
        display: 'block'
      });
    }

    gsap.set(charB, {
      x: -splitDistance,
      y: 0,
      opacity: 0,
      filter: 'blur(8px)',
      color: '#ffffff',
      textShadow: 'none'
    });

    gsap.set(charO, {
      x: deltaX_O - splitDistance,
      y: deltaY_O,
      opacity: 0,
      filter: 'blur(8px)',
      color: '#ffffff',
      textShadow: 'none'
    });

    gsap.set(charX, {
      x: deltaX_X - splitDistance,
      y: deltaY_X,
      opacity: 0,
      filter: 'blur(8px)',
      color: '#ffffff',
      textShadow: 'none'
    });

    gsap.set(cinemaIntro, {
      x: cinemaX - splitDistance,
      y: 0,
      opacity: 0,
      scaleX: 1,
      scaleY: 1,
      filter: 'blur(8px)',
      transformOrigin: 'left center',
      display: 'block'
    });
  }

  // Ensure initial frame is set immediately
  setInitialFrame();

  // At the start of every cycle in the timeline
  tl.call(setInitialFrame);

  // Stage 0: "WELLCOME TO" and "BOX CINEMA" pull apart from center
  if (welcomeIntro) {
    tl.to(welcomeIntro, {
      duration: 0.9,
      x: welcomeX,
      opacity: 1,
      filter: 'blur(0px)',
      ease: 'power3.out'
    }, '+=0.05');
  }

  tl.to([charB, charO, charX, cinemaIntro], {
    duration: 0.9,
    opacity: 1,
    filter: 'blur(0px)',
    ease: 'power3.out'
  }, '<');

  tl.to(charB, { duration: 0.9, x: 0, ease: 'power3.out' }, '<');
  tl.to(charO, { duration: 0.9, x: deltaX_O, ease: 'power3.out' }, '<');
  tl.to(charX, { duration: 0.9, x: deltaX_X, ease: 'power3.out' }, '<');
  tl.to(cinemaIntro, { duration: 0.9, x: cinemaX, ease: 'power3.out' }, '<');

  // Stage 1: Pause on full "WELLCOME TO BOX CINEMA" hero frame
  tl.to({}, { duration: 1.1 });

  // Stage 2: Handover - "WELLCOME TO" glides left & dissolves away
  if (welcomeIntro) {
    tl.to(welcomeIntro, {
      duration: 0.55,
      x: welcomeX - 60,
      opacity: 0,
      filter: 'blur(10px)',
      ease: 'power2.in'
    });
  }

  // At the SAME TIME, stage glides smoothly back to center
  if (stageCenterShift !== 0) {
    tl.to(stage, {
      duration: 0.75,
      x: 0,
      ease: 'power3.inOut'
    }, '<');
  }

  // Stage 3: "CINEMA" tucks into "BOX"
  tl.to(cinemaIntro, {
    duration: 0.55,
    x: cinemaX - 35,
    scaleX: 0,
    opacity: 0,
    ease: 'power2.in'
  }, '-=0.25');

  // Stage 4: B, O, X move from horizontal to vertical stack
  tl.to([charO, charX], {
    duration: 0.85,
    x: 0,
    y: 0,
    ease: 'power3.inOut'
  }, '-=0.15');

  // B, O, X illuminate in neon red
  tl.to([charB, charO, charX], {
    duration: 0.45,
    color: '#e50914',
    textShadow: '0 0 25px rgba(229, 9, 20, 0.75), 0 0 45px rgba(229, 9, 20, 0.35)',
    ease: 'power1.out'
  }, '-=0.4');

  // Stage 5: "EYOND", "RDINARY", "-PERIENCE" unfold horizontally
  tl.to(tailB, {
    duration: 0.58,
    width: tailBWidth,
    opacity: 1,
    ease: 'power2.out'
  }, '+=0.05');

  tl.to(tailO, {
    duration: 0.58,
    width: tailOWidth,
    opacity: 1,
    ease: 'power2.out'
  }, '-=0.42');

  tl.to(tailX, {
    duration: 0.62,
    width: tailXWidth,
    opacity: 1,
    ease: 'power2.out'
  }, '-=0.42');

  // Stage 6: Hold completed acronym so viewers can appreciate it
  tl.to({}, { duration: 3.0 });

  // Stage 7: Smooth cinematic dissolve before repeating
  tl.to(stage, {
    duration: 0.65,
    opacity: 0,
    filter: 'blur(8px)',
    ease: 'power2.in'
  });

  tl.to({}, { duration: 0.3 });

  // Interactive Replay on click (restarts immediately)
  stage.style.cursor = 'pointer';
  stage.setAttribute('title', 'Click to replay animation');
  stage.addEventListener('click', () => {
    tl.restart();
  });
}

// Resilient execution
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initAboutHeroAnim);
} else {
  initAboutHeroAnim();
}
