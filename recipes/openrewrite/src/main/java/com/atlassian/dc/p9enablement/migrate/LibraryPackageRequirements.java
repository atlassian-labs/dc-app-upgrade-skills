package com.atlassian.dc.p9enablement.migrate;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;

/**
 * Finds packages referenced by selected library classes with the JDK's jdeps tool.
 *
 * <p>References represented in class-file metadata, including generic signatures,
 * annotations, and class literals, are handled by jdeps. References assembled from
 * dynamic strings are not statically discoverable and are therefore not included.</p>
 */
public final class LibraryPackageRequirements {
    private static final Pattern DEPENDENCY_LINE = Pattern.compile(
            "^\\s+([^\\s]+)\\s+->\\s+([^\\s]+)(?:\\s+.*)?$");
    private static final String MULTI_RELEASE_PREFIX = "META-INF/versions/";
    private static final String DEFAULT_CLASS_PATH = ".";

    private LibraryPackageRequirements() {
    }

    /**
     * Returns package targets from jdeps dependency lines for every class in a jar.
     *
     * @param jar jar to inspect
     * @param javaMajor target Java feature version passed to jdeps
     * @return referenced package names
     * @throws IOException if jdeps is unavailable, fails, or the jar cannot be read
     */
    public static Set<String> referencedPackages(Path jar, int javaMajor) throws IOException {
        requireJavaMajor(javaMajor);
        validateJarClassFiles(jar);

        Path temporaryDirectory = Files.createTempDirectory("library-package-requirements-");
        try {
            Path flattenedJar = temporaryDirectory.resolve("flattened.jar");
            if (!copyAllClasses(jar, flattenedJar, javaMajor)) {
                return Set.of();
            }
            return runJdeps(flattenedJar, javaMajor);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    /**
     * Returns the packages implemented by the effective classes in a library jar.
     *
     * <p>For a multi-release jar, the highest variant no newer than {@code javaMajor}
     * is selected for each class. Module descriptors are excluded because they are
     * metadata rather than package contents.</p>
     *
     * @param jar jar to inspect
     * @param javaMajor target Java feature version used to select multi-release classes
     * @return package names contained by the effective jar classes
     * @throws IOException if the jar is malformed or a class file cannot be read
     */
    public static Set<String> containedPackages(Path jar, int javaMajor) throws IOException {
        requireJavaMajor(javaMajor);
        validateJarClassFiles(jar);

        Map<String, byte[]> classes = effectiveClasses(jar, javaMajor);
        return Collections.unmodifiableSet(new java.util.TreeSet<>(classes.keySet().stream()
                .map(LibraryPackageRequirements::packageName)
                .filter(packageName -> !packageName.isEmpty())
                .toList()));
    }

    private static Set<String> runJdeps(Path jar, int javaMajor) throws IOException {
        ToolProvider jdeps = ToolProvider.findFirst("jdeps")
                .orElseThrow(() -> new IOException("JDK tool jdeps is unavailable"));
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        int exitCode;
        try {
            exitCode = jdeps.run(new PrintWriter(output), new PrintWriter(errors),
                    "--ignore-missing-deps", "--multi-release", Integer.toString(javaMajor),
                    "-filter:archive", "-verbose:package", jar.toString());
        } catch (RuntimeException exception) {
            throw new IOException("jdeps failed for " + jar, exception);
        }

        if (exitCode != 0) {
            String detail = errors.toString().trim();
            if (detail.isEmpty()) {
                detail = output.toString().trim();
            }
            throw new IOException("jdeps failed for " + jar + " (exit code " + exitCode + "): " + detail);
        }

        return parseDependencyLines(output.toString());
    }

    /**
     * Finds references made by classes belonging to the selected package roots in
     * the plugin's effective Bundle-ClassPath. The selected classes are copied to
     * a temporary multi-release jar before jdeps is run, so unrelated application
     * classes in the plugin cannot affect the result.
     *
     * @param plugin plugin jar containing the Bundle-ClassPath
     * @param classPrefixes package or class path roots, in slash or dotted form
     * @param javaMajor target Java feature version passed to jdeps
     * @return referenced package names, or an empty set when no selected classes exist
     * @throws IOException if the plugin is malformed, a selected class cannot be
     *                     copied, or jdeps fails
     */
    public static Set<String> bundledReferences(Path plugin, List<String> classPrefixes, int javaMajor)
            throws IOException {
        requireJavaMajor(javaMajor);
        List<String> prefixes = classPrefixes.stream().map(LibraryPackageRequirements::normalizePrefix)
                .toList();

        Path temporaryDirectory = Files.createTempDirectory("library-package-requirements-");
        try {
            Path selectedJar = temporaryDirectory.resolve("selected.jar");
            boolean selectedClasses = copySelectedClasses(plugin, prefixes, selectedJar, javaMajor);
            if (!selectedClasses) {
                return Set.of();
            }
            return runJdeps(selectedJar, javaMajor);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Could not inspect selected classes in " + plugin, exception);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private static Set<String> parseDependencyLines(String output) {
        Set<String> packages = new LinkedHashSet<>();
        for (String line : output.lines().toList()) {
            Matcher matcher = DEPENDENCY_LINE.matcher(line);
            if (matcher.matches()) {
                String source = matcher.group(1);
                String target = matcher.group(2);
                if (!source.endsWith(".jar") && !target.equals("not") && !target.equals("not-found")) {
                    packages.add(target);
                }
            }
        }
        return Collections.unmodifiableSet(new java.util.TreeSet<>(packages));
    }

    private static void validateJarClassFiles(Path jar) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            java.util.Enumeration<JarEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream input = archive.getInputStream(entry)) {
                    validateClassFile(entry.getName(), input.readAllBytes());
                }
            }
        } catch (java.util.zip.ZipException exception) {
            throw new IOException("Malformed jar: " + jar, exception);
        }
    }

    private static boolean copySelectedClasses(Path plugin, List<String> prefixes, Path selectedJar, int javaMajor)
            throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();

        try (JarFile pluginJar = new JarFile(plugin.toFile())) {
            Manifest pluginManifest = pluginJar.getManifest();
            boolean multiRelease = isMultiRelease(pluginManifest);
            List<String> classPath = bundleClassPath(pluginManifest);
            for (String location : classPath) {
                if (location.equals(DEFAULT_CLASS_PATH)) {
                    copyClasses(pluginJar, "", prefixes, javaMajor, multiRelease, classes);
                    continue;
                }

                JarEntry classPathEntry = pluginJar.getJarEntry(location);
                if (classPathEntry != null && !classPathEntry.isDirectory()) {
                    byte[] nestedBytes;
                    try (InputStream input = pluginJar.getInputStream(classPathEntry)) {
                        nestedBytes = input.readAllBytes();
                    }
                    copyNestedClasses(nestedBytes, prefixes, javaMajor, classes);
                } else {
                    String directory = location.endsWith("/") ? location : location + "/";
                    copyClasses(pluginJar, directory, prefixes, javaMajor, multiRelease, classes);
                }
            }
        } catch (java.util.zip.ZipException exception) {
            throw new IOException("Malformed plugin jar: " + plugin, exception);
        }

        if (classes.isEmpty()) {
            return false;
        }

        writeClasses(selectedJar, classes);
        return true;
    }

    private static boolean copyAllClasses(Path source, Path output, int javaMajor) throws IOException {
        Map<String, byte[]> classes = effectiveClasses(source, javaMajor);
        if (classes.isEmpty()) {
            return false;
        }
        writeClasses(output, classes);
        return true;
    }

    private static Map<String, byte[]> effectiveClasses(Path source, int javaMajor) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (JarFile archive = new JarFile(source.toFile())) {
            Manifest manifest = archive.getManifest();
            copyClasses(archive, "", List.of(""), javaMajor, isMultiRelease(manifest), classes);
        } catch (java.util.zip.ZipException exception) {
            throw new IOException("Malformed jar: " + source, exception);
        }
        return classes;
    }

    private static void writeClasses(Path output, Map<String, byte[]> classes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(output), manifest)) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                archive.putNextEntry(new JarEntry(entry.getKey()));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }
    }

    private static List<String> bundleClassPath(Manifest manifest) {
        if (manifest == null) {
            return List.of(DEFAULT_CLASS_PATH);
        }
        String value = manifest.getMainAttributes().getValue("Bundle-ClassPath");
        if (value == null || value.isBlank()) {
            return List.of(DEFAULT_CLASS_PATH);
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).map(entry -> entry.replace("\"", ""))
                .filter(entry -> !entry.isEmpty()).toList();
    }

    private static void copyClasses(JarFile jar, String locationPrefix, List<String> prefixes,
                                       int javaMajor, boolean multiRelease,
                                       Map<String, byte[]> classes) throws IOException {
        Map<String, byte[]> sourceClasses = new LinkedHashMap<>();
        Map<String, Integer> sourceVersions = new LinkedHashMap<>();
        java.util.Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(locationPrefix)) {
                String className = name.substring(locationPrefix.length());
                JarEntry selectedEntry = entry;
                copyClass(className, prefixes, javaMajor, multiRelease, sourceClasses, sourceVersions,
                        () -> {
                            try (InputStream input = jar.getInputStream(selectedEntry)) {
                                return input.readAllBytes();
                            }
                        });
            }
        }
        mergeClasses(classes, sourceClasses);
    }

    private static void copyNestedClasses(byte[] nestedBytes, List<String> prefixes, int javaMajor,
                                          Map<String, byte[]> classes) throws IOException {
        Map<String, byte[]> sourceClasses = new LinkedHashMap<>();
        Map<String, Integer> sourceVersions = new LinkedHashMap<>();
        try (JarInputStream nested = new JarInputStream(new ByteArrayInputStream(nestedBytes))) {
            boolean multiRelease = isMultiRelease(nested.getManifest());
            JarEntry entry;
            while ((entry = nested.getNextJarEntry()) != null) {
                if (!entry.isDirectory()) {
                    copyClass(entry.getName(), prefixes, javaMajor, multiRelease, sourceClasses, sourceVersions,
                            nested::readAllBytes);
                }
            }
        } catch (java.util.zip.ZipException exception) {
            throw new IOException("Malformed nested jar in Bundle-ClassPath", exception);
        }
        mergeClasses(classes, sourceClasses);
    }

    private static void mergeClasses(Map<String, byte[]> classes, Map<String, byte[]> sourceClasses) {
        sourceClasses.forEach(classes::putIfAbsent);
    }

    private static void copyClass(String name, List<String> prefixes, int javaMajor, boolean multiRelease,
                                  Map<String, byte[]> sourceClasses, Map<String, Integer> sourceVersions,
                                  ByteSupplier bytesSupplier) throws IOException {
        if (!name.endsWith(".class")) {
            return;
        }
        int version = 0;
        String className = name;
        if (name.startsWith(MULTI_RELEASE_PREFIX)) {
            if (!multiRelease) {
                return;
            }
            int versionEnd = name.indexOf('/', MULTI_RELEASE_PREFIX.length());
            if (versionEnd < 0) {
                throw new IOException("Malformed multi-release class path: " + name);
            }
            try {
                version = Integer.parseInt(name.substring(MULTI_RELEASE_PREFIX.length(), versionEnd));
            } catch (NumberFormatException exception) {
                throw new IOException("Malformed multi-release class path: " + name, exception);
            }
            if (version > javaMajor) {
                return;
            }
            className = name.substring(versionEnd + 1);
        }
        if (!isSelectedClass(className, prefixes)
                || sourceVersions.getOrDefault(className, -1) >= version) {
            return;
        }
        byte[] bytes = bytesSupplier.read();
        validateClassFile(name, bytes);
        sourceClasses.put(className, bytes);
        sourceVersions.put(className, version);
    }

    private static boolean isMultiRelease(Manifest manifest) {
        return manifest != null
                && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"));
    }

    @FunctionalInterface
    private interface ByteSupplier {
        byte[] read() throws IOException;
    }

    private static boolean isSelectedClass(String name, List<String> prefixes) {
        if (!name.endsWith(".class")) {
            return false;
        }
        String className = name;
        if (className.startsWith(MULTI_RELEASE_PREFIX)) {
            int versionEnd = className.indexOf('/', MULTI_RELEASE_PREFIX.length());
            if (versionEnd < 0) {
                return false;
            }
            className = className.substring(versionEnd + 1);
        }
        if (className.equals("module-info.class")) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix.isEmpty() || className.equals(prefix + ".class") || className.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String packageName(String className) {
        int slash = className.lastIndexOf('/');
        return slash < 0 ? "" : className.substring(0, slash).replace('/', '.');
    }

    private static void validateClassFile(String name, byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != 0xCAFEBABE) {
                throw new IOException("Malformed class file: " + name + " has an invalid magic number");
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            int constantPoolCount = input.readUnsignedShort();
            if (constantPoolCount < 1) {
                throw new IOException("Malformed class file: " + name + " has no constant pool");
            }
            for (int index = 1; index < constantPoolCount; index++) {
                switch (input.readUnsignedByte()) {
                    case 1 -> input.skipBytes(input.readUnsignedShort());
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4);
                    case 5, 6 -> {
                        input.skipBytes(8);
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> input.skipBytes(2);
                    case 15 -> input.skipBytes(3);
                    default -> throw new IOException("Malformed class file: " + name + " has an invalid constant-pool tag");
                }
            }
            input.skipBytes(6);
            skipTable(input);
            skipMembers(input);
            skipMembers(input);
            skipAttributes(input);
            if (input.available() != 0) {
                throw new IOException("Malformed class file: " + name + " has trailing bytes");
            }
        } catch (EOFException exception) {
            throw new IOException("Malformed class file: " + name + " is truncated", exception);
        }
    }

    private static void skipTable(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            input.readUnsignedShort();
        }
    }

    private static void skipMembers(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            input.skipBytes(6);
            skipAttributes(input);
        }
    }

    private static void skipAttributes(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            input.readUnsignedShort();
            int length = input.readInt();
            if (length < 0 || input.skipBytes(length) != length) {
                throw new IOException("Malformed class file attribute");
            }
        }
    }

    private static String normalizePrefix(String prefix) {
        String normalized = prefix.trim().replace('.', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void requireJavaMajor(int javaMajor) throws IOException {
        if (javaMajor < 9) {
            throw new IOException("jdeps multi-release target must be at least Java 9: " + javaMajor);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) {
        try {
            if (Files.exists(directory)) {
                try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best effort cleanup; the inspected inputs are never modified.
                        }
                    });
                }
            }
        } catch (IOException ignored) {
            // Best effort cleanup; the inspected inputs are never modified.
        }
    }

}
