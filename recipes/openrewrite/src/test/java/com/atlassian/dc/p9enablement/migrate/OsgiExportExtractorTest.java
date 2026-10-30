package com.atlassian.dc.p9enablement.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OsgiExportExtractorTest {
    @TempDir
    Path directory;

    @Test
    void readsRefappPolicyFromDistributionClassesForWarAndExplodedInputs() throws Exception {
        Path distribution = distribution();
        Files.delete(distribution.resolve("META-INF/maven/com.atlassian.jira/atlassian-jira-webapp/pom.properties"));
        Path metadata = distribution.resolve("META-INF/maven/com.atlassian.refapp/atlassian-refapp/pom.properties");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "version=9.0.6");
        try (Stream<Path> files = Files.list(distribution.resolve("WEB-INF/classes/osgi"))) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }

        Path source = directory.resolve("sources");
        Path compiled = directory.resolve("compiled");
        Map<String, String> sources = Map.of(
                "jakarta/servlet/ServletContext.java", """
                        package jakarta.servlet;
                        public interface ServletContext { java.util.Set<String> getResourcePaths(String path); }
                        """,
                "com/atlassian/plugin/refimpl/version/HostVersion.java", """
                        package com.atlassian.plugin.refimpl.version;
                        public class HostVersion {
                            public HostVersion(jakarta.servlet.ServletContext context) {
                                if (!context.getResourcePaths("/WEB-INF/lib").contains("/WEB-INF/lib/refapp-core-9.0.6.jar"))
                                    throw new IllegalArgumentException("Missing RefApp library");
                            }
                            public String getValue() { return "9.0.6"; }
                        }
                        """,
                RefappExportConfiguration.FACTORY_RESOURCE.replace(".class", ".java"), """
                        package com.atlassian.plugin.refimpl.plugins;
                        import java.util.*;
                        import com.atlassian.plugin.refimpl.version.HostVersion;
                        public class PackageScannerConfigurationFactory {
                            public static PackageScannerConfigurationFactory getPackageScannerConfiguration(
                                    jakarta.servlet.ServletContext context, HostVersion host) {
                                return new PackageScannerConfigurationFactory();
                            }
                            public List<String> getPackageIncludes() { return List.of("example.*"); }
                            public List<String> getPackageExcludes() { return List.of("example.unrelated"); }
                            public Set<String> getOsgiPublicPackages() { return Set.of("example.*", "java.*", "sun.*"); }
                            public Set<String> getOsgiPublicPackagesExcludes() { return Set.of("example.internal"); }
                            public Set<String> getOsgiDeprecatedPackages() { return Set.of(); }
                            public boolean treatDeprecatedPackagesAsPublic() { return true; }
                            public Map<String, String> getPackageVersions() { return Map.of("example.api", "4.2.0"); }
                        }
                        """);
        List<String> arguments = new ArrayList<>(List.of("-d", compiled.toString()));
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = source.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            arguments.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        archive(compiled, distribution.resolve("WEB-INF/lib/refapp-core-9.0.6.jar"));
        Path war = directory.resolve("refapp.war");
        archive(distribution, war);
        Path scope = scopeReport();
        Files.writeString(scope, Files.readString(scope).replace("\"example.absent\"",
                "\"example.absent\",\"example.unrelated\""));

        for (Path input : List.of(distribution, war)) {
            Path output = directory.resolve(input.getFileName() + ".json");
            OsgiExportExtractor.main(new String[]{
                    "--workspace", directory.toString(), "--product", "refapp", "--product-version", "9.0.6",
                    "--target-java-version", Integer.toString(Runtime.version().feature()),
                    "--distribution", input.toString(), "--verification-report", scope.toString(),
                    "--output", output.toString()});
            OsgiExportCatalog.Snapshot snapshot = OsgiExportCatalog.read(output);
            assertEquals("refapp", snapshot.product());
            assertEquals("9.0.6", snapshot.sourceVersion());
            assertTrue(snapshot.exports().stream().anyMatch(export ->
                    export.packageName().equals("example.api") && export.version().equals("4.2.0")));
            assertTrue(snapshot.exports().stream().noneMatch(export ->
                    List.of("example.internal", "example.unrelated").contains(export.packageName())));
        }
    }

    private static void archive(Path directory, Path archive) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive), manifest);
             Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(directory.relativize(file).toString()));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    @Test
    void extractsProductPolicyAndSystemExportsWithoutInventingHostVersions() throws Exception {
        Path distribution = distribution();
        Path snapshot = directory.resolve("snapshot.json");
        extract(distribution, "12.0.0-beta1", snapshot);
        OsgiExportCatalog.Snapshot result = OsgiExportCatalog.read(snapshot);

        assertEquals("12.0.0", result.normalizedVersion());
        assertEquals("12.0.0-beta1", result.sourceVersion());
        assertTrue(result.exports().stream().anyMatch(export ->
                export.packageName().equals("java.lang") && !export.restricted()));
        OsgiExportCatalog.Export host = result.exports().stream()
                .filter(export -> export.packageName().equals("example.api")).findFirst().orElseThrow();
        assertEquals("", host.version(), "A library Bundle-Version is not a host package export version");
        assertEquals("", host.bundleSymbolicName());
        assertFalse(Files.readString(snapshot).contains("\"bundleSymbolicName\""));
        assertFalse(Files.readString(snapshot).contains("\"bundleVersion\""));
        assertTrue(result.exports().stream().noneMatch(export -> export.packageName().equals("example.internal")));
        assertTrue(result.covers("example.internal"));
        assertFalse(result.covers("example.unrelated"));
        assertTrue(result.exports().stream().noneMatch(export -> export.packageName().equals("example.unrelated")));
        assertTrue(result.exports().stream().allMatch(export -> export.attributes().isEmpty()));
        assertTrue(result.exports().stream().noneMatch(export -> export.packageName().equals("example.absent")));
        assertTrue(result.exports().stream().anyMatch(export ->
                export.packageName().equals("example.plugin") && export.version().equals("2.0.0")
                        && !export.restricted()));
        assertTrue(result.exports().stream().noneMatch(export ->
                export.bundleSymbolicName().contains("/") || export.bundleSymbolicName().endsWith(".jar")));
        assertTrue(result.exports().stream().anyMatch(export -> export.packageName().equals("sun.misc")));
        assertFalse(result.provenance().get("distributionSha256").equals("directory"));
    }

    @Test
    void refusesIncorrectProductAndJavaLabels() throws Exception {
        Path distribution = distribution();
        assertThrows(IOException.class, () -> extract(distribution, "12.0.0", directory.resolve("wrong.json")));
        IllegalArgumentException unsupportedProduct = assertThrows(IllegalArgumentException.class,
                () -> OsgiExportExtractor.main(new String[]{
                        "--workspace", directory.toString(), "--product", "bitbucket", "--product-version", "12.0.0",
                        "--target-java-version", Integer.toString(Runtime.version().feature()),
                        "--distribution", distribution.toString(), "--verification-report", scopeReport().toString(),
                        "--output", directory.resolve("unsupported-product.json").toString()}));
        assertTrue(unsupportedProduct.getMessage().contains("supports only jira, confluence and refapp"));
        assertThrows(IOException.class, () -> OsgiExportExtractor.main(new String[]{
                "--workspace", directory.toString(), "--product", "jira", "--product-version", "12.0.0-beta1",
                "--target-java-version", "999", "--distribution", distribution.toString(),
                "--verification-report", scopeReport().toString(),
                "--output", directory.resolve("wrong-java.json").toString()}));
    }

    @Test
    void includesContainerClassesOnlyWhenProductExportsThem() throws Exception {
        Path distribution = distribution();
        Path container = directory.resolve("tomcat/lib");
        writeJar(container.resolve("servlet-api.jar"), Map.of(),
                Map.of("example/container/Servlet.class", "", "unexported/container/Internal.class", ""));
        writeJar(container.resolve("unrelated-felix.jar"),
                Map.of("Bundle-SymbolicName", "org.apache.felix.framework", "Bundle-Version", "9.0.0"), Map.of());
        Path snapshot = directory.resolve("container.json");
        OsgiExportExtractor.main(new String[]{
                "--workspace", directory.toString(), "--product", "jira", "--product-version", "12.0.0-beta1",
                "--target-java-version", Integer.toString(Runtime.version().feature()),
                "--distribution", distribution.toString(), "--container-libs", container.toString(),
                "--verification-report", scopeReport().toString(),
                "--output", snapshot.toString()});
        OsgiExportCatalog.Snapshot result = OsgiExportCatalog.read(snapshot);
        assertTrue(result.exports().stream().anyMatch(export -> export.packageName().equals("example.container")));
        assertTrue(result.exports().stream().noneMatch(export -> export.packageName().equals("unexported.container")));
        assertNotNull(result.provenance().get("containerLibrariesSha256"));
        assertTrue(result.exports().stream().filter(export -> export.packageName().equals("example.container"))
                .allMatch(export -> export.bundleVersion().isBlank()));
    }

    private void extract(Path distribution, String version, Path output) throws Exception {
        OsgiExportExtractor.main(new String[]{
                "--workspace", directory.toString(), "--product", "jira", "--product-version", version,
                "--target-java-version", Integer.toString(Runtime.version().feature()),
                "--distribution", distribution.toString(), "--verification-report", scopeReport().toString(),
                "--output", output.toString()});
    }

    private Path scopeReport() throws IOException {
        Path report = directory.resolve("verification.json");
        Files.writeString(report, """
                {"verificationScope":"selected-bundled-library-requirements","artifactSha256":"test",
                 "verification":{"mandatoryImports":["example.api","example.internal","example.container",
                 "example.plugin","example.absent","java.lang","sun.misc"]}}
                """);
        return report;
    }

    private Path distribution() throws IOException {
        Path root = directory.resolve("product");
        Path classes = root.resolve("WEB-INF/classes");
        Files.createDirectories(classes.resolve("osgi"));
        Path metadata = root.resolve("META-INF/maven/com.atlassian.jira/atlassian-jira-webapp/pom.properties");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "version=12.0.0-beta1");
        Files.writeString(classes.resolve("osgi/package-includes.txt"), "example.*\n");
        Files.writeString(classes.resolve("osgi/package-excludes.txt"), "");
        Files.writeString(classes.resolve("osgi/public-packages.txt"), "example.api\nexample.container\nexample.unrelated\nsun.misc\njava.*\njavax.xml.*\n");
        Files.writeString(classes.resolve("osgi/public-packages-excludes.txt"), "");
        writeJar(root.resolve("WEB-INF/lib/framework.jar"),
                Map.of("Bundle-SymbolicName", "org.apache.felix.framework", "Bundle-Version", "7.0.5"),
                Map.of("org/osgi/framework/Bundle.class", ""));
        writeJar(root.resolve("WEB-INF/lib/plugin-framework.jar"), Map.of(),
                Map.of("jdk11-packages.txt", "javax.xml.parsers\n", "osgi-packages.txt", "org.osgi.framework=1.8.0\n"));
        writeJar(root.resolve("WEB-INF/lib/library.jar"),
                Map.of("Bundle-SymbolicName", "library", "Bundle-Version", "9.0.0",
                        "Export-Package", "example.absent;version=9"),
                Map.of("example/api/Api.class", "", "example/internal/Impl.class", "", "example/unrelated/Thing.class", ""));
        writeJar(root.resolve("WEB-INF/atlassian-bundled-plugins/plugin.jar"),
                Map.of("Bundle-SymbolicName", "plugin", "Bundle-Version", "3.0.0",
                        "Export-Package", "example.plugin;version=2.0.0;atlassian-access=public"), Map.of());
        return root;
    }

    private static void writeJar(Path path, Map<String, String> headers, Map<String, String> files) throws IOException {
        Files.createDirectories(path.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        headers.forEach((key, value) -> manifest.getMainAttributes().putValue(key, value));

        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                output.putNextEntry(new JarEntry(file.getKey()));
                output.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
