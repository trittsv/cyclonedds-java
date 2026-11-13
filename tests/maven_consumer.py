# Copyright(c) 2026 Sven Trittler. SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
"""Build/install the artifacts, then exercise a standalone ordinary Maven project."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--maven", required=True)
parser.add_argument("--build", type=Path, required=True)
parser.add_argument("--platform", required=True)
parser.add_argument("--version", required=True)
parser.add_argument("--repository", type=Path)
args = parser.parse_args()
source = Path(__file__).resolve().parent.parent
repository = args.repository or args.build / "maven-test-repository"
repository = repository.resolve()
base = [args.maven, "-B", "-ntp", "-Dmaven.repo.local=" + str(repository)]

def run(command, cwd, succeeds=True):
    result = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
    if succeeds != (result.returncode == 0):
        raise AssertionError(result.stdout)
    return result.stdout

run(base + ["-f", str(source / "maven/pom.xml"),
    "-Dcyclonedds.runtime.jar=" + str(args.build / f"org.eclipse.cyclonedds-{args.version}.jar"),
    "-Dcyclonedds.idlc.jar=" + str(args.build / f"org.eclipse.cyclonedds.idlc-{args.version}.jar"), "clean", "install"], source)
with tempfile.TemporaryDirectory(prefix="cyclonedds maven consumer ") as temporary:
    project = Path(temporary) / "app"
    shutil.copytree(source / "tests/maven-consumer", project)
    run(base + ["package"], project)  # Default: compiler version equals plugin version.
    compiler_version = args.version + "-test-" + hashlib.sha256(temporary.encode()).hexdigest()[:12]
    # Force an alternate compiler-only bundle to be fetched through Maven's repository
    # resolver, independently of the already-installed launcher/plugin.
    remote = Path(temporary) / "tool repository"
    relative = Path("org/eclipse/cyclonedds/org.eclipse.cyclonedds.idlc") / compiler_version
    remote_version = remote / relative
    remote_version.mkdir(parents=True)
    compiler_name = f"org.eclipse.cyclonedds.idlc-{compiler_version}.jar"
    cached_compiler = repository / relative / compiler_name
    installed = repository / "org/eclipse/cyclonedds/org.eclipse.cyclonedds.idlc" / args.version
    compiler_data = (installed / f"org.eclipse.cyclonedds.idlc-{args.version}.jar").read_bytes()
    pom_data = (installed / f"org.eclipse.cyclonedds.idlc-{args.version}.pom").read_text().replace(
        "<artifactId>org.eclipse.cyclonedds.idlc</artifactId>",
        f"<artifactId>org.eclipse.cyclonedds.idlc</artifactId><version>{compiler_version}</version>").encode()
    for name, data in ((compiler_name, compiler_data), (f"org.eclipse.cyclonedds.idlc-{compiler_version}.pom", pom_data)):
        (remote_version / name).write_bytes(data)
        (remote_version / (name + ".sha1")).write_text(hashlib.sha1(data).hexdigest())
    base = base + ["-Dcyclonedds.idlc.version=" + compiler_version]
    pom = project / "pom.xml"
    pom.write_text(pom.read_text().replace("</project>",
        f"<pluginRepositories><pluginRepository><id>compiler-fixture</id><url>{remote.as_uri()}</url><snapshots><enabled>true</enabled></snapshots></pluginRepository></pluginRepositories></project>"))
    command = base + ["package"]
    # No CMake, Git checkout or installed idlc appears in this standalone build.
    first = run(command, project)
    output = project / "target/generated-sources/cyclonedds/MavenDemo/Message.java"
    assert output.is_file() and (project / "target/classes/MavenDemo/Message.class").is_file(), first
    assert (project / "target/classes/Demo.class").is_file(), first
    assert cached_compiler.is_file(), "Compiler was not fetched from the configured Maven repository"
    assert "compiler-fixture" in (repository / relative / "_remote.repositories").read_text()
    before = output.stat().st_mtime_ns
    unchanged = run(base + ["-o", "package"], project)
    assert "up to date" in unchanged and output.stat().st_mtime_ns == before, unchanged
    runtime = repository / f"org/eclipse/cyclonedds/org.eclipse.cyclonedds/{args.version}/org.eclipse.cyclonedds-{args.version}.jar"
    java = Path(os.environ.get("JAVA_HOME", "")) / "bin/java" if os.environ.get("JAVA_HOME") else shutil.which("java")
    with zipfile.ZipFile(runtime) as jar:
        assert not any(n.startswith(("META-INF/cyclonedds-idlc/", "org/eclipse/cyclonedds/idlc/", "org/eclipse/cyclonedds/maven/")) or n == "META-INF/maven/plugin.xml" for n in jar.namelist())
    compiler_artifact = installed / f"org.eclipse.cyclonedds.idlc-{args.version}.jar"
    with zipfile.ZipFile(compiler_artifact) as jar:
        assert "META-INF/maven/plugin.xml" in jar.namelist()
        assert "org/eclipse/cyclonedds/idlc/Idlc.class" in jar.namelist()
        assert not any(n.startswith(("org/eclipse/cyclonedds/native/", "org/eclipse/cyclonedds/internal/", "org/omg/dds/")) for n in jar.namelist())
    # The very same compiler artifact works as a CLI without Maven libraries.
    run([str(java), "-jar", str(compiler_artifact), "-v"], project)
    message = run([str(java), "-cp", str(project / "target/classes") + os.pathsep + str(runtime), "Demo"], project)
    assert "Generated during Maven build" in message
    output.write_text("// tampered generated source\n")
    run(base + ["-o", "package"], project)
    assert "public class Message" in output.read_text()
    # Include/define configuration, including non-.idl include files.
    pom = project / "pom.xml"
    pom.write_text(pom.read_text().replace("<executions><execution><goals><goal>generate</goal>",
        "<configuration><includeDirectories><includeDirectory>${project.basedir}/schema includes</includeDirectory></includeDirectories><defines><define>ENABLED=1</define></defines></configuration><executions><execution><goals><goal>generate</goal>"))
    includes = project / "schema includes"
    includes.mkdir()
    header = includes / "bound.inc"
    header.write_text("#define TEXT_BOUND 20\n")
    schema = project / "src/main/idl/Message.idl"
    original = schema.read_text()
    schema.write_text('#include "bound.inc"\n#if !ENABLED\n#error missing definition\n#endif\n' + original.replace("string text", "string<TEXT_BOUND> text"))
    run(base + ["-o", "package"], project)
    assert "20L" in output.read_text()
    header.write_text("#define TEXT_BOUND 30\n")
    run(base + ["-o", "package"], project)
    assert "30L" in output.read_text()
    # A failed compiler must retain the last successful sources and fail Maven.
    previous = output.read_bytes()
    schema.write_text("module Invalid { struct Broken { long ; }; };\n")
    failure = run(base + ["-o", "package"], project, succeeds=False)
    assert "IDL compiler exited with status" in failure and "Message.idl" in failure, failure
    assert output.read_bytes() == previous
    schema.write_text(original)
    run(base + ["-o", "package"], project)
    # Deleted schemas and even a removed input directory must remove stale Java.
    extra = schema.parent / "Extra.idl"
    extra.write_text("module MavenDemo { @final struct Extra { long value; }; };\n")
    run(base + ["-o", "generate-sources"], project)
    generated_extra = output.parent / "Extra.java"
    assert generated_extra.exists()
    extra.unlink()
    run(base + ["-o", "generate-sources"], project)
    assert not generated_extra.exists()
    shutil.rmtree(schema.parent)
    run(base + ["-o", "generate-sources"], project)
    assert not output.exists()
    print("Maven consumer generation/compilation, runtime/compiler separation, automatic host resolution, offline rebuild, includes, defines, stale removal and compiler failure passed")
