package org.rogmann.mcp.examples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import org.rogmann.llmva4j.mcp.McpToolImplementation;
import org.rogmann.llmva4j.mcp.McpToolInputSchema;
import org.rogmann.llmva4j.mcp.McpToolInterface;
import org.rogmann.llmva4j.mcp.McpToolPropertyDescription;

/**
 * MCP tool implementation for managing fields in a Java class file.
 * Supports adding multiple fields, or replacing/deleting a single field.
 * Security checks ensure only allowed projects and safe paths are accessed.
 */
public class ManageJavaFieldsTool implements McpToolImplementation {

    private static final Logger LOGGER = Logger.getLogger(ManageJavaFieldsTool.class.getName());

    private static final String NAME = "manage_java_fields";

    private final McpToolInterface toolInterface;

    /**
     * Constructs the tool and initializes its interface description.
     */
    public ManageJavaFieldsTool() {
        McpToolPropertyDescription projectNameDesc = new McpToolPropertyDescription(
            "string",
            "Name of the project"
        );
        McpToolPropertyDescription pathInProjectDesc = new McpToolPropertyDescription(
            "string",
            "Path of the Java class file relative to the project directory"
        );
        McpToolPropertyDescription actionDesc = new McpToolPropertyDescription(
            "string",
            "Action to perform: ADD, REPLACE, or DELETE"
        );
        McpToolPropertyDescription fieldDesc = new McpToolPropertyDescription(
            "string",
            "Name of the field to replace or delete (required for REPLACE and DELETE)"
        );
        McpToolPropertyDescription textDesc = new McpToolPropertyDescription(
            "string",
            "Full text of field(s), including Javadoc; for ADD, multiple fields can be provided as a single string"
        );
        McpToolPropertyDescription afterFieldDesc = new McpToolPropertyDescription(
            "string",
            "Optional field name after which the new field(s) should be inserted (for ADD)"
        );
        McpToolPropertyDescription beforeFieldDesc = new McpToolPropertyDescription(
            "string",
            "Optional field name before which the new field(s) should be inserted (for ADD)"
        );

        Map<String, McpToolPropertyDescription> properties = Map.of(
            "projectName", projectNameDesc,
            "pathInProject", pathInProjectDesc,
            "action", actionDesc,
            "field", fieldDesc,
            "text", textDesc,
            "afterField", afterFieldDesc,
            "beforeField", beforeFieldDesc
        );

        List<String> requiredFields = List.of("projectName", "pathInProject", "action");

        // Add conditional requirements based on action
        // Note: Full validation is done in call() due to dynamic requirements

        McpToolInputSchema inputSchema = new McpToolInputSchema("object", properties, requiredFields);

        this.toolInterface = new McpToolInterface(
            NAME,
            "Manage Java Fields",
            "Adds, replaces, or deletes a field in a Java class file with safety checks on project access and path traversal.",
            inputSchema
        );
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public McpToolInterface getTool() {
        return toolInterface;
    }

    @Override
    public List<Map<String, Object>> call(Map<String, Object> params) {
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

        String projectName = (String) arguments.get("projectName");
        String pathInProject = (String) arguments.get("pathInProject");
        String action = (String) arguments.get("action");
        String fieldName = (String) arguments.get("field");
        String fieldText = (String) arguments.get("text");
        String afterField = (String) arguments.get("afterField");
        String beforeField = (String) arguments.get("beforeField");

        Map<String, Object> result = new HashMap<>();
        result.put("status", "failed");

        String projectDirProp = System.getProperty("IDE_PROJECT_DIR");
        if (projectDirProp == null || projectDirProp.isBlank()) {
            result.put("error", "System property IDE_PROJECT_DIR is not set");
            LOGGER.severe("IDE_PROJECT_DIR system property is not defined.");
            return List.of(result);
        }

        Path projectBaseDir = Paths.get(projectDirProp).toAbsolutePath().normalize();
        if (!Files.exists(projectBaseDir)) {
            result.put("error", "Project base directory does not exist.");
            LOGGER.severe("Project base directory does not exist: " + projectBaseDir);
            return List.of(result);
        }

        String projectFilterProp = System.getProperty("IDE_PROJECT_FILTER");
        Pattern projectFilterPattern = null;
        if (projectFilterProp != null && !projectFilterProp.isBlank()) {
            try {
                projectFilterPattern = Pattern.compile(projectFilterProp);
            } catch (PatternSyntaxException e) {
                result.put("error", "Invalid regex in IDE_PROJECT_FILTER.");
                LOGGER.severe("Invalid regex in IDE_PROJECT_FILTER: " + e.getMessage());
                return List.of(result);
            }
        }

        if (projectFilterPattern != null && !projectFilterPattern.matcher(projectName).matches()) {
            result.put("error", "Project name '" + projectName + "' is not allowed.");
            LOGGER.warning("Access denied to project '" + projectName + "' due to filter.");
            return List.of(result);
        }

        Path projectDir = projectBaseDir.resolve(projectName).normalize();
        if (!projectDir.startsWith(projectBaseDir)) {
            result.put("error", "Project directory is outside base directory, access denied");
            LOGGER.warning("Attempted directory traversal in project name: " + projectName);
            return List.of(result);
        }

        if (!Files.exists(projectDir)) {
            result.put("error", "Project directory does not exist: project " + projectName);
            LOGGER.severe("Project directory does not exist: " + projectDir);
            return List.of(result);
        }

        Path targetFile = projectDir.resolve(pathInProject).normalize();
        if (!targetFile.startsWith(projectDir)) {
            result.put("error", "Path traversal detected in pathInProject: " + projectName);
            LOGGER.warning("Path traversal attempt detected: " + pathInProject);
            return List.of(result);
        }

        if (!Files.exists(targetFile)) {
            result.put("error", "File does not exist: " + targetFile);
            LOGGER.info("File not found: " + targetFile);
            return List.of(result);
        }

        if (Files.isDirectory(targetFile)) {
            result.put("error", "Path refers to a directory, not a file: " + projectBaseDir.relativize(targetFile));
            LOGGER.info("Cannot manage fields in a directory: " + targetFile);
            return List.of(result);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(targetFile);
        } catch (IOException e) {
            result.put("error", "Failed to read file " + projectBaseDir.relativize(targetFile));
            LOGGER.severe("IOException while reading file " + targetFile + ": " + e.getMessage());
            return List.of(result);
        }

        List<String> modifiedLines;
        try {
            switch (action.toUpperCase()) {
                case "ADD":
                    if (fieldText == null || fieldText.trim().isEmpty()) {
                        result.put("error", "Parameter 'text' is required for action ADD");
                        LOGGER.warning("Missing 'text' parameter for ADD action in file: " + targetFile);
                        return List.of(result);
                    }
                    modifiedLines = addField(lines, fieldText, afterField, beforeField);
                    break;
                case "REPLACE":
                    if (fieldName == null || fieldName.trim().isEmpty()) {
                        result.put("error", "Parameter 'field' is required for action REPLACE");
                        LOGGER.warning("Missing 'field' parameter for REPLACE action in file: " + targetFile);
                        return List.of(result);
                    }
                    if (fieldText == null || fieldText.trim().isEmpty()) {
                        result.put("error", "Parameter 'text' is required for action REPLACE");
                        LOGGER.warning("Missing 'text' parameter for REPLACE action in file: " + targetFile);
                        return List.of(result);
                    }
                    modifiedLines = replaceField(lines, fieldName, fieldText);
                    break;
                case "DELETE":
                    if (fieldName == null || fieldName.trim().isEmpty()) {
                        result.put("error", "Parameter 'field' is required for action DELETE");
                        LOGGER.warning("Missing 'field' parameter for DELETE action in file: " + targetFile);
                        return List.of(result);
                    }
                    modifiedLines = deleteField(lines, fieldName);
                    break;
                default:
                    result.put("error", "Invalid action: " + action + ". Supported values: ADD, REPLACE, DELETE");
                    LOGGER.warning("Invalid action specified: " + action);
                    return List.of(result);
            }
        } catch (IllegalArgumentException e) {
            result.put("error", "Error while modifying file '" + pathInProject + "': " + e.getMessage());
            LOGGER.warning(e.getMessage());
            return List.of(result);
        }

        try {
            Files.write(targetFile, modifiedLines, StandardOpenOption.TRUNCATE_EXISTING);
            result.put("status", "success");
            result.put("message", "Successfully performed " + action + " on field '" + (fieldName != null ? fieldName : "<multiple>") +
                    "' in file: " + projectBaseDir.relativize(targetFile));
            LOGGER.info("Successfully performed " + action + " on field '" + (fieldName != null ? fieldName : "<multiple>") +
                    "' in file: " + targetFile);
        } catch (IOException e) {
            result.put("error", "Failed to write file '" + pathInProject + "'");
            LOGGER.severe("IOException while writing file " + targetFile + ": " + e.getMessage());
            throw new UncheckedIOException(e);
        }

        return List.of(result);
    }

    /**
     * Adds one or more fields into the list of source lines.
     * The insertion point can be specified relative to another field.
     *
     * @param lines source lines
     * @param fieldText full text of field(s) including Javadoc
     * @param afterField optional field name after which to insert
     * @param beforeField optional field name before which to insert
     * @return modified lines
     */
    static List<String> addField(List<String> lines, String fieldText,
                                 String afterField, String beforeField) {
        int insertIndex = -1;

        if (afterField != null && !afterField.isEmpty()) {
            insertIndex = findEndOfField(lines, afterField);
            if (insertIndex == -1) {
                throw new IllegalArgumentException("Field '" + afterField + "' not found for positioning");
            }
            insertIndex++;
        } else if (beforeField != null && !beforeField.isEmpty()) {
            insertIndex = findStartOfField(lines, beforeField);
            if (insertIndex == -1) {
                throw new IllegalArgumentException("Field '" + beforeField + "' not found for positioning");
            }
        } else {
            // Default: insert after last field or at beginning of class body
            insertIndex = findInsertionPointForFields(lines);
        }

        if (insertIndex == -1) {
            throw new IllegalArgumentException("Could not determine insertion point for new field(s)");
        }

        String indentation = computeIndentation(lines);
        List<String> fieldLines = fieldText.lines().map(line -> insertIndentation(line, indentation)).collect(Collectors.toList());
        List<String> result = new ArrayList<>(lines.size() + fieldLines.size() + 1);
        result.addAll(lines.subList(0, insertIndex));
        result.addAll(fieldLines);
        if (insertIndex < lines.size()) {
            result.addAll(lines.subList(insertIndex, lines.size()));
        }

        return result;
    }

    /**
     * Replaces an existing field with new text.
     *
     * @param lines source lines
     * @param fieldName name of field to replace
     * @param fieldText new field text including Javadoc
     * @return modified lines
     */
    static List<String> replaceField(List<String> lines, String fieldName, String fieldText) {
        int start = findStartOfField(lines, fieldName);
        if (start == -1) {
            throw new IllegalArgumentException("Field '" + fieldName + "' not found for replacement");
        }
        int end = findEndOfField(lines, fieldName);
        if (end == -1) {
            throw new IllegalArgumentException("Could not find end of field '" + fieldName + "'");
        }

        String indentation = computeIndentation(lines);
        List<String> fieldLines = fieldText.lines().map(line -> insertIndentation(line, indentation)).collect(Collectors.toList());

        List<String> result = new ArrayList<>();
        result.addAll(lines.subList(0, start));
        result.addAll(fieldLines);
        result.addAll(lines.subList(end + 1, lines.size()));
        return result;
    }

    /**
     * Deletes a field from the source.
     *
     * @param lines source lines
     * @param fieldName name of field to delete
     * @return modified lines
     */
    static List<String> deleteField(List<String> lines, String fieldName) {
        int start = findStartOfField(lines, fieldName);
        if (start == -1) {
            throw new IllegalArgumentException("Field '" + fieldName + "' not found for deletion");
        }
        int end = findEndOfField(lines, fieldName);
        if (end == -1) {
            throw new IllegalArgumentException("Could not find end of field '" + fieldName + "'");
        }

        List<String> result = new ArrayList<>();
        result.addAll(lines.subList(0, start));
        result.addAll(lines.subList(end + 1, lines.size()));
        return result;
    }

    /**
     * Finds the starting line index of a field by name.
     *
     * @param lines source lines
     * @param fieldName field name
     * @return index of the first line of the field, or -1 if not found
     */
    static int findStartOfField(List<String> lines, String fieldName) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (isFieldLine(line)
                    && (line.contains(" " + fieldName + " ") || line.contains(" " + fieldName + "=") || line.contains(" " + fieldName + ";"))) {
                // Check if it's not part of a comment or string
                if (!isInCommentOrString(line, fieldName)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Finds the last line index of a field (ending with semicolon).
     *
     * @param lines source lines
     * @param fieldName field name
     * @return index of the line containing the field's semicolon, or -1 if not found
     */
    static int findEndOfField(List<String> lines, String fieldName) {
        int start = findStartOfField(lines, fieldName);
        if (start == -1) {
            return -1;
        }

        // Simple case: field ends on same line
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            int semicolonIndex = line.indexOf(';');
            if (semicolonIndex != -1) {
                // Verify that the field name appears before the semicolon and not in comment
                String prefix = line.substring(0, semicolonIndex);
                if (prefix.contains(fieldName) && !isInCommentOrString(prefix, fieldName)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Determines the default insertion point for fields (after last field or after class opening brace).
     *
     * @param lines source lines
     * @return line index for insertion
     */
    static int findInsertionPointForFields(List<String> lines) {
        // Look for last field
        int lastFieldEnd = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (isFieldLine(line)) {
                // Find line with semicolon
                for (int j = i; j < lines.size(); j++) {
                    if (lines.get(j).contains(";")) {
                        lastFieldEnd = j;
                        break;
                    }
                }
            }
        }

        if (lastFieldEnd != -1) {
            return lastFieldEnd + 1;
        }

        // Fallback: after class opening brace
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().endsWith("{")) {
                return i + 1;
            }
        }

        // Last resort
        return 1;
    }

    /**
     * Checks whether a line likely contains a field declaration.
     *
     * @param line trimmed source line
     * @return true if line starts with access modifier or type and ends with semicolon
     */
    static boolean isFieldLine(String line) {
        if (!line.replaceFirst("[ \t]*//.*", "").endsWith(";")) {
            return false;
        }
        return line.startsWith("public ") ||
               line.startsWith("private ") ||
               line.startsWith("protected ") ||
               line.startsWith("static ") ||
               line.matches("^[a-zA-Z<>\\[\\]\\?\\s]+\\s+[a-zA-Z][a-zA-Z0-9_]*\\s*=") ||
               line.matches("^[a-zA-Z<>\\[\\]\\?\\s]+\\s+[a-zA-Z][a-zA-Z0-9_]*;") ||
               line.matches("^final\\s+[a-zA-Z].*");
    }

    /**
     * Checks if a field name appears inside a comment or string literal.
     * This is a simplified check.
     *
     * @param line source line
     * @param fieldName field name
     * @return true if likely inside comment or string
     */
    static boolean isInCommentOrString(String line, String fieldName) {
        int index = line.indexOf(" " + fieldName);
        if (index == -1) {
            return false;
        }

        // Check for comments
        if (line.substring(0, index).contains("//")) {
            return true;
        }
        int commentStart = line.indexOf("/*");
        int commentEnd = line.indexOf("*/");
        if (commentStart != -1 && commentEnd == -1 && commentStart < index) {
            return true;
        }
        if (commentStart != -1 && commentEnd != -1 && commentStart < index && index < commentEnd) {
            return true;
        }

        // Check for string literals (very basic)
        int quoteCountBefore = (int) line.substring(0, index).chars().filter(ch -> ch == '"').count();
        if (quoteCountBefore % 2 == 1) {
            return true;
        }

        return false;
    }

    /**
     * Compute the first indentation (if present).
     * @param lines source lines
     * @return indentation
     */
    static String computeIndentation(List<String> lines) {
        Set<String> setIndentations = new HashSet<>();
        lines.stream()
            .filter(line -> !line.isBlank()).filter(line -> line.startsWith(" ") || line.startsWith("\t"))
            .map(line -> line.replaceFirst("[^ \t\r\n].*", ""))
            .forEach(setIndentations::add);
        return setIndentations.stream()
            .sorted(Comparator.comparingInt(String::length).thenComparing(String::compareTo))
            .findFirst().orElse("");
    }

    /**
     * Inserts an indentation if necessary.
     * @param line source line
     * @param indentation indentation
     * @return source line
     */
    static String insertIndentation(String line, String indentation) {
        if ((!line.startsWith(" ") && !line.startsWith("\t")) && indentation != null && indentation.length() > 0) {
            return indentation + line;
        }
        return line;
    }
}
