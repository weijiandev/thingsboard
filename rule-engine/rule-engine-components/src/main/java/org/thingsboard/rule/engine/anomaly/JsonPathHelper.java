package org.thingsboard.rule.engine.anomaly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;

final class JsonPathHelper {

    private JsonPathHelper() {
    }

    static Double getDouble(ObjectNode root, String path) {
        JsonNode node = getNode(root, path);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.getNodeType() == JsonNodeType.STRING) {
            try {
                return Double.parseDouble(node.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static JsonNode getNode(ObjectNode root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return null;
        }
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (String segment : segments) {
            if (current == null) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }

    static void ensurePath(ObjectNode root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return;
        }
        String[] segments = path.split("\\.");
        ObjectNode current = root;
        for (String segment : Arrays.copyOf(segments, segments.length - 1)) {
            JsonNode child = current.get(segment);
            if (!(child instanceof ObjectNode)) {
                child = current.putObject(segment);
            }
            current = (ObjectNode) child;
        }
    }
}
