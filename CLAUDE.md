# BigStitcher

`net.preibisch:BigStitcher` (artifact `BigStitcher`, "Big Stitcher") — a Fiji/ImageJ
plugin for **multiview stitching of large datasets**: aligning and fusing the
overlapping image tiles of light-sheet / multi-tile acquisitions into a single
volume. Code lives under `net.preibisch.stitcher.*` (`algorithm`, `process`, `gui`,
`plugin`, `headless`); the ImageJ menu is wired in `src/main/resources/plugins.config`
(`Plugins > BigStitcher > …`), entry point `net.preibisch.stitcher.plugin.BigStitcher`.

**Most of the heavy lifting is in [multiview-reconstruction](https://github.com/JaneliaSciComp/multiview-reconstruction)
(MVR)** — BigStitcher is largely a stitching-focused GUI/workflow on top of it. MVR is
pinned via the `multiview-reconstruction.version` property in `pom.xml`. Sibling repos
in the same family: multiview-simulation (MVS), Stitching, Descriptor_based_registration.
Cluster/cloud + command-line parallelization lives in BigStitcher-Spark (separate repo).
Built on the `pom-scijava` BOM (the version is set by `<parent>`).

## `update_fiji_deps.py` — Fiji dependency-update / migration-validation tool

Standalone Python (stdlib only; needs `mvn` on PATH; uses `~/.m2`). Built to **validate
the zarrv3 dependency migration inside a real Fiji before release**: it rewrites a Fiji
install's dependency jars to the versions the five family projects build against, drops in
the freshly built project plugin jars, and prints pom-scijava BOM comparison tables.

- **Scope** = union of the *direct* `<dependencies>` declared by the five projects (MVS,
  MVR, BigStitcher, Stitching, Descriptor_based_registration), each at the version that
  project resolves it to (max across projects). Expects the five as sibling repos under `../`.
- **Modifies Fiji IN PLACE; keeps no backup** — to undo, re-extract the Fiji ZIP.
- **Never downgrades**: a dependency Fiji already has *newer* is kept (reported under `(C)`).

### Flags
- `--dry-run` (default) | `--apply`.
- `--transitive` (**required**): `{direct, closure, full}`, each optionally `-upgrade-only`.
  - `direct` — the projects' declared direct deps only.
  - `closure` — + the transitive subtree pulled in by upgraded libs. Subtrees rooted at a
    dep Fiji already has are **pruned** (avoids dragging in OMERO/Solr/etc.), BUT a present
    transitive dep that is **older than the upgraded libs need is still upgraded** (never
    downgraded). This is the mode for the zarrv3 stack (new n5 S3/HDF5 backends).
  - `full` — + the projects' entire runtime closure.
  - `-upgrade-only` — suppress ALL new installs; only version-bump jars already present
    (project plugin jars are still installed). `closure-upgrade-only` realigns the skewed
    transitive deps without pulling in the AWS SDK.
- `--exclude G[,G...]` — artifactId / `groupId:artifactId` / groupId to never install or
  walk transitively (also deletes matching present jars). Used for the **OpenSeaDragon-export
  deps (`pyramidio, ijp-kheops, generic-archiver`) which ship via the BigStitcher update
  site, NOT core Fiji** — the code must run without them unless that export is used.
- `--remove A[,A...]` — delete matching jars by filename (e.g. obsolete AWS SDK v1
  `aws-java-sdk-*,jmespath-java` and the legacy `SPIM_Registration`).
- `--add P[,P...]` — whitelist (`g:*` / `g:a` / bare token) limiting which *new* installs are allowed.

### Canonical command (rebuilds the test Fiji reproducibly)
```
python3 update_fiji_deps.py ~/Downloads/Fiji --apply --transitive closure \
    --exclude pyramidio,ijp-kheops,generic-archiver \
    --remove aws-java-sdk-core,aws-java-sdk-s3,aws-java-sdk-kms,jmespath-java,SPIM_Registration
xattr -dr com.apple.quarantine ~/Downloads/Fiji      # clear macOS quarantine on new jars
```
Then: **do NOT run Help > Update Fiji** on that install (it can revert the swapped jars).

### Why the closure upgrades matter (key insight)
The zarrv3 wave (n5 4.x, n5-universe 3.x, n5-zarr 2.x, n5-aws-s3 5.x switched AWS SDK v1→v2)
is a coordinated set of breaking changes. A transitive dep Fiji shipped that is *too old*
for the new n5 causes a `NoSuchMethodError` at runtime, not a build error — e.g. n5 4.0.1
needs `commons-compress 1.28.0` (`GzipCompressorInputStream.builder()`), the AWS v2
`netty-nio-client` needs netty `4.1.115` (Fiji had 4.1.73), and n5-zstandard 2.0.0 needs a
newer `zstd-jni`. The `closure` present-but-too-old upgrade behavior exists specifically to
catch these.

### Caveats
- Only the five projects' graph + its closure is realigned. **Standalone Fiji plugins coupled
  to the same libraries are NOT touched and may break independently** — e.g. bumping
  n5-universe 2.x→3.x relocated `OmeNgffMetadataParser` out of the `…ome.ngff.v04` package,
  which breaks the Fiji-shipped `n5-ij` importer (`File > Import N5`) until n5-ij is bumped
  to a matching version (4.4.1→5.0.0). Not a BigStitcher concern (BigStitcher doesn't use n5-ij).
- pom-scijava 44.0.0 is pre-zarrv3 (n5 3.5.1), so the projects' override blocks are
  load-bearing; 44.0.1-SNAPSHOT has the zarrv3 stack landed (most overrides become redundant
  once it releases). Fiji's own jars come from update sites, not the BOM (e.g. bio-formats
  8.5.0 from Fiji-Latest, newer than pom-scijava's 8.4.0 → kept).
