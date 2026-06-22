#!/usr/bin/env python3
"""
update_fiji_deps.py — make a Fiji install carry the dependency versions that the
five Preibisch-lab projects (multiview-simulation, multiview-reconstruction,
BigStitcher, Stitching, Descriptor_based_registration) build against, then drop in
the freshly built project plugin jars.

Scope = the union of the *direct* `<dependencies>` each project's pom explicitly
declares, at the version that project resolves them to. For each such dependency:
  - present in Fiji but older  -> UPGRADE
  - present in Fiji but newer   -> KEPT (never downgraded)
  - absent from Fiji            -> ADD (install), so Fiji is self-contained
Libraries the projects don't declare are left untouched.

Standard library only. Requires `mvn` on PATH; uses your local ~/.m2 and downloads
only what's missing. Modifies the Fiji install IN PLACE — to get the original back,
re-extract your Fiji ZIP (the script keeps no backup).

Example — build/refresh the test Fiji in one reproducible call (run on a freshly
re-extracted Fiji), then clear macOS quarantine on the new native jars:
    python3 update_fiji_deps.py ~/Downloads/Fiji --apply --transitive closure \\
        --exclude pyramidio,ijp-kheops,generic-archiver \\
        --remove aws-java-sdk-core,aws-java-sdk-s3,aws-java-sdk-kms,jmespath-java,SPIM_Registration
    xattr -dr com.apple.quarantine ~/Downloads/Fiji
"""

from __future__ import annotations

import argparse
import functools
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

# --------------------------------------------------------------------------- #
# Constants
# --------------------------------------------------------------------------- #

POM_NS = "http://maven.apache.org/POM/4.0.0"
_ANSI = re.compile(r"\x1b\[[0-9;]*m")

M2 = Path.home() / ".m2" / "repository"

SCIJAVA_REPO = "https://maven.scijava.org/content/groups/public"
POM_SCIJAVA_METADATA = f"{SCIJAVA_REPO}/org/scijava/pom-scijava/maven-metadata.xml"

# The zarrv3 / imaging stack shown in the BOM comparison tables (display order).
MIGRATION_ARTIFACTS = [
    ("n5",                    "org.janelia.saalfeldlab", "n5"),
    ("n5-zarr",               "org.janelia.saalfeldlab", "n5-zarr"),
    ("n5-universe",           "org.janelia.saalfeldlab", "n5-universe"),
    ("n5-aws-s3",             "org.janelia.saalfeldlab", "n5-aws-s3"),
    ("n5-imglib2",            "org.janelia.saalfeldlab", "n5-imglib2"),
    ("n5-blosc",              "org.janelia.saalfeldlab", "n5-blosc"),
    ("n5-hdf5",               "org.janelia.saalfeldlab", "n5-hdf5"),
    ("n5-google-cloud",       "org.janelia.saalfeldlab", "n5-google-cloud"),
    ("n5-zstandard",          "org.janelia",             "n5-zstandard"),
    ("imglib2",               "net.imglib2",             "imglib2"),
    ("imglib2-cache",         "net.imglib2",             "imglib2-cache"),
    ("imglib2-realtransform", "net.imglib2",             "imglib2-realtransform"),
    ("imglib2-algorithm",     "net.imglib2",             "imglib2-algorithm"),
    ("bigdataviewer-core",    "sc.fiji",                 "bigdataviewer-core"),
    ("mpicbg",                "mpicbg",                  "mpicbg"),
]

# Temp settings.xml used only to resolve an unreleased pom-scijava SNAPSHOT parent
# (snapshots live on the scijava repo, not Maven Central).
_SNAP_SETTINGS = f"""<settings>
  <profiles><profile><id>scijava</id>
    <repositories><repository><id>scijava.public</id><url>{SCIJAVA_REPO}</url>
      <releases><enabled>true</enabled></releases>
      <snapshots><enabled>true</enabled></snapshots></repository></repositories>
    <pluginRepositories><pluginRepository><id>scijava.public</id><url>{SCIJAVA_REPO}</url>
      <releases><enabled>true</enabled></releases>
      <snapshots><enabled>true</enabled></snapshots></pluginRepository></pluginRepositories>
  </profile></profiles>
  <activeProfiles><activeProfile>scijava</activeProfile></activeProfiles>
</settings>
"""


# --------------------------------------------------------------------------- #
# Small helpers
# --------------------------------------------------------------------------- #

def log(msg: str = "") -> None:
    print(msg, flush=True)


def warn(msg: str) -> None:
    print(f"  ! {msg}", file=sys.stderr, flush=True)


def die(msg: str, code: int = 1) -> "None":
    print(f"ERROR: {msg}", file=sys.stderr, flush=True)
    sys.exit(code)


def run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True)


def mvn_bin() -> str:
    exe = shutil.which("mvn")
    if not exe:
        die("`mvn` not found on PATH (needed for help:effective-pom / dependency:get).")
    return exe


def parse_pom_properties(text: str) -> dict:
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        out[k.strip()] = v.strip()
    return out


# Maven-style qualifier ordering (lower = older). Release/"" is the baseline; a
# numeric token outranks any qualifier at the same position.
_QUAL_RANK = {
    "alpha": -6, "a": -6, "beta": -5, "b": -5, "milestone": -4, "m": -4,
    "rc": -3, "cr": -3, "snapshot": -2, "": 0, "ga": 0, "final": 0,
    "release": 0, "sp": 1,
}


def _ver_tokens(v: str) -> list:
    v = v.lower().replace("-", ".").replace("_", ".")
    toks = []
    for seg in v.split("."):
        toks.extend(re.findall(r"\d+|[a-z]+", seg))
    return toks


def compare_versions(a: str, b: str) -> int:
    """Maven-ish version compare. Returns 1 if a>b, -1 if a<b, 0 if equal.
    Good enough to distinguish upgrades from downgrades for our jars."""
    ta, tb = _ver_tokens(a), _ver_tokens(b)
    for i in range(max(len(ta), len(tb))):
        x = ta[i] if i < len(ta) else "0"
        y = tb[i] if i < len(tb) else "0"
        xd, yd = x.isdigit(), y.isdigit()
        if xd and yd:
            c = (int(x) > int(y)) - (int(x) < int(y))
        elif xd:
            c = 1                      # numeric outranks a qualifier
        elif yd:
            c = -1
        else:
            rx = _QUAL_RANK.get(x, 0.5)   # unknown qualifier ranks above known ones
            ry = _QUAL_RANK.get(y, 0.5)
            c = (rx > ry) - (rx < ry) or (x > y) - (x < y)
        if c:
            return c
    return 0


# --------------------------------------------------------------------------- #
# pom-scijava BOM resolution (used only for the comparison tables)
# --------------------------------------------------------------------------- #

def effective_pom_xml(mvn: str, pom: Path, offline: bool, soft: bool = False,
                      settings: Path | None = None):
    cmd = [mvn, "-B"]
    if offline:
        cmd.append("-o")
    if settings:
        cmd += ["-s", str(settings)]
    cmd += ["-f", str(pom), "help:effective-pom"]
    cp = run(cmd)
    out = cp.stdout
    start = out.find("<project")
    end = out.rfind("</project>")
    if start == -1 or end == -1:
        if soft:
            return None
        sys.stderr.write(cp.stdout[-2000:])
        sys.stderr.write(cp.stderr[-2000:])
        die(f"could not extract effective-pom XML from `mvn help:effective-pom -f {pom}` "
            f"(offline={offline}). Is the project resolvable?")
    return out[start:end + len("</project>")]


_MINIMAL_POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.scijava</groupId>
    <artifactId>pom-scijava</artifactId>
    <version>{v}</version>
    <relativePath/>
  </parent>
  <groupId>tmp.fiji.deps</groupId>
  <artifactId>base-probe</artifactId>
  <version>0</version>
</project>
"""


def parent_version(pom: Path) -> str | None:
    m = re.search(r"<parent>.*?<version>(.*?)</version>.*?</parent>",
                  pom.read_text(), flags=re.S)
    return m.group(1).strip() if m else None


def base_effective_dm(mvn: str, scijava_version: str, offline: bool) -> dict:
    """pom-scijava default dependencyManagement (no project overrides), via a
    throwaway minimal pom. Used only to attribute provenance of each version."""
    with tempfile.TemporaryDirectory() as td:
        pom = Path(td) / "pom.xml"
        pom.write_text(_MINIMAL_POM.format(v=scijava_version))
        xml = effective_pom_xml(mvn, pom, offline, soft=True)
    return parse_dependency_management(xml) if xml else {}


def _ver_key(v: str) -> list:
    return [int(p) if p.isdigit() else -1 for p in re.split(r"[.-]", v)]


def latest_snapshot_bom_version() -> str | None:
    """Newest pom-scijava *-SNAPSHOT from the scijava repo metadata (network).
    Retries a couple of times to ride out a transient blip on a flaky connection."""
    data = None
    for _ in range(3):
        try:
            data = urllib.request.urlopen(POM_SCIJAVA_METADATA, timeout=10).read().decode()
            break
        except Exception:
            continue
    if data is None:
        return None
    snaps = [v for v in re.findall(r"<version>(.*?)</version>", data) if v.endswith("-SNAPSHOT")]
    snaps.sort(key=_ver_key)
    return snaps[-1] if snaps else None


def snapshot_bom_dm(mvn: str, version: str) -> dict:
    """Effective dependencyManagement of an unreleased pom-scijava SNAPSHOT,
    resolved via a temp settings.xml that enables the scijava snapshots repo."""
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        (td / "settings.xml").write_text(_SNAP_SETTINGS)
        (td / "pom.xml").write_text(_MINIMAL_POM.format(v=version))
        xml = effective_pom_xml(mvn, td / "pom.xml", offline=False, soft=True,
                                settings=td / "settings.xml")
    return parse_dependency_management(xml) if xml else {}


def print_bom_table(title: str, fiji: dict, bom: dict, projects: dict) -> None:
    """Compare the imaging/zarrv3 stack across Fiji, a pom-scijava BOM, and the
    version the five projects resolve each dependency to."""
    log(f"\n{title}")
    log(f"  {'artifact':24}{'Fiji':16}{'pom-scijava':16}{'your projects':18}note")
    log("  " + "-" * 86)
    for name, g, a in MIGRATION_ARTIFACTS:
        ga = (g, a)
        fv, pv, ov = fiji.get(ga, "-"), bom.get(ga, "-"), projects.get(ga, "-")
        if ov == "-":
            note = ""
        elif ov == pv:
            note = "matches pom-scijava (no override)"
        else:
            note = "<- override (pom-scijava still older)"
        log(f"  {name:24}{fv:16}{pv:16}{ov:18}{note}")


def parse_dependency_management(xml_text: str) -> dict:
    """Return {(groupId, artifactId): version} from <dependencyManagement> ONLY.
    Classifier'd managed entries collapse to the same GA (we key on version)."""
    root = ET.fromstring(xml_text)

    def q(tag: str) -> str:
        return f"{{{POM_NS}}}{tag}"

    dm = root.find(q("dependencyManagement"))
    result: dict = {}
    if dm is None:
        return result
    deps = dm.find(q("dependencies"))
    if deps is None:
        return result
    for dep in deps.findall(q("dependency")):
        g = dep.findtext(q("groupId"))
        a = dep.findtext(q("artifactId"))
        v = dep.findtext(q("version"))
        if not g or not a or not v:
            continue
        if "${" in v:  # unresolved property — should not happen in an effective pom
            continue
        result[(g, a)] = v
    return result


# --------------------------------------------------------------------------- #
# The target set: direct dependencies the projects explicitly declare
# --------------------------------------------------------------------------- #

def _ns_of(root) -> str:
    return root.tag[1:root.tag.index("}")] if root.tag.startswith("{") else ""


def declared_direct_deps(pom: Path) -> dict:
    """{(groupId, artifactId): scope} for the pom's OWN top-level <dependencies>
    (not dependencyManagement, not profiles/build). Test scope excluded."""
    root = ET.fromstring(pom.read_text())
    ns = _ns_of(root)
    q = (lambda t: f"{{{ns}}}{t}") if ns else (lambda t: t)
    deps = root.find(q("dependencies"))
    out: dict = {}
    if deps is None:
        return out
    for d in deps.findall(q("dependency")):
        g, a = d.findtext(q("groupId")), d.findtext(q("artifactId"))
        scope = (d.findtext(q("scope")) or "compile").strip()
        if not g or not a or scope == "test":
            continue
        out[(g.strip(), a.strip())] = scope
    return out


def _parse_dependency_list(text: str) -> dict:
    """Parse `mvn dependency:list -DoutputFile=` output -> {(groupId, artifactId): version}.
    Lines look like `g:a:type[:classifier]:version:scope`; test scope is dropped."""
    out: dict = {}
    for line in text.splitlines():
        line = _ANSI.sub("", line).split(" -- ")[0].strip()
        if " " in line:                       # header / log lines
            continue
        parts = line.split(":")
        if len(parts) < 5:
            continue
        g, a = parts[0], parts[1]
        version, scope = (parts[4], parts[5]) if len(parts) >= 6 else (parts[3], parts[4])
        if scope.strip() == "test":
            continue
        out[(g, a)] = version
    return out


def resolved_direct_deps(mvn: str, repo: Path) -> dict:
    """{(groupId, artifactId): version} of the project's *direct* dependencies,
    resolved by `mvn dependency:list -DexcludeTransitive=true` (compile+runtime)."""
    with tempfile.TemporaryDirectory() as td:
        outf = Path(td) / "deps.txt"
        cmd = [mvn, "-B", "-o", "-DexcludeTransitive=true", "-DincludeScope=runtime",
               f"-DoutputFile={outf}", "-DappendOutput=false",
               "dependency:list", "-f", str(repo / "pom.xml")]
        cp = run(cmd)
        if not outf.is_file():
            tail = cp.stdout.strip().splitlines()[-1:] or [str(cp.returncode)]
            warn(f"dependency:list failed for {repo.name}: {tail[0]}")
            return {}
        return _parse_dependency_list(outf.read_text())


_CLOSURE_POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>org.scijava</groupId><artifactId>pom-scijava</artifactId>
    <version>{sjv}</version><relativePath/></parent>
  <groupId>tmp.fiji.deps</groupId><artifactId>closure-probe</artifactId><version>0</version>
  <repositories><repository><id>scijava.public</id><url>{repo}</url></repository></repositories>
  <dependencies>
{deps}
  </dependencies>
</project>
"""


_TREE_LINE = re.compile(r"^([| +\\-]*)([A-Za-z0-9].*)$")


def transitive_frontier(mvn: str, scijava_version: str, deps: list,
                        present_gas: set, direct_gas: set, project_gas: set) -> dict:
    """Absent transitive deps hanging off the given (changed) deps -> {(g,a): version}.
    Walks `mvn dependency:tree` and PRUNES any subtree rooted at a dep Fiji already
    has (or that we handle directly) — so e.g. ijp-kheops -> bio-formats(present) -> OMERO
    is not pulled in, while n5-aws-s3 -> (absent) AWS SDK v2 is. Offline-first."""
    if not deps:
        return {}
    dep_xml = "\n".join(
        f"    <dependency><groupId>{g}</groupId><artifactId>{a}</artifactId>"
        f"<version>{v}</version></dependency>" for g, a, v in deps)
    pom_text = _CLOSURE_POM.format(sjv=scijava_version, repo=SCIJAVA_REPO, deps=dep_xml)
    tree = None
    for offline in (True, False):
        with tempfile.TemporaryDirectory() as td:
            (Path(td) / "pom.xml").write_text(pom_text)
            outf = Path(td) / "tree.txt"
            cmd = [mvn, "-B", f"-DoutputFile={outf}", "-DappendOutput=false",
                   "dependency:tree", "-f", str(Path(td) / "pom.xml")]
            if offline:
                cmd.insert(1, "-o")
            run(cmd)
            if outf.is_file():
                tree = outf.read_text()
                break
    if tree is None:
        warn("could not resolve transitive tree (offline and online both failed)")
        return {}
    frontier: dict = {}
    prune_at = None      # skip nodes deeper than this (a pruned subtree)
    for line in tree.splitlines():
        m = _TREE_LINE.match(_ANSI.sub("", line.rstrip()))
        if not m:
            continue
        depth = len(m.group(1)) // 3
        parts = m.group(2).split(":")
        if len(parts) < 5:               # root probe line, etc.
            continue
        if prune_at is not None and depth > prune_at:
            continue
        prune_at = None
        if depth < 2:                    # root (0) and the changed deps (1): descend, don't add
            continue
        g, a, version, scope = parts[0], parts[1], parts[-2], parts[-1]
        if scope in ("test", "provided", "system"):
            prune_at = depth
            continue
        ga = (g, a)
        if ga in present_gas or ga in direct_gas or ga in project_gas:
            prune_at = depth             # Fiji already provides it -> prune its subtree
        else:
            frontier[ga] = version       # absent -> add, and descend into its children
    return frontier


def full_runtime_closure(mvn: str, projects: list) -> dict:
    """Union of the projects' full runtime dependency lists (transitive), keeping
    the newest version seen per (groupId, artifactId)."""
    clo: dict = {}
    for repo, _g, _a in projects:
        with tempfile.TemporaryDirectory() as td:
            outf = Path(td) / "deps.txt"
            run([mvn, "-B", "-o", "-DincludeScope=runtime", f"-DoutputFile={outf}",
                 "-DappendOutput=false", "dependency:list", "-f", str(repo / "pom.xml")])
            if not outf.is_file():
                continue
            for ga, v in _parse_dependency_list(outf.read_text()).items():
                if ga not in clo or compare_versions(v, clo[ga]) > 0:
                    clo[ga] = v
    return clo


def build_direct_targets(mvn: str, projects: list, project_gas: set) -> dict:
    """Union of the 5 projects' declared direct deps -> version (max across
    projects). Excludes the project self-artifacts (installed separately)."""
    per: dict = {}   # GA -> {version: [repo, ...]}
    for repo, _g, _a in projects:
        declared = declared_direct_deps(repo / "pom.xml")
        resolved = resolved_direct_deps(mvn, repo)
        cnt = 0
        for ga in declared:
            if ga in project_gas:
                continue
            v = resolved.get(ga)
            if not v:
                continue
            per.setdefault(ga, {}).setdefault(v, []).append(repo.name)
            cnt += 1
        log(f"  {repo.name}: {cnt} declared direct deps")
    targets: dict = {}
    for ga, vmap in per.items():
        best = max(vmap, key=functools.cmp_to_key(compare_versions))
        targets[ga] = best
        if len(vmap) > 1:
            detail = ", ".join(f"{v} ({'/'.join(ps)})" for v, ps in vmap.items())
            log(f"  note: {ga[0]}:{ga[1]} declared at multiple versions [{detail}] "
                f"-> using newest {best}")
    return targets


# --------------------------------------------------------------------------- #
# Jar identity
# --------------------------------------------------------------------------- #

class JarId:
    __slots__ = ("path", "group", "artifact", "version", "classifier", "via")

    def __init__(self, path, group, artifact, version, classifier, via):
        self.path = path
        self.group = group
        self.artifact = artifact
        self.version = version
        self.classifier = classifier  # "" if none, None if unknown
        self.via = via                # "pom.properties" | "filename"

    @property
    def ga(self):
        return (self.group, self.artifact)


def _classifier_from_name(stem: str, artifact: str, version: str):
    base = f"{artifact}-{version}"
    if stem == base:
        return ""
    if stem.startswith(base + "-"):
        return stem[len(base) + 1:]
    return None  # filename version disagrees with metadata; classifier unknown


def read_jar_identity(jar: Path, known_artifacts: set | None = None) -> JarId | None:
    """Identify a jar. Authority order: embedded pom.properties, then a
    filename heuristic disambiguated against known artifactIds."""
    stem = jar.name[:-4] if jar.name.endswith(".jar") else jar.name
    try:
        with zipfile.ZipFile(jar) as zf:
            candidates = []
            for name in zf.namelist():
                if re.match(r"META-INF/maven/[^/]+/[^/]+/pom\.properties$", name):
                    props = parse_pom_properties(zf.read(name).decode("utf-8", "replace"))
                    g, a, v = props.get("groupId"), props.get("artifactId"), props.get("version")
                    if g and a and v:
                        candidates.append((g, a, v))
            # Prefer the embedded artifact whose id prefixes the filename
            # (uber jars embed several pom.properties).
            chosen = None
            for g, a, v in candidates:
                if stem == f"{a}-{v}" or stem.startswith(f"{a}-{v}-"):
                    if chosen is None or len(a) > len(chosen[1]):
                        chosen = (g, a, v)
            if chosen is None and len(candidates) == 1:
                chosen = candidates[0]
            if chosen:
                g, a, v = chosen
                return JarId(jar, g, a, v, _classifier_from_name(stem, a, v), "pom.properties")
    except zipfile.BadZipFile:
        warn(f"not a valid zip, skipping: {jar.name}")
        return None

    # Fallback (no pom.properties): match the filename against known artifactIds,
    # treating '-' and '_' as EQUIVALENT so an underscore-renamed plugin jar
    # (multiview_reconstruction-8.1.2.jar) still maps to its canonical hyphenated
    # artifactId and isn't missed -> wrongly re-added. The matched artifactId must
    # be followed by a version digit, so "jna-platform-5.14.0" does NOT match "jna".
    if known_artifacts:
        norm_stem = stem.replace("_", "-")
        best = None  # (canonical_artifactId, normalized_len)
        for a in known_artifacts:
            na = a.replace("_", "-")
            if norm_stem == na or (norm_stem.startswith(na + "-")
                                   and norm_stem[len(na) + 1:len(na) + 2].isdigit()):
                if best is None or len(na) > best[1]:
                    best = (a, len(na))
        if best is not None:
            rest = norm_stem[best[1]:].lstrip("-")   # version (no classifier split here)
            return JarId(jar, None, best[0], rest, "", "filename")
    return None


def jar_groupid(jar: Path, artifact_id: str) -> str | None:
    """groupId recorded in the jar's pom.properties for this artifactId, or None
    when the jar has no such metadata (groupId then simply unknown)."""
    try:
        with zipfile.ZipFile(jar) as zf:
            for name in zf.namelist():
                if re.match(r"META-INF/maven/[^/]+/[^/]+/pom\.properties$", name):
                    props = parse_pom_properties(zf.read(name).decode("utf-8", "replace"))
                    if props.get("artifactId") == artifact_id:
                        return props.get("groupId")
    except (zipfile.BadZipFile, OSError):
        pass
    return None


def find_jars_by_artifact(fiji: Path, artifact_id: str, group_id: str | None = None) -> list:
    """Jars named `<artifactId>-<version…>.jar` in jars/ or plugins/ (filename-based,
    so it also catches jars without pom.properties). When group_id is given, a jar
    whose pom.properties reports a DIFFERENT groupId is skipped — so a generic name
    like 'utils' doesn't match across groups (org.renjin:utils vs
    software.amazon.awssdk:utils). Jars with unknown groupId still match."""
    out = []
    for sub in ("jars", "plugins"):
        d = fiji / sub
        if not d.is_dir():
            continue
        for p in sorted(d.glob(f"{artifact_id}-*.jar")):
            if not p.name[len(artifact_id) + 1:len(artifact_id) + 2].isdigit():
                continue
            if group_id is not None:
                g = jar_groupid(p, artifact_id)
                if g is not None and g != group_id:
                    continue              # same artifactId, different library -> not a match
            out.append(p)
    return out


def find_present_jar(fiji: Path, group_id: str | None, artifact_id: str) -> tuple | None:
    """First present jar for this (groupId, artifactId) as (path, version), else None."""
    hits = find_jars_by_artifact(fiji, artifact_id, group_id)
    if not hits:
        return None
    return (hits[0], hits[0].name[len(artifact_id) + 1:-4])


def scan_fiji_jars(fiji: Path, backup_root: Path, known_artifacts: set) -> list[JarId]:
    jars: list[JarId] = []
    for sub in ("jars", "plugins"):
        d = fiji / sub
        if not d.is_dir():
            continue
        for jar in d.rglob("*.jar"):
            if backup_root in jar.parents:
                continue
            jid = read_jar_identity(jar, known_artifacts)
            if jid is not None:
                jars.append(jid)
    return jars


# --------------------------------------------------------------------------- #
# Project coordinates + built jars
# --------------------------------------------------------------------------- #

def project_coords(pom: Path) -> tuple[str, str]:
    """(groupId, artifactId) of the project itself (parent block stripped)."""
    text = pom.read_text()
    text = re.sub(r"<parent>.*?</parent>", "", text, flags=re.S)

    def first(tag):
        m = re.search(rf"<{tag}>(.*?)</{tag}>", text, flags=re.S)
        return m.group(1).strip() if m else None

    return first("groupId"), first("artifactId")


def find_built_jar(repo: Path) -> Path | None:
    """Newest main artifact jar in target/ (excludes tests/sources/javadoc/original)."""
    tdir = repo / "target"
    if not tdir.is_dir():
        return None
    cands = [
        p for p in tdir.glob("*.jar")
        if not re.search(r"-(tests|sources|javadoc|original)\.jar$", p.name)
    ]
    if not cands:
        return None
    return max(cands, key=lambda p: p.stat().st_mtime)


# --------------------------------------------------------------------------- #
# Artifact resolution (for dependency updates)
# --------------------------------------------------------------------------- #

def m2_file(g: str, a: str, v: str, classifier: str) -> Path:
    fname = f"{a}-{v}" + (f"-{classifier}" if classifier else "") + ".jar"
    return M2 / Path(*g.split(".")) / a / v / fname


def resolve_artifact(mvn: str, g: str, a: str, v: str, classifier: str) -> Path | None:
    """Path to the jar in ~/.m2 — used directly if already cached (offline), else
    downloaded via `mvn dependency:get`."""
    direct = m2_file(g, a, v, classifier)
    if direct.is_file():
        return direct
    if v.endswith("-SNAPSHOT"):
        # timestamped snapshots in ~/.m2 have non-literal filenames
        hits = sorted((direct.parent).glob(f"{a}-*{('-' + classifier) if classifier else ''}.jar")) \
            if direct.parent.is_dir() else []
        return hits[-1] if hits else None
    artifact = f"{g}:{a}:{v}" + (f":jar:{classifier}" if classifier else "")
    cp = run([mvn, "-B", "dependency:get", f"-Dartifact={artifact}"])
    if direct.is_file():
        return direct
    warn(f"could not resolve {artifact}: "
         f"{cp.stdout.strip().splitlines()[-1] if cp.stdout.strip() else cp.returncode}")
    return None


def needs_legacy_plugins_dir(jar: Path) -> bool:
    """True only for an ImageJ1 plugin — a jar shipping a root `plugins.config`,
    which ImageJ1's legacy menu scan discovers from the plugins/ folder.

    NOT used for ImageJ2/SciJava plugins (those carry @Plugin annotations indexed
    at META-INF/json/org.scijava.plugin.Plugin, no plugins.config): SciJava finds
    them anywhere on the classpath, so Fiji keeps them in jars/ alongside ordinary
    libraries — verified here (imagej-ops, imagej-plugins-commands, scijava-common
    all carry @Plugin, lack plugins.config, and live in jars/). Routing by the
    @Plugin index instead would wrongly drag every @Plugin-bearing library into
    plugins/."""
    try:
        with zipfile.ZipFile(jar) as zf:
            return "plugins.config" in zf.namelist()
    except (zipfile.BadZipFile, OSError):
        return False


def install_dir_for(fiji: Path, src_jar: Path) -> Path:
    """Where a NOT-yet-present jar should be installed: plugins/ only for ImageJ1
    plugins.config jars, jars/ for everything else (libraries AND ImageJ2 plugins,
    which are discovered from the classpath regardless of folder)."""
    return fiji / ("plugins" if needs_legacy_plugins_dir(src_jar) else "jars")


def dest_filename(artifact_id: str, version: str, classifier: str, in_plugins: bool) -> str:
    """The on-disk jar name. In plugins/, ImageJ1 only scans a jar if its filename
    contains an underscore, so the artifactId is underscore-ified: hyphens become
    underscores, or a trailing '_' is appended when it has neither
    (multiview-reconstruction -> multiview_reconstruction, BigStitcher -> BigStitcher_).
    In jars/ the Maven name is kept verbatim."""
    base = artifact_id
    if in_plugins and "_" not in base:
        base = base.replace("-", "_") if "-" in base else base + "_"
    cl = f"-{classifier}" if classifier else ""
    return f"{base}-{version}{cl}.jar"


def upgraded_name(old_name: str, old_version: str, new_version: str) -> str | None:
    """The new filename for an EXISTING jar: the current name with only the version
    token swapped. Guarantees it matches the current name apart from the version
    (so Fiji recognizes it as a new version of the same file), preserving whatever
    base name / classifier / rename convention Fiji already uses. None if the
    version can't be located in the name (caller then derives a fresh name)."""
    marker = f"-{old_version}"
    idx = old_name.rfind(marker)
    if idx == -1:
        return None
    return old_name[:idx] + f"-{new_version}" + old_name[idx + len(marker):]


def validate_jar(jar: Path, g: str, a: str, v: str) -> bool:
    """Confirm the resolved jar is a real zip whose embedded GA/version match."""
    if not zipfile.is_zipfile(jar):
        return False
    try:
        with zipfile.ZipFile(jar) as zf:
            for name in zf.namelist():
                if re.match(r"META-INF/maven/[^/]+/[^/]+/pom\.properties$", name):
                    props = parse_pom_properties(zf.read(name).decode("utf-8", "replace"))
                    if (props.get("groupId"), props.get("artifactId"), props.get("version")) == (g, a, v):
                        return True
    except zipfile.BadZipFile:
        return False
    # No pom.properties to confirm against — accept if it is at least a valid zip.
    return True


# --------------------------------------------------------------------------- #
# Apply
# --------------------------------------------------------------------------- #

def place_jar(new_src: Path, install_dir: Path, dest_name: str,
              remove: list[Path] | None = None) -> None:
    """Copy new_src into install_dir as dest_name, then delete any old copies
    (remove) that aren't the new file. No backup — restore from the Fiji ZIP."""
    dest = install_dir / dest_name
    install_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(str(new_src), str(dest))
    for old in remove or []:
        if old.is_file() and old.resolve() != dest.resolve():
            old.unlink()


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def main() -> None:
    here = Path(__file__).resolve().parent          # .../workspace/BigStitcher
    ws = here.parent                                # .../workspace
    default_projects = [
        ws / "BigStitcher",
        ws / "multiview-reconstruction",
        ws / "multiview-simulation",
        ws / "Stitching",
        ws / "Descriptor_based_registration",
    ]

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("fiji", type=Path, help="path to the Fiji installation")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--dry-run", action="store_true", default=True,
                   help="(default) report only, change nothing")
    g.add_argument("--apply", dest="apply", action="store_true",
                   help="perform the updates (restore from your Fiji ZIP to undo)")
    ap.add_argument("--transitive", required=True,
                    choices=["direct", "direct-upgrade-only", "closure",
                             "closure-upgrade-only", "full", "full-upgrade-only"],
                    help="REQUIRED. Which dependencies to handle. SCOPE: direct = the projects' "
                    "direct deps only; closure = + the subtree pulled in by upgraded libs "
                    "(pruned at deps Fiji already has; needed for the new n5 S3/HDF5 backends); "
                    "full = + the projects' entire runtime closure. The '-upgrade-only' suffix "
                    "suppresses ALL new installs: it only version-bumps dependency jars already "
                    "present in Fiji (the project plugin jars are still installed).")
    ap.add_argument("--exclude", default="", metavar="G[,G...]",
                    help="comma-separated artifactIds (or groupId:artifactId, or groupId) to "
                    "NEVER install or pull in transitively, e.g. "
                    "'pyramidio,ijp-kheops,generic-archiver' (OpenSeaDragon-export deps that "
                    "belong on the update site, not in core Fiji; code runs without them "
                    "unless that export is used). Excluding a closure root also prunes its "
                    "whole subtree.")
    ap.add_argument("--remove", default="", metavar="A[,A...]",
                    help="comma-separated artifactIds of jars to DELETE from Fiji if present, "
                    "e.g. the obsolete AWS SDK v1 'aws-java-sdk-core,aws-java-sdk-s3,"
                    "aws-java-sdk-kms,jmespath-java'. Unlike --exclude this does not touch "
                    "dependency resolution — it just deletes matching jars (filename-based, "
                    "for reproducible cleanup of unrelated cruft).")
    ap.add_argument("--add", default="", metavar="P[,P...]",
                    help="whitelist of deps to INSTALL when new (direct or transitive): when "
                    "given, a jar is added only if it matches a pattern. Patterns: "
                    "'groupId:artifactId', 'groupId:*', or bare 'groupId'/'artifactId'. E.g. "
                    "'software.amazon.awssdk:*,io.netty:*,software.amazon.eventstream' pulls just "
                    "the AWS SDK v2 stack. Upgrades of already-present jars and the project jars "
                    "are not affected.")
    args = ap.parse_args()

    dry_run = not args.apply
    scope = args.transitive.replace("-upgrade-only", "")     # direct | closure | full
    upgrade_only = args.transitive.endswith("-upgrade-only")  # no new installs, bumps only
    fiji: Path = args.fiji.expanduser().resolve()
    if not fiji.is_dir():
        die(f"not a directory: {fiji}")
    if not (fiji / "jars").is_dir():
        die(f"{fiji} does not look like a Fiji install (no jars/ dir)")

    mvn = mvn_bin()
    project_repos = default_projects

    # Project coordinates (for map exclusion + install) ---------------------- #
    projects = []  # list of (repo, group, artifact)
    project_gas = set()
    for repo in project_repos:
        pom = repo / "pom.xml"
        if not pom.is_file():
            warn(f"project repo has no pom.xml, skipping: {repo}")
            continue
        gco, aco = project_coords(pom)
        if gco and aco:
            projects.append((repo, gco, aco))
            project_gas.add((gco, aco))

    # Target set: explicitly-declared direct dependencies ------------------- #
    log("Collecting explicitly-declared direct dependencies from the projects...")
    direct_targets = build_direct_targets(mvn, projects, project_gas)

    # --exclude: never install/upgrade these, and (by dropping them from the closure
    # roots) never walk their transitive subtree either.
    exclude_tokens = [t.strip() for t in args.exclude.split(",") if t.strip()]
    remove_tokens = [t.strip().split(":")[-1] for t in args.remove.split(",") if t.strip()]
    add_tokens = [t.strip() for t in args.add.split(",") if t.strip()]

    def is_excluded(g_: str, a_: str) -> bool:
        return any(t in (a_, g_, f"{g_}:{a_}") for t in exclude_tokens)

    def add_allows(g_: str, a_: str) -> bool:
        """With --add given, a NEW install is allowed only if it matches a pattern
        ('g:*' = whole group, 'g:a' = exact, bare token = groupId or artifactId)."""
        if not add_tokens:
            return True
        for p in add_tokens:
            if p.endswith(":*"):
                if g_ == p[:-2]:
                    return True
            elif ":" in p:
                if f"{g_}:{a_}" == p:
                    return True
            elif g_ == p or a_ == p:
                return True
        return False

    if exclude_tokens:
        dropped = sorted(f"{g}:{a}" for g, a in direct_targets if is_excluded(g, a))
        direct_targets = {ga: v for ga, v in direct_targets.items() if not is_excluded(*ga)}
        log(f"  excluding (per --exclude): {', '.join(dropped) if dropped else '(no direct dep matched)'}")
    log(f"  -> {len(direct_targets)} distinct direct dependencies across the projects\n")
    known_artifacts = {a for (_g, a) in direct_targets} | {a for (_g, a) in project_gas}

    # Scan Fiji -------------------------------------------------------------- #
    log(f"Scanning {fiji}/jars and {fiji}/plugins ...")
    jars = scan_fiji_jars(fiji, fiji / ".dep-update-backup", known_artifacts)
    log(f"  found {len(jars)} jars\n")

    # Jars without pom.properties were identified by filename only (group=None).
    # Recover their groupId from the target set by artifactId (unique ids only),
    # so e.g. a bare jna-5.14.0.jar matches net.java.dev.jna:jna instead of
    # looking absent and being wrongly re-added.
    art_to_group: dict = {}
    ambiguous = set()
    for g_, a_ in list(direct_targets) + list(project_gas):
        if a_ in art_to_group and art_to_group[a_] != g_:
            ambiguous.add(a_)
        art_to_group[a_] = g_
    for a_ in ambiguous:
        art_to_group.pop(a_, None)
    for jid in jars:
        if jid.group is None and jid.artifact in art_to_group:
            jid.group = art_to_group[jid.artifact]

    # --exclude also removes any matching jar already present (so the result is
    # the same whether or not a prior run installed them).
    excluded_present = [j for j in jars if exclude_tokens and is_excluded(j.group or "", j.artifact)]
    remove_present = sorted({p for tok in remove_tokens for p in find_jars_by_artifact(fiji, tok)})

    # Classify --------------------------------------------------------------- #
    upgrades = []      # (JarId, target_version)  present + project newer
    kept = []          # (JarId, target_version)  present + Fiji newer (kept)
    present_gas = set()
    for jid in jars:
        present_gas.add(jid.ga)
        if jid.ga in project_gas or jid.ga not in direct_targets:
            continue
        tv = direct_targets[jid.ga]
        c = compare_versions(tv, jid.version)
        if c > 0:
            upgrades.append((jid, tv))
        elif c < 0:
            kept.append((jid, tv))
        # c == 0 -> already current
    adds = sorted((ga, v) for ga, v in direct_targets.items()
                  if ga not in present_gas and ga not in project_gas and add_allows(*ga))

    # pom-scijava released baseline — reused for the BOM table below, and to find
    # which direct deps the projects pin AWAY from the BOM (whose transitive subtree
    # may bring jars Fiji lacks, e.g. n5-aws-s3's switch to AWS SDK v2).
    released = parent_version(projects[0][0] / "pom.xml") if projects else None
    bom_rel = ((base_effective_dm(mvn, released, offline=True)
                or base_effective_dm(mvn, released, offline=False)) if released else {})

    # Transitive deps the upgraded libraries pull in that Fiji lacks (--transitive).
    # Defined against the BOM baseline (not the live Fiji), so re-runs are stable.
    # Only jars ABSENT from Fiji are added; present ones are never touched here.
    trans_adds = []        # [((g, a), version)]              truly absent -> install
    trans_upgrades = []    # [((g, a), version, old_path, old_version)]  present older -> replace
    if scope != "direct":
        log(f"Resolving transitive dependencies ({args.transitive}) ...")
        if scope == "full":
            clo = full_runtime_closure(mvn, projects)
            raw = {ga: v for ga, v in clo.items()
                   if ga not in present_gas and ga not in direct_targets
                   and ga not in project_gas and not is_excluded(*ga)}
        else:  # closure: subtree of the deps our projects override away from the BOM,
               # pruned at any dep Fiji already has (avoids dragging in OMERO/Scala/etc.)
            if bom_rel:
                changed = [(g, a, v) for (g, a), v in direct_targets.items()
                           if bom_rel.get((g, a)) != v]
            else:  # baseline unavailable -> fall back to what differs from Fiji
                changed = ([(j.group, j.artifact, t) for j, t in upgrades]
                           + [(g, a, v) for (g, a), v in adds])
            fr = transitive_frontier(mvn, released, changed, present_gas,
                                     set(direct_targets), project_gas)
            raw = {ga: v for ga, v in fr.items() if not is_excluded(*ga)}
        # A "missing" GA may actually be present under a jar without pom.properties
        # (so the scan didn't identify it) -> classify by FILENAME so we replace it
        # instead of installing a duplicate.
        for (g_, a_), v in sorted(raw.items()):
            ex = find_present_jar(fiji, g_, a_)
            if ex is None:
                if add_allows(g_, a_):                 # --add whitelist (new installs only)
                    trans_adds.append(((g_, a_), v))
            elif compare_versions(v, ex[1]) > 0:
                trans_upgrades.append(((g_, a_), v, ex[0], ex[1]))
            # else: already present at an equal/newer version -> leave it
        log(f"  -> {len(trans_adds)} new + {len(trans_upgrades)} upgrade(s) "
            f"(of {len(raw)} transitive candidates)\n")

    # Project jars ----------------------------------------------------------- #
    # tuple: (group, artifact, version, src_jar, existing_list, install_dir, dest_name, note)
    project_actions = []
    by_ga = {}
    for jid in jars:
        by_ga.setdefault(jid.ga, []).append(jid)
    for repo, gco, aco in projects:
        src = find_built_jar(repo)
        if src is None:
            resolved_note = "no target/*.jar (build it: mvn -f %s/pom.xml install)" % repo.name
            project_actions.append((gco, aco, None, None, [], None, None, resolved_note))
            continue
        sid = read_jar_identity(src)
        ver = sid.version if sid else "?"
        existing = by_ga.get((gco, aco), [])
        # replace an existing copy in place; otherwise route by plugins.config
        install_dir = existing[0].path.parent if existing else install_dir_for(fiji, src)
        # reuse the existing filename (version-swapped) so it matches apart from
        # the version; otherwise derive (underscore-ify for plugins/).
        dn = None
        if existing:
            dn = upgraded_name(existing[0].path.name, existing[0].version, ver)
        if dn is None:
            dn = dest_filename(aco, ver, "", install_dir.name == "plugins")
        up_to_date = any(j.version == ver and j.classifier in ("", None) for j in existing)
        note = "up-to-date" if up_to_date else None
        project_actions.append((gco, aco, ver, src, existing, install_dir, dn, note))

    # ---------------------------------------------------------------------- #
    # Report
    # ---------------------------------------------------------------------- #
    log("=" * 78)
    log(f"Fiji: {fiji}")
    log(f"Mode: {'DRY-RUN (no changes)' if dry_run else 'APPLY'}")
    log("=" * 78)

    log(f"\n(A) UPGRADE — present, older than your projects  [{len(upgrades)}]")
    for jid, tgt in sorted(upgrades, key=lambda x: (x[0].group, x[0].artifact)):
        cl = f" ({jid.classifier})" if jid.classifier else ""
        log(f"    {jid.group}:{jid.artifact}{cl}  {jid.version}  ->  {tgt}")

    addnote = "  [--add whitelist active]" if add_tokens else ""
    skip = "  — SKIPPED (upgrade-only)" if upgrade_only else ""
    log(f"\n(B) ADD — declared by your projects, absent from Fiji  [{len(adds)}]{addnote}{skip}")
    if adds and not upgrade_only:
        log("    (-> plugins/ if the jar ships a plugins.config, else jars/)")
    for (g_, a_), v in adds:
        log(f"    {g_}:{a_}  ->  install {v}")

    new_skip = " SKIPPED" if upgrade_only else ""
    log(f"\n(B2) TRANSITIVE (--transitive={args.transitive}) — pulled in by upgraded "
        f"libraries  [{len(trans_adds)} new{new_skip}, {len(trans_upgrades)} upgraded]{addnote}")
    for (g_, a_), v in trans_adds:
        log(f"    {g_}:{a_}  ->  install {v}  (new{new_skip.lower()})")
    for (g_, a_), v, _op, ov in trans_upgrades:
        log(f"    {g_}:{a_}  {ov}  ->  {v}")

    log(f"\n(C) KEPT — Fiji already newer than your projects (NOT downgraded)  [{len(kept)}]")
    for jid, tgt in sorted(kept, key=lambda x: (x[0].group, x[0].artifact)):
        log(f"    {jid.group}:{jid.artifact}  Fiji={jid.version}  (your projects: {tgt})")

    if exclude_tokens:
        log(f"\n(X) REMOVE — matched --exclude, currently in Fiji  [{len(excluded_present)}]")
        for j in sorted(excluded_present, key=lambda x: x.artifact):
            log(f"    {j.group}:{j.artifact}  {j.version}  ({j.path.relative_to(fiji)})")

    if remove_tokens:
        log(f"\n(Y) REMOVE — matched --remove, currently in Fiji  [{len(remove_present)}]")
        for p in remove_present:
            log(f"    {p.relative_to(fiji)}")

    log(f"\nPROJECT JARS  [{len(project_actions)}]")
    for (g_, a_, v, src, existing, _d, dn, note) in project_actions:
        ex = ", ".join(sorted({j.version for j in existing})) or "absent"
        tag = f"  [{note}]" if note else ""
        dest = f"  -> {_d.name}/{dn}" if _d and dn else ""
        log(f"    {g_}:{a_}  build={v}  in-fiji={ex}{dest}{tag}")

    log("")

    # ---------------------------------------------------------------------- #
    # BOM comparison tables (imaging/zarrv3 stack: Fiji vs BOM vs your projects)
    # ---------------------------------------------------------------------- #
    fiji_versions = {j.ga: j.version for j in jars if j.group}
    log("\nResolving pom-scijava BOM tables (extra mvn calls)...")
    if released and bom_rel:
        print_bom_table(f"=== Migration stack vs pom-scijava {released} "
                        f"(released BOM your projects build on) ===",
                        fiji_versions, bom_rel, direct_targets)
    elif released:
        log(f"  (could not resolve pom-scijava {released}; skipped)")
    snapver = latest_snapshot_bom_version()
    if snapver:
        bom_snap = snapshot_bom_dm(mvn, snapver)
        if bom_snap:
            print_bom_table(f"=== Migration stack vs pom-scijava {snapver} "
                            f"(latest unreleased BOM) ===",
                            fiji_versions, bom_snap, direct_targets)
        else:
            log(f"  (could not resolve pom-scijava {snapver} — needs network to "
                f"the scijava repo; skipped)")
    else:
        log("  (latest-SNAPSHOT table skipped: couldn't reach the scijava server "
            "(transient/offline) — the released table above is unaffected; re-run when online)")
    log("")

    # ---------------------------------------------------------------------- #
    # Apply
    # ---------------------------------------------------------------------- #
    if dry_run:
        log("Dry-run only. Re-run with --apply to perform these changes.")
        return

    upgraded = added = tadded = tupgraded = installed = failed = 0

    def install_absent(g_: str, a_: str, v: str) -> bool:
        src = resolve_artifact(mvn, g_, a_, v, "")
        if src is None or not validate_jar(src, g_, a_, v):
            if src is not None:
                warn(f"resolved jar failed validation, skipping: {src}")
            return False
        idir = install_dir_for(fiji, src)
        # safety net: if a same-artifactId jar is already present (e.g. one without
        # pom.properties the scan couldn't identify), replace it instead of leaving a
        # duplicate on the classpath.
        ex = find_present_jar(fiji, g_, a_)
        place_jar(src, idir, dest_filename(a_, v, "", idir.name == "plugins"),
                  remove=[ex[0]] if ex else None)
        return True

    if upgrades:
        log("Upgrading present dependencies ...")
        for jid, tgt in upgrades:
            src = resolve_artifact(mvn, jid.group, jid.artifact, tgt, jid.classifier or "")
            if src is None or not validate_jar(src, jid.group, jid.artifact, tgt):
                if src is not None:
                    warn(f"resolved jar failed validation, skipping: {src}")
                failed += 1
                continue
            dn = (upgraded_name(jid.path.name, jid.version, tgt)
                  or dest_filename(jid.artifact, tgt, jid.classifier or "",
                                   jid.path.parent.name == "plugins"))
            place_jar(src, jid.path.parent, dn, remove=[jid.path])
            upgraded += 1

    if adds and not upgrade_only:
        log("Adding absent direct dependencies ...")
        for (g_, a_), v in adds:
            if install_absent(g_, a_, v):
                added += 1
            else:
                failed += 1

    if trans_adds and not upgrade_only:
        log("Adding transitive dependencies ...")
        for (g_, a_), v in trans_adds:
            if install_absent(g_, a_, v):
                tadded += 1
            else:
                failed += 1

    if trans_upgrades:
        log("Upgrading transitive dependencies ...")
        for (g_, a_), v, old_path, old_ver in trans_upgrades:
            src = resolve_artifact(mvn, g_, a_, v, "")
            if src is None or not validate_jar(src, g_, a_, v):
                if src is not None:
                    warn(f"resolved jar failed validation, skipping: {src}")
                failed += 1
                continue
            dn = (upgraded_name(old_path.name, old_ver, v)
                  or dest_filename(a_, v, "", old_path.parent.name == "plugins"))
            place_jar(src, old_path.parent, dn, remove=[old_path])
            tupgraded += 1

    log("Installing project plugin jars ...")
    for (g_, a_, v, src, existing, install_dir, dn, note) in project_actions:
        if src is None:
            warn(f"{g_}:{a_}: {note}")
            continue
        if note == "up-to-date":
            continue
        place_jar(src, install_dir, dn, remove=[j.path for j in existing])
        installed += 1

    removed = 0
    if excluded_present:
        log("Removing excluded jars ...")
        for j in excluded_present:
            if j.path.is_file():
                j.path.unlink()
                removed += 1

    rmvd = 0
    if remove_tokens:
        log("Removing --remove jars ...")
        for tok in remove_tokens:                       # re-glob: catch current state
            for p in find_jars_by_artifact(fiji, tok):
                if p.is_file():
                    p.unlink()
                    rmvd += 1

    cs = fiji / ".checksums"
    if cs.is_file():
        cs.unlink()
        log("Removed .checksums (Fiji regenerates it).")

    log("")
    log("=" * 78)
    log(f"Direct: upgraded {upgraded}, added {added}. Transitive: added {tadded}, "
        f"upgraded {tupgraded}. Project jars: {installed}. "
        f"Removed: {removed} excluded + {rmvd} explicit. Failures: {failed}.")
    log("")
    log("!! Do NOT run Help > Update Fiji on this install — it may revert the swapped jars.")
    log("!! To undo everything, just re-extract your Fiji ZIP.")
    log("=" * 78)


if __name__ == "__main__":
    main()
