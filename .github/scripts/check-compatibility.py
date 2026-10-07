#!/usr/bin/env python3
"""Fail closed on the exact packaged descriptor and each pinned verifier verdict.

The standalone Plugin Verifier may return zero for compatibility problems. Its
per-plugin verdict and problem files, rather than that exit status, are the gate.
Run with a freshly emptied reports directory for the same archive.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

PLUGIN_ID = "com.github.biomejs.intellijbiome"


def check(archive, reports, targets, version):
    problems = []
    descriptors = []
    upper_build = None
    try:
        with zipfile.ZipFile(archive) as package:
            roots = {Path(name).parts[0] for name in package.namelist() if name}
            if len(roots) != 1:
                problems.append("Expected one top-level plugin directory in archive")
            for name in package.namelist():
                if name.endswith(".jar"):
                    with zipfile.ZipFile(io.BytesIO(package.read(name))) as jar:
                        if "META-INF/plugin.xml" in jar.namelist():
                            descriptors.append(ET.fromstring(jar.read("META-INF/plugin.xml")))
        if len(descriptors) != 1:
            problems.append("Expected exactly one plugin descriptor in archive")
        else:
            descriptor = descriptors[0]
            if descriptor.findtext("id") != PLUGIN_ID:
                problems.append("Unexpected plugin id in archive")
            if descriptor.findtext("version") != version:
                problems.append("Packaged plugin version differs from requested version")
            idea = descriptor.find("idea-version")
            if idea is None or idea.get("since-build") != "253":
                problems.append("Packaged since-build must remain 253")
            if idea is not None:
                upper_build = idea.get("until-build")
                if upper_build != "262.*":
                    problems.append("Packaged until-build must match verified range 262.*")
    except (OSError, zipfile.BadZipFile, ET.ParseError, KeyError) as error:
        problems.append(f"Cannot inspect archive: {error}")

    verdicts = {}
    for target in targets:
        try:
            build = tuple(int(part) for part in target.removeprefix("WS-").split('.'))
            if not target.startswith("WS-") or build[0] < 253:
                problems.append(f"Packaged descriptor excludes target {target}")
            if upper_build:
                upper = tuple(float("inf") if part == "*" else int(part) for part in upper_build.split('.'))
                if build > upper:
                    problems.append(f"Packaged descriptor excludes target {target}")
        except (ValueError, IndexError):
            problems.append(f"Invalid target or descriptor build range: {target}, {upper_build}")
        target_reports = reports / target / "plugins" / PLUGIN_ID
        candidates = sorted(target_reports.glob("*/verification-verdict.txt"))
        if not candidates:
            problems.append(f"Missing exact verdict for {target}")
            continue
        if len(candidates) != 1:
            problems.append(f"Expected exactly one verdict for {target}")
            continue
        verdict = candidates[0]
        if verdict.parent.name != version:
            problems.append(f"Verdict version differs for {target}")
        text = verdict.read_text(encoding="utf-8").strip()
        verdicts[target] = text
        if not (text == "Compatible" or text.startswith("Compatible.")):
            problems.append(f"{target} is not compatible: {text}")
        for filename in ("compatibility-problems.txt", "plugin-structure-warnings.txt", "missing-dependencies.txt"):
            file = verdict.with_name(filename)
            if file.exists() and file.read_text(encoding="utf-8").strip():
                problems.append(f"{target} has nonempty {filename}")

    provenance = {
        "artifact": archive.name,
        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest() if archive.is_file() else None,
        "pluginId": PLUGIN_ID,
        "version": version,
        "sinceBuild": "253",
        "untilBuild": upper_build,
        "compileSdk": "WS-253.28294.332",
        "targets": verdicts,
    }
    return problems, provenance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--reports", required=True, type=Path)
    parser.add_argument("--target", required=True, action="append")
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    problems, provenance = check(args.archive, args.reports, args.target, args.version)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1
    encoded = json.dumps(provenance, indent=2, sort_keys=True)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0


if __name__ == "__main__":
    sys.exit(main())
