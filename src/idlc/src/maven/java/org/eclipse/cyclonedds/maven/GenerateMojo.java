/* Copyright(c) 2026 Sven Trittler. SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause */
package org.eclipse.cyclonedds.maven;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.*;
import org.eclipse.cyclonedds.idlc.*;

/** Generates Java from src/main/idl before the normal Java compilation. */
@Mojo(name="generate", defaultPhase=LifecyclePhase.GENERATE_SOURCES, threadSafe=true)
public final class GenerateMojo extends AbstractMojo {
    @Parameter(defaultValue="${project}", readonly=true, required=true)
    private MavenProject project;
    @javax.inject.Inject private RepositorySystem repositorySystem;
    @Parameter(defaultValue="${repositorySystemSession}", readonly=true, required=true)
    private RepositorySystemSession repositorySession;
    @Parameter(defaultValue="${project.basedir}/src/main/idl", property="cyclonedds.idlc.sourceDirectory")
    private File sourceDirectory;
    @Parameter(defaultValue="${project.build.directory}/generated-sources/cyclonedds", property="cyclonedds.idlc.outputDirectory")
    private File outputDirectory;
    @Parameter(defaultValue="${project.build.directory}/cyclonedds-idlc-tools")
    private File toolDirectory;
    /** Additional directories for IDL #include; their contents are tracked for changes. */
    @Parameter private List<File> includeDirectories = new ArrayList<>();
    /** Preprocessor definitions, such as FEATURE=1. */
    @Parameter private List<String> defines = new ArrayList<>();
    /** Defaults to the plugin's version, keeping compiler/backend versions together. */
    @Parameter(property="cyclonedds.idlc.version") private String compilerVersion;
    /** Optional local compiler JAR for offline/tool development use. */
    @Parameter(property="cyclonedds.idlc.compilerJar") private File compilerJar;
    @Parameter(property="cyclonedds.idlc.skip", defaultValue="false") private boolean skip;
    @Override public void execute() throws MojoExecutionException {
        if (skip) {
            if (outputDirectory.isDirectory()) project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
            getLog().info("Skipping Cyclone DDS IDL generation"); return;
        }
        try {
            Path output = outputDirectory.toPath().toAbsolutePath().normalize();
            if (!Files.exists(sourceDirectory.toPath()) && !Files.exists(output.resolve(".cyclonedds-idlc"))) {
                getLog().debug("No IDL source directory: " + sourceDirectory); return;
            }
            Path archive = compilerJar == null ? resolveCompiler() : compilerJar.toPath();
            boolean changed = IdlGenerator.generate(archive, sourceDirectory.toPath(), output,
                includeDirectories.stream().map(File::toPath).toList(), defines, toolDirectory.toPath(), getLog()::info);
            project.addCompileSourceRoot(output.toString());
            getLog().info(changed ? "Generated DDS Java sources in " + output : "DDS Java sources are up to date");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new MojoExecutionException("IDL generation interrupted", error);
        } catch (IOException | IllegalArgumentException | ArtifactResolutionException error) {
            throw new MojoExecutionException("Cannot generate DDS Java sources: " + error.getMessage(), error);
        }
    }
    private Path resolveCompiler() throws ArtifactResolutionException, IOException {
        if (compilerVersion == null || compilerVersion.isBlank()) return Idlc.archive();
        String version = compilerVersion;
        var artifact = new DefaultArtifact("org.eclipse.cyclonedds", "org.eclipse.cyclonedds.idlc", "", "jar", version);
        List<RemoteRepository> repositories = new ArrayList<>(project.getRemotePluginRepositories());
        for (RemoteRepository repository : project.getRemoteProjectRepositories()) if (repositories.stream().noneMatch(r -> r.getUrl().equals(repository.getUrl()))) repositories.add(repository);
        var request = new ArtifactRequest(artifact, repositories, "plugin");
        return repositorySystem.resolveArtifact(repositorySession, request).getArtifact().getFile().toPath();
    }
}
