package com.atlassian.dc.p9enablement.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryPackageRequirementsTest {
    @TempDir
    Path directory;

    @Test
    void findsMetadataAndClassLiteralReferencesInARealCompiledClass() throws IOException {
        Path output = compile(Map.of(
                "external.api.ExternalType", "package external.api; public class ExternalType {}",
                "external.api.UsesExternal", "package external.api; public @interface UsesExternal { Class<?> value(); }",
                "library.api.Helper", "package library.api; public class Helper {}",
                "library.api.Library", "package library.api;"
                        + "@external.api.UsesExternal(external.api.ExternalType.class) "
                        + "public class Library { library.api.Helper helper;"
                        + " java.util.List<external.api.ExternalType> values;"
                        + " public Class<external.api.ExternalType> type() {"
                        + " return external.api.ExternalType.class; } }"));
        Path library = directory.resolve("library.jar");
        Files.write(library, jar(Map.of(), Map.of(
                "library/api/Helper.class", classBytes(output, "library.api.Helper"),
                "library/api/Library.class", classBytes(output, "library.api.Library"))));

        Set<String> references = LibraryPackageRequirements.referencedPackages(
                library, Runtime.version().feature());

        assertTrue(references.contains("external.api"));
        assertFalse(references.contains("library.api"));
    }

    @Test
    void treatsNamedModuleJarsAsClasspathInputs() throws IOException {
        Path externalModule = compileModule("external.module", "module external.module { exports external.api; }",
                Map.of("external.api.ExternalType", "package external.api; public class ExternalType {}"), null);
        Path libraryModule = compileModule("library.module", "module library.module { requires external.module; }",
                Map.of("library.api.Library", "package library.api; public class Library {"
                        + " external.api.ExternalType value; }"), externalModule);
        Path library = directory.resolve("named-module-library.jar");
        Files.write(library, jar(Map.of(), Map.of(
                "module-info.class", classBytes(libraryModule, "module-info"),
                "library/api/Library.class", classBytes(libraryModule, "library.api.Library"))));

        Set<String> references = LibraryPackageRequirements.referencedPackages(
                library, Runtime.version().feature());

        assertTrue(references.contains("external.api"));
        Set<String> containedPackages = LibraryPackageRequirements.containedPackages(
                library, Runtime.version().feature());
        assertEquals(Set.of("library.api"), containedPackages);
    }

    @Test
    void inventoriesTheEffectiveMultiReleasePackagesAndOmitsModuleDescriptors() throws IOException {
        int javaMajor = Runtime.version().feature();
        Path baseOutput = compile(Map.of(
                "library.base.Base", "package library.base; public class Base {}"));
        Path variantOutput = compile(Map.of(
                "library.variant.Variant", "package library.variant; public class Variant {}"));
        Path moduleOutput = compileModule("empty.module", "module empty.module {}", Map.of(), null);

        Path library = directory.resolve("multi-release-library.jar");
        Files.write(library, jar(manifest(Map.of("Multi-Release", "true")), Map.of(
                "module-info.class", classBytes(moduleOutput, "module-info"),
                "library/base/Base.class", classBytes(baseOutput, "library.base.Base"),
                "META-INF/versions/" + javaMajor + "/library/variant/Variant.class",
                classBytes(variantOutput, "library.variant.Variant"))));

        assertEquals(Set.of("library.base", "library.variant"),
                LibraryPackageRequirements.containedPackages(library, javaMajor));
    }

    @Test
    void inspectsFlattenedClassesNestedJarsAndDeclaredDirectoriesWithoutScanningThePlugin() throws IOException {
        Path output = compile(Map.of(
                "external.api.ExternalType", "package external.api; public class ExternalType {}",
                "unrelated.api.UnrelatedType", "package unrelated.api; public class UnrelatedType {}",
                "library.api.Flattened", "package library.api; public class Flattened { external.api.ExternalType value; }",
                "library.api.Nested", "package library.api; public class Nested { external.api.ExternalType value; }",
                "library.api.InClassesDirectory", "package library.api; public class InClassesDirectory { external.api.ExternalType value; }",
                "application.api.Application", "package application.api; public class Application { unrelated.api.UnrelatedType value; }"));
        byte[] nested = jar(Map.of(), Map.of("library/api/Nested.class", classBytes(output, "library.api.Nested")));
        Path plugin = directory.resolve("plugin.jar");
        Files.write(plugin, jar(Map.of("Bundle-ClassPath", ".,lib/library.jar,classes"), Map.of(
                "library/api/Flattened.class", classBytes(output, "library.api.Flattened"),
                "application/api/Application.class", classBytes(output, "application.api.Application"),
                "classes/library/api/InClassesDirectory.class",
                classBytes(output, "library.api.InClassesDirectory"),
                "lib/library.jar", nested)));

        Set<String> references = LibraryPackageRequirements.bundledReferences(
                plugin, List.of("library/api/"), Runtime.version().feature());

        assertTrue(references.contains("external.api"));
        assertFalse(references.contains("unrelated.api"));
    }

    @Test
    void selectsTheTargetMultiReleaseVariantFromANestedLibrary() throws IOException {
        int javaMajor = Runtime.version().feature();
        Path baseOutput = compile(Map.of(
                "external.base.BaseType", "package external.base; public class BaseType {}",
                "library.api.Variant", "package library.api; public class Variant { external.base.BaseType value; }"));
        Path variantOutput = compile(Map.of(
                "external.variant.VariantType", "package external.variant; public class VariantType {}",
                "library.api.Variant", "package library.api; public class Variant { external.variant.VariantType value; }"));

        Manifest nestedManifest = manifest(Map.of("Multi-Release", "true"));
        byte[] nested = jar(nestedManifest, Map.of(
                "library/api/Variant.class", classBytes(baseOutput, "library.api.Variant"),
                "META-INF/versions/" + javaMajor + "/library/api/Variant.class",
                classBytes(variantOutput, "library.api.Variant")));
        Path plugin = directory.resolve("multi-release-plugin.jar");
        Files.write(plugin, jar(Map.of("Bundle-ClassPath", "lib/variant.jar"),
                Map.of("lib/variant.jar", nested)));

        Set<String> references = LibraryPackageRequirements.bundledReferences(
                plugin, List.of("library/api"), javaMajor);

        assertTrue(references.contains("external.variant"));
        assertFalse(references.contains("external.base"));
    }

    @Test
    void returnsNoReferencesWhenASelectedRootContainsNoClasses() throws IOException {
        Path plugin = directory.resolve("plugin.jar");
        Files.write(plugin, jar(Map.of("Bundle-ClassPath", "."), Map.of(
                "application/api/Application.class", new byte[] {0, 1, 2})));

        assertEquals(Set.of(), LibraryPackageRequirements.bundledReferences(
                plugin, List.of("library/api"), Runtime.version().feature()));
    }

    @Test
    void reportsMalformedSelectedBytecodeInsteadOfSkippingIt() throws IOException {
        Path plugin = directory.resolve("malformed-plugin.jar");
        Files.write(plugin, jar(Map.of("Bundle-ClassPath", "."), Map.of(
                "library/api/Broken.class", new byte[] {0, 1, 2, 3})));

        assertThrows(IOException.class, () -> LibraryPackageRequirements.bundledReferences(
                plugin, List.of("library/api"), Runtime.version().feature()));
    }

    private Path compile(Map<String, String> sources) throws IOException {
        Path sourceRoot = Files.createDirectories(directory.resolve("sources-" + sources.hashCode()));
        Path outputRoot = Files.createDirectories(directory.resolve("classes-" + sources.hashCode()));
        List<Path> sourceFiles = sources.entrySet().stream().map(entry -> {
            try {
                Path source = sourceRoot.resolve(entry.getKey().replace('.', '/') + ".java");
                Files.createDirectories(source.getParent());
                Files.writeString(source, entry.getValue());
                return source;
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }).toList();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("A JDK compiler is required by this test");
        }
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(
                    sourceFiles.stream().map(Path::toFile).toList());
            Boolean compiled = compiler.getTask(null, fileManager, null,
                    List.of("-d", outputRoot.toString()), null, units).call();
            assertEquals(Boolean.TRUE, compiled);
        }
        return outputRoot;
    }

    private Path compileModule(String moduleName, String moduleDeclaration, Map<String, String> sources,
                               Path modulePath) throws IOException {
        Path sourceRoot = Files.createDirectories(directory.resolve("module-sources-" + moduleName));
        Path outputRoot = Files.createDirectories(directory.resolve("module-classes-" + moduleName));
        Path moduleInfo = sourceRoot.resolve("module-info.java");
        Files.writeString(moduleInfo, moduleDeclaration);
        List<Path> sourceFiles = new java.util.ArrayList<>();
        sourceFiles.add(moduleInfo);
        for (Map.Entry<String, String> sourceEntry : sources.entrySet()) {
            Path source = sourceRoot.resolve(sourceEntry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, sourceEntry.getValue());
            sourceFiles.add(source);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("A JDK compiler is required by this test");
        }
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(
                    sourceFiles.stream().map(Path::toFile).toList());
            List<String> options = new java.util.ArrayList<>(List.of("-d", outputRoot.toString()));
            if (modulePath != null) {
                options.addAll(List.of("--module-path", modulePath.toString()));
            }
            Boolean compiled = compiler.getTask(null, fileManager, null, options, null, units).call();
            assertEquals(Boolean.TRUE, compiled);
        }
        return outputRoot;
    }

    private static byte[] classBytes(Path output, String className) throws IOException {
        return Files.readAllBytes(output.resolve(className.replace('.', '/') + ".class"));
    }

    private static byte[] jar(Map<String, String> headers, Map<String, byte[]> entries) throws IOException {
        return jar(manifest(headers), entries);
    }

    private static byte[] jar(Manifest manifest, Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream archive = new JarOutputStream(bytes, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                archive.putNextEntry(new JarEntry(entry.getKey()));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Manifest manifest(Map<String, String> headers) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        headers.forEach((name, value) -> manifest.getMainAttributes().putValue(name, value));
        return manifest;
    }
}
