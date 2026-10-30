package com.atlassian.dc.p9enablement.migrate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleDependencyModelTest {
    @Test
    void preservesCatalogOrderAndCoversEverySupportedFamily() {
        assertEquals(List.of(
                "commons-io", "commons-codec", "commons-text", "org-json", "jsoup", "jdom2", "rome",
                "commons-validator", "xerces-xalan", "commons-collections3", "gson", "net-oauth", "jtidy"),
                List.copyOf(BundleDependencyModel.families().keySet()));

        assertDependency("commons-io", "commons-io:commons-io", "org/apache/commons/io/");
        assertDependency("commons-codec", "commons-codec:commons-codec", "org/apache/commons/codec/");
        assertDependency("commons-text", "org.apache.commons:commons-text", "org/apache/commons/text/");
        assertDependency("org-json", "org.json:json", "org/json/");
        assertDependency("jsoup", "org.jsoup:jsoup", "org/jsoup/");
        assertDependency("jdom2", "org.jdom:jdom2", "org/jdom2/");
        assertDependency("rome", "com.rometools:rome", "com/rometools/rome/");
        assertDependency("commons-validator", "commons-validator:commons-validator", "org/apache/commons/validator/");
        BundleDependencyModel.DependencySpec xerces = BundleDependencyModel.family("xerces-xalan").dependencies().get(0);
        assertEquals("xerces:xercesImpl", xerces.coordinate());
        assertEquals(List.of("org/apache/xerces/", "org/apache/xml/serialize/", "org/apache/wml/"),
                BundleDependencyModel.classPrefixes(xerces));
        assertDependency("commons-collections3", "commons-collections:commons-collections", "org/apache/commons/collections/");
        assertDependency("gson", "com.google.code.gson:gson", "com/google/gson/");
        assertDependency("net-oauth", "net.oauth.core:oauth", "net/oauth/");
        assertDependency("jtidy", "jtidy:jtidy", "org/w3c/tidy/");
    }

    @Test
    void addsJaxenOnlyWhenJdomXPathIsUsed() {
        assertEquals(List.of("org.jdom:jdom2"), coordinates(
                BundleDependencyModel.specsFor("jdom2", List.of("org.jdom2.Element"))));
        assertEquals(List.of("org.jdom:jdom2", "jaxen:jaxen"), coordinates(
                BundleDependencyModel.specsFor("jdom2", List.of("org.jdom2.xpath.XPathFactory"))));
        assertEquals(List.of("org.jaxen.*"), BundleDependencyModel.optionalPackages(
                "jdom2", List.of("org.jdom2.Element")));
        assertEquals(List.of("org.jdom.*", "org.dom4j.*", "nu.xom.*"), BundleDependencyModel.optionalPackages(
                "jdom2", List.of("org.jdom2.xpath.XPathFactory")));

        assertEquals(List.of("org.jdom.*", "org.dom4j.*", "nu.xom.*"), BundleDependencyModel.optionalPackages(
                "rome", List.of("com.rometools.rome.feed.synd.SyndFeed", "org.jdom2.xpath.XPathFactory")));
    }

    @Test
    void alwaysIncludesRomeCompanionsAndUsesJaxenOnlyForOptionalXPath() {
        assertEquals(List.of("com.rometools:rome", "com.rometools:rome-utils", "org.jdom:jdom2"), coordinates(
                BundleDependencyModel.specsFor("rome", List.of("com.rometools.rome.feed.synd.SyndFeed"))));

        List<BundleDependencyModel.DependencySpec> romeArtifacts = BundleDependencyModel.specsFor("rome", List.of()).stream()
                .filter(specification -> specification.coordinate().equals("com.rometools:rome")
                        || specification.coordinate().equals("com.rometools:rome-utils")).toList();
        assertEquals(2, romeArtifacts.size());
        assertTrue(romeArtifacts.stream()
                .allMatch(specification -> specification.exclusions().stream()
                        .anyMatch(exclusion -> exclusion.coordinate().equals("org.slf4j:slf4j-api"))));

        assertEquals(List.of("com.rometools.rome", "com.rometools.utils", "org.jdom2"),
                BundleDependencyModel.bundledPackages("rome", List.of("com.rometools.rome.feed.synd.SyndFeed")));
        assertEquals(List.of("org.jaxen.*"), BundleDependencyModel.optionalPackages(
                "rome", List.of("com.rometools.rome.feed.synd.SyndFeed")));
    }

    @Test
    void distinguishesValidatorRoutinesFromXmlRules() {
        BundleDependencyModel.DependencySpec routines = BundleDependencyModel.specsFor(
                "commons-validator", List.of("org.apache.commons.validator.routines.EmailValidator")).get(0);
        assertEquals(1, BundleDependencyModel.specsFor(
                "commons-validator", List.of("org.apache.commons.validator.routines.EmailValidator")).size());
        assertEquals(4, routines.exclusions().size());
        assertEquals(5, BundleDependencyModel.specsFor(
                "commons-validator", List.of("org.apache.commons.validator.Field")).size());
        assertEquals(List.of(
                "org.apache.commons.digester.*", "org.apache.commons.beanutils.*",
                "org.apache.commons.collections.*", "org.apache.commons.logging.*"),
                BundleDependencyModel.optionalPackages("commons-validator",
                        List.of("org.apache.commons.validator.routines.EmailValidator")));
    }

    @Test
    void selectsSupportedXercesArtifactsAndReportsHtmlAsUnsupported() {
        assertEquals(List.of(), BundleDependencyModel.specsFor(
                "xerces-xalan", List.of("org.apache.html.dom.HTMLDocumentImpl")));
        assertEquals(List.of("org.apache.html.dom"), BundleDependencyModel.unsupportedPackagePrefixes("xerces-xalan"));
        assertEquals(List.of("xerces:xercesImpl"), coordinates(BundleDependencyModel.specsFor(
                "xerces-xalan", List.of("org.apache.xml.serialize.OutputFormat"))));
        assertEquals(List.of("xalan:xalan", "xalan:serializer"), coordinates(BundleDependencyModel.specsFor(
                "xerces-xalan", List.of("org.apache.xml.res.XSLTProcessor"))));
        assertFalse(BundleDependencyModel.specsFor(
                "xerces-xalan", List.of("org.apache.xml.resolver.Catalog")).stream()
                .anyMatch(specification -> specification.coordinate().equals("xalan:xalan")));
        assertEquals(List.of("org.apache.xml.resolver*", "sun.io"), BundleDependencyModel.optionalPackages(
                "xerces-xalan", List.of("org.apache.xerces.parsers.DOMParser")));
    }

    @Test
    void modelsArtifactExclusionsAndJtidyOptionalAntPackages() {
        BundleDependencyModel.DependencySpec xerces = BundleDependencyModel.family("xerces-xalan").dependencies().get(0);
        assertEquals(List.of("javax/xml/", "org/xml/sax/", "org/w3c/dom/Document.class", "org/w3c/dom/Node.class"),
                BundleDependencyModel.forbiddenPrefixes(xerces));
        assertEquals(List.of("org.apache.tools.ant.*"), BundleDependencyModel.optionalPackages(
                "jtidy", List.of("org.w3c.tidy.Tidy")));
        assertTrue(BundleDependencyModel.classPrefixes(
                BundleDependencyModel.family("gson").dependencies().get(0)).get(0).startsWith("com/google/gson/"));
        assertEquals(List.of("com.google.errorprone.annotations.*"), BundleDependencyModel.optionalPackages(
                "gson", List.of("com.google.gson.Gson")));
        assertEquals(List.of("com.google.re2j.*", "org.jspecify.annotations.*"),
                BundleDependencyModel.optionalPackages("jsoup", List.of("org.jsoup.Jsoup")));
    }

    @Test
    void blocksJsoupRe2jUsageButAllowsOrdinaryJsoup() {
        for (String reference : List.of("org.jsoup.helper.Re2jRegex", "com.google.re2j.Pattern")) {
            BundleDependencyModel.FamilyAssessment assessment = BundleDependencyModel.assess(
                    "jsoup", List.of("org.jsoup.Jsoup", reference));

            assertTrue(assessment.dependencies().isEmpty());
            assertTrue(assessment.unsupported().isEmpty());
            assertEquals(List.of(
                    "jsoup re2j selector usage requires confirming and bundling its compatible re2j dependency"),
                    assessment.unresolved());
        }

        BundleDependencyModel.FamilyAssessment ordinary = BundleDependencyModel.assess(
                "jsoup", List.of("org.jsoup.Jsoup"));

        assertEquals(List.of("org.jsoup:jsoup"), coordinates(ordinary.dependencies()));
        assertEquals(List.of("org.jsoup.Jsoup"), ordinary.supportedReferences());
        assertTrue(ordinary.unresolved().isEmpty());
    }

    @Test
    void reportsUnsupportedHtmlWithoutBlockingSupportedXml() {
        for (List<String> supported : List.of(List.<String>of(), List.of("org.apache.xerces.parsers.DOMParser"))) {
            List<String> references = java.util.stream.Stream.concat(
                    supported.stream(), java.util.stream.Stream.of("org.apache.html.dom.HTMLDocumentImpl")).toList();
            BundleDependencyModel.FamilyAssessment assessment = BundleDependencyModel.assess("xerces-xalan", references);

            assertEquals(supported, assessment.supportedReferences());
            assertEquals(supported.isEmpty() ? List.of() : List.of("xerces:xercesImpl"),
                    coordinates(assessment.dependencies()));
            assertTrue(assessment.unresolved().isEmpty());
            assertEquals(List.of(new BundleDependencyModel.UnsupportedUsage("xerces", "xercesImpl",
                    "unsupported: org.apache.html.dom usage has no supported bundling repair")), assessment.unsupported());
        }
    }

    @Test
    void doesNotPlanDependenciesWithoutObservedUsage() {
        for (String family : BundleDependencyModel.families().keySet()) {
            BundleDependencyModel.FamilyAssessment assessment = BundleDependencyModel.assess(family, List.of());
            assertTrue(assessment.dependencies().isEmpty());
            assertTrue(assessment.unsupported().isEmpty());
            assertTrue(assessment.unresolved().isEmpty());
        }
    }

    private static void assertDependency(String familyId, String coordinate, String prefix) {
        BundleDependencyModel.DependencySpec dependency = BundleDependencyModel.family(familyId).dependencies().get(0);
        assertEquals(coordinate, dependency.coordinate());
        assertEquals(List.of(prefix), BundleDependencyModel.classPrefixes(dependency));
    }

    private static List<String> coordinates(List<BundleDependencyModel.DependencySpec> specifications) {
        return specifications.stream().map(BundleDependencyModel.DependencySpec::coordinate).toList();
    }
}
