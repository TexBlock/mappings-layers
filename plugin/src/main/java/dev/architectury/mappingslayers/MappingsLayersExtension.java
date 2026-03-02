/*
 * This file is part of architectury.
 * Copyright (C) 2021 architectury
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package dev.architectury.mappingslayers;

import com.google.common.hash.Hashing;
import dev.architectury.mappingslayers.api.Mappings;
import dev.architectury.mappingslayers.api.MappingsReaders;
import dev.architectury.mappingslayers.api.MappingsTransformationBuilder;
import dev.architectury.mappingslayers.api.MappingsTransformationContext;
import dev.architectury.mappingslayers.impl.MappingsTransformationBuilderImpl;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MappingsLayersExtension implements MappingsTransformationContext {
    private static final Logger LOGGER = Logging.getLogger(MappingsLayersExtension.class);
    private final Project project;
    private final Configuration configuration;
    private final Path cacheFolder;
    
    public MappingsLayersExtension(Project project) {
        this.project = project;
        this.configuration = this.project.getConfigurations().detachedConfiguration();
        this.cacheFolder = project.getGradle().getGradleUserHomeDir().toPath().resolve("caches/mappings-layers");
        try {
            Files.createDirectories(cacheFolder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    public Dependency from(Object notation) {
        return from(notation, (Action<MappingsTransformationBuilder>) null);
    }
    
    public Dependency from(Object notation, @Nullable Closure<MappingsTransformationBuilder> closure) {
        return createMappingsDependency(resolveMappings(notation), builder(toAction(closure)));
    }
    
    public Dependency from(Object notation, @Nullable Action<MappingsTransformationBuilder> action) {
        return createMappingsDependency(resolveMappings(notation), builder(action));
    }
    
    public MappingsTransformationBuilder builder() {
        return builder((Action<MappingsTransformationBuilder>) null);
    }
    
    public MappingsTransformationBuilder builder(@Nullable Closure<MappingsTransformationBuilder> closure) {
        return builder(toAction(closure));
    }
    
    public MappingsTransformationBuilder builder(@Nullable Action<MappingsTransformationBuilder> action) {
        MappingsTransformationBuilderImpl builder = new MappingsTransformationBuilderImpl(this);
        if (action != null) {
            action.execute(builder);
        }
        return builder;
    }
    
    public Dependency createMappingsDependency(Mappings mappings) {
        return createMappingsDependency(mappings, null);
    }
    
    public Dependency createMappingsDependency(Mappings mappings, @Nullable MappingsTransformationBuilder builder) {
        String uuid = mappings.uuid();
        if (builder != null) {
            uuid += "||||||||||";
            uuid += builder.uuid();
        }
        String sha256 = Hashing.sha256().hashBytes(uuid.getBytes(StandardCharsets.UTF_16)).toString();
        Path resolve = cacheFolder.resolve("mappings/" + sha256 + "-1.0.0.jar");
        if (!Files.exists(resolve)) {
            try {
                Files.createDirectories(resolve.getParent());
                if (builder != null) {
                    mappings = mappings.withTransformations(builder.getTransformations());
                }
                Map<String, String> env = new HashMap<>();
                env.put("create", "true");
                URI uri = new URI("jar:" + resolve.toUri());
                try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
                    Files.createDirectories(fs.getPath("mappings"));
                    Files.writeString(fs.getPath("mappings/mappings.tiny"),
                            mappings.serializeToTiny(), StandardOpenOption.CREATE);
                }
            } catch (IOException | URISyntaxException e) {
                try {
                    Files.deleteIfExists(resolve);
                } catch (IOException ignored) {
                }
                throw new RuntimeException(e);
            }
        }
        
        project.getRepositories().flatDir(repository -> repository.dir(resolve.getParent().toFile()));
        return project.getDependencies().create(resolve.toFile());
    }
    
    @Override
    public Mappings resolveMappings(Object o) {
        LOGGER.lifecycle("Resolving mappings dependency: {}", o);
        
        if (o instanceof Mappings) {
            LOGGER.info("Object is already a Mappings instance, returning directly");
            return (Mappings) o;
        }
        
        try {
            return resolveMappingsFromObject(o);
        } catch (Exception e) {
            LOGGER.error("Completely failed to resolve mappings for: {}", o, e);
            throw new RuntimeException("Unable to resolve mappings dependency: " + o, e);
        }
    }
    
    /**
     * Attempts to resolve mappings from various input types
     */
    private Mappings resolveMappingsFromObject(Object o) {
        // Handle String inputs (file paths, URLs, dependency notations)
        if (o instanceof String) {
            String str = (String) o;
            
            // Check if it's a file path
            File file = new File(str);
            if (file.exists() && file.isFile()) {
                LOGGER.info("Loading mappings from local file: {}", file.getAbsolutePath());
                try {
                    return MappingsReaders.readDetection(file.toPath());
                } catch (Exception e) {
                    LOGGER.warn("Failed to read mappings from file: {}", file.getAbsolutePath(), e);
                }
            }
            
            // Check if it's a URL
            if (str.startsWith("http://") || str.startsWith("https://")) {
                LOGGER.info("Downloading mappings from URL: {}", str);
                try {
                    return downloadAndReadMappings(str);
                } catch (Exception e) {
                    LOGGER.warn("Failed to download mappings from URL: {}", str, e);
                }
            }
        }
        
        // Fall back to Gradle dependency resolution
        return resolveMappingsViaGradle(o);
    }
    
    /**
     * Downloads mappings from a URL and reads them
     */
    private Mappings downloadAndReadMappings(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        Path tempFile = Files.createTempFile("mappings-download-", ".tmp");
        try {
            try (java.io.InputStream in = url.openStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return MappingsReaders.readDetection(tempFile);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // Ignore cleanup failures
            }
        }
    }
    
    /**
     * Resolves mappings using Gradle's dependency resolution mechanism
     */
    private Mappings resolveMappingsViaGradle(Object o) {
        if (o instanceof Dependency) {
            LOGGER.info("Resolving Dependency object: {}", o);
            try {
                Set<File> dependencyFiles = project.getConfigurations().detachedConfiguration((Dependency) o).getFiles();
                if (dependencyFiles.size() != 1) {
                    throw new AssertionError("Expecting only 1 file, got " + dependencyFiles.size() + " files!");
                }
                File file = dependencyFiles.iterator().next();
                LOGGER.info("Resolved dependency file: {}", file.getAbsolutePath());
                return MappingsReaders.readDetection(file.toPath());
            } catch (Exception e) {
                LOGGER.error("Failed to resolve Dependency object: {}", o, e);
                throw new RuntimeException("Failed to resolve dependency: " + o, e);
            }
        }
        
        LOGGER.info("Resolving dependency notation: {}", o);
        try {
            Dependency dependency = project.getDependencies().add(configuration.getName(), o);
            LOGGER.debug("Added dependency to configuration: {}", dependency);
            
            Set<File> dependencyFiles = configuration.files(dependency);
            LOGGER.debug("Found {} dependency files", dependencyFiles.size());
            
            if (dependencyFiles.size() != 1) {
                throw new AssertionError("Expecting only 1 file, got " + dependencyFiles.size() + " files!");
            }
            
            File file = dependencyFiles.iterator().next();
            LOGGER.info("Successfully resolved mappings file: {}", file.getAbsolutePath());
            return MappingsReaders.readDetection(file.toPath());
        } catch (Exception e) {
            LOGGER.error("Failed to resolve mappings dependency: {}", o, e);
            String errorMsg = String.format(
                "Failed to resolve mappings dependency: %s. %n" +
                "Possible causes:%n" +
                "1. The dependency doesn't exist in any configured repositories%n" +
                "2. Network connectivity issues%n" +
                "3. Repository configuration issues%n" +
                "4. Invalid dependency notation%n" +
                "Please check your build.gradle configuration and ensure the dependency is accessible.", 
                o
            );
            throw new RuntimeException(errorMsg, e);
        }
    }
    
    @Nullable
    private static <T> Action<T> toAction(@Nullable Closure<T> closure) {
        if (closure == null) return null;
        return obj -> {
            Closure<T> clone = (Closure<T>) closure.clone();
            clone.setResolveStrategy(1);
            clone.setDelegate(obj);
            clone.call(obj);
        };
    }
}
