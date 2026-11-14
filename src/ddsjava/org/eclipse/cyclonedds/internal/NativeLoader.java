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

package org.eclipse.cyclonedds.internal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Loads a matching bundled core/JNI pair, or the development system library. */
public final class NativeLoader {
    private NativeLoader() {}
    private static boolean loaded;
    public static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        os = os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("linux") ? "linux" : os.contains("windows") ? "windows" : os.replaceAll("[^a-z0-9]", "");
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        arch = switch (arch) { case "arm64", "aarch64" -> "aarch64"; case "amd64", "x86_64" -> "x86_64"; default -> arch; };
        return os + "-" + arch;
    }
    public static synchronized void load() {
        if (loaded) return;
        String prefix = "/org/eclipse/cyclonedds/native/" + platform() + "/";
        InputStream manifest = NativeLoader.class.getResourceAsStream(prefix + "libraries.list");
        if (manifest == null) {
            if (NativeLoader.class.getResource("/org/eclipse/cyclonedds/native/platforms.list") != null)
                throw new UnsatisfiedLinkError("The Cyclone DDS runtime JAR does not contain natives for " + platform() + ". Use a universal JAR containing this platform.");
            // The thin development JAR supports an explicitly installed runtime.
            System.loadLibrary("cycloneddsjava"); loaded = true; return;
        }
        Path directory = null;
        List<Path> extracted = new ArrayList<>();
        try (var input = new BufferedReader(new InputStreamReader(manifest, StandardCharsets.UTF_8))) {
            directory = Files.createTempDirectory("org.eclipse.cyclonedds-" + platform() + "-");
            directory.toFile().deleteOnExit();
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = line.split(" ", 2);
                if (fields.length != 2 || !fields[0].matches("[a-f0-9]{64}") || !fields[1].matches("[A-Za-z0-9][A-Za-z0-9_.+-]*"))
                    throw new IOException("Invalid native library manifest");
                Path file = directory.resolve(fields[1]);
                file.toFile().deleteOnExit(); extracted.add(file);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream resource = NativeLoader.class.getResourceAsStream(prefix + fields[1])) {
                    if (resource == null) throw new IOException("Missing bundled library " + fields[1]);
                    try (var stream = new DigestInputStream(resource, digest)) { Files.copy(stream, file); }
                }
                if (!MessageDigest.isEqual(HexFormat.of().parseHex(fields[0]), digest.digest()))
                    throw new IOException("Checksum mismatch for " + fields[1]);
            }
            if (extracted.isEmpty()) throw new IOException("Empty native library manifest");
            // The manifest orders dependencies before JNI. Verify every file
            // before loading any, so a partial/corrupted bundle fails cleanly.
            for (Path file : extracted) System.load(file.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException | NoSuchAlgorithmException | UnsatisfiedLinkError error) {
            for (Path file : extracted) try { Files.deleteIfExists(file); } catch (IOException ignored) { /* Windows may keep a loaded DLL open. */ }
            if (directory != null) try { Files.deleteIfExists(directory); } catch (IOException ignored) { /* Scheduled for JVM-exit cleanup. */ }
            var failure = new UnsatisfiedLinkError("Cannot load bundled Cyclone DDS libraries for " + platform() + ": " + error.getMessage());
            failure.initCause(error); throw failure;
        }
    }
}
