# Planet texture assets

The renderer looks in this directory for equirectangular surface maps. Drop
files in with the naming convention below and they are picked up automatically
on the next launch — no code changes needed. Bodies without a map fall back to
their flat data-table color.

## Naming convention

```
<key>_diffuse.png|jpg     color/albedo map        (used by the current renderer)
<key>_normal.png|jpg      tangent-space normals   (reserved hook — shader pipeline)
<key>_specular.png|jpg    specular/gloss mask     (reserved hook — shader pipeline)
```

`<key>` is the lowercase body name:

`sun, mercury, venus, earth, mars, jupiter, saturn, uranus, neptune, pluto,
ceres, haumea, eris, moon`

Examples: `earth_diffuse.jpg`, `mars_diffuse.png`, `jupiter_diffuse.jpg`,
`earth_normal.png` (held for the programmable-pipeline upgrade).

Maps must be equirectangular (2:1 longitude/latitude) projections. The
renderer aligns texture poles with each body's obliquity-tilted rotation axis
and spins them at the true sidereal rate.

## Recommended sources (public domain / free)

- NASA Visible Earth (Blue Marble): https://visibleearth.nasa.gov
- USGS Astrogeology planetary mosaics: https://astrogeology.usgs.gov
- Solar System Scope textures (CC BY 4.0): https://www.solarsystemscope.com/textures/
- JPL photojournal: https://photojournal.jpl.nasa.gov

2K (2048x1024) is plenty for the fixed-function renderer; 4K+ works but loads
slower. Power-of-two dimensions are recommended (mipmaps are generated at load).
