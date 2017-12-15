/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language.swift.internal;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.gradle.api.Buildable;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.LibraryBinaryIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.file.collections.FileCollectionAdapter;
import org.gradle.api.internal.file.collections.MinimalFileSet;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.language.cpp.internal.NativeDependencyCache;
import org.gradle.language.nativeplatform.internal.Names;
import org.gradle.language.swift.SwiftBinary;
import org.gradle.language.swift.tasks.SwiftCompile;
import org.gradle.nativeplatform.internal.modulemap.ModuleMap;

import javax.inject.Inject;
import java.io.File;
import java.util.Map;
import java.util.Set;

import static org.gradle.language.cpp.CppBinary.DEBUGGABLE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.OPTIMIZED_ATTRIBUTE;

public class DefaultSwiftBinary implements SwiftBinary {
    private final String name;
    private final Provider<String> module;
    private final boolean debuggable;
    private final boolean optimized;
    private final boolean testable;
    private final FileCollection source;
    private final FileCollection compileModules;
    private final FileCollection linkLibs;
    private final Configuration runtimeLibs;
    private final DirectoryProperty objectsDir;
    private final RegularFileProperty moduleFile;
    private final Property<SwiftCompile> compileTaskProperty;
    private final Configuration importPathConfiguration;

    public DefaultSwiftBinary(String name, ProjectLayout projectLayout, final ObjectFactory objectFactory, Provider<String> module, boolean debuggable, boolean optimized, boolean testable, FileCollection source, ConfigurationContainer configurations, Configuration implementation) {
        this.name = name;
        this.module = module;
        this.debuggable = debuggable;
        this.optimized = optimized;
        this.testable = testable;
        this.source = source;
        this.objectsDir = projectLayout.directoryProperty();
        this.moduleFile = projectLayout.fileProperty();
        this.compileTaskProperty = objectFactory.property(SwiftCompile.class);

        Names names = Names.of(name);

        // TODO - reduce duplication with C++ binary
        importPathConfiguration = configurations.maybeCreate(names.withPrefix("swiftCompile"));
        importPathConfiguration.extendsFrom(implementation);
        importPathConfiguration.setCanBeConsumed(false);
        importPathConfiguration.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.SWIFT_API));
        importPathConfiguration.getAttributes().attribute(DEBUGGABLE_ATTRIBUTE, debuggable);
        importPathConfiguration.getAttributes().attribute(OPTIMIZED_ATTRIBUTE, optimized);

        Configuration nativeLink = configurations.maybeCreate(names.withPrefix("nativeLink"));
        nativeLink.extendsFrom(implementation);
        nativeLink.setCanBeConsumed(false);
        nativeLink.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.NATIVE_LINK));
        nativeLink.getAttributes().attribute(DEBUGGABLE_ATTRIBUTE, debuggable);
        nativeLink.getAttributes().attribute(OPTIMIZED_ATTRIBUTE, optimized);

        Configuration nativeRuntime = configurations.maybeCreate(names.withPrefix("nativeRuntime"));
        nativeRuntime.extendsFrom(implementation);
        nativeRuntime.setCanBeConsumed(false);
        nativeRuntime.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME));
        nativeRuntime.getAttributes().attribute(DEBUGGABLE_ATTRIBUTE, debuggable);
        nativeRuntime.getAttributes().attribute(OPTIMIZED_ATTRIBUTE, optimized);

        compileModules = new FileCollectionAdapter(new ModulePath(importPathConfiguration));
        linkLibs = nativeLink;
        runtimeLibs = nativeRuntime;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Provider<String> getModule() {
        return module;
    }

    @Override
    public boolean isDebuggable() {
        return debuggable;
    }

    @Override
    public boolean isOptimized() {
        return optimized;
    }

    @Override
    public boolean isTestable() {
        return testable;
    }

    @Override
    public FileCollection getSwiftSource() {
        return source;
    }

    @Override
    public FileCollection getCompileModules() {
        return compileModules;
    }

    @Override
    public FileCollection getLinkLibraries() {
        return linkLibs;
    }

    @Override
    public FileCollection getRuntimeLibraries() {
        return runtimeLibs;
    }

    public DirectoryProperty getObjectsDir() {
        return objectsDir;
    }

    public RegularFileProperty getModuleFile() {
        return moduleFile;
    }

    public Configuration getImportPathConfiguration() {
        return importPathConfiguration;
    }

    @Override
    public FileCollection getObjects() {
        return objectsDir.getAsFileTree().matching(new PatternSet().include("**/*.obj", "**/*.o"));
    }

    @Override
    public Property<SwiftCompile> getCompileTask() {
        return compileTaskProperty;
    }

    @Inject
    protected NativeDependencyCache getNativeDependencyCache() {
        throw new UnsupportedOperationException();
    }

    private class ModulePath implements MinimalFileSet, Buildable {
        private final Configuration importPathConfig;

        private Set<File> result;

        ModulePath(Configuration importPathConfig) {
            this.importPathConfig = importPathConfig;
        }

        @Override
        public String getDisplayName() {
            return "Module include path for " + DefaultSwiftBinary.this.toString();
        }

        @Override
        public Set<File> getFiles() {
            if (result == null) {
                result = Sets.newLinkedHashSet();
                Map<String, ModuleMap> moduleMaps = Maps.newHashMap();
                for (ResolvedArtifactResult artifact : importPathConfig.getIncoming().getArtifacts()) {
                    Usage usage = artifact.getVariant().getAttributes().getAttribute(Usage.USAGE_ATTRIBUTE);
                    if (usage != null && Usage.C_PLUS_PLUS_API.equals(usage.getName())) {
                        String moduleName;

                        ComponentIdentifier id = artifact.getId().getComponentIdentifier();
                        if (ModuleComponentIdentifier.class.isAssignableFrom(id.getClass())) {
                            moduleName = ((ModuleComponentIdentifier) id).getModule();
                        } else if (ProjectComponentIdentifier.class.isAssignableFrom(id.getClass())) {
                            moduleName = ((ProjectComponentIdentifier) id).getProjectName();
                        } else {
                            throw new IllegalArgumentException("Could not determine the name of " + id.getDisplayName() + ": unknown component identifier type: " + id.getClass().getSimpleName());
                        }

                        ModuleMap moduleMap;
                        if (moduleMaps.containsKey(moduleName)) {
                            moduleMap = moduleMaps.get(moduleName);
                        } else {
                            moduleMap = new ModuleMap(moduleName, Lists.<String>newArrayList());
                            moduleMaps.put(moduleName, moduleMap);
                        }
                        moduleMap.getPublicHeaderPaths().add(artifact.getFile().getAbsolutePath());
                    }
                    // TODO Change this to only add SWIFT_API artifacts and instead parse modulemaps to discover compile task inputs
                    result.add(artifact.getFile());
                }

                if (!moduleMaps.isEmpty()) {
                    NativeDependencyCache cache = getNativeDependencyCache();
                    for (ModuleMap moduleMap : moduleMaps.values()) {
                        result.add(cache.getModuleMapFile(moduleMap));
                    }
                }
            }
            return result;
        }

        @Override
        public TaskDependency getBuildDependencies() {
            return importPathConfig.getBuildDependencies();
        }
    }
}
