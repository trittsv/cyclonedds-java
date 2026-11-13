/* Copyright(c) 2026 Sven Trittler. SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause */
package org.eclipse.cyclonedds.idlc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** Assembles checked platform build outputs into separate runtime and compiler distributions. */
public final class UniversalJar {
    private UniversalJar() {}
    private static final String METADATA = "META-INF/cyclonedds/bundle.properties";
    private static final String RUNTIME = "org/eclipse/cyclonedds/native/";
    private static final String COMPILER = "META-INF/cyclonedds-idlc/";
    private static final Set<String> PLATFORMS = Set.of("linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64", "windows-x86_64", "windows-aarch64");
    private static void put(Map<String, byte[]> entries, String name, byte[] value) throws IOException {
        byte[] previous = entries.putIfAbsent(name, value);
        if (previous != null && !Arrays.equals(previous, value)) throw new IOException("Conflicting platform build resource: " + name);
    }
    private static byte[] read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing platform resource: " + name);
        try (var input = jar.getInputStream(entry)) { return input.readAllBytes(); }
    }
    private static Properties properties(byte[] data) throws IOException {
        var properties = new Properties(); properties.load(new ByteArrayInputStream(data)); return properties;
    }
    private static void verify(JarFile jar, String prefix, boolean compiler) throws IOException {
        String manifest = new String(read(jar, prefix + (compiler ? "files.sha256" : "libraries.list")), StandardCharsets.UTF_8);
        Set<String> paths = new HashSet<>();
        for (String line : manifest.lines().toList()) {
            String pattern = compiler ? "[a-f0-9]{64} (bin|lib)/[A-Za-z0-9][A-Za-z0-9_.+-]*" : "[a-f0-9]{64} [A-Za-z0-9][A-Za-z0-9_.+-]*";
            if (!line.matches(pattern)) throw new IOException("Invalid platform manifest: " + prefix);
            String path = line.substring(65);
            if (!paths.add(path)) throw new IOException("Duplicate platform manifest entry: " + path);
            byte[] digest = Idlc.sha256().digest(read(jar, prefix + path));
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(line.substring(0,64)), digest)) throw new IOException("Platform checksum mismatch: " + prefix + path);
        }
        if (paths.isEmpty()) throw new IOException("Empty platform manifest: " + prefix);
        if (compiler) {
            var settings = properties(read(jar, prefix + "compiler.properties"));
            String executable = settings.getProperty("executable", ""), backend = settings.getProperty("backend", "");
            if (!executable.startsWith("bin/") || !backend.startsWith("lib/") || !paths.contains(executable) || !paths.contains(backend))
                throw new IOException("Incomplete compiler manifest: " + prefix);
        }
    }
    /** All inputs must share the same Java bytecode, version and notices. */
    public static void assemble(Path output, String version, String selectedKind, Set<String> required, List<Path> inputs) throws IOException {
        if (!Set.of("runtime", "compiler").contains(selectedKind)) throw new IOException("Expected --kind runtime or compiler");
        if (required.isEmpty() || !PLATFORMS.containsAll(required)) throw new IOException("Invalid required platform list: " + required);
        Map<String, byte[]> entries = new TreeMap<>();
        Set<String> runtime = new TreeSet<>(), compiler = new TreeSet<>();
        for (Path input : inputs) try (var jar = new JarFile(input.toFile())) {
            Properties metadata = properties(read(jar, METADATA));
            String platform = metadata.getProperty("platform"), kind = metadata.getProperty("kind");
            if (!version.equals(metadata.getProperty("version"))) throw new IOException("Version mismatch in " + input);
            if (!PLATFORMS.contains(platform == null ? "" : platform)) throw new IOException("Invalid platform in " + input);
            boolean isCompiler = "compiler".equals(kind);
            if (!isCompiler && !"runtime".equals(kind)) throw new IOException("Expected a platform compiler/runtime JAR: " + input);
            if (!selectedKind.equals(kind)) continue;
            String prefix = (isCompiler ? COMPILER : RUNTIME) + platform + "/";
            if (!(isCompiler ? compiler : runtime).add(platform)) throw new IOException("Duplicate " + kind + " for " + platform);
            verify(jar, prefix, isCompiler);
            if (isCompiler && !platform.equals(properties(read(jar, prefix + "compiler.properties")).getProperty("platform")))
                throw new IOException("Compiler platform mismatch in " + input);
            var sourceEntries = jar.entries();
            while (sourceEntries.hasMoreElements()) {
                var entry = sourceEntries.nextElement(); String name = entry.getName();
                if (entry.isDirectory() || name.equals("META-INF/MANIFEST.MF") || name.equals(METADATA) || name.equals(RUNTIME + "platforms.list") || name.equals(COMPILER + "platforms.list")) continue;
                if (name.startsWith("/") || Arrays.asList(name.split("/")).contains("..") || name.contains("\\")) throw new IOException("Invalid JAR resource path: " + name);
                if ((name.startsWith(RUNTIME) || name.startsWith(COMPILER)) && !name.startsWith(prefix)) throw new IOException("Unexpected foreign native resource: " + name);
                put(entries, name, read(jar, name));
            }
        }
        Set<String> platforms = selectedKind.equals("runtime") ? runtime : compiler;
        if (!platforms.containsAll(required)) throw new IOException("Missing required " + selectedKind + " platforms: " + difference(required, platforms));
        List<String> mandatory = selectedKind.equals("runtime")
            ? List.of("org/eclipse/cyclonedds/internal/NativeLoader.class")
            : List.of("org/eclipse/cyclonedds/idlc/Idlc.class", "META-INF/cyclonedds/LICENSE-mcpp");
        for (String entry : mandatory) if (!entries.containsKey(entry)) throw new IOException("Incomplete " + selectedKind + " JAR: missing " + entry);
        for (String name : entries.keySet()) {
            boolean compilerEntry = name.startsWith(COMPILER) || name.startsWith("org/eclipse/cyclonedds/idlc/") || name.startsWith("org/eclipse/cyclonedds/maven/") || name.equals("META-INF/maven/plugin.xml");
            boolean runtimeEntry = name.startsWith(RUNTIME) || name.startsWith("org/eclipse/cyclonedds/internal/") || name.startsWith("org/omg/dds/");
            if (selectedKind.equals("runtime") && compilerEntry || selectedKind.equals("compiler") && runtimeEntry)
                throw new IOException("Unexpected " + (compilerEntry ? "compiler" : "runtime") + " resource in " + selectedKind + " JAR: " + name);
        }
        byte[] index = (String.join("\n", platforms) + "\n").getBytes(StandardCharsets.UTF_8);
        entries.put((selectedKind.equals("runtime") ? RUNTIME : COMPILER) + "platforms.list", index);
        entries.put(METADATA, ("version=" + version + "\nkind=universal-" + selectedKind + "\nplatforms=" + String.join(",", platforms) + "\n").getBytes(StandardCharsets.UTF_8));
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (selectedKind.equals("compiler")) manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, Idlc.class.getName());
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        var manifestBytes = new ByteArrayOutputStream(); manifest.write(manifestBytes);
        entries.put("META-INF/MANIFEST.MF", manifestBytes.toByteArray());
        output = output.toAbsolutePath(); Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".cyclonedds-universal-", ".jar");
        try {
            try (var jar = new JarOutputStream(Files.newOutputStream(temporary))) {
                for (var entry : entries.entrySet()) {
                    var target = new JarEntry(entry.getKey()); target.setTime(0);
                    jar.putNextEntry(target); jar.write(entry.getValue()); jar.closeEntry();
                }
            }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static Set<String> difference(Set<String> expected, Set<String> actual) { var missing = new TreeSet<>(expected); missing.removeAll(actual); return missing; }
    /** --input-dir consumes CI platform JAR artifacts recursively. */
    public static void main(String[] args) throws IOException {
        Path output = null; String version = null, kind = null; Set<String> required = new TreeSet<>(); List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "--output" -> output = Path.of(args[++i]);
            case "--kind" -> kind = args[++i];
            case "--version" -> version = args[++i];
            case "--platforms" -> required.addAll(List.of(args[++i].split(",")));
            case "--input-dir" -> { try (var paths = Files.walk(Path.of(args[++i]))) { inputs.addAll(paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar")).sorted().toList()); } }
            default -> inputs.add(Path.of(args[i]));
        }
        if (output == null || version == null || kind == null || required.isEmpty() || inputs.isEmpty())
            throw new IllegalArgumentException("Usage: UniversalJar --kind runtime|compiler --output output.jar --version VERSION --platforms PLATFORM,... [--input-dir DIR] platform.jar...");
        assemble(output, version, kind, required, inputs);
        System.out.println("Universal " + kind + " JAR: " + output + " (required platforms: " + String.join(",", required) + ")");
    }
}
