package com.opennetsim.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates deterministic, offline open-source license assets from resolved runtime POM metadata
 * and copies the repository Apache 2.0 LICENSE.txt file.
 */
@CacheableTask
public abstract class GenerateOpenSourceLicensesTask extends DefaultTask {

    private static final int MAX_PARENT_HOPS = 4;

    @Input
    public abstract ListProperty<String> getResolvedCoordinates();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getPomFiles();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getApacheLicenseFile();

    @OutputDirectory
    public abstract DirectoryProperty getOutputAssetsDir();

    @TaskAction
    public void generate() throws IOException {
        Map<String, PomMetadata> pomsByCoord = new HashMap<>();
        for (File file : getPomFiles().getFiles()) {
            PomMetadata metadata = parsePom(file);
            if (metadata != null) {
                pomsByCoord.put(metadata.coordinate, metadata);
            }
        }

        List<String> rows = new ArrayList<>();
        for (String coord : getResolvedCoordinates().get()) {
            PomMetadata meta = pomsByCoord.get(coord);
            if (meta == null) {
                throw new GradleException("No se encontró el POM de la dependencia " + coord);
            }
            List<LicenseEntry> licenses = resolveLicensesWithParents(meta, pomsByCoord);
            LicenseEntry normalized = normalizeFossLicense(licenses, meta.url);
            if (normalized == null) {
                throw new GradleException("Dependencia sin licencia FOSS permitida: " + coord + " -> " + licenses);
            }
            String resolvedUrl = meta.url.isEmpty() ? normalized.url : meta.url;
            rows.add(String.join(
                "\t",
                sanitizeTsvField(meta.group),
                sanitizeTsvField(meta.artifact),
                sanitizeTsvField(meta.name),
                sanitizeTsvField(meta.version),
                sanitizeTsvField(normalized.name),
                sanitizeTsvField(resolvedUrl)
            ));
        }

        File licensesDir = getOutputAssetsDir().dir("licenses").get().getAsFile();
        if (!licensesDir.exists() && !licensesDir.mkdirs()) {
            throw new IOException("No se pudo crear el directorio " + licensesDir);
        }

        String tsvContent = rows.isEmpty() ? "" : String.join("\n", rows) + "\n";
        Files.writeString(licensesDir.toPath().resolve("libraries.tsv"), tsvContent, StandardCharsets.UTF_8);

        String licenseText = Files.readString(
            getApacheLicenseFile().get().getAsFile().toPath(),
            StandardCharsets.UTF_8
        ).replace("\r\n", "\n");
        Files.writeString(licensesDir.toPath().resolve("apache-2.0.txt"), licenseText, StandardCharsets.UTF_8);
    }

    private static List<LicenseEntry> resolveLicensesWithParents(
        PomMetadata start,
        Map<String, PomMetadata> pomsByCoord
    ) {
        PomMetadata current = start;
        for (int hop = 0; hop < MAX_PARENT_HOPS && current != null; hop++) {
            if (!current.licenses.isEmpty()) {
                return current.licenses;
            }
            current = current.parentCoordinate != null ? pomsByCoord.get(current.parentCoordinate) : null;
        }
        return List.of();
    }

    private static LicenseEntry normalizeFossLicense(List<LicenseEntry> licenses, String fallbackUrl) {
        for (LicenseEntry entry : licenses) {
            String lowerName = entry.name.toLowerCase(Locale.ROOT);
            String lowerUrl = entry.url.toLowerCase(Locale.ROOT);
            if ((lowerName.contains("apache") && lowerName.contains("2.0"))
                || lowerUrl.contains("apache.org/licenses/license-2.0")) {
                String url = entry.url.isEmpty() ? "https://www.apache.org/licenses/LICENSE-2.0" : entry.url;
                return new LicenseEntry("Apache-2.0", url);
            }
            if (lowerName.equals("mit")
                || lowerName.equals("mit license")
                || lowerName.equals("the mit license")
                || lowerUrl.contains("opensource.org/licenses/mit")) {
                String url = entry.url.isEmpty() ? "https://opensource.org/licenses/MIT" : entry.url;
                return new LicenseEntry("MIT", url);
            }
            if (lowerName.contains("bsd") && (lowerName.contains("3") || lowerName.contains("2"))) {
                String url = entry.url.isEmpty() ? fallbackUrl : entry.url;
                return new LicenseEntry(entry.name, url);
            }
        }
        return null;
    }

    private static String sanitizeTsvField(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    public static PomMetadata parsePom(File file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element root = factory.newDocumentBuilder().parse(file).getDocumentElement();

            Element parentEl = firstDirectChild(root, "parent");
            String parentGroup = parentEl != null ? directText(parentEl, "groupId") : null;
            String parentArtifact = parentEl != null ? directText(parentEl, "artifactId") : null;
            String parentVersion = parentEl != null ? directText(parentEl, "version") : null;
            String parentCoord = (parentGroup != null && parentArtifact != null && parentVersion != null)
                ? parentGroup + ":" + parentArtifact + ":" + parentVersion
                : null;

            String group = directText(root, "groupId");
            if (group == null) {
                group = parentGroup;
            }
            String artifact = directText(root, "artifactId");
            String version = directText(root, "version");
            if (version == null) {
                version = parentVersion;
            }
            if (group == null || artifact == null || version == null) {
                return null;
            }

            String name = directText(root, "name");
            if (name == null) {
                name = artifact;
            }
            String url = directText(root, "url");
            if (url == null) {
                url = "";
            }

            List<LicenseEntry> licenses = new ArrayList<>();
            for (Element licensesEl : directChildren(root, "licenses")) {
                for (Element licenseEl : directChildren(licensesEl, "license")) {
                    String lName = directText(licenseEl, "name");
                    String lUrl = directText(licenseEl, "url");
                    licenses.add(new LicenseEntry(lName != null ? lName : "", lUrl != null ? lUrl : ""));
                }
            }

            return new PomMetadata(
                group + ":" + artifact + ":" + version,
                group,
                artifact,
                version,
                name,
                url,
                parentCoord,
                licenses
            );
        } catch (Exception e) {
            throw new GradleException("Error al leer el POM " + file.getName() + ": " + e.getMessage(), e);
        }
    }

    private static List<Element> directChildren(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element el && tagName.equals(el.getTagName())) {
                result.add(el);
            }
        }
        return result;
    }

    private static Element firstDirectChild(Element parent, String tagName) {
        List<Element> list = directChildren(parent, tagName);
        return list.isEmpty() ? null : list.get(0);
    }

    private static String directText(Element parent, String tagName) {
        Element el = firstDirectChild(parent, tagName);
        if (el == null) {
            return null;
        }
        String text = el.getTextContent();
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class LicenseEntry {
        public final String name;
        public final String url;

        public LicenseEntry(String name, String url) {
            this.name = name;
            this.url = url;
        }

        @Override
        public String toString() {
            return name + " (" + url + ")";
        }
    }

    public static final class PomMetadata {
        public final String coordinate;
        public final String group;
        public final String artifact;
        public final String version;
        public final String name;
        public final String url;
        public final String parentCoordinate;
        public final List<LicenseEntry> licenses;

        public PomMetadata(
            String coordinate,
            String group,
            String artifact,
            String version,
            String name,
            String url,
            String parentCoordinate,
            List<LicenseEntry> licenses
        ) {
            this.coordinate = coordinate;
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.name = name;
            this.url = url;
            this.parentCoordinate = parentCoordinate;
            this.licenses = licenses;
        }
    }
}
