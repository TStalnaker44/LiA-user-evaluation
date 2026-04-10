package chatbot.tools;

import com.example.my_plugin.SbomComponentExtractor;
import com.intellij.openapi.project.Project;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProjectToolData {
    private final Project project;

    ProjectToolData(Project project) {
        this.project = project;
    }

    JSONObject loadSurvey() {
        Path path = resolveProjectFile(".license-tool", "license-survey.json");
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
            JSONObject detected = root.optJSONObject("detectedLicense");
            if (detected != null) {
                detected.remove("excerpt");
                detected.remove("fullText");
                detected.remove("rawText");
                detected.remove("licenseText");
            }
            return root;
        } catch (Exception e) {
            return null;
        }
    }

    JSONArray loadDependencies() {
        JSONArray fromSummary = loadDependenciesFromSummary();
        if (fromSummary != null && !fromSummary.isEmpty()) {
            return fromSummary;
        }
        return loadDependenciesFromBom();
    }

    String loadDependencyTree() {
        Map<String, JSONObject> summaryIndex = buildDependencyIndex(loadDependenciesFromSummary());
        Path treePath = resolveProjectFile(".license-tool", "dependency-tree.txt");
        if (treePath != null && Files.exists(treePath)) {
            try {
                List<String> normalized = new ArrayList<>();
                boolean rootSeen = false;
                for (String rawLine : Files.readAllLines(treePath, StandardCharsets.UTF_8)) {
                    if (rawLine == null) {
                        continue;
                    }
                    String line = rawLine.replace("\r", "");
                    if (line.startsWith("[INFO] ")) {
                        line = line.substring(7);
                    }
                    if (!line.isBlank()) {
                        String formatted = formatDependencyTreeLine(line, summaryIndex);
                        if (formatted == null || formatted.isBlank()) {
                            continue;
                        }
                        if (!rootSeen) {
                            rootSeen = true;
                            continue;
                        }
                        normalized.add(formatted);
                    }
                }
                if (!normalized.isEmpty()) {
                    return String.join("\n", normalized);
                }
            } catch (Exception ignored) {
            }
        }

        JSONArray dependencies = loadDependenciesFromSummary();
        if (dependencies == null || dependencies.isEmpty()) {
            return "";
        }
        return buildDependencyTreeFromSummary(dependencies);
    }

    JSONObject findDependency(String dependencyName) {
        if (dependencyName == null || dependencyName.isBlank()) {
            return null;
        }
        JSONArray dependencies = loadDependencies();
        String expected = dependencyName.trim().toLowerCase();
        for (int i = 0; i < dependencies.length(); i++) {
            JSONObject dependency = dependencies.optJSONObject(i);
            if (dependency == null) {
                continue;
            }
            String id = dependency.optString("id", "").toLowerCase();
            String name = dependency.optString("name", "").toLowerCase();
            if (expected.equals(id) || expected.equals(name) || id.contains(expected) || name.contains(expected)) {
                return dependency;
            }
        }
        return null;
    }

    private JSONArray loadDependenciesFromSummary() {
        Path path = resolveProjectFile(".license-tool", "dependency-summary.json");
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
            JSONArray raw = root.optJSONArray("dependencies");
            if (raw == null) {
                return null;
            }
            JSONArray normalized = new JSONArray();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject dep = raw.optJSONObject(i);
                if (dep == null) {
                    continue;
                }
                String group = dep.optString("group", "");
                String name = dep.optString("name", "");
                String version = dep.optString("version", "");
                String id = joinId(group, name, version);
                JSONObject normalizedDep = new JSONObject();
                normalizedDep.put("id", id);
                normalizedDep.put("group", group);
                normalizedDep.put("name", name);
                normalizedDep.put("version", version);
                normalizedDep.put("licenses", dep.optJSONArray("licenses") == null ? new JSONArray() : dep.optJSONArray("licenses"));
                if (dep.has("direct")) {
                    normalizedDep.put("direct", dep.optBoolean("direct", false));
                }
                String introducedBy = dep.optString("introducedBy", "").trim();
                if (!introducedBy.isBlank()) {
                    normalizedDep.put("introducedBy", introducedBy);
                }
                JSONArray dependencyPath = dep.optJSONArray("dependencyPath");
                if (dependencyPath != null) {
                    normalizedDep.put("dependencyPath", dependencyPath);
                }
                normalized.put(normalizedDep);
            }
            return normalized;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildDependencyTreeFromSummary(JSONArray dependencies) {
        TreeNode root = new TreeNode("");
        for (int i = 0; i < dependencies.length(); i++) {
            JSONObject dependency = dependencies.optJSONObject(i);
            if (dependency == null) {
                continue;
            }
            JSONArray path = dependency.optJSONArray("dependencyPath");
            List<String> pathSteps = new ArrayList<>();
            if (path != null) {
                for (int j = 0; j < path.length(); j++) {
                    String step = path.optString(j, "").trim();
                    if (!step.isBlank()) {
                        pathSteps.add(step);
                    }
                }
            }
            if (pathSteps.isEmpty()) {
                String id = dependency.optString("id", "").trim();
                if (!id.isBlank()) {
                    pathSteps.add(id);
                }
            }
            if (pathSteps.isEmpty()) {
                continue;
            }

            TreeNode current = root;
            for (String step : pathSteps) {
                current = current.children.computeIfAbsent(step, TreeNode::new);
            }
            current.dependency = dependency;
        }

        List<String> lines = new ArrayList<>();
        for (TreeNode child : root.children.values()) {
            appendTreeLines(child, 0, lines);
        }
        return String.join("\n", lines).trim();
    }

    private Map<String, JSONObject> buildDependencyIndex(JSONArray dependencies) {
        Map<String, JSONObject> index = new LinkedHashMap<>();
        if (dependencies == null) {
            return index;
        }
        for (int i = 0; i < dependencies.length(); i++) {
            JSONObject dependency = dependencies.optJSONObject(i);
            if (dependency == null) {
                continue;
            }
            String id = dependency.optString("id", "").trim();
            if (!id.isBlank()) {
                index.put(id, dependency);
            }
        }
        return index;
    }

    private String formatDependencyTreeLine(String line, Map<String, JSONObject> summaryIndex) {
        int idx = 0;
        while (idx < line.length()) {
            char c = line.charAt(idx);
            if (Character.isLetterOrDigit(c)) {
                break;
            }
            idx++;
        }
        String prefix = line.substring(0, idx);
        String coordinates = line.substring(idx).trim();
        String simplified = simplifyTreeCoordinates(coordinates, summaryIndex);
        int depth = prefix.length() / 3;
        if (depth <= 0) {
            return simplified;
        }
        return "  ".repeat(Math.max(0, depth - 1)) + "- " + simplified;
    }

    private String simplifyTreeCoordinates(String coordinates, Map<String, JSONObject> summaryIndex) {
        String[] parts = coordinates.split(":");
        if (parts.length < 4) {
            return coordinates;
        }
        String group = parts[0].trim();
        String artifact = parts[1].trim();
        String version = parts.length >= 5 ? parts[parts.length - 2].trim() : parts[3].trim();
        if (group.isBlank() || artifact.isBlank() || version.isBlank()) {
            return coordinates;
        }
        String id = joinId(group, artifact, version);
        StringBuilder builder = new StringBuilder(id);
        String licenses = formatLicenseSummary(summaryIndex.get(id));
        if (!licenses.isBlank()) {
            builder.append(" [").append(licenses).append("]");
        }
        return builder.toString();
    }

    private void appendTreeLines(TreeNode node, int depth, List<String> lines) {
        String indent = "  ".repeat(Math.max(0, depth));
        StringBuilder line = new StringBuilder(indent).append("- ").append(node.id);
        String licenses = formatLicenseSummary(node.dependency);
        if (!licenses.isBlank()) {
            line.append(" [").append(licenses).append("]");
        }
        lines.add(line.toString());
        for (TreeNode child : node.children.values()) {
            appendTreeLines(child, depth + 1, lines);
        }
    }

    private String formatLicenseSummary(JSONObject dependency) {
        if (dependency == null) {
            return "";
        }
        JSONArray licenses = dependency.optJSONArray("licenses");
        if (licenses == null || licenses.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < licenses.length(); i++) {
            JSONObject license = licenses.optJSONObject(i);
            if (license == null) {
                continue;
            }
            String value = license.optString("type", "").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return String.join(", ", values);
    }

    private JSONArray loadDependenciesFromBom() {
        Path path = resolveProjectFile(".license-tool", "bom.xml");
        if (path == null || !Files.exists(path)) {
            return new JSONArray();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(path.toFile());
            JSONArray dependencies = new JSONArray();
            for (Element component : SbomComponentExtractor.extractDependencyComponents(document)) {
                String group = textContent(component, "group");
                String name = textContent(component, "name");
                String version = textContent(component, "version");
                JSONObject dependency = new JSONObject();
                dependency.put("id", joinId(group, name, version));
                dependency.put("group", group);
                dependency.put("name", name);
                dependency.put("version", version);
                dependency.put("licenses", extractLicenses(component));
                dependencies.put(dependency);
            }
            return dependencies;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private JSONArray extractLicenses(Element component) {
        JSONArray licenses = new JSONArray();
        NodeList licenseNodes = component.getElementsByTagName("license");
        for (int i = 0; i < licenseNodes.getLength(); i++) {
            Element license = (Element) licenseNodes.item(i);
            JSONObject info = new JSONObject();
            info.put("type", firstNonBlank(
                    textContent(license, "id"),
                    textContent(license, "name"),
                    textContent(license, "expression")
            ));
            String url = textContent(license, "url");
            if (!url.isBlank()) {
                info.put("url", url);
            }
            licenses.put(info);
        }
        return licenses;
    }

    private String textContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || nodes.item(0) == null) {
            return "";
        }
        String value = nodes.item(0).getTextContent();
        return value == null ? "" : value.trim();
    }

    private String joinId(String group, String name, String version) {
        List<String> parts = new ArrayList<>();
        if (group != null && !group.isBlank()) {
            parts.add(group.trim());
        }
        if (name != null && !name.isBlank()) {
            parts.add(name.trim());
        }
        if (version != null && !version.isBlank()) {
            parts.add(version.trim());
        }
        return String.join(":", parts);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private Path resolveProjectFile(String first, String second) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return null;
        }
        return Path.of(basePath, first, second);
    }

    private static final class TreeNode {
        private final String id;
        private final Map<String, TreeNode> children = new LinkedHashMap<>();
        private JSONObject dependency;

        private TreeNode(String id) {
            this.id = id;
        }
    }
}
