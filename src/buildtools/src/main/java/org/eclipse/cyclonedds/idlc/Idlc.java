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
package org.eclipse.cyclonedds.idlc;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/** Runs the packaged native compiler as a child process; no JNI or system install. */
public final class Idlc implements AutoCloseable {
    private static final String PREFIX = "META-INF/cyclonedds-idlc/";
    private final String prefix;
    private final Path directory;
    private final Path executable;
    private final Path backend;

    public static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        os = os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("linux") ? "linux" : os.contains("windows") ? "windows" : os;
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        arch = switch (arch) { case "arm64", "aarch64" -> "aarch64"; case "amd64", "x86_64" -> "x86_64"; default -> arch; };
        if (!Set.of("macos", "linux", "windows").contains(os) || !Set.of("aarch64", "x86_64").contains(arch))
            throw new IllegalArgumentException("Unsupported IDL compiler host: " + os + "/" + arch);
        return os + "-" + arch;
    }
    public static Idlc open(Path archive, Path temporaryRoot) throws IOException {
        Files.createDirectories(temporaryRoot);
        Path directory = Files.createTempDirectory(temporaryRoot, "idlc-");
        try { return new Idlc(archive, directory); }
        catch (IOException | RuntimeException error) {
            try { deleteTree(directory); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    private Idlc(Path archive, Path directory) throws IOException {
        this.directory = directory;
        try (var jar = new JarFile(archive.toFile())) {
            prefix = PREFIX + platform() + "/";
            if (jar.getJarEntry(prefix + "compiler.properties") == null)
                throw new IOException("JAR does not contain an IDL compiler for build host " + platform());
            var properties = new Properties();
            try (var input = resource(jar, "compiler.properties")) { properties.load(input); }
            String packagedPlatform = properties.getProperty("platform");
            if (!platform().equals(packagedPlatform)) throw new IOException("IDL compiler is for " + packagedPlatform + "; build host is " + platform());
            Set<String> paths = new HashSet<>();
            try (var input = new BufferedReader(new InputStreamReader(resource(jar, "files.sha256"), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (!line.matches("[a-f0-9]{64} (bin|lib)/[A-Za-z0-9][A-Za-z0-9_.+-]*")) throw new IOException("Invalid compiler manifest");
                    String name = line.substring(65);
                    if (!paths.add(name)) throw new IOException("Duplicate compiler entry: " + name);
                    Path output = directory.resolve(name);
                    Files.createDirectories(output.getParent());
                    MessageDigest digest = sha256();
                    try (var stream = new DigestInputStream(resource(jar, name), digest)) { Files.copy(stream, output); }
                    if (!MessageDigest.isEqual(HexFormat.of().parseHex(line.substring(0,64)), digest.digest()))
                        throw new IOException("Compiler checksum mismatch: " + name);
                }
            }
            String program = properties.getProperty("executable"), library = properties.getProperty("backend");
            if (program == null || library == null || !program.startsWith("bin/") || !library.startsWith("lib/") || !paths.contains(program) || !paths.contains(library))
                throw new IOException("Compiler manifest is incomplete");
            executable = directory.resolve(program); backend = directory.resolve(library);
            if (!executable.toFile().setExecutable(true, true) && !platform().startsWith("windows-"))
                throw new IOException("Cannot make IDL compiler executable: " + executable);
        }
    }
    private InputStream resource(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(prefix + name);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing compiler resource: " + name);
        return jar.getInputStream(entry);
    }
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    /** Compiler diagnostics are forwarded verbatim. Nonzero status fails the build. */
    public void generate(List<String> arguments, Path workingDirectory, Consumer<String> diagnostics) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(executable.toString(), "-l", backend.toString()));
        command.addAll(arguments);
        var builder = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true);
        var environment = builder.environment();
        String libraries = directory.resolve("lib").toString();
        environment.put("LD_LIBRARY_PATH", libraries);
        environment.put("DYLD_LIBRARY_PATH", libraries);
        if (platform().startsWith("windows-")) {
            String pathKey = environment.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
            environment.put(pathKey, libraries + File.pathSeparator + directory.resolve("bin") + File.pathSeparator + environment.getOrDefault(pathKey, ""));
        }
        Process process = builder.start();
        try {
            try (var output = process.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
                String line; while ((line = output.readLine()) != null) diagnostics.accept(line);
            }
            int exit = process.waitFor();
            if (exit != 0) throw new IOException("IDL compiler exited with status " + exit);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
        }
    }
    @Override public void close() throws IOException { deleteTree(directory); }
    static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    /** Location of this compiler JAR, shared by the CLI and the Maven goal. */
    public static Path archive() throws IOException {
        try { return Path.of(Idlc.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (URISyntaxException error) { throw new IOException("Cannot locate Cyclone DDS compiler JAR", error); }
    }
    /** The compiler JAR is also usable directly: java -jar ... -o generated Foo.idl. */
    public static void main(String[] arguments) throws IOException, InterruptedException {
        if (arguments.length == 1 && arguments[0].equals("--print-platform")) { System.out.println(platform()); return; }
        Path archive = archive();
        try (var compiler = open(archive, Path.of(System.getProperty("java.io.tmpdir")))) {
            compiler.generate(List.of(arguments), Path.of("").toAbsolutePath(), System.out::println);
        }
    }
}
