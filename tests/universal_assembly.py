# Copyright(c) 2026 Sven Trittler. SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
"""Separate archive aggregation/host selection; synthetic foreign inputs are never shipped."""
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

java, launcher, runtime, compiler, main_jar, version = sys.argv[1:]
metadata = "META-INF/cyclonedds/bundle.properties"
with zipfile.ZipFile(runtime) as jar:
    host = next(line.split("=", 1)[1] for line in jar.read(metadata).decode().splitlines() if line.startswith("platform="))
foreign = "linux-x86_64" if host != "linux-x86_64" else "macos-aarch64"
with tempfile.TemporaryDirectory(prefix="cyclonedds universal test ") as temporary:
    root = Path(temporary)
    def run(*command, succeeds=True):
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
        assert (result.returncode == 0) == succeeds, result.stdout
        return result.stdout
    def assemble(target, inputs, kind="runtime", required=host, succeeds=True, selected_version=version):
        return run(java, "-cp", launcher, "org.eclipse.cyclonedds.idlc.UniversalJar", "--kind", kind, "--output", str(target),
                   "--version", selected_version, "--platforms", required, *map(str, inputs), succeeds=succeeds)
    for kind in ("runtime", "compiler"):
        first, second = root / (kind + "-first.jar"), root / (kind + "-second.jar")
        assemble(first, [runtime, compiler], kind=kind)
        assemble(second, [compiler, runtime], kind=kind)
        assert first.read_bytes() == second.read_bytes(), "Assembly depends on input order/timestamps"
        assert "Missing required" in assemble(root / "missing.jar", [runtime, compiler], kind=kind, required=host + "," + foreign, succeeds=False)
    assert "Missing required compiler" in assemble(root / "missing-compiler.jar", [runtime], kind="compiler", succeeds=False)
    assert "Version mismatch" in assemble(root / "version.jar", [runtime, compiler], selected_version="0.0.0", succeeds=False)
    assert "Duplicate" in assemble(root / "duplicate.jar", [runtime, runtime], succeeds=False)
    # Structural foreign fixtures exercise archive namespaces, never foreign execution.
    foreign_jars = []
    for kind, original in (("runtime", runtime), ("compiler", compiler)):
        output = root / ("foreign-" + kind + ".jar")
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(output, "w") as target:
            for entry in source.infolist():
                if entry.is_dir(): continue
                name, data = entry.filename.replace(host + "/", foreign + "/"), source.read(entry)
                if entry.filename == metadata or entry.filename.endswith(("compiler.properties", "platforms.list")):
                    data = data.replace(host.encode(), foreign.encode())
                target.writestr(name, data)
        foreign_jars.append(output)
    multi_runtime, multi_compiler = root / "multi-runtime.jar", root / "multi-compiler.jar"
    for kind, output, prefix in (("runtime", multi_runtime, "org/eclipse/cyclonedds/native/"), ("compiler", multi_compiler, "META-INF/cyclonedds-idlc/")):
        assemble(output, [runtime, compiler, *foreign_jars], kind=kind, required=host + "," + foreign)
        with zipfile.ZipFile(output) as jar:
            assert set(jar.read(prefix + "platforms.list").decode().splitlines()) == {host, foreign}
            if kind == "runtime":
                assert "org/eclipse/cyclonedds/idlc/Idlc.class" not in jar.namelist()
                assert not any(n.startswith("META-INF/cyclonedds-idlc/") for n in jar.namelist())
            else:
                assert "org/eclipse/cyclonedds/internal/NativeLoader.class" not in jar.namelist()
                assert not any(n.startswith("org/eclipse/cyclonedds/native/") for n in jar.namelist())
    assert run(java, "-jar", str(multi_compiler), "--print-platform").strip() == host
    run(java, "-jar", str(multi_compiler), "-v")
    corrupt = root / "corrupt.jar"
    with zipfile.ZipFile(compiler) as source, zipfile.ZipFile(corrupt, "w") as target:
        for entry in source.infolist():
            data = source.read(entry)
            if "/bin/idlc" in entry.filename and not entry.is_dir(): data = b"invalid"
            target.writestr(entry.filename, data)
    assert "checksum mismatch" in assemble(root / "rejected.jar", [corrupt], kind="compiler", succeeds=False)
    foreign_only = root / "foreign-only.jar"
    assemble(foreign_only, foreign_jars, kind="compiler", required=foreign)
    assert "does not contain an IDL compiler for build host" in run(java, "-jar", str(foreign_only), "-v", succeeds=False)
    print("Separate universal runtime/compiler archives, checksums, completeness, deterministic merge and host selection passed")
