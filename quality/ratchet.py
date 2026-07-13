#!/usr/bin/env python3
"""Collect and compare deterministic Detekt findings for every reactor Kotlin module."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

BLOCKING_RULES = {
    "CyclomaticComplexMethod", "CognitiveComplexMethod", "LongMethod", "LargeClass",
    "TooManyFunctions", "NestedBlockDepth", "LongParameterList", "ComplexCondition",
    "ReturnCount", "ThrowsCount",
}
SUPPRESSION = re.compile(r"@Suppress(?:Warnings)?|//\s*(?:detekt:disable|ktlint-disable)")


def reactor_modules(root: Path) -> list[str]:
    tree = ET.parse(root / "pom.xml")
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    modules = [node.text for node in tree.findall(".//m:modules/m:module", namespace) if node.text]
    return sorted(module for module in modules if (root / module / "src/main/kotlin").is_dir())


def normalize_shape(line: str) -> str:
    line = re.sub(r'"(?:\\.|[^"\\])*"', "STR", line)
    line = re.sub(r"\b\d+(?:\.\d+)?\b", "NUM", line)
    line = re.sub(r"\b[A-Za-z_][A-Za-z0-9_]*\b", "ID", line)
    return re.sub(r"\s+", "", line)


def fingerprint(rule: str, message: str, source_line: str) -> str:
    normalized_message = re.sub(r"['`][^'`]+['`]", "'ID'", message)
    value = f"{rule}|{normalized_message}|{normalize_shape(source_line)}"
    return hashlib.sha256(value.encode()).hexdigest()[:20]


def collect(root: Path) -> dict:
    modules = reactor_modules(root)
    findings: list[dict] = []
    suppressions: list[dict] = []
    for module in modules:
        report = root / module / "target/detekt/detekt.xml"
        if not report.is_file():
            raise ValueError(f"missing Detekt report for module {module}: {report}")
        for file_node in ET.parse(report).getroot().findall("file"):
            source = Path(file_node.attrib["name"])
            try:
                relative = source.resolve().relative_to(root.resolve()).as_posix()
                lines = source.read_text().splitlines()
            except (OSError, ValueError) as exc:
                raise ValueError(f"invalid Detekt source path {source}: {exc}") from exc
            for error in file_node.findall("error"):
                rule = error.attrib.get("source", "").removeprefix("detekt.")
                line_number = int(error.attrib.get("line", "1"))
                source_line = lines[line_number - 1] if 0 < line_number <= len(lines) else ""
                message = error.attrib.get("message", "")
                findings.append({
                    "module": module, "rule": rule, "path": relative,
                    "fingerprint": fingerprint(rule, message, source_line),
                })
        source_root = root / module / "src/main/kotlin"
        for source in sorted(source_root.rglob("*.kt")):
            for number, line in enumerate(source.read_text().splitlines(), 1):
                if SUPPRESSION.search(line):
                    suppressions.append({"path": source.relative_to(root).as_posix(), "line": number, "text": line.strip()})
    return {
        "schemaVersion": 1,
        "modules": modules,
        "blockingRules": sorted(BLOCKING_RULES),
        "findings": sorted(findings, key=lambda item: (item["module"], item["rule"], item["path"], item["fingerprint"])),
        "suppressions": suppressions,
    }


def compare(baseline: dict, current: dict) -> dict:
    if baseline.get("schemaVersion") != 1 or current.get("schemaVersion") != 1:
        raise ValueError("unsupported or missing ratchet schemaVersion")
    if baseline.get("modules") != current.get("modules"):
        raise ValueError(f"Kotlin module set changed: {baseline.get('modules')} -> {current.get('modules')}")
    base = [item for item in baseline["findings"] if item["rule"] in BLOCKING_RULES]
    now = [item for item in current["findings"] if item["rule"] in BLOCKING_RULES]
    exact_base = Counter((x["module"], x["rule"], x["path"], x["fingerprint"]) for x in base)
    exact_now = Counter((x["module"], x["rule"], x["path"], x["fingerprint"]) for x in now)
    common = exact_base & exact_now
    exact_base -= common
    exact_now -= common
    by_shape_base: dict[tuple, list[tuple]] = defaultdict(list)
    by_shape_now: dict[tuple, list[tuple]] = defaultdict(list)
    for key, count in exact_base.items():
        by_shape_base[(key[0], key[1], key[3])].extend([key] * count)
    for key, count in exact_now.items():
        by_shape_now[(key[0], key[1], key[3])].extend([key] * count)
    ambiguous = []
    new = []
    renamed = 0
    for shape in sorted(set(by_shape_base) | set(by_shape_now)):
        before, after = by_shape_base[shape], by_shape_now[shape]
        if before and after:
            if len(before) == len(after) == 1:
                renamed += 1
            else:
                ambiguous.append({"shape": shape, "before": len(before), "after": len(after)})
        elif after:
            new.extend(after)
    baseline_suppressions = {(x["path"], x["text"]) for x in baseline.get("suppressions", [])}
    current_suppressions = {(x["path"], x["text"]) for x in current.get("suppressions", [])}
    new_suppressions = sorted(current_suppressions - baseline_suppressions)
    return {"ok": not new and not ambiguous and not new_suppressions, "new": new,
            "ambiguous": ambiguous, "renamed": renamed, "newSuppressions": new_suppressions,
            "resolved": max(0, len(base) - len(now))}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("collect", "check"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    current = collect(args.root)
    if args.command == "collect":
        result = current
    else:
        if not args.baseline or not args.baseline.is_file():
            raise SystemExit("missing --baseline")
        result = compare(json.loads(args.baseline.read_text()), current)
        result["modules"] = current["modules"]
        result["findingCount"] = len(current["findings"])
        result["suppressionCount"] = len(current["suppressions"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps(result, indent=2, sort_keys=True))
    if args.command == "check" and not result["ok"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
