# Astronomy Suite

A pair of interactive Java solar-system simulators with a built-in astronomy
curriculum that ramps from complete-beginner concepts up to second-year
stellar-astrophysics (U of T **AST 221 – Stars and Planets**) material.

There are two simulators in this repo:

| Sim | Entry point | Launch | Notes |
|-----|-------------|--------|-------|
| **2-D orbital sim** (education focus) | `src/SolarSystemSimulation.java` (+ `src/GLSphereRenderer.java`) | `./run2d.ps1` | Single-file Swing app with a GPU sphere shader. Hosts the **Lesson system**. Needs **JDK 24**. |
| **3-D sim** | `src/astrosim/` (`astrosim.Main`) | `./run.ps1` | Full 3-D OpenGL scene, orbit camera, spacecraft. |

## Requirements

- **JDK 24** (the 2-D sim uses unnamed `_ ->` variables and other Java 22+ features).
- **JOGL 2.6** + **GlueGen 2.6** natives for Windows, expected one directory *above* the
  project root (see `run.ps1` / `run2d.ps1` for the exact paths). These native libraries
  are **not** committed here — download them from the [JogAmp builds](https://jogamp.org/deployment/archive/master/)
  and place them alongside the project as the scripts expect.
- FlatLaf is bundled in `libs/`.

## Running

```powershell
# 2-D educational sim (GUI)
./run2d.ps1

# 2-D sim with the GPU sphere shader on from the start
./run2d.ps1 -Gpu

# Head-less showcase screenshots
./run2d.ps1 -Shot out\shots

# 3-D sim
./run.ps1
```

## The Lesson System

Open **Education → Enable Lesson Mode**, pick a lesson from **Education → Select Lesson**,
then **Start Guided Tour**. Press **N** to advance a tour step.

The curriculum is organised as a tiered ladder — foundations → orbital mechanics →
planetary science → stellar astrophysics — designed to carry a learner from
naked-eye beginner concepts up to AST 221 level. Solar-system topics are taught as
interactive 3-D guided tours; stellar-physics topics (HR diagram, fusion, stellar
structure and evolution) are taught as illustrated in-app lesson panels.

## Planetarium: jump to a real date

**Simulation → Go to date…** scrubs the whole system to any calendar date
(e.g. `2027-03-14`), placing every planet at its real position for that moment;
the HUD shows the current simulated date. `Jump to today` and `Reset to J2000
epoch` are there too. `simTimeSec = 0` is the J2000.0 epoch, and the planets are
seeded from real J2000 mean elements, so dates map to true sky configurations
(good to well under a degree over a range of decades).

## Real comets and minor bodies

The sim ships with a catalog of real Solar-System objects on their true orbits —
comets (1P/Halley, 2P/Encke, 55P/Tempel-Tuttle, 109P/Swift-Tuttle,
C/1995 O1 Hale-Bopp), asteroids and near-Earth objects (Vesta, Pallas, Juno,
Hygiea, Eros, Bennu), and distant objects (Chiron, Quaoar, Sedna). The
**Objects** menu toggles each category and the labels. Comets grow a diffuse
anti-sunward ion + dust tail as they near the Sun, drawn at true physical scale.

Pair this with **Simulation → Go to date…**: e.g. jump to `1986-02-09` to watch
1P/Halley round the Sun with its tail.

## Repository layout

```
src/SolarSystemSimulation.java   2-D Swing sim + lesson system
src/GLSphereRenderer.java        GPU sphere shader for the 2-D sim
src/astrosim/                    3-D OpenGL sim
src/tools/ScreenGrab.java        screenshot helper
assets/textures/                 planet / moon / sun texture maps
libs/                            bundled FlatLaf look-and-feel
run.ps1 / run2d.ps1              build + launch scripts
```

## License

See [LICENSE](LICENSE).
