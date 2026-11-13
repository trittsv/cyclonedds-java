/* Copyright(c) 2026 Sven Trittler. SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause */
package org.eclipse.cyclonedds.idlc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** Tracks all source/include inputs and owns only its dedicated generated directory. */
public final class IdlGenerator {
    private IdlGenerator() {}
    private static final String STATE = ".cyclonedds-idlc";
    public static boolean generate(Path tool, Path sources, Path output, List<Path> includes, List<String> defines,
                                   Path temporaryRoot, Consumer<String> log) throws IOException, InterruptedException {
        sources = sources.toAbsolutePath().normalize(); output = output.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(output)) throw new IOException("Generated source directory cannot be a symlink: " + output);
        List<Path> roots = new ArrayList<>(); roots.add(sources);
        for (Path include : includes) {
            Path root = include.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) throw new IOException("IDL include directory does not exist: " + root);
            roots.add(root);
        }
        for (Path root : roots) if (root.startsWith(output) || output.startsWith(root))
            throw new IOException("IDL inputs and generated output must be separate directories: " + output);
        var inputs = files(sources).stream().filter(p -> p.getFileName().toString().endsWith(".idl")).toList();
        // The native parser requires long Windows names, not 8.3 aliases such as RUNNER~1.
        String sourcePath = Files.exists(sources) ? sources.toRealPath().toString() : sources.toString();
        List<String> options = new ArrayList<>(List.of("-I", sourcePath));
        for (Path root : roots.subList(1, roots.size())) { options.add("-I"); options.add(root.toRealPath().toString()); }
        for (String define : defines) { options.add("-D"); options.add(define); }
        MessageDigest digest = Idlc.sha256();
        update(digest, "cyclonedds-idlc-state-v1");
        hash(digest, tool); for (String option : options) update(digest, option);
        for (Path root : roots) {
            update(digest, root.toString());
            for (Path path : files(root)) hash(digest, path);
        }
        String inputHash = HexFormat.of().formatHex(digest.digest());
        Path state = output.resolve(STATE);
        if (Files.exists(output)) {
            if (!Files.isDirectory(output)) throw new IOException("Generated output is not a directory: " + output);
            if (!Files.isRegularFile(state)) {
                try (var contents = Files.list(output)) {
                    if (contents.findAny().isPresent()) throw new IOException("Refusing to replace an unmanaged generated directory: " + output);
                }
            } else {
                List<String> previous = Files.readAllLines(state, StandardCharsets.UTF_8);
                if (previous.size() == 2 && previous.get(0).equals(inputHash) && previous.get(1).equals(outputHash(output))) return false;
            }
        }
        Files.createDirectories(output.getParent());
        Path staging = Files.createTempDirectory(output.getParent(), ".cyclonedds-generate-");
        try {
            if (!inputs.isEmpty()) try (var compiler = Idlc.open(tool, temporaryRoot)) {
                for (Path input : inputs) {
                    List<String> arguments = new ArrayList<>(options);
                    arguments.add("-o"); arguments.add(staging.toRealPath().toString()); arguments.add(input.toRealPath().toString());
                    compiler.generate(arguments, sources.toRealPath(), log);
                }
            }
            Files.writeString(staging.resolve(STATE), inputHash + "\n" + outputHash(staging) + "\n", StandardCharsets.UTF_8);
            // Keep the last successful sources intact when compilation fails.
            Path backup = null;
            if (Files.exists(output)) {
                backup = Files.createTempDirectory(output.getParent(), ".cyclonedds-previous-");
                Files.delete(backup); Files.move(output, backup);
            }
            try { Files.move(staging, output); }
            catch (IOException error) {
                if (backup != null) Files.move(backup, output);
                throw error;
            }
            if (backup != null) Idlc.deleteTree(backup);
        } finally { Idlc.deleteTree(staging); }
        return true;
    }
    private static List<Path> files(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        if (!Files.isDirectory(root)) throw new IOException("IDL source directory is not a directory: " + root);
        try (var paths = Files.walk(root)) { return paths.filter(Files::isRegularFile).sorted().toList(); }
    }
    private static String outputHash(Path root) throws IOException {
        MessageDigest digest = Idlc.sha256();
        for (Path path : files(root)) if (!path.equals(root.resolve(STATE))) {
            update(digest, root.relativize(path).toString());
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void hash(MessageDigest digest, Path path) throws IOException {
        update(digest, path.toAbsolutePath().toString());
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
    }
    private static void update(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }
}
