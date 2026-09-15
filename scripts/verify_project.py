#!/usr/bin/env python3
"""
Static verification for the KeyProxy Android project.

There is no JDK or Android SDK in this environment, so `./gradlew` cannot run.
This script is the next best thing: it checks the invariants that a compiler or
the Android resource merger would otherwise catch, so that structural mistakes
are found here rather than on a user's phone.

Checks performed
  1. Every XML file is well-formed.
  2. gradle/libs.versions.toml parses, and every `libs.*` accessor used in the
     Gradle scripts resolves to a catalog entry.
  3. Every Android resource reference (@string/@drawable/@mipmap/@style/@xml and
     R.string.X / R.drawable.X / R.style.X) resolves to a definition.
  4. Every component named in AndroidManifest.xml exists as a Kotlin file.
  5. Every Kotlin file's package declaration matches its directory.
  6. Brackets, braces and parentheses balance in every Kotlin file, ignoring
     strings, comments and character literals.
  7. Every intra-project `import dev.kbt117.keyproxy...` resolves to a symbol
     actually declared somewhere in the project.
  8. No leftover placeholder markers.

Exit code 0 == all checks passed.
"""

from __future__ import annotations

import re
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app"
SRC_MAIN = APP / "src" / "main"
SRC_TEST = APP / "src" / "test"
JAVA_ROOT = SRC_MAIN / "java"
PKG_ROOT = "dev/kbt117/keyproxy"

failures: list[str] = []
checks_run = 0


def check(name: str, condition: bool, detail: str = "") -> None:
    global checks_run
    checks_run += 1
    if not condition:
        failures.append(f"{name}{': ' + detail if detail else ''}")


# --------------------------------------------------------------------------
# 1. XML well-formedness
# --------------------------------------------------------------------------
def check_xml() -> None:
    xml_files = sorted(APP.rglob("*.xml")) + sorted((ROOT / ".github").rglob("*.xml"))
    xml_files = [p for p in xml_files if p.is_file()]
    check("xml: at least one XML file found", len(xml_files) > 0, f"found {len(xml_files)}")
    for path in xml_files:
        try:
            ET.parse(path)
            check(f"xml well-formed: {path.relative_to(ROOT)}", True)
        except ET.ParseError as exc:
            check(f"xml well-formed: {path.relative_to(ROOT)}", False, str(exc))


# --------------------------------------------------------------------------
# 2. Version catalog
# --------------------------------------------------------------------------
def _norm(alias: str) -> str:
    """
    Gradle splits a version-catalog alias on '-', '_' and '.' to build the
    nested accessor, so all three are equivalent separators. Normalising both
    sides the same way is what makes the comparison meaningful.
    """
    return alias.replace("-", ".").replace("_", ".")


def check_version_catalog() -> dict[str, str]:
    catalog_path = ROOT / "gradle" / "libs.versions.toml"
    data = tomllib.loads(catalog_path.read_text())

    versions = set(data.get("versions", {}))
    libraries = {_norm(k) for k in data.get("libraries", {})}
    plugins = {_norm(k) for k in data.get("plugins", {})}

    # Every version.ref must point at a declared version.
    for section in ("libraries", "plugins"):
        for name, spec in data.get(section, {}).items():
            ref = spec.get("version", {}).get("ref") if isinstance(spec.get("version"), dict) else None
            if ref is not None:
                check(
                    f"catalog: {section}.{name} version.ref '{ref}' is declared",
                    ref in versions,
                    f"unknown version '{ref}'",
                )

    # Every libs.* accessor used in the Gradle scripts must resolve.
    #
    # The trailing `*` (not `+`) matters: single-segment aliases such as
    # `libs.okhttp` and `libs.junit` are legal and common, and an earlier `+`
    # here silently skipped every one of them.
    gradle_files = sorted(ROOT.glob("*.gradle.kts")) + sorted(APP.glob("*.gradle.kts"))
    accessor = re.compile(r"\blibs\.([\w-]+(?:\.[\w-]+)*)")
    used = 0
    for gf in gradle_files:
        for match in accessor.finditer(gf.read_text()):
            dotted = match.group(1)
            used += 1
            parts = dotted.split(".")
            if parts[0] == "plugins":
                key = _norm(".".join(parts[1:]))
                check(
                    f"catalog: libs.plugins.{'.'.join(parts[1:])} resolves",
                    key in plugins,
                    f"no [plugins] entry (looked for '{key}'; have {sorted(plugins)})",
                )
            else:
                key = _norm(dotted)
                check(
                    f"catalog: libs.{dotted} resolves",
                    key in libraries,
                    f"no [libraries] entry '{key}'",
                )
    check("catalog: libs.* accessors were found and checked", used > 0, f"only {used}")
    return {"libraries": str(len(libraries)), "plugins": str(len(plugins))}


# --------------------------------------------------------------------------
# 3. Resource references
# --------------------------------------------------------------------------
def check_resources() -> None:
    res = SRC_MAIN / "res"

    defined: dict[str, set[str]] = {"string": set(), "drawable": set(), "mipmap": set(),
                                    "style": set(), "color": set(), "xml": set()}

    for values_file in sorted((res / "values").glob("*.xml")):
        try:
            tree = ET.parse(values_file)
        except ET.ParseError:
            continue  # already reported by check_xml()
        for child in tree.getroot():
            tag = child.tag
            if tag in defined and child.get("name"):
                defined[tag].add(child.get("name"))

    # File-based resources: res/<type>[-qualifiers]/name.ext
    for entry in sorted(res.iterdir()):
        if not entry.is_dir():
            continue
        base_type = entry.name.split("-")[0]
        if base_type in ("values",):
            continue
        if base_type not in defined:
            defined[base_type] = set()
        for f in entry.iterdir():
            if f.is_file():
                defined[base_type].add(f.stem)

    # References from XML: @type/name and @+id/id (ids are auto-created).
    xml_ref = re.compile(r"@(?:\+)?(\w+)/([\w.]+)")
    xml_files = list(SRC_MAIN.rglob("*.xml"))
    refs_seen = 0
    for path in xml_files:
        # Skip anything malformed; check_xml() has already reported it, and
        # crashing here would hide every other finding.
        try:
            text = path.read_text()
            ET.fromstring(text)
        except ET.ParseError:
            continue
        for m in xml_ref.finditer(text):
            rtype, name = m.group(1), m.group(2)
            if rtype in ("id", "android"):
                continue
            refs_seen += 1
            check(
                f"resource @{rtype}/{name} in {path.name}",
                name in defined.get(rtype, set()),
                f"not defined; available {rtype}: {sorted(defined.get(rtype, set()))}",
            )

    # References from Kotlin: R.type.name
    kt_ref = re.compile(r"\bR\.(\w+)\.(\w+)\b")
    kt_files = list(JAVA_ROOT.rglob("*.kt"))
    for path in kt_files:
        for m in kt_ref.finditer(path.read_text()):
            rtype, name = m.group(1), m.group(2)
            refs_seen += 1
            check(
                f"resource R.{rtype}.{name} in {path.name}",
                name in defined.get(rtype, set()),
                f"not defined; available {rtype}: {sorted(defined.get(rtype, set()))}",
            )
    check("resources: references were found and checked", refs_seen > 0, f"only {refs_seen}")


# --------------------------------------------------------------------------
# 4. Manifest components exist
# --------------------------------------------------------------------------
def check_manifest() -> None:
    manifest = SRC_MAIN / "AndroidManifest.xml"
    try:
        tree = ET.parse(manifest)
    except ET.ParseError as exc:
        # Reported in detail by check_xml(); bail out rather than crashing.
        check("manifest: parseable", False, str(exc))
        return
    root = tree.getroot()
    ns = {"android": "http://schemas.android.com/apk/res/android"}

    app = root.find("application")
    check("manifest: <application> present", app is not None)
    if app is None:
        return

    package = None
    build_gradle = (APP / "build.gradle.kts").read_text()
    m = re.search(r'namespace\s*=\s*"([^"]+)"', build_gradle)
    if m:
        package = m.group(1)
    check("manifest: namespace declared in build.gradle.kts", package is not None)

    found_components = 0
    for tag in ("activity", "service", "receiver", "provider"):
        for comp in app.findall(tag):
            name = comp.get(f"{{{ns['android']}}}name")
            if not name:
                continue
            found_components += 1
            fqcn = name if name.startswith(".") is False and "." in name[1:] else (package or "") + name
            if name.startswith("."):
                fqcn = (package or "") + name
            rel = fqcn.replace(".", "/") + ".kt"
            check(
                f"manifest: <{tag}> {name} has a source file",
                (JAVA_ROOT / rel).exists(),
                f"expected {rel}",
            )

    check("manifest: at least one component declared", found_components > 0)

    # Permissions must be exactly the documented minimal set.
    declared = {
        p.get(f"{{{ns['android']}}}name")
        for p in root.findall("uses-permission")
    }
    expected = {
        "android.permission.INTERNET",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    }
    check(
        "manifest: permission set is exactly the documented minimum",
        declared == expected,
        f"declared={sorted(x for x in declared if x)} expected={sorted(expected)}",
    )

    # No dangerous/runtime permissions anywhere.
    dangerous = {"android.permission.POST_NOTIFICATIONS", "android.permission.NEARBY_WIFI_DEVICES",
                 "android.permission.ACCESS_FINE_LOCATION", "android.permission.CAMERA"}
    check(
        "manifest: no dangerous permissions requested",
        declared.isdisjoint(dangerous),
        f"found {sorted(declared & dangerous)}",
    )


# --------------------------------------------------------------------------
# 5-7. Kotlin structural checks
# --------------------------------------------------------------------------
def strip_kotlin_noise(source: str) -> str:
    """Remove comments, string and char literals so bracket counting is honest."""
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        ch = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        # Line comment
        if ch == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue
        # Block comment (Kotlin supports nesting)
        if ch == "/" and nxt == "*":
            depth = 1
            i += 2
            while i < n and depth > 0:
                if source[i] == "/" and i + 1 < n and source[i + 1] == "*":
                    depth += 1
                    i += 2
                elif source[i] == "*" and i + 1 < n and source[i + 1] == "/":
                    depth -= 1
                    i += 2
                else:
                    i += 1
            continue
        # Triple-quoted string
        if source.startswith('"""', i):
            i += 3
            while i < n and not source.startswith('"""', i):
                i += 1
            i += 3
            continue
        # Regular string
        if ch == '"':
            i += 1
            while i < n and source[i] != '"':
                if source[i] == "\\":
                    i += 2
                else:
                    i += 1
            i += 1
            continue
        # Char literal
        if ch == "'":
            i += 1
            while i < n and source[i] != "'":
                if source[i] == "\\":
                    i += 2
                else:
                    i += 1
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


DECL_RE = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|internal|private|protected|abstract|open|final|sealed|data|enum|value|annotation|inline|suspend|operator|infix|external|tailrec|companion)\s+)*"
    r"(?:class|object|interface|fun|val|var|typealias)\s+"
    # An extension declaration is `val Receiver.name` / `fun Receiver.name()`;
    # the importable symbol is the last dotted segment, not the receiver.
    r"(?:[A-Za-z_]\w*\s*\.\s*)?([A-Za-z_]\w*)",
    re.MULTILINE,
)

# Symbols AGP synthesises, so they have no source declaration to find.
GENERATED_SYMBOLS = {"R", "BuildConfig"}


def check_kotlin() -> dict[str, set[str]]:
    kt_files = sorted(JAVA_ROOT.rglob("*.kt")) + sorted(SRC_TEST.rglob("*.kt"))
    check("kotlin: source files found", len(kt_files) > 0, f"found {len(kt_files)}")

    symbols: dict[str, set[str]] = {}

    for path in kt_files:
        rel = path.relative_to(ROOT)
        raw = path.read_text()
        cleaned = strip_kotlin_noise(raw)

        # 5. package matches directory
        pkg_match = re.search(r"^package\s+([\w.]+)", cleaned, re.MULTILINE)
        if pkg_match:
            expected_dir = pkg_match.group(1).replace(".", "/")
            actual_dir = str(path.parent.relative_to(
                JAVA_ROOT if JAVA_ROOT in path.parents else SRC_TEST / "java"
            ))
            check(f"kotlin: package matches directory in {rel}",
                  actual_dir == expected_dir,
                  f"package '{pkg_match.group(1)}' -> '{expected_dir}' but file is in '{actual_dir}'")
            symbols.setdefault(pkg_match.group(1), set()).update(
                DECL_RE.findall(cleaned)
            )
        else:
            check(f"kotlin: has a package declaration in {rel}", False)

        # 6. balanced brackets
        for open_c, close_c in (("{", "}"), ("(", ")"), ("[", "]")):
            o = cleaned.count(open_c)
            c = cleaned.count(close_c)
            check(f"kotlin: balanced '{open_c}{close_c}' in {rel}", o == c,
                  f"{o} open vs {c} close")

        # 8. placeholder markers
        for marker in ("TODO(", "FIXME", "PLACEHOLDER", "XXX:"):
            check(f"kotlin: no '{marker}' in {rel}", marker not in raw)

    return symbols


def check_imports(symbols: dict[str, set[str]]) -> None:
    kt_files = sorted(JAVA_ROOT.rglob("*.kt")) + sorted(SRC_TEST.rglob("*.kt"))
    import_re = re.compile(r"^import\s+(dev\.kbt117\.keyproxy\.[\w.]+)", re.MULTILINE)
    checked = 0
    for path in kt_files:
        rel = path.relative_to(ROOT)
        cleaned = strip_kotlin_noise(path.read_text())
        for m in import_re.finditer(cleaned):
            fq = m.group(1)
            checked += 1
            pkg, _, name = fq.rpartition(".")
            if name in GENERATED_SYMBOLS:
                continue  # generated by AGP, no source declaration
            known = symbols.get(pkg, set())
            check(f"import {fq} in {rel}", name in known,
                  f"no '{name}' declared in package '{pkg}' "
                  f"(declared: {sorted(known)[:8]})")
    check("imports: intra-project imports were checked", checked > 0, f"only {checked}")


# --------------------------------------------------------------------------
# 9. Android 16 readiness and the security invariants from the brief
# --------------------------------------------------------------------------
def check_android16_and_security() -> None:
    """
    Asserts the specific platform and security requirements, so that a future
    edit which quietly regresses them fails here rather than on a device.
    """
    manifest_path = SRC_MAIN / "AndroidManifest.xml"
    try:
        root = ET.parse(manifest_path).getroot()
    except ET.ParseError:
        return  # reported elsewhere

    A = "{http://schemas.android.com/apk/res/android}"
    app = root.find("application")
    if app is None:
        return

    # --- Foreground service: specialUse + written justification -------------
    services = app.findall("service")
    check("fgs: exactly one service declared", len(services) == 1, f"found {len(services)}")
    for svc in services:
        name = svc.get(f"{A}name") or "?"
        fgs_type = svc.get(f"{A}foregroundServiceType")
        check(f"fgs {name}: type is specialUse", fgs_type == "specialUse",
              f"got {fgs_type!r}")
        check(f"fgs {name}: not exported", svc.get(f"{A}exported") == "false",
              f"got {svc.get(f'{A}exported')!r}")
        subtype = [
            p.get(f"{A}value") for p in svc.findall("property")
            if p.get(f"{A}name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        ]
        check(f"fgs {name}: PROPERTY_SPECIAL_USE_FGS_SUBTYPE declared",
              len(subtype) == 1 and bool(subtype[0]),
              "API 34+ requires this justification for specialUse")

    perms = {p.get(f"{A}name") for p in root.findall("uses-permission")}
    check("fgs: FOREGROUND_SERVICE_SPECIAL_USE permission declared",
          "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in perms)

    # --- Predictive back (mandatory for targetSdk 36) -----------------------
    check("a16: enableOnBackInvokedCallback is true",
          app.get(f"{A}enableOnBackInvokedCallback") == "true",
          "predictive back is on by default for API 36 targets")

    # --- Network security config: cleartext only for loopback ---------------
    nsc_name = app.get(f"{A}networkSecurityConfig")
    check("netsec: networkSecurityConfig referenced", bool(nsc_name), "attribute missing")
    if nsc_name:
        nsc_path = SRC_MAIN / "res" / "xml" / (nsc_name.split("/")[-1] + ".xml")
        check(f"netsec: {nsc_path.name} exists", nsc_path.exists())
        if nsc_path.exists():
            nsc = ET.parse(nsc_path).getroot()
            base = nsc.find("base-config")
            check("netsec: base-config forbids cleartext",
                  base is not None and base.get("cleartextTrafficPermitted") == "false",
                  "all upstream traffic must be TLS")
            for dc in nsc.findall("domain-config"):
                if dc.get("cleartextTrafficPermitted") != "true":
                    continue
                for domain in dc.findall("domain"):
                    text = (domain.text or "").strip()
                    check(f"netsec: cleartext domain '{text}' is loopback only",
                          text in {"127.0.0.1", "localhost", "::1"},
                          "cleartext must never extend beyond the device")
                    check(f"netsec: cleartext domain '{text}' excludes subdomains",
                          domain.get("includeSubdomains") == "false")

    # --- Backups must never carry key material ------------------------------
    for rules_attr, label in (("dataExtractionRules", "dataExtractionRules"),
                              ("fullBackupContent", "backup_rules")):
        ref = app.get(f"{A}{rules_attr}")
        check(f"backup: {label} referenced", bool(ref))
        if ref:
            p = SRC_MAIN / "res" / "xml" / (ref.split("/")[-1] + ".xml")
            if p.exists():
                body = p.read_text()
                check(f"backup: {p.name} excludes sharedpref", 'domain="sharedpref"' in body,
                      "encrypted values must never be backed up")
    check("backup: allowBackup is false", app.get(f"{A}allowBackup") == "false")

    # --- The server must bind loopback only ---------------------------------
    server_src = (JAVA_ROOT / PKG_ROOT / "server" / "ProxyServer.kt").read_text()
    host = re.search(r'const val HOST: String = "([^"]+)"', server_src)
    check("security: proxy binds loopback only",
          host is not None and host.group(1) == "127.0.0.1",
          f"HOST is {host.group(1) if host else 'missing'}; binding wider would expose the "
          "key to the LAN and require the Android 16 local-network permission")

    # --- SDK levels ----------------------------------------------------------
    gradle_text = (APP / "build.gradle.kts").read_text()
    for key, want in (("compileSdk", 36), ("targetSdk", 36), ("minSdk", 26)):
        m = re.search(rf"{key}\s*=\s*(\d+)", gradle_text)
        check(f"sdk: {key} == {want}", m is not None and int(m.group(1)) == want,
              f"got {m.group(1) if m else 'missing'}")

    # --- Gradle wrapper satisfies the AGP floor ------------------------------
    props = (ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text()
    dm = re.search(r"distributionUrl=.*gradle-(\d+)\.(\d+)", props)
    check("gradle: wrapper is >= 8.13 (AGP 8.13 floor)",
          dm is not None and (int(dm.group(1)), int(dm.group(2))) >= (8, 13),
          f"got {dm.group(0) if dm else 'unparseable'}")
    check("gradle: wrapper jar is committed",
          (ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar").exists(),
          "without it ./gradlew cannot bootstrap on a fresh clone")


# --------------------------------------------------------------------------
def main() -> int:
    check_xml()
    stats = check_version_catalog()
    check_resources()
    check_manifest()
    symbols = check_kotlin()
    check_imports(symbols)
    check_android16_and_security()

    print(f"Ran {checks_run} assertions.")
    print(f"Version catalog: {stats['libraries']} libraries, {stats['plugins']} plugins.")
    if failures:
        print(f"\nFAILED ({len(failures)}):")
        for f in failures:
            print(f"  ✗ {f}")
        return 1
    print("\nAll static checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
