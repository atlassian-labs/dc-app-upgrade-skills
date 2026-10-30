package com.atlassian.dc.p9enablement.migrate;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads RefApp's default export policy using the configuration classes shipped in its WAR. */
record RefappExportConfiguration(String hostVersion, Set<String> includes, Set<String> excludes,
                                 Set<String> publicPackages, Set<String> publicExcludes,
                                 Set<String> deprecatedPackages, Map<String, String> versions) {
    public static final String FACTORY_RESOURCE =
            "com/atlassian/plugin/refimpl/plugins/PackageScannerConfigurationFactory.class";

    public static RefappExportConfiguration read(Path distribution, Path containerLibraries, Path workspace)
            throws IOException {
        if (System.getProperty("refapp.packageExport.overrides") != null) {
            throw new IOException("Remove refapp.packageExport.overrides to extract RefApp's default policy");
        }
        Files.createDirectories(workspace);
        Path temporary = Files.createTempDirectory(workspace, "refapp-export-policy-");
        try {
            List<Path> libraries = libraries(distribution, temporary);
            Set<String> resourcePaths = libraries.stream()
                    .map(path -> "/WEB-INF/lib/" + path.getFileName()).collect(Collectors.toSet());
            List<Path> classpath = new ArrayList<>(libraries);
            if (containerLibraries != null) {
                try (Stream<Path> files = Files.walk(containerLibraries)) {
                    classpath.addAll(files.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".jar")).sorted().toList());
                }
            }
            List<URL> urls = new ArrayList<>();
            for (Path library : classpath) {
                urls.add(library.toUri().toURL());
            }
            // The tooling's dependencies must not replace classes from the selected product.
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new),
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> servletContext = loader.loadClass("jakarta.servlet.ServletContext");
                Object context = Proxy.newProxyInstance(loader, new Class<?>[]{servletContext},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("getResourcePaths")
                                    && arguments[0].equals("/WEB-INF/lib")) {
                                return resourcePaths;
                            }
                            throw new UnsupportedOperationException("RefApp policy requested " + method.getName());
                        });
                Class<?> hostType = loader.loadClass("com.atlassian.plugin.refimpl.version.HostVersion");
                Object host = hostType.getConstructor(servletContext).newInstance(context);
                Class<?> factory = loader.loadClass(FACTORY_RESOURCE.replace('/', '.').replace(".class", ""));
                Object configuration = factory.getMethod("getPackageScannerConfiguration", servletContext, hostType)
                        .invoke(null, context, host);
                Map<String, String> versions = new TreeMap<>();
                ((Map<?, ?>) invoke(configuration, "getPackageVersions"))
                        .forEach((key, value) -> versions.put(String.class.cast(key), String.class.cast(value)));
                if (!(boolean) invoke(configuration, "treatDeprecatedPackagesAsPublic")) {
                    throw new IOException("RefApp policy hides deprecated packages; extraction adapter needs updating");
                }
                return new RefappExportConfiguration((String) invoke(host, "getValue"),
                        strings(configuration, "getPackageIncludes"), strings(configuration, "getPackageExcludes"),
                        strings(configuration, "getOsgiPublicPackages"), strings(configuration, "getOsgiPublicPackagesExcludes"),
                        strings(configuration, "getOsgiDeprecatedPackages"), Map.copyOf(versions));
            } catch (ReflectiveOperationException | LinkageError exception) {
                throw new IOException("Unable to read the released RefApp scanner configuration", exception);
            }
        } finally {
            try (Stream<Path> files = Files.walk(temporary)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private static Object invoke(Object instance, String method) throws ReflectiveOperationException {
        return instance.getClass().getMethod(method).invoke(instance);
    }

    private static Set<String> strings(Object instance, String method) throws ReflectiveOperationException {
        return ((Collection<?>) invoke(instance, method)).stream()
                .map(String.class::cast).collect(Collectors.toUnmodifiableSet());
    }

    private static List<Path> libraries(Path distribution, Path temporary) throws IOException {
        if (Files.isDirectory(distribution)) {
            try (Stream<Path> files = Files.list(distribution.resolve("WEB-INF/lib"))) {
                return files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".jar")).sorted().toList();
            }
        }
        List<Path> libraries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(distribution.toFile())) {
            for (ZipEntry entry : zip.stream().filter(candidate ->
                    candidate.getName().matches("WEB-INF/lib/[^/]+\\.jar")).sorted(
                    Comparator.comparing(ZipEntry::getName)).toList()) {
                Path library = temporary.resolve(Path.of(entry.getName()).getFileName());
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, library);
                }
                libraries.add(library);
            }
        }
        return List.copyOf(libraries);
    }
}
