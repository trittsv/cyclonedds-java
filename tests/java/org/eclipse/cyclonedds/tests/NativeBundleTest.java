/*
 * Copyright(c) 2026 Sven Trittler
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
 * v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
*/

package org.eclipse.cyclonedds.tests;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.eclipse.cyclonedds.internal.NativeLoader;

/** Checks failure behavior of the distributable JAR in isolated JVMs. */
public final class NativeBundleTest {
    private NativeBundleTest() {}
    public static final class Probe {
        private Probe() {}
        public static void main(String[] args) {
            try { NativeLoader.load(); throw new AssertionError("Expected native loading to fail"); }
            catch (UnsatisfiedLinkError error) {
                assert error.getMessage().contains(args[0]) : error;
            }
        }
    }
    private static void probe(Path bundle, Path temp, String message, boolean foreignArchitecture) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String tests = Path.of(NativeBundleTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var command = new ArrayList<>(List.of(javaExecutable, "-ea", "-Djava.io.tmpdir=" + temp));
        if (foreignArchitecture) command.add("-Dos.arch=unsupported-test");
        command.addAll(List.of("-cp", tests + File.pathSeparator + bundle, Probe.class.getName(), message));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor(); throw new AssertionError("Native loader probe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assert process.exitValue() == 0 : output;
        try (var files = Files.list(temp)) { assert files.findAny().isEmpty() : "Extraction directory was not cleaned"; }
    }
    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]);
        try (var bundle = new ZipFile(original.toFile())) {
            assert bundle.stream().noneMatch(entry -> entry.getName().contains("/patches/") || entry.getName().endsWith(".patch"))
                : "The runtime must use the unmodified core without bundled patches";
        }
        Path directory = Files.createTempDirectory("cyclonedds-bundle-test-");
        Path extraction = Files.createDirectory(directory.resolve("extraction"));
        Path corrupt = directory.resolve("corrupt.jar");
        try {
            boolean changed = false;
            try (var input = new ZipFile(original.toFile()); var output = new ZipOutputStream(Files.newOutputStream(corrupt))) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    byte[] bytes;
                    try (var stream = input.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                    if (entry.getName().endsWith("/libraries.list")) {
                        bytes[0] = bytes[0] == '0' ? (byte) '1' : (byte) '0'; changed = true;
                    }
                    output.putNextEntry(new ZipEntry(entry.getName())); output.write(bytes); output.closeEntry();
                }
            }
            assert changed;
            probe(corrupt, extraction, "Checksum mismatch", false);
            probe(original, extraction, "does not contain natives", true);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Native bundle: corrupted resources, unsupported platform and extraction cleanup passed");
    }
}
