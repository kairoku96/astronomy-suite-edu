# Stellar spectra (AST221 Python Project 1)

Eight optical spectra from the **Sloan Digital Sky Survey**, in SDSS's own `spec-*.fits`
format (gzipped here, otherwise byte-for-byte the files handed out with
*Project 1: Investigating Blackbody Radiation*). `SdssSpectra.java` reads them for the
Lesson Studio's project lesson.

- **HDU 1 (COADD)**: `loglam` (log₁₀ of the vacuum wavelength in Å), `flux` (10⁻¹⁷ erg s⁻¹ cm⁻² Å⁻¹),
  `ivar` (inverse variance of the flux), 3825–3826 pixels from 382 to 922 nm.
- **HDU 2 (SPECOBJ)**: the pipeline's fit. `ELODIE_*` columns describe the star in the
  ELODIE library whose spectrum matched best.

All eight are fibres of plate 2667, observed on MJD 54142 (2007 February 11). They lie within
13′ of the centre of the old open cluster **M67** (NGC 2682, about 860 pc away). The `HD` number
in each file name is the ELODIE **template** star, not the star SDSS observed: the temperatures,
spectral types, log g and [Fe/H] are the template's.

| File | Fibre | Template type | T_eff (K) |
| --- | --- | --- | --- |
| spec-HD010476 | 371 | K1V | 5169 |
| spec-HD014374 | 361 | G0 | 5261 |
| spec-HD019019 | 488 | F8 | 5932 |
| spec-HD024053 | 445 | G0 | 5480 |
| spec-HD024552 | 408 | G0 | 5671 |
| spec-HD039587 | 464 | G0V | 5913 |
| spec-HD158614 | 485 | G8IV-V | 5563 |
| spec-HD165341 | 469 | K0V | 5139 |

Data: Sloan Digital Sky Survey (https://www.sdss.org), free to use with acknowledgement.
Funding for SDSS and SDSS-II was provided by the Alfred P. Sloan Foundation, the
Participating Institutions, the National Science Foundation, the U.S. Department of Energy,
NASA, the Japanese Monbukagakusho, the Max Planck Society and the Higher Education Funding
Council for England.
