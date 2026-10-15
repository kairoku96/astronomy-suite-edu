# ELP/MPP02 lunar series

`elpmpp02.bin.gz` holds the 35 901 terms of the ELP/MPP02 lunar theory:

> J. Chapront & G. Francou, "The lunar theory ELP revisited. Introduction of new planetary
> perturbations", *Astronomy & Astrophysics* 404, 735–742 (2003).
> IMCCE / Observatoire de Paris, ftp://cyrano-se.obspm.fr/pub/2_lunar_solutions/2_elpmpp02/

The series are in evaluated form: one amplitude and one argument polynomial per term, with
the parameters fitted to lunar laser ranging. They were taken from the binaries of
[simple-elpmpp02-rs](https://github.com/twjang/simple-elpmpp02-rs) (MIT License, © 2024
Taewoong Jang), commit `bccec6c805ca6f7917ebaa8847f6dcd649b2a54b`. That project generated
them from the six IMCCE files with a transcription of the authors' Fortran setup.

Rebuild with `java tools.FetchElpMpp02` from the project root. `MoonEphemerisTest` checks
the result against the authors' reference positions and against JPL Horizons.
