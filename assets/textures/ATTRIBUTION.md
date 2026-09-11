# Texture attribution

The `*_diffuse.jpg` equirectangular surface maps in this directory are from
**Solar System Scope** (https://www.solarsystemscope.com/textures/), sourced at
8K where available and downsampled to 4K (2K for venus/uranus/neptune, which have
no higher-res source), and are licensed under **Creative Commons Attribution 4.0
International (CC BY 4.0)** — https://creativecommons.org/licenses/by/4.0/

These maps are derived from NASA / public planetary imagery.

Files: sun, mercury, venus (atmosphere), earth (daymap), mars, jupiter,
saturn, uranus, neptune, moon — each as `<key>_diffuse.jpg`.

Bodies without a map here (pluto, ceres, haumea, eris) fall back to their
flat data-table color.

## Earth night lights & clouds (NASA, public domain)

- `earth_night.jpg` — NASA Earth Observatory / NASA Visible Earth, "Earth's City
  Lights" (2400×1200). https://visibleearth.nasa.gov/images/55167/earths-city-lights
- `earth_clouds.jpg` — NASA Visible Earth Blue Marble cloud composite (2048×1024).
  https://visibleearth.nasa.gov/images/57747/blue-marble-clouds

NASA imagery is not copyrighted and may be used freely (with attribution) per NASA
media guidelines. Used as an emissive night-side layer and a rotating cloud layer
on Earth.

## Derived relief maps (GPU normal-map hook)

- `mars_normal.jpg` — a tangent-space **normal map derived from `mars_diffuse.jpg`**
  (luminance treated as a height proxy → Sobel gradient → normal). It is a
  procedural relief approximation, NOT measured MOLA elevation data. Consumed by
  the GPU sphere shader (`GLSphereRenderer`) to add per-pixel surface relief near
  the terminator when the GPU path is on. Inherits the CC BY 4.0 attribution of the
  diffuse map it was generated from. Drop a `<key>_normal.jpg` / `<key>_specular.jpg`
  beside any body's diffuse to light up the same hook for that body.
