# Lesson Plan: AST221 (Days 2–8) in the Lesson Studio

Source: AST221 *Astrophysics I: Planets, Sun, and Stars* (UTM, Prof. Hirsch), slides for
Days 2–8, the material the term test covers. Each slide topic below gets two ratings.

**Fit**: can the topic be taught inside a solar-system simulation?

- **Strong**: the sim already models the physics; the lesson is a guided scene.
- **Partial**: one real solar-system body can carry the idea, but the sim needs a new overlay or what-if.
- **Weak**: the topic is about spectra, atoms, optics or other stars. A chart or notebook teaches it
  better, so leave it out of the 3-D scene or give it only a sentence of narration.

**Difficulty**: two parts.

- **Level** is how hard the topic is for a student, using the Lesson Studio's levels (Beginner /
  Intermediate / Advanced).
- **Build** is how much work it is to make:
  - **S**: a guided tour of features the sim already has, plus narration and questions.
  - **M**: a new overlay, inset chart or what-if parameter.
  - **L**: a new subsystem.

**Done** marks topics the two existing lessons already cover.

---

## Day 2: Telescopes

| Topic | Fit | Level / Build | How it fits the sim |
| --- | --- | --- | --- |
| Angular size, small-angle approximation, the 1 AU / 1″ / 1 pc triangle | Strong | Beginner / S | The Sky from Earth view already draws the Sun and Moon at their true angular sizes, and a total eclipse shows they nearly match. Compare the Sun's size from every planet. |
| Diffraction limit, Airy pattern, resolution (θ ≈ 1.22 λ/D) | Partial | Intermediate / M | Add an aperture slider that blurs the zoomed sky view with an Airy PSF. Targets: whether a given aperture can split Pluto and Charon (~0.9″), the Galilean moons, or Saturn's rings. This also answers the slide's "binary at 10 pc, 1 AU apart" question with a solar-system example. |
| Seeing, Fried parameter r₀, speckle, adaptive optics | Weak | Intermediate / M | The sim has refraction and extinction but no turbulence. At most, add a seeing slider beside the diffraction blur (seeing-limited vs diffraction-limited). |
| Collecting area, sensitivity, signal-to-noise | Partial | Beginner / S | The sky view already computes a limiting magnitude. Tie it to aperture (+5 log₁₀(D / 7 mm)) and watch Uranus, Neptune, Titan and Pluto appear as the mirror grows. |
| Radio interferometry, baselines, VLBI | Partial | Intermediate / M | On the Earth Explorer globe, place two dishes and compute θ ≈ λ/B as you drag them apart, up to an Earth-sized baseline. The same idea leads into Day 7: Earth's orbit as a 2 AU baseline. |

## Day 3: Coordinates and Planning Observations

| Topic | Fit | Level / Build | How it fits the sim |
| --- | --- | --- | --- |
| Alt/Az, zenith, altitude of the NCP = observer latitude | Strong | Beginner / S | Already in the Sky from Earth view (horizon, grids, any location). Set the observer to Mississauga (43.55° N) and point at Polaris. |
| RA/Dec, local sidereal time, hour angle | Strong | Intermediate / S | The equatorial grid and apparent sidereal time already exist. Add a meridian line and an "LST / HA" readout for the selected object. |
| Is the target up tonight? When is it highest? (RA vs the Sun's RA) | Strong | Intermediate / S | This is the slides' Saturn example (RA 00h51m, Dec +2.6°). Rise, set and transit times and oppositions already exist in the sky view and event finder. Minimum zenith angle = \|lat − dec\|. |
| Final project: Titan's orbital period | Strong | Intermediate / M | Titan is on its real orbit. Simulate several nights of telescope views of Saturn, log Titan's offset, and fit a sinusoid for P (15.95 d). A good rehearsal for the real project. |
| Final project: Sun's rotation from sunspots | Partial | Beginner / M | The Sun already spins at 25.38 d. Track a texture feature over several days, then explain why Earth sees 27.3 d (synodic vs sidereal). The texture's spots don't evolve, so call it an idealised exercise. |
| Final project: Starlink orbital period | Weak | Beginner / L | The sim has no artificial Earth satellites. Skip it. |

## Day 4: Radiative Quantities

| Topic | Fit | Level / Build | How it fits the sim |
| --- | --- | --- | --- |
| Solid angle (4π sr; Sun ≈ Moon ≈ 6.8×10⁻⁵ sr) | Strong | Beginner / S | The flux lesson already builds 4πr² as a Riemann sum. Add the solid angle of the Sun as seen from each planet. |
| Luminosity vs flux, inverse-square law, surface flux vs flux at d | Strong | Intermediate / **Done** | **Luminosity, Flux & Intensity**: Gaussian bubble, 1/4/9 tiles, surface flux vs flux at 1 AU. |
| Intensity, F = ∫ I cosθ dΩ | Strong | Intermediate / **Done** | Same lesson: intensity doesn't change with distance, and F = πI (R/d)². |
| Monochromatic flux F_λ vs F_ν | Weak | Intermediate / M | A spectral idea with no 3-D geometry. At most, add an inset chart on the Sun. |

## Day 5: Blackbody Radiation

| Topic | Fit | Level / Build | How it fits the sim |
| --- | --- | --- | --- |
| Planck's law, Wien's law (λ_max for 5800 K) | Partial | Intermediate / M | An inset Planck curve for the real Sun (5772 K, peak ≈ 500 nm). Extension: each planet's thermal peak (~10–100 µm). |
| Stefan–Boltzmann, L = 4πR²σT⁴, flux at distance d | Strong | Intermediate / M | Equilibrium temperature of every planet, T_eq = T☉ √(R☉ / 2d) (1 − A)^¼, from the real distances. It varies with eccentricity: compare Mars at perihelion and aphelion. |
| M dwarf vs G star comparisons (F_G/F_M, L_G/L_M) | Partial | Intermediate / M | What-if: swap the Sun for a 3000 K, 0.1 R☉ star and see where Earth-temperature orbits move (L ≈ 6×10⁻⁴ L☉, so the habitable zone shrinks to ~0.025 AU). |
| Filter response curves, energy vs photon flux | Weak | Advanced / M | Numerical integration over a filter curve fits a notebook better (as in Python project 2). |

## Day 6: Magnitudes, Spectra and Spectral Types

| Topic | Fit | Level / Build | How it fits the sim |
| --- | --- | --- | --- |
| Magnitudes, Vega zero point, Δm = 5 ↔ 100× | Strong | Beginner / S | The sky view already gives V magnitudes for stars and planets. Compare Venus, Jupiter, Sirius, Vega and Polaris; watch Mars brighten toward opposition. |
| Absolute magnitude, distance modulus (Sun: m = −26.8, M = 4.74) | Strong | Intermediate / S | Show the Sun's apparent magnitude from each planet, then "move" the Sun to 10 pc. |
| Colour (B−V) is independent of distance | Partial | Beginner / S | The star catalog already has B−V and tints stars by it. Compare Betelgeuse with Rigel. |
| Spectral types OBAFGKM, brown dwarfs (L, T, Y) | Partial | Beginner / M | The catalog has no spectral-type column. Bin stars by B−V into approximate types and add a "show only O/B" or "only M" filter. |
| Bohr model, Balmer lines (656 nm), Boltzmann and Saha | Weak | Advanced / M | Atomic physics. An energy-level diagram belongs in a panel or slide, not the 3-D scene. |

## Day 7: Parallax, Kepler's Laws, Newton's Gravity

| Topic | Fit | Level / Build | How it fits the sim |
| --- | --- | --- | --- |
| Parallax and the parsec (p = 1/d) | Strong | Intermediate / M | Earth's real orbit is the baseline: a nearby star traces a yearly ellipse (exaggerated scale) and spirals once proper motion is added, as in the slides. Needs distances for a few nearby stars (Alpha Cen, Barnard's, Sirius); the catalog has proper motions but no parallaxes. |
| Hipparcos and Gaia limits (1 mas → 1 kpc; 0.1 mas → 10 kpc) | Weak | Beginner / S | Numbers only: one narration line in the parallax lesson. |
| Kepler I: ellipses, r(θ), perihelion and aphelion | Strong | Beginner / **Done** | **Orbits That Stretch**. Could add the slides' Mars example. |
| Kepler II: equal areas = angular momentum | Strong | Beginner / **Done** | Same lesson (equal-time wedges on the real Mercury). |
| Kepler III: P² = a³ | Strong | Beginner / S | Plot the planets on log P vs log a, then the Galilean and Saturnian moons on their own lines (the moon data already passes a Kepler III test). |
| Newton's form: weighing a system from P and a (51 Peg b; a star of 2 M☉) | Strong | Intermediate / S–M | Weigh the Sun from Earth, Jupiter from Io, Pluto+Charon from Charon. What-if: double the Sun's mass and watch Earth's period shorten. |
| Both bodies orbit the centre of mass | Strong | Intermediate / S | Already modelled: Pluto and Charon orbit a point between them, and the barycentric frame shows the Sun's own wobble. |

## Day 8: Binary Stars and Measuring Masses

| Topic | Fit | Level / Build | How it fits the sim |
| --- | --- | --- | --- |
| Visual binaries; minimum period from a projected separation | Partial | Beginner / S | Scale check: a 5000 AU separation is ten times Sedna's semi-major axis (~500 AU). Kepler III gives P ≳ 350,000 yr for 1 M☉. |
| Astrometric binaries (unseen companion) | Strong | Intermediate / M | Watch the Sun's real wobble about the barycentre, mostly from Jupiter, as an astrometrist at 10 pc would see it (~0.5 mas). Pluto–Charon is a resolved example. |
| Spectroscopic binaries: Doppler shift, K, P, e, sin i, mass function | Strong | Advanced / M | Plot the Sun's line-of-sight velocity, taken from the N-body state, for a chosen viewing inclination: K ≈ 12.5 m/s from Jupiter. Tilt the viewer to see the sin i effect and derive the mass function. |
| Eclipsing binaries, light curves | Strong | Intermediate / M | Use planetary transits: Jupiter's transit depth (~1%) seen from far away, plus the transits of Mercury and Venus the event finder already finds. |
| Worked example: K_A, K_B → mass ratio → M_tot → individual masses | Strong | Advanced / S | Do it on Pluto–Charon with the real velocities: K ratio = mass ratio ≈ 0.12. |

---

## Lesson sequence (built)

All twelve lessons are in the Lesson Studio (Education → Lessons), in this order. Each ends with "check yourself" questions built
from the slide problems, so the set also works as term-test practice.

| # | Lesson | Level | Build | Slide days | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | **Reading the Sky**: alt/az, NCP = latitude, RA/Dec, LST, hour angle, "can I see Saturn tonight?" | Beginner → Intermediate | S | 3 | Built |
| 2 | **How Big Does It Look?**: angular size, small angles, solid angle from each planet | Beginner | S | 2, 4 | Built |
| 3 | **Telescope Power**: aperture → limiting magnitude, diffraction blur, splitting Pluto–Charon, interferometer baselines | Intermediate | M | 2 | Built |
| 4 | **Orbits That Stretch**: Kepler I and II | Beginner | — | 7 | Existed |
| 5 | **Weighing the Solar System**: Kepler III, Newton's form, weighing the Sun, Jupiter and Pluto | Beginner → Intermediate | S–M | 7 | Built |
| 6 | **Luminosity, Flux & Intensity** | Intermediate | — | 4 | Existed |
| 7 | **Hot Bodies Glow**: Planck and Wien for the Sun, Stefan–Boltzmann, planet temperatures, the M-dwarf what-if | Intermediate | M | 5 | Built |
| 8 | **Project 1: Blackbody Radiation** (Python project 1, coded in the sim): Planck's law for the Sun, a sunspot, Wien by argmax and across the planets, Bλ vs Bν, sunlight at every planet | Intermediate | L | 4, 5 | Built |
| 9 | **How Bright?**: magnitudes, Vega, absolute magnitude, the Sun moved to 10 pc, B−V colour | Beginner → Intermediate | S | 6 | Built |
| 10 | **Measuring Distance**: parallax from Earth's orbit, parsec, proper-motion spiral, Gaia's reach | Intermediate | M | 7 | Built |
| 11 | **The Sun Wobbles Too**: centre of mass, astrometric wobble, radial-velocity curve, sin i, transits, binary-star masses | Advanced | M | 7, 8 | Built |
| 12 | **Titan Lab** (project rehearsal): log Titan nightly, fit its period, recover Saturn's mass | Intermediate | M | 3, 7 | Built |

**Left out of the 3-D scenes** (Weak fit; teach them with a chart, notebook or slide):

- F_λ vs F_ν
- filter integration and photon flux
- the Bohr model, Boltzmann and Saha equations
- seeing and adaptive optics
- the Starlink project

### What changed while building

- **Seeing and adaptive optics** went into Telescope Power after all, as an image simulator
  of the real Pluto–Charon pair: an Airy core holding the Strehl ratio exp(−σ²) and a seeing halo
  (∝ λ^−1/5). An air-density slider scales the turbulence (Cn² ∝ ρ², so r₀ ∝ ρ^−6/5 and the seeing
  ∝ ρ^6/5) and the extinction (∝ ρ). AO leaves its fitting error 0.28 (d/r₀)^5/3 for 0.5 m actuator
  spacing plus 200 nm of other errors, so it works in K (Strehl ≈ 0.67 on Mauna Kea) and not in V.
- **The Bohr model** got a short step in How Bright?, with an energy-level diagram beside
  Vega (the strongest Balmer lines), rather than a full lesson.
- **Spectral classes** come from each star's B − V colour, since the catalogue has no
  spectral-type column.
- **Parallax** uses catalogued parallaxes and proper motions for five named stars, with
  Earth's position from the simulation.
- **Starlink** and **filter integration** stay out, as planned.
- **Python Project 1** (blackbody radiation) became lesson 8, a coding lesson. Students fill in
  real Python cells inside the sim (run by the Python on their computer, with numpy and astropy),
  and each task is about the solar system: light time from the live Earth–Sun distance, Planck's
  law for the Sun, a sunspot, the Sun's peak by argmax and Wien's law, Wien across the planets,
  B_λ vs B_ν (so **F_λ vs F_ν** is taught after all), and the sunlight reaching Earth and every
  planet today. The notebook's SDSS stars were dropped in favour of the Sun.

- **Steps beyond the slides are hidden by default:** the seasons check (lesson 4), planet
  equilibrium temperatures, albedo and habitable zones (three steps of lesson 7), planets'
  changing brightness (lesson 8) and the aliasing check (lesson 11). They stay in the code;
  start the app with `-Dsss.lessons.all=true` to show them.

To re-render every step head-less: `-Dsss.lessonshot=<dir>`, optionally with
`-Dsss.lessonshot.only=1,9`.
