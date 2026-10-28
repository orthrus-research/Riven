# Project commitments

Accepted on October 19, 2026.

## Ownership and runtime

- Riven owns a deterministic terrain model, exact native cube content and
  the shared content rules used to produce efficient distant representations.
  Original Riven material uses [NRR 1.0](../LICENSE), with the exceptions in
  [NOTICE.md](../NOTICE.md).
- Cubature is the final public name of the CubicChunks fork Orthrus Research maintains
  independently. It owns cubic scheduling, lighting, lifetime, storage,
  delivery and integration with Distant Horizons. Preserve upstream rights,
  notices and source obligations. Public branding uses Cubature; existing
  mod IDs, Java packages and world formats remain compatibility interfaces.
- Distant Horizons is an official upstream dependency. Its separately
  installed JAR remains byte-for-byte upstream. Cubature supplies the runtime
  compatibility and optimization patches through Mixins or ASM. Runtime
  patching can change DH behavior; an unchanged on-disk JAR does not imply
  unchanged runtime classes or an exemption from DH's license.
- Cleanroom remains the official, unchanged runtime. Integrations must stay
  within its license. Fugue remains an explicit runtime dependency.
- Supersymmetry, Susy-Core and their removed content/dependency closure are
  outside the active profile. SeedGen-like capabilities will be independently
  implemented from Riven's requirements and existing terrain model.

The exact versions and hashes live in `runtime.lock.json`, `dev/cc.lock.json`
and `dev/dh.lock.json`. New official DH releases require a reviewed pin update.
The former DH fork and its patch chain are historical evidence. Moving any
of its work into Cubature requires explicit implementation, preserved
provenance and license review, and new qualification; the architecture
decision alone does not port or validate those optimizations.

## Runtime patch rules

Use public interfaces when they provide the required behavior. Keep necessary
class transformations and DH-specific integration inside Cubature, bound to
the supported DH version and relevant cubic-world behavior. Preserve normal
noncubic paths. Riven supplies terrain semantics through the shared provider;
it does not acquire DH implementation code under NRR.

Independently authored hooks do not acquire a universal exemption merely
because they are Mixins. Copied or adapted DH implementation remains subject
to its applicable license. Review each patch's source and combination terms;
include any required notices, license texts, corresponding source and build
or replacement instructions with distributions. Do not mark the entire
Cubature/DH combination as MIT or NRR based on file packaging alone.

Keep upstream copyright and modification notices. Validate both build targets
when shared code changes, and qualify new optimizations on Java 26 in fresh
owned instances. Build results alone establish neither client rendering
correctness nor an improvement from terrain request to visible frame.

## Release boundary

This finalizes project direction and the license offered for original Riven
material. It does not assert that every upstream dependency or historical
source copy is cleared for republishing. Official DH is acquired unchanged
from upstream; this project does not maintain a derivative DH distribution.

Before publishing Cubature binaries, supply matching source and the complete
build inputs/patches for the released revision, preserve the component terms,
and review the actual DH integration under those terms. Before redistributing
a complete runtime, resolve the retained audit's dependency provenance,
notice and exact-release metadata findings, including the Scalar discrepancy.
Using official upstream binaries does not itself resolve those findings.

Existing qualification records retain their exact artifact hashes and scope.
License packaging changes may be checked by comparing class and content
entries; they do not establish new native or performance qualification.
