package com.atlassian.dc.p9enablement.migrate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads the versioned, product-specific OSGi export evidence used by the verifier. */
public final class OsgiExportCatalog {
    public static final int SCHEMA_VERSION = 2;
    private static final String RESOURCE_ROOT = "osgi-exports/";

    private OsgiExportCatalog() {
    }

    public static Snapshot read(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return read(new ObjectMapper().readTree(input), path.toString());
        }
    }

    public static Snapshot read(InputStream input, String source) throws IOException {
        return read(new ObjectMapper().readTree(input), source);
    }

    private static Snapshot read(JsonNode root, String source) throws IOException {
        if (root == null || !root.isObject()) {
            throw new IOException("OSGi export snapshot is not an object: " + source);
        }

        ObjectMapper mapper = new ObjectMapper();
        Snapshot snapshot = mapper.treeToValue(root, Snapshot.class);
        if (snapshot.schemaVersion() != 1 && snapshot.schemaVersion() != SCHEMA_VERSION) {
            throw new IOException("Unsupported OSGi export snapshot schema " + snapshot.schemaVersion()
                    + ": " + source);
        }
        if (snapshot.product().isBlank() || snapshot.normalizedVersion().isBlank()
                || snapshot.sourceVersion().isBlank() || snapshot.javaMajor() <= 0) {
            throw new IOException("Incomplete OSGi export snapshot: " + source);
        }
        if (snapshot.schemaVersion() == 2 && !root.path("coveredPackages").isArray()) {
            throw new IOException("Scoped export snapshot lacks coveredPackages: " + source);
        }
        return snapshot;
    }

    /** Selects the strongest available evidence for a product/version request. */
    public static Selection selectBundled(ClassLoader loader, String product, String targetVersion,
                                          int targetJavaMajor) {
        if (product == null || product.isBlank() || targetVersion == null || targetVersion.isBlank()) {
            return Selection.unavailable("target product and version are required");
        }

        List<String> paths;
        try (InputStream input = loader.getResourceAsStream(RESOURCE_ROOT + "index.json")) {
            if (input == null) {
                return Selection.unavailable("bundled OSGi export index is unavailable");
            }
            JsonNode index = new ObjectMapper().readTree(input);
            if (!index.isArray()) {
                return Selection.unavailable("bundled OSGi export index is not an array");
            }
            paths = new ArrayList<>();
            for (JsonNode entry : index) {
                if (entry.isTextual()) {
                    paths.add(entry.textValue());
                }
            }
        } catch (IOException exception) {
            return Selection.unavailable("unable to read bundled OSGi export index: " + exception.getMessage());
        }

        String normalizedTarget = normalizeVersion(targetVersion);
        if (major(normalizedTarget) < 0) {
            return Selection.unavailable("target version is not a semantic version: " + targetVersion);
        }
        List<SnapshotLocation> candidates = new ArrayList<>();
        for (String relativePath : paths) {
            String resource = RESOURCE_ROOT + relativePath;
            if (!resource.startsWith(RESOURCE_ROOT + product + "/") || !resource.endsWith(".json")) {
                continue;
            }

            try (InputStream input = loader.getResourceAsStream(resource)) {
                if (input != null) {
                    candidates.add(new SnapshotLocation(read(input, resource), resource));
                }
            } catch (IOException ignored) {
                // A malformed candidate is an evidence gap, not permission to use a different product.
            }
        }

        List<SnapshotLocation> sameProduct = candidates.stream()
                .filter(candidate -> sameProduct(candidate.snapshot(), product))
                .toList();
        if (sameProduct.isEmpty()) {
            return Selection.unavailable("no bundled export snapshot for " + product);
        }

        Comparator<SnapshotLocation> exactness = Comparator
                .comparing((SnapshotLocation candidate) -> !candidate.snapshot().normalizedVersion()
                        .equals(normalizedTarget))
                .thenComparing(candidate -> !candidate.snapshot().sourceVersion().equals(targetVersion))
                .thenComparing(candidate -> distance(candidate.snapshot().normalizedVersion(), normalizedTarget))
                .thenComparing(candidate -> candidate.snapshot().javaMajor() == targetJavaMajor ? 0 : 1);
        SnapshotLocation selected = sameProduct.stream().sorted(exactness).findFirst().orElseThrow();
        Snapshot snapshot = selected.snapshot();
        MatchQuality quality = snapshot.normalizedVersion().equals(normalizedTarget)
                && snapshot.sourceVersion().equals(targetVersion)
                && snapshot.javaMajor() == targetJavaMajor
                ? MatchQuality.EXACT : MatchQuality.PROVISIONAL;
        List<String> gaps = selectionGaps(snapshot, product, targetVersion, targetJavaMajor, quality);
        return new Selection(snapshot, quality, selected.resource(), List.copyOf(gaps));
    }

    public static Selection selectOverride(Path path, String product, String targetVersion,
                                           int targetJavaMajor) throws IOException {
        Snapshot snapshot = read(path);
        MatchQuality quality = snapshot.product().equalsIgnoreCase(product)
                && snapshot.normalizedVersion().equals(normalizeVersion(targetVersion))
                && snapshot.sourceVersion().equals(targetVersion)
                && snapshot.javaMajor() == targetJavaMajor
                ? MatchQuality.EXACT : MatchQuality.PROVISIONAL;
        List<String> gaps = selectionGaps(snapshot, product, targetVersion, targetJavaMajor, quality);
        return new Selection(snapshot, quality, path.toString(), List.copyOf(gaps));
    }

    public static String normalizeVersion(String version) {
        if (version == null) {
            return "";
        }
        int suffix = version.indexOf('-');
        return (suffix < 0 ? version : version.substring(0, suffix)).trim();
    }

    private static List<String> selectionGaps(Snapshot snapshot, String product, String targetVersion,
                                              int targetJavaMajor, MatchQuality quality) {
        List<String> gaps = new ArrayList<>();
        if (!snapshot.product().equalsIgnoreCase(product)) {
            gaps.add("export snapshot product " + snapshot.product() + " does not match target " + product);
        }
        if (!snapshot.sourceVersion().equals(targetVersion)) {
            gaps.add("export snapshot sourceVersion " + snapshot.sourceVersion()
                    + " does not exactly match target " + targetVersion);
        }
        if (snapshot.javaMajor() != targetJavaMajor) {
            gaps.add("export snapshot Java " + snapshot.javaMajor()
                    + " does not match target Java " + targetJavaMajor);
        }
        if (quality == MatchQuality.PROVISIONAL && gaps.isEmpty()) {
            gaps.add("export snapshot is only an approximate match");
        }
        return gaps;
    }

    private static boolean sameProduct(Snapshot snapshot, String product) {
        return snapshot.product().equalsIgnoreCase(product);
    }

    private static int major(String version) {
        try {
            return Integer.parseInt(version.split("\\.", 2)[0]);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static long distance(String left, String right) {
        Version a = Version.parse(left);
        Version b = Version.parse(right);
        return Math.abs((long) a.major() - b.major()) * 1_000_000L
                + Math.abs((long) a.minor() - b.minor()) * 1_000L
                + Math.abs((long) a.micro() - b.micro());
    }

    public enum MatchQuality {
        EXACT,
        PROVISIONAL,
        UNAVAILABLE
    }

    public record Selection(Snapshot snapshot, MatchQuality matchQuality, String provenance,
                            List<String> evidenceGaps) {
        public Selection {
            evidenceGaps = List.copyOf(evidenceGaps == null ? List.of() : evidenceGaps);
        }

        public static Selection unavailable(String reason) {
            return new Selection(null, MatchQuality.UNAVAILABLE, "none", List.of(reason));
        }

        public boolean available() {
            return snapshot != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Snapshot(int schemaVersion, String product, String normalizedVersion, String sourceVersion,
                           int javaMajor, String generatedAt, String evidenceKind,
                           Map<String, String> provenance, List<Export> exports, List<String> coveredPackages) {
        public Snapshot {
            product = Objects.requireNonNullElse(product, "");
            normalizedVersion = Objects.requireNonNullElse(normalizedVersion, "");
            sourceVersion = Objects.requireNonNullElse(sourceVersion, "");
            generatedAt = Objects.requireNonNullElse(generatedAt, "");
            evidenceKind = Objects.requireNonNullElse(evidenceKind, "");
            provenance = Map.copyOf(provenance == null ? Map.of() : provenance);
            exports = List.copyOf(exports == null ? List.of() : exports);
            coveredPackages = List.copyOf(coveredPackages == null ? List.of() : coveredPackages);
        }
        public Snapshot(int schemaVersion, String product, String normalizedVersion, String sourceVersion,
                        int javaMajor, String generatedAt, String evidenceKind,
                        Map<String, String> provenance, List<Export> exports) {
            this(schemaVersion, product, normalizedVersion, sourceVersion, javaMajor, generatedAt,
                    evidenceKind, provenance, exports, List.of());
        }

        public boolean covers(String packageName) {
            return schemaVersion == 1 || coveredPackages.contains(packageName);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Export(String packageName, String version, String bundleSymbolicName, String bundleVersion,
                         Map<String, String> attributes, Map<String, String> directives) {
        public Export {
            packageName = Objects.requireNonNullElse(packageName, "");
            version = Objects.requireNonNullElse(version, "");
            bundleSymbolicName = Objects.requireNonNullElse(bundleSymbolicName, "");
            bundleVersion = Objects.requireNonNullElse(bundleVersion, "");
            attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
            directives = Map.copyOf(directives == null ? Map.of() : directives);
        }

        public boolean restricted() {
            return isTrue(attributes.get("x-internal")) || isTrue(directives.get("x-internal"))
                    || "internal".equalsIgnoreCase(attributes.get("visibility"))
                    || directives.containsKey("x-friends")
                    || internalAccess(attributes) || internalAccess(directives);
        }

        private static boolean internalAccess(Map<String, String> values) {
            return values.entrySet().stream().anyMatch(entry ->
                    entry.getKey().equalsIgnoreCase("atlassian-access")
                            && entry.getValue().equalsIgnoreCase("internal"));
        }

        private static boolean isTrue(String value) {
            return "true".equalsIgnoreCase(value);
        }
    }

    private record SnapshotLocation(Snapshot snapshot, String resource) {
    }

    private record Version(int major, int minor, int micro, String qualifier) implements Comparable<Version> {
        private static Version parse(String value) {
            String[] parts = (value == null ? "" : value).split("\\.", 4);
            return new Version(number(parts, 0), number(parts, 1), number(parts, 2), parts.length > 3 ? parts[3] : "");
        }

        private static int number(String[] parts, int index) {
            if (index >= parts.length) {
                return 0;
            }
            try {
                return Integer.parseInt(parts[index]);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        @Override
        public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            result = result == 0 ? Integer.compare(minor, other.minor) : result;
            result = result == 0 ? Integer.compare(micro, other.micro) : result;
            return result == 0 ? qualifier.compareTo(other.qualifier) : result;
        }
    }
}
