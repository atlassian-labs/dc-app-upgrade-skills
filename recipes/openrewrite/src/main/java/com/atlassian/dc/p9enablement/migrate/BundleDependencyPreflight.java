package com.atlassian.dc.p9enablement.migrate;

import com.atlassian.dc.p9enablement.migrate.BundleDependencyModel.DependencySpec;
import com.atlassian.dc.p9enablement.migrate.OsgiExportCatalog.Export;
import com.atlassian.dc.p9enablement.migrate.OsgiExportCatalog.Selection;
import com.atlassian.dc.p9enablement.migrate.OsgiExportCatalog.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Product-aware preflight used by grouped bundling discovery. */
public final class BundleDependencyPreflight {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private BundleDependencyPreflight() {
    }

    public static BundlePlan.ExportEvidence evidence(Selection selection, String product, String targetVersion,
                                                      int targetJavaVersion, String source) {
        Snapshot snapshot = selection.snapshot();
        return new BundlePlan.ExportEvidence(source, product, targetVersion, targetJavaVersion,
                selection.matchQuality().name().toLowerCase(Locale.ROOT),
                snapshot == null ? "" : snapshot.product(),
                snapshot == null ? "" : snapshot.normalizedVersion(),
                snapshot == null ? "" : snapshot.sourceVersion(),
                snapshot == null ? 0 : snapshot.javaMajor(),
                digest(snapshot), snapshot == null ? "" : snapshot.generatedAt(), selection.evidenceGaps());
    }

    public static Classification classify(String family, DependencySpec specification, List<String> references,
                                          Selection selection, String importPackage) {
        List<String> packages = referencedPackages(specification, references, selection.snapshot());
        if (!selection.available() || selection.matchQuality() != OsgiExportCatalog.MatchQuality.EXACT) {
            return new Classification(BundlePlan.DependencyClassificationStatus.UNCERTAIN,
                    packages, "exact product, source version, and Java export evidence is required");
        }

        Snapshot snapshot = selection.snapshot();
        List<String> constrained = constrainedImports(importPackage);
        List<String> missing = new ArrayList<>();
        for (String packageName : packages) {
            if (!snapshot.covers(packageName)) {
                return new Classification(BundlePlan.DependencyClassificationStatus.UNCERTAIN, packages,
                        packageName + " is outside the scoped snapshot coverage");
            }

            if (constrained.stream().anyMatch(name -> packageMatches(name, packageName))) {
                return new Classification(BundlePlan.DependencyClassificationStatus.UNCERTAIN, packages,
                        packageName + " has a constrained Import-Package clause requiring compatibility verification");
            }

            Export export = snapshot.exports().stream()
                    .filter(candidate -> candidate.packageName().equals(packageName)).findFirst().orElse(null);
            if (export == null) {
                missing.add(packageName + " is not exported by the target");
            } else if (export.restricted()) {
                missing.add(packageName + " is restricted by the target export");
            }
        }

        if (!missing.isEmpty()) {
            return new Classification(BundlePlan.DependencyClassificationStatus.NEEDS_BUNDLING, packages,
                    String.join(", ", missing));
        }

        return new Classification(BundlePlan.DependencyClassificationStatus.PROVIDED_BY_TARGET, packages,
                "all relevant packages are exported by the exact target snapshot");
    }

    public static List<String> referencedPackages(DependencySpec specification, List<String> references,
                                                  Snapshot snapshot) {
        Set<String> packages = new LinkedHashSet<>();
        List<String> prefixes = BundleDependencyModel.classPrefixes(specification).stream()
                .map(prefix -> prefix.replace('/', '.'))
                .map(prefix -> prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix)
                .toList();
        for (String reference : references == null ? List.<String>of() : references) {
            String packageName = packageForReference(reference, prefixes, snapshot);
            if (packageName != null) {
                packages.add(packageName);
            }
        }

        // Companion artifacts are selected by the family closure even when the source
        // only mentions the root. Their package roots must still be checked separately.
        if (packages.isEmpty()) {
            packages.addAll(prefixes);
        }
        return packages.stream().sorted().toList();
    }

    public static boolean directlyReferenced(DependencySpec specification, List<String> references) {
        List<String> prefixes = BundleDependencyModel.classPrefixes(specification).stream()
                .map(prefix -> prefix.replace('/', '.'))
                .map(prefix -> prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix)
                .toList();
        return (references == null ? List.<String>of() : references).stream()
                .map(reference -> reference.endsWith(".*") ? reference.substring(0, reference.length() - 2) : reference)
                .anyMatch(reference -> prefixes.stream()
                        .anyMatch(prefix -> reference.equals(prefix) || reference.startsWith(prefix + ".")));
    }

    private static String packageForReference(String reference, List<String> prefixes, Snapshot snapshot) {
        String dotted = reference.replace('/', '.');
        String base = dotted.endsWith(".*") ? dotted.substring(0, dotted.length() - 2) : dotted;
        String root = prefixes.stream().filter(prefix -> base.equals(prefix) || base.startsWith(prefix + "."))
                .max(Comparator.comparingInt(String::length)).orElse(null);
        if (root == null) {
            return null;
        }

        String known = knownPackage(base, snapshot);
        if (known != null) {
            return known;
        }

        if (dotted.endsWith(".*") && !lastSegment(base).matches("[A-Z_$].*")) {
            return base;
        }
        String suffix = base.substring(root.length());
        if (suffix.isEmpty()) {
            return root;
        }
        String[] segments = suffix.substring(1).split("\\.");
        if (segments.length == 1 || segments[segments.length - 1].matches("[A-Z_$].*")) {
            return root + (segments.length == 1 ? "" : "." + String.join(".", java.util.Arrays.copyOf(segments, segments.length - 1)));
        }
        return root + suffix;
    }

    private static String knownPackage(String value, Snapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return Stream.concat(snapshot.coveredPackages().stream(), snapshot.exports().stream().map(Export::packageName))
                .filter(packageName -> value.equals(packageName)
                        || value.startsWith(packageName + ".")
                        && firstSegment(value.substring(packageName.length() + 1)).matches("[A-Z_$].*"))
                .max(Comparator.comparingInt(String::length)).orElse(null);
    }

    private static String firstSegment(String value) {
        int separator = value.indexOf('.');
        return separator < 0 ? value : value.substring(0, separator);
    }

    private static List<String> constrainedImports(String importPackage) {
        if (importPackage == null || importPackage.isBlank()) {
            return List.of();
        }
        return BundlingArtifactVerifier.splitHeader(importPackage, ',').stream()
                .filter(clause -> clause.contains(";"))
                .filter(clause -> BundlingArtifactVerifier.splitHeader(clause, ';').stream().skip(1)
                        .anyMatch(segment -> !segment.trim().equalsIgnoreCase("resolution:=optional")))
                .map(clause -> BundlingArtifactVerifier.splitHeader(clause, ';').get(0).trim())
                .toList();
    }

    private static boolean packageMatches(String left, String right) {
        String selector = left.trim();
        if (selector.endsWith(".*") && right.equals(selector.substring(0, selector.length() - 2))) {
            return true;
        }
        String pattern = java.util.Arrays.stream(selector.split("\\*", -1))
                .map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining(".*"));
        return right.matches(pattern);
    }

    private static String lastSegment(String packageName) {
        int separator = packageName.lastIndexOf('.');
        return separator < 0 ? packageName : packageName.substring(separator + 1);
    }

    private static String digest(Snapshot snapshot) {
        if (snapshot == null) {
            return "";
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(snapshot);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot fingerprint OSGi export evidence", exception);
        }
    }

    public record Classification(String status, List<String> packages, String reason) {
        public Classification {
            packages = List.copyOf(packages == null ? List.of() : packages);
        }
    }
}
