package com.atlassian.dc.p9enablement.migrate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The catalog of platform libraries that can be bundled by the grouped
 * migration.  Discovery and the OpenRewrite recipe both use this catalog so
 * that a plan cannot request a dependency with rules different from the ones
 * used to analyse it.
 *
 * <p>Version defaults originate primarily from the Platform 9 declarations in
 * {@code dc-platform/dependencies/pom.xml} and its dependency modules. JTidy
 * follows the version declared by {@code jira-core}; companion versions also
 * follow the owning library's POM (for example, JDOM 2's optional Jaxen dependency).
 * Before changing a default, recheck those declarations and the target product's
 * actual dependencies, then regenerate affected OSGi export snapshots and verify
 * bundling against the target product. These defaults are not a latest-version policy.
 */
public final class BundleDependencyModel {

    private static final Map<String, Family> FAMILIES = createFamilies();

    private BundleDependencyModel() {
    }

    public static Map<String, Family> families() {
        return FAMILIES;
    }

    public static Family family(final String id) {
        Family family = FAMILIES.get(id);
        if (family == null) {
            throw new IllegalArgumentException("Unsupported platform dependency family: " + id);
        }
        return family;
    }

    /**
     * Assesses observed usage before planning dependencies. Unsupported usage is
     * reported without preventing supported usage from migrating; unresolved
     * prerequisites block migration of the family.
     */
    public static FamilyAssessment assess(final String familyId, final List<String> references) {
        Family family = family(familyId);
        if (familyId.equals("jsoup") && references.stream()
                .anyMatch(reference -> reference.contains("Re2jRegex") || matchesPrefix(reference, "com.google.re2j"))) {
            return new FamilyAssessment(List.of(), List.of(), List.of(), List.of(
                    "jsoup re2j selector usage requires confirming and bundling its compatible re2j dependency"));
        }

        List<UnsupportedUsage> unsupported = familyId.equals("xerces-xalan")
                && hasPrefix(references, "org.apache.html.dom")
                ? List.of(new UnsupportedUsage("xerces", "xercesImpl",
                        "unsupported: org.apache.html.dom usage has no supported bundling repair"))
                : List.of();
        List<String> supportedReferences = references.stream()
                .filter(reference -> unsupportedPackagePrefixes(family).stream()
                        .noneMatch(prefix -> matchesPrefix(reference, prefix)))
                .toList();
        List<DependencySpec> dependencies = supportedReferences.isEmpty()
                ? List.of() : specsFor(family, supportedReferences);
        return new FamilyAssessment(supportedReferences, dependencies, unsupported, List.of());
    }

    /**
     * Returns the dependencies required by a family for the observed source
     * references. The result includes conditional companions and preserves the
     * declared order so plans remain stable.
     */
    public static List<DependencySpec> specsFor(final Family family, final List<String> references) {
        List<String> observedReferences = references == null ? List.of() : references;
        if ("xerces-xalan".equals(family.id())) {
            return xercesSpecifications(family, observedReferences);
        }

        List<DependencySpec> specifications = new ArrayList<>(family.dependencies());
        boolean validatorXmlUsage = "commons-validator".equals(family.id())
                && !observedReferences.isEmpty() && !onlyRoutines(observedReferences);
        if (validatorXmlUsage || hasConditionalReference(family, observedReferences)) {
            specifications.addAll(family.conditionalDependencies());
        }

        if ("commons-validator".equals(family.id()) && onlyRoutines(observedReferences)) {
            DependencySpec root = specifications.get(0);
            specifications.set(0, withExclusions(root, List.of(
                    exclusion("commons-beanutils", "commons-beanutils"),
                    exclusion("commons-digester", "commons-digester"),
                    exclusion("commons-logging", "commons-logging"),
                    exclusion("commons-collections", "commons-collections"))));
        }

        return distinct(specifications);
    }

    public static List<DependencySpec> specsFor(final String familyId, final List<String> references) {
        return specsFor(family(familyId), references);
    }

    /**
     * Declared reflective requirements that bytecode type-reference analysis cannot see.
     * Gson's UnsafeAllocator calls Class.forName("sun.misc.Unsafe"); verification still
     * intersects this hint with the built plugin's actual mandatory imports.
     */
    public static Set<String> reflectivePackageReferences(String coordinate) {
        return coordinate.equals("com.google.code.gson:gson") ? Set.of("sun.misc") : Set.of();
    }

    /** Returns the class-file prefixes supplied by one dependency artifact. */
    public static List<String> classPrefixes(final DependencySpec specification) {
        return switch (specification.coordinate()) {
            case "commons-io:commons-io" -> List.of("org/apache/commons/io/");
            case "commons-codec:commons-codec" -> List.of("org/apache/commons/codec/");
            case "org.apache.commons:commons-text" -> List.of("org/apache/commons/text/");
            case "org.json:json" -> List.of("org/json/");
            case "org.jsoup:jsoup" -> List.of("org/jsoup/");
            case "org.jdom:jdom2" -> List.of("org/jdom2/");
            case "jaxen:jaxen" -> List.of("org/jaxen/");
            case "com.rometools:rome" -> List.of("com/rometools/rome/");
            case "com.rometools:rome-utils" -> List.of("com/rometools/utils/");
            case "commons-validator:commons-validator" -> List.of("org/apache/commons/validator/");
            case "commons-beanutils:commons-beanutils" -> List.of("org/apache/commons/beanutils/");
            case "commons-digester:commons-digester" -> List.of("org/apache/commons/digester/");
            case "commons-logging:commons-logging" -> List.of("org/apache/commons/logging/");
            case "commons-collections:commons-collections" -> List.of("org/apache/commons/collections/");
            case "xerces:xercesImpl" -> List.of(
                    "org/apache/xerces/", "org/apache/xml/serialize/", "org/apache/wml/");
            case "xalan:xalan" -> List.of(
                    "org/apache/xalan/", "org/apache/xpath/", "org/apache/xml/dtm/",
                    "org/apache/xml/utils/", "org/apache/xml/res/", "org/apache/bcel/",
                    "org/apache/regexp/");
            case "xalan:serializer" -> List.of("org/apache/xml/serializer/");
            case "com.google.code.gson:gson" -> List.of("com/google/gson/");
            case "net.oauth.core:oauth" -> List.of("net/oauth/");
            case "jtidy:jtidy" -> List.of("org/w3c/tidy/");
            default -> List.of();
        };
    }

    /** Returns class-file prefixes that must not be present because of exclusions. */
    public static List<String> forbiddenPrefixes(final DependencySpec specification) {
        Set<String> prefixes = new LinkedHashSet<>();
        for (Exclusion exclusion : specification.exclusions()) {
            switch (exclusion.coordinate()) {
                case "xml-apis:xml-apis" -> {
                    prefixes.add("javax/xml/");
                    prefixes.add("org/xml/sax/");
                    prefixes.add("org/w3c/dom/Document.class");
                    prefixes.add("org/w3c/dom/Node.class");
                }
                default -> {
                    // Other exclusions protect platform-provided packages but are
                    // not artifact-wide invariants when the app already supplied them.
                }
            }
        }
        return List.copyOf(prefixes);
    }

    /**
     * Returns package patterns that may remain optional in a pinned
     * Import-Package instruction for this family and observed references.
     */
    public static List<String> optionalPackages(final Family family, final List<String> references) {
        List<String> observedReferences = references == null ? List.of() : references;
        Set<String> packages = new LinkedHashSet<>();
        switch (family.id()) {
            case "commons-io" -> packages.add("sun.nio.ch");
            case "jdom2", "rome" -> {
                if (hasPrefix(observedReferences, "org.jdom2.xpath")) {
                    packages.add("org.jdom.*");
                    packages.add("org.dom4j.*");
                    packages.add("nu.xom.*");
                } else {
                    packages.add("org.jaxen.*");
                }
            }
            case "jsoup" -> {
                packages.add("com.google.re2j.*");
                packages.add("org.jspecify.annotations.*");
            }
            case "gson" -> packages.add("com.google.errorprone.annotations.*");
            case "commons-validator" -> {
                if (onlyRoutines(observedReferences)) {
                    packages.add("org.apache.commons.digester.*");
                    packages.add("org.apache.commons.beanutils.*");
                    packages.add("org.apache.commons.collections.*");
                    packages.add("org.apache.commons.logging.*");
                }
            }
            case "xerces-xalan" -> {
                List<DependencySpec> specifications = specsFor(family, observedReferences);
                if (containsCoordinate(specifications, "xerces:xercesImpl")) {
                    packages.add("org.apache.xml.resolver*");
                    packages.add("sun.io");
                }
                if (containsCoordinate(specifications, "xalan:xalan")
                        && !containsCoordinate(specifications, "xerces:xercesImpl")) {
                    packages.add("org.apache.xerces.parsers");
                    packages.add("org.apache.xml.serialize");
                }
            }
            case "jtidy" -> packages.add("org.apache.tools.ant.*");
            default -> {
                // The remaining bundled artifacts have no known optional imports.
            }
        }
        return List.copyOf(packages);
    }

    public static List<String> optionalPackages(final String familyId, final List<String> references) {
        return optionalPackages(family(familyId), references);
    }

    /** Returns all class-file package prefixes expected from the selected family. */
    public static List<String> bundledPackages(final Family family, final List<String> references) {
        Set<String> packages = new LinkedHashSet<>();
        for (DependencySpec specification : specsFor(family, references)) {
            for (String prefix : classPrefixes(specification)) {
                packages.add(packageName(prefix));
            }
        }
        return List.copyOf(packages);
    }

    public static List<String> bundledPackages(final String familyId, final List<String> references) {
        return bundledPackages(family(familyId), references);
    }

    /** Returns implementation package prefixes that have no supported bundling path. */
    public static List<String> unsupportedPackagePrefixes(final Family family) {
        return "xerces-xalan".equals(family.id()) ? List.of("org.apache.html.dom") : List.of();
    }

    public static List<String> unsupportedPackagePrefixes(final String familyId) {
        return unsupportedPackagePrefixes(family(familyId));
    }

    private static String packageName(final String classPrefix) {
        String dotted = classPrefix.replace('/', '.');
        return dotted.endsWith(".") ? dotted.substring(0, dotted.length() - 1) : dotted;
    }

    private static Map<String, Family> createFamilies() {
        Map<String, Family> families = new LinkedHashMap<>();
        families.put("commons-io", new Family("commons-io", List.of("org.apache.commons.io"),
                List.of(dependency("commons-io", "commons-io", "2.22.0")), List.of(), List.of()));
        families.put("commons-codec", new Family("commons-codec", List.of("org.apache.commons.codec"),
                List.of(dependency("commons-codec", "commons-codec", "1.22.1")), List.of(), List.of()));
        families.put("commons-text", new Family("commons-text", List.of("org.apache.commons.text"),
                List.of(dependency("org.apache.commons", "commons-text", "1.15.0",
                        List.of(new Exclusion("org.apache.commons", "commons-lang3")))), List.of(), List.of()));
        families.put("org-json", new Family("org-json", List.of("org.json"),
                List.of(dependency("org.json", "json", "20240303")), List.of(), List.of()));
        families.put("jsoup", new Family("jsoup", List.of("org.jsoup"),
                List.of(dependency("org.jsoup", "jsoup", "1.23.2")), List.of(), List.of()));
        families.put("jdom2", new Family("jdom2", List.of("org.jdom2"),
                List.of(dependency("org.jdom", "jdom2", "2.0.6.1")),
                List.of(dependency("jaxen", "jaxen", "1.2.0")), List.of("org.jdom2.xpath")));
        families.put("rome", new Family("rome", List.of("com.rometools.rome", "com.rometools.utils"),
                List.of(dependency("com.rometools", "rome", "2.1.0",
                                List.of(new Exclusion("org.slf4j", "slf4j-api"))),
                        dependency("com.rometools", "rome-utils", "2.1.0",
                                List.of(new Exclusion("org.slf4j", "slf4j-api"))),
                        dependency("org.jdom", "jdom2", "2.0.6.1")),
                List.of(dependency("jaxen", "jaxen", "1.2.0")), List.of("org.jdom2.xpath")));
        families.put("commons-validator", new Family("commons-validator", List.of("org.apache.commons.validator"),
                List.of(dependency("commons-validator", "commons-validator", "1.11.0")),
                List.of(dependency("commons-beanutils", "commons-beanutils", "1.11.0"),
                        dependency("commons-digester", "commons-digester", "2.1"),
                        dependency("commons-logging", "commons-logging", "1.3.5"),
                        dependency("commons-collections", "commons-collections", "3.2.2")),
                List.of("org.apache.commons.validator.Validator", "org.apache.commons.validator.ValidatorResources")));
        families.put("xerces-xalan", new Family("xerces-xalan", List.of(
                "org.apache.xerces", "org.apache.xml.serialize", "org.apache.html.dom", "org.apache.wml",
                "org.apache.xalan", "org.apache.xpath", "org.apache.xml.dtm", "org.apache.xml.utils",
                "org.apache.xml.res", "org.apache.bcel", "org.apache.regexp", "org.apache.xml.serializer"),
                List.of(dependency("xerces", "xercesImpl", "2.12.2",
                                List.of(new Exclusion("xml-apis", "xml-apis"))),
                        dependency("xalan", "xalan", "2.7.3"),
                        dependency("xalan", "serializer", "2.7.3")), List.of(), List.of()));
        families.put("commons-collections3", new Family("commons-collections3", List.of("org.apache.commons.collections"),
                List.of(dependency("commons-collections", "commons-collections", "3.2.2")), List.of(), List.of()));
        families.put("gson", new Family("gson", List.of("com.google.gson"),
                List.of(dependency("com.google.code.gson", "gson", "2.14.0",
                        List.of(new Exclusion("com.google.errorprone", "error_prone_annotations")))), List.of(), List.of()));
        families.put("net-oauth", new Family("net-oauth", List.of("net.oauth"),
                List.of(dependency("net.oauth.core", "oauth", "20100601-atlassian-5")), List.of(), List.of()));
        families.put("jtidy", new Family("jtidy", List.of("org.w3c.tidy"),
                List.of(dependency("jtidy", "jtidy", "r8-20050104")), List.of(), List.of()));
        return Collections.unmodifiableMap(families);
    }

    private static List<DependencySpec> xercesSpecifications(final Family family,
                                                              final List<String> references) {
        Set<String> referencesSet = new LinkedHashSet<>(references);
        List<DependencySpec> specifications = new ArrayList<>();
        if (hasAnyPrefix(referencesSet, List.of(
                "org.apache.xerces", "org.apache.xml.serialize", "org.apache.wml"))) {
            specifications.add(family.dependencies().get(0));
        }
        if (hasAnyPrefix(referencesSet, List.of(
                "org.apache.xalan", "org.apache.xpath", "org.apache.xml.dtm", "org.apache.xml.utils",
                "org.apache.xml.res", "org.apache.bcel", "org.apache.regexp", "org.apache.xml.serializer"))) {
            specifications.add(family.dependencies().get(1));
            specifications.add(family.dependencies().get(2));
        }
        return distinct(specifications);
    }

    private static boolean hasConditionalReference(final Family family, final List<String> references) {
        return family.conditionalPackagePrefixes().stream().anyMatch(prefix -> hasPrefix(references, prefix));
    }

    private static boolean onlyRoutines(final List<String> references) {
        return !references.isEmpty() && references.stream()
                .allMatch(reference -> matchesPrefix(reference, "org.apache.commons.validator.routines"));
    }

    private static boolean hasPrefix(final List<String> references, final String prefix) {
        return references.stream().anyMatch(reference -> matchesPrefix(reference, prefix));
    }

    private static boolean hasAnyPrefix(final Set<String> references, final List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> references.stream().anyMatch(reference -> matchesPrefix(reference, prefix)));
    }

    private static boolean matchesPrefix(String reference, String prefix) {
        return reference.equals(prefix) || reference.startsWith(prefix + ".");
    }

    private static boolean containsCoordinate(final List<DependencySpec> specifications, final String coordinate) {
        return specifications.stream().anyMatch(specification -> specification.coordinate().equals(coordinate));
    }

    private static List<DependencySpec> distinct(final List<DependencySpec> specifications) {
        return specifications.stream().distinct().toList();
    }

    private static DependencySpec withExclusions(final DependencySpec specification, final List<Exclusion> exclusions) {
        return new DependencySpec(specification.groupId(), specification.artifactId(), specification.version(), exclusions);
    }

    private static DependencySpec dependency(final String groupId, final String artifactId, final String version) {
        return dependency(groupId, artifactId, version, List.of());
    }

    private static DependencySpec dependency(final String groupId, final String artifactId, final String version,
                                            final List<Exclusion> exclusions) {
        return new DependencySpec(groupId, artifactId, version, exclusions);
    }

    private static Exclusion exclusion(final String groupId, final String artifactId) {
        return new Exclusion(groupId, artifactId);
    }

    public record FamilyAssessment(List<String> supportedReferences, List<DependencySpec> dependencies,
                                   List<UnsupportedUsage> unsupported, List<String> unresolved) {
        public FamilyAssessment {
            supportedReferences = List.copyOf(supportedReferences);
            dependencies = List.copyOf(dependencies);
            unsupported = List.copyOf(unsupported);
            unresolved = List.copyOf(unresolved);
        }
    }

    public record UnsupportedUsage(String groupId, String artifactId, String reason) {
    }

    public record Family(String id, List<String> packagePrefixes, List<DependencySpec> dependencies,
                         List<DependencySpec> conditionalDependencies, List<String> conditionalPackagePrefixes) {
        public Family {
            packagePrefixes = List.copyOf(packagePrefixes);
            dependencies = List.copyOf(dependencies);
            conditionalDependencies = List.copyOf(conditionalDependencies);
            conditionalPackagePrefixes = List.copyOf(conditionalPackagePrefixes);
        }
    }

    public record DependencySpec(String groupId, String artifactId, String version, List<Exclusion> exclusions) {
        public DependencySpec {
            exclusions = List.copyOf(exclusions);
        }

        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    public record Exclusion(String groupId, String artifactId) {
        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }
}
