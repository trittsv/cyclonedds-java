#!/usr/bin/env python3
# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

"""Inventory fresh upstream checkouts and validate source-to-CTest traceability.

Refreshing is deliberately separate from checking: CI must not silently accept
new upstream tests as covered. No third-party Python modules are required.
"""
import argparse
import ast
import collections
import hashlib
import json
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
REPOSITORIES = {
    "python": "https://github.com/eclipse-cyclonedds/cyclonedds-python",
    "cxx": "https://github.com/eclipse-cyclonedds/cyclonedds-cxx",
}


def inventory(python, cxx):
    result = {"revisions": {}, "tests": [], "probes": [], "fixtures": []}
    for repo, checkout in (("python", python), ("cxx", cxx)):
        result["revisions"][repo] = subprocess.check_output(
            ["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
        if repo == "python":
            for source in sorted((checkout / "tests").rglob("test_*.py")):
                class Tests(ast.NodeVisitor):
                    scope = ()
                    def visit_FunctionDef(self, node):
                        if node.name.startswith("test_"):
                            result["tests"].append({"repo": repo, "file": str(source.relative_to(checkout)),
                                "name": ".".join(self.scope + (node.name,)), "line": node.lineno,
                                "nested": bool(self.scope),
                                "parameterized": any("parametrize" in ast.unparse(d) for d in node.decorator_list)})
                        previous = self.scope
                        self.scope += (node.name,)
                        self.generic_visit(node)
                        self.scope = previous
                    visit_AsyncFunctionDef = visit_FunctionDef
                    def visit_ClassDef(self, node):
                        previous = self.scope
                        self.scope += (node.name,)
                        self.generic_visit(node)
                        self.scope = previous
                Tests().visit(ast.parse(source.read_text(encoding="utf-8")))
        else:
            for source in sorted((checkout / "src/ddscxx/tests").glob("*.cpp")):
                # Preserve newlines and therefore source locations when removing comments.
                contents = re.sub(r'//[^\n]*|/\*[\s\S]*?\*/',
                    lambda m: " " + "\n" * m.group().count("\n"), source.read_text(encoding="utf-8"))
                for m in re.finditer(r'\b((?:TYPED_)?TEST(?:_F|_P)?)\s*\(\s*(\w+)\s*,\s*(\w+)\s*\)', contents):
                    result["tests"].append({"repo": repo, "file": str(source.relative_to(checkout)),
                        "name": m[2] + "." + m[3], "line": contents.count("\n", 0, m.start()) + 1,
                        "parameterized": m[1].startswith("TYPED_") or m[1].endswith("_P"),
                        "disabled": m[2].startswith("DISABLED_") or m[3].startswith("DISABLED_")})
            source = checkout / "src/ddscxx/tests/CMakeLists.txt"
            contents = source.read_text(encoding="utf-8")
            for m in re.finditer(r'add_idlcxx_rejection_test\(\s*(IDLCXXRecursiveProbes\.\w+)\s+(\w+\.idl)', contents):
                result["probes"].append({"repo": repo, "file": str(source.relative_to(checkout)),
                    "name": m[1], "fixture": "src/ddscxx/tests/data/" + m[2],
                    "line": contents.count("\n", 0, m.start()) + 1})
            name = "IDLCXXRecursiveProbes.supported_recursive_type_forms"
            result["probes"].append({"repo": repo, "file": str(source.relative_to(checkout)),
                "name": name, "line": contents.count("\n", 0, contents.index(name)) + 1})
            for fixture in sorted((checkout / "src/ddscxx/tests/data").glob("Recursive*.idl")):
                result["fixtures"].append({"repo": repo, "file": str(fixture.relative_to(checkout)),
                    "sha256": hashlib.sha256(fixture.read_bytes()).hexdigest()})
    return result


def key(row):
    return f'{row["repo"]}:{row["file"]}:{row["name"]}'


def all_rows(data):
    return data["tests"] + data.get("probes", [])


def check(data, mappings, build):
    rows = {key(row): row for row in all_rows(data)}
    if len(rows) != len(all_rows(data)):
        raise ValueError("Duplicate upstream test identifier")
    registered = None
    if build:
        ctest = json.loads(subprocess.check_output(["ctest", "--test-dir", str(build), "--show-only=json-v1"], text=True))
        registered = {test["name"] for test in ctest["tests"]}
    for source, mapping in mappings.items():
        if source not in rows:
            raise ValueError(f"Stale upstream mapping: {source}")
        if mapping["status"] not in ("adapted", "partial", "not-applicable", "missing"):
            raise ValueError(f"Invalid status: {source}")
        if not mapping.get("reason"):
            raise ValueError(f"Missing explanation: {source}")
        if mapping["status"] in ("adapted", "partial"):
            if not mapping.get("ctest") or not mapping.get("sources"):
                raise ValueError(f"Missing runnable evidence: {source}")
            for source_file in mapping["sources"]:
                if not (ROOT / source_file).is_file():
                    raise ValueError(f"Missing test source: {source_file}")
            if registered is not None:
                for name in mapping["ctest"]:
                    if name not in registered:
                        raise ValueError(f"Missing CTest case: {name}")
    for fixture in data.get("fixtures", []):
        local = HERE / "idl" / Path(fixture["file"]).name
        if hashlib.sha256(local.read_bytes()).hexdigest() != fixture["sha256"]:
            raise ValueError(f"Vendored upstream fixture differs: {local}")
    return rows


def report(data, mappings):
    counts = collections.Counter(mappings.get(key(row), {}).get("status", "unreviewed") for row in all_rows(data))
    print("Upstream definitions:", dict(collections.Counter(row["repo"] for row in data["tests"])))
    print("Additional CMake probes:", len(data.get("probes", [])))
    print("Traceability:", dict(sorted(counts.items())))
    print("Definition counts exclude parameter expansion; unreviewed does not mean absent.")
    print("C++ feature guards and nested Python helpers affect upstream collection.")


def markdown(data, mappings):
    lines = ['<!--\nCopyright(c) 2026 Sven Trittler\n\nThis program and the accompanying materials are made available under the\nterms of the Eclipse Public License v. 2.0 which is available at\nhttp://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License\nv. 1.0 which is available at\nhttp://www.eclipse.org/org/documents/edl-v10.php.\n\nSPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause\n-->', "", "<!-- Generated by tests/upstream/audit.py; edit coverage.json instead. -->",
        "# Upstream test traceability", "",
        "Definitions are counted before parameter expansion. `adapted` means the recorded Java scenario",
        "exercises equivalent behavior through Java APIs; `partial` retains explicit limitations.",
        "`unreviewed` makes no coverage claim. A rejection test is not coverage of a working feature.", ""]
    for repo, sha in data["revisions"].items():
        lines += [f"- [{repo} revision {sha}]({REPOSITORIES[repo]}/tree/{sha})"]
    lines += ["", "| Upstream definition | Status | Java CTest / remaining work |", "| --- | --- | --- |"]
    for row in all_rows(data):
        mapping = mappings.get(key(row), {})
        url = f'{REPOSITORIES[row["repo"]]}/blob/{data["revisions"][row["repo"]]}/{row["file"]}#L{row["line"]}'
        evidence = ", ".join(f'`{name}`' for name in mapping.get("ctest", []))
        note = mapping.get("reason", "Individual assertions and parameter variants still require review.")
        lines += [f'| [{row["repo"]}: {row["name"]}]({url}) | {mapping.get("status", "unreviewed")} | {evidence} {note.replace("|", "/")} |']
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", type=Path, help="Fresh cyclonedds-python checkout")
    parser.add_argument("--cxx", type=Path, help="Fresh cyclonedds-cxx checkout")
    parser.add_argument("--refresh", action="store_true", help="Replace inventory from both checkouts")
    parser.add_argument("--build", type=Path, help="Validate CTest evidence in a configured Java build")
    parser.add_argument("--check", action="store_true", help="Require the committed Markdown report to match")
    args = parser.parse_args()
    if args.refresh:
        if not args.python or not args.cxx:
            parser.error("--refresh requires --python and --cxx")
        data = inventory(args.python.resolve(), args.cxx.resolve())
        (HERE / "inventory.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    else:
        data = json.loads((HERE / "inventory.json").read_text(encoding="utf-8"))
    mappings = json.loads((HERE / "coverage.json").read_text(encoding="utf-8"))
    check(data, mappings, args.build)
    output = markdown(data, mappings)
    if args.check:
        if (HERE / "coverage.md").read_text(encoding="utf-8") != output:
            raise ValueError("Stale coverage.md: run tests/upstream/audit.py")
    else:
        (HERE / "coverage.md").write_text(output, encoding="utf-8")
    report(data, mappings)


if __name__ == "__main__":
    main()
