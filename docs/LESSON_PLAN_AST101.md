# Lesson Plan: AST101 in the Lesson Studio

Source: AST101 *Exploring the Solar System* (UTM, Dr. Lea Hirsch), slides for Days 1–33 and the final
review. AST101 is the non-specialist course, so its bundle keeps the conceptual steps and drops the
ones that lean on equations the course doesn't teach (radians, solid angle, RA/Dec and sidereal time,
magnitudes, parsecs, intensity integrals, interferometry).

The bundle reuses the AST221 lessons. A lesson whose kept steps change its focus gets a new title,
subtitle and blurb for AST101 (Stellar Parallax, Spectral Lines, Luminosity & Flux). The bundle is defined
in `LessonStudio.COURSES`, and every step is named by its title. A title that matches no step stops the
app at start-up, so renaming a step can't silently drop it from a course. A step marked
`.only("AST101")` is one course's own version of a shared step: same title, so the course picks it by
name, and the other course keeps its version (`.only("AST221")`). AST101's angular-size steps use this
to stay in degrees.

## The bundle, in course order

| # | Lesson (AST101 title) | Slide days | Steps kept |
| - | --- | --- | --- |
| 1 | How Big Does It Look? | 3 (angles; the Sun and Moon) | Angular size · Same angle, different sizes · A planet's tiny disc · Check: the Moon's angular size (AST101's own degrees-only versions, with the hand at arm's length, total vs annular eclipses and field-of-view stops) |
| 2 | Reading the Sky | 5 (the local sky) | Altitude and azimuth · The pole star sits at your latitude |
| 3 | Orbits That Stretch | 6 (seasons), 9–11 (Kepler, energy) | all 9, including the seasons check AST221 hides |
| 4 | **Stellar Parallax** | 9 (Tycho's search for parallax) | Parallax: Earth's orbit as a baseline (AST101's own version: no parsec; distance in light-years, set against what Tycho could measure) |
| 5 | Weighing the Solar System | 10 (Newton's version of Kepler III) | all 7 |
| 6 | **Luminosity & Flux** | 22 (light spreads out with distance) | Luminosity · Gaussian bubble · Flux · Check: moving Earth out · Check: a bigger bubble |
| 7 | Hot Bodies Glow | 13 (thermal light), 22 (planet temperatures, albedo), 32 (habitable zone) | Blackbody · Stefan–Boltzmann · How warm is each planet? · Mars's seasons of distance · Around a different star · Check: the Sun's colour |
| 8 | **Spectral Lines** | 14 (atoms and spectra) | Where absorption lines come from |
| 9 | Telescope Power | 15 (collecting area, resolution), 17 (seeing, AO) | A bigger bucket for light · Check: a 1-second exposure · Diffraction and seeing · Check: resolving the binary (AST101's own versions of all but the exposure check: light as "times too faint for your eye" instead of magnitudes, resolution in arcseconds instead of radians) |
| 10 | The Sun Wobbles Too | 11 (centre of mass), 30–31 (exoplanet detection) | The Sun orbits too · Radial velocity · Transits and eclipses · Check: which star is heavier? |

The steps AST221 hides as beyond its slides (seasons, planet temperatures, Mars's distance, the habitable
zone) are in AST101's slides, so this bundle shows them.

## AST101 topics with no lesson yet

These are mostly about planets and small bodies (Days 18–29). Many of them suit the simulation well.

| Slide days | Topic | Fit |
| --- | --- | --- |
| 7 | Moon phases, synchronous rotation, lunar and solar eclipses, why not every month | Strong: the ELP/MPP02 Moon and the eclipse paths are already in the sim |
| 9 | Retrograde motion of Mars, phases of Venus, geocentric vs heliocentric | Strong: the sky view shows the real retrograde loops |
| 12 | Tides, tidal locking, spring and neap tides | Partial: needs a tidal-bulge overlay |
| 3–4 | Scale of the Solar System, light travel time | Strong: the light-time view exists |
| 18 | Patterns in the Solar System, nebular theory, frost line | Partial: orderly orbits are real, and the disk needs a what-if |
| 20–23 | Planet interiors, geology, atmospheres, greenhouse effect, Venus vs Earth | Weak to partial: mostly surface and atmosphere science |
| 25–27 | Giant planets, the Io–Europa–Ganymede resonance, rings and shepherd moons | Strong for the resonance and ring speeds (Kepler III), since the moons are on real orbits |
| 28–29 | Asteroid belt and Kirkwood gaps, comets and their tails, meteor showers, Pluto–Charon | Strong: real minor bodies, comet tails and meteor showers are in the sim |
| 32–33 | Transit spectroscopy, exoplanet demographics, the Drake equation | Weak: charts, not 3-D scenes |
