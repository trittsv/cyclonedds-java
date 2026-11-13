# Copyright(c) 2026 Sven Trittler. SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
"""Exercise the relocatable compiler JAR, checksum failures and native diagnostics."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

java, javac, compiler, runtime, source = sys.argv[1:]
with tempfile.TemporaryDirectory(prefix="cyclonedds idlc test ") as temporary:
    root = Path(temporary)
    environment = dict(os.environ, PATH=str(root / "no-tools"), LD_LIBRARY_PATH="", DYLD_LIBRARY_PATH="", DYLD_FALLBACK_LIBRARY_PATH="")
    def run(*args, succeeds=True):
        result = subprocess.run(args, env=environment, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
        if succeeds != (result.returncode == 0):
            raise AssertionError(result.stdout)
        return result.stdout
    generated = root / "generated java"
    generated.mkdir()
    run(java, "-jar", compiler, "-o", str(generated), str(Path(source) / "examples/helloworld/HelloWorld.idl"))
    assert (generated / "HelloWorld/Message.java").is_file()
    run(javac, "--release", "17", "-cp", runtime, "-d", str(root / "classes"), str(generated / "HelloWorld/Message.java"))
    probe = root / "NativeProbe.java"
    probe.write_text("public final class NativeProbe { public static void main(String[] args) { org.eclipse.cyclonedds.internal.NativeLoader.load(); } }\n")
    run(javac, "--release", "17", "-cp", runtime, "-d", str(root / "classes"), str(probe))
    run(java, "-Djava.library.path=" + str(root / "no-system-natives"), "-cp", str(root / "classes") + os.pathsep + runtime, "NativeProbe")
    bad_idl = root / "Broken.idl"
    bad_idl.write_text("module Broken { struct Nope { long ; }; };\n")
    diagnostics = run(java, "-jar", compiler, "-o", str(generated), str(bad_idl), succeeds=False)
    assert "Broken.idl" in diagnostics and "status" in diagnostics, diagnostics
    with zipfile.ZipFile(compiler) as original:
        resources = {entry.filename: original.read(entry) for entry in original.infolist()}
    host = run(java, "-jar", compiler, "--print-platform").strip()
    prefix = "META-INF/cyclonedds-idlc/" + host + "/"
    for case in ("checksum", "platform", "traversal"):
        content = dict(resources)
        if case == "checksum":
            entry = next(name for name in content if name.startswith(prefix + "bin/") and not name.endswith("/"))
            content[entry] = b"corrupted compiler"
        elif case == "platform":
            content[prefix + "compiler.properties"] = b"platform=unsupported-host\n"
        else:
            content[prefix + "files.sha256"] = ("0" * 64 + " ../escape\n").encode()
        damaged = root / (case + ".jar")
        with zipfile.ZipFile(damaged, "w") as jar:
            for name, data in content.items():
                jar.writestr(name, data)
        diagnostic = run(java, "-jar", str(damaged), "-h", succeeds=False)
        assert {"checksum": "checksum mismatch", "platform": "build host", "traversal": "Invalid compiler manifest"}[case] in diagnostic, diagnostic
    print("Standalone compiler generation, empty PATH, paths with spaces, diagnostics and corrupt-bundle rejection passed")
