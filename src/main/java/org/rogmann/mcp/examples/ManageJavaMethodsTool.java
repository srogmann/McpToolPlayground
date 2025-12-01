package org.rogmann.mcp.examples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.rogmann.llmva4j.mcp.McpToolImplementation;
import org.rogmann.llmva4j.mcp.McpToolInputSchema;
import org.rogmann.llmva4j.mcp.McpToolInterface;
import org.rogmann.llmva4j.mcp.McpToolPropertyDescription;

/**
 * MCP tool implementation for managing methods in a Java class file.
 * Supports adding, replacing, or deleting methods based on parameters.
 * Security checks ensure only allowed projects and safe paths are accessed.
 */
public class ManageJavaMethodsTool implements McpToolImplementation {

    private static final Logger LOGGER = Logger.getLogger(ManageJavaMethodsTool.class.getName());

    private static final String NAME = "manage_java_methods";

    private final McpToolInterface toolInterface;

    /**
     * Constructs the tool and initializes its interface description.
     */
    public ManageJavaMethodsTool() {
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
        McpToolPropertyDescription methodDesc = new McpToolPropertyDescription(
            "string",
            "Name of the method, including signature in case of ambiguity (e.g., update(String, int))"
        );
        McpToolPropertyDescription textDesc = new McpToolPropertyDescription(
            "string",
            "Full text of the method including Javadoc, required for ADD and REPLACE"
        );
        McpToolPropertyDescription afterMethodDesc = new McpToolPropertyDescription(
            "string",
            "Optional method name after which the new method should be inserted (for ADD)"
        );
        McpToolPropertyDescription beforeMethodDesc = new McpToolPropertyDescription(
            "string",
            "Optional method name before which the new method should be inserted (for ADD)"
        );
        McpToolPropertyDescription fieldTextDesc = new McpToolPropertyDescription(
                "string",
                "Optional full text of fields to be added including JavaDoc (for ADD only)"
            );

        Map<String, McpToolPropertyDescription> properties = Map.of(
            "projectName", projectNameDesc,
            "pathInProject", pathInProjectDesc,
            "action", actionDesc,
            "method", methodDesc,
            "text", textDesc,
            "afterMethod", afterMethodDesc,
            "beforeMethod", beforeMethodDesc,
            "fieldText", fieldTextDesc
        );

        List<String> requiredFields = List.of("projectName", "pathInProject", "action", "method");

        McpToolInputSchema inputSchema = new McpToolInputSchema("object", properties, requiredFields);

        this.toolInterface = new McpToolInterface(
            NAME,
            "Manage Java Methods",
            "Adds, replaces, or deletes a method in a Java class file with safety checks on project access and path traversal.",
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
        String methodName = (String) arguments.get("method");
        String methodText = (String) arguments.get("text");
        String afterMethod = (String) arguments.get("afterMethod");
        String beforeMethod = (String) arguments.get("beforeMethod");
        String fieldText = (String) arguments.get("fieldText");
        

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
            LOGGER.info("Cannot manage methods in a directory: " + targetFile);
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
                    if (methodText == null || methodText.trim().isEmpty()) {
                        result.put("error", "Parameter 'text' is required for action ADD");
                        LOGGER.warning("Missing 'text' parameter for ADD action in file: " + targetFile);
                        return List.of(result);
                    }
                    modifiedLines = addMethod(lines, methodName, methodText, afterMethod, beforeMethod);
                    
                    if (fieldText != null) {
                        modifiedLines = ManageJavaFieldsTool.addField(modifiedLines, fieldText, null, null);
                    }
                    break;
                case "REPLACE":
                    if (methodText == null || methodText.trim().isEmpty()) {
                        result.put("error", "Parameter 'text' is required for action REPLACE");
                        LOGGER.warning("Missing 'text' parameter for REPLACE action in file: " + targetFile);
                        return List.of(result);
                    }
                    modifiedLines = replaceMethod(lines, methodName, methodText);
                    break;
                case "DELETE":
                    modifiedLines = deleteMethod(lines, methodName);
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
            result.put("message", "Successfully performed " + action + " on method '" + methodName + "' in file: " + projectBaseDir.relativize(targetFile));
            LOGGER.info("Successfully performed " + action + " on method '" + methodName + "' in file: " + targetFile);
        } catch (IOException e) {
            result.put("error", "Failed to write file '" + pathInProject);
            LOGGER.severe("IOException while writing file " + targetFile + ": " + e.getMessage());
            throw new UncheckedIOException(e);
        }

        return List.of(result);
    }

    /**
     * Adds a method into the list of source lines.
     * The insertion point can be specified relative to another method.
     *
     * @param lines source lines
     * @param methodName name of method to add
     * @param methodText full method text including Javadoc
     * @param afterMethod optional method name after which to insert
     * @param beforeMethod optional method name before which to insert
     * @return modified lines
     */
    static List<String> addMethod(List<String> lines, String methodName, String methodText,
                                   String afterMethod, String beforeMethod) {
        int insertIndex = -1;

        if (afterMethod != null && !afterMethod.isEmpty()) {
            insertIndex = findEndOfMethod(lines, afterMethod);
            if (insertIndex == -1) {
                throw new IllegalArgumentException("Method '" + afterMethod + "' not found for positioning");
            }
            insertIndex++;
        } else if (beforeMethod != null && !beforeMethod.isEmpty()) {
            insertIndex = findStartOfMethod(lines, beforeMethod);
            if (insertIndex == -1) {
                throw new IllegalArgumentException("Method '" + beforeMethod + "' not found for positioning");
            }
        } else {
            // Default: insert before last closing brace of the class
            insertIndex = findInsertionPointForLastMethod(lines);
        }

        if (insertIndex == -1) {
            throw new IllegalArgumentException("Could not determine insertion point for method '" + methodName + "'");
        }

        List<String> methodLines = methodText.lines().collect(Collectors.toList());
        List<String> result = new java.util.ArrayList<>(lines.size() + methodLines.size() + 1);
        result.addAll(lines.subList(0, insertIndex));
        result.addAll(methodLines);
        if (insertIndex < lines.size()) {
            result.addAll(lines.subList(insertIndex, lines.size()));
        }

        return result;
    }

    /**
     * Replaces an existing method with new text.
     *
     * @param lines source lines
     * @param methodName name of method to replace
     * @param methodText new method text including Javadoc
     * @return modified lines
     */
    static List<String> replaceMethod(List<String> lines, String methodName, String methodText) {
        int start = findStartOfMethod(lines, methodName);
        if (start == -1) {
            throw new IllegalArgumentException("Method '" + methodName + "' not found for replacement");
        }
        int end = findEndOfMethod(lines, methodName);
        if (end == -1) {
            throw new IllegalArgumentException("Could not find end of method '" + methodName + "'");
        }

        List<String> result = new java.util.ArrayList<>();
        result.addAll(lines.subList(0, start));
        result.addAll(methodText.lines().collect(Collectors.toList()));
        result.addAll(lines.subList(end + 1, lines.size()));
        return result;
    }

    /**
     * Deletes a method from the source.
     *
     * @param lines source lines
     * @param methodName name of method to delete
     * @return modified lines
     */
    static List<String> deleteMethod(List<String> lines, String methodName) {
        int start = findStartOfMethod(lines, methodName);
        if (start == -1) {
            throw new IllegalArgumentException("Method '" + methodName + "' not found for deletion");
        }
        int end = findEndOfMethod(lines, methodName);
        if (end == -1) {
            throw new IllegalArgumentException("Could not find end of method '" + methodName + "'");
        }

        List<String> result = new java.util.ArrayList<>();
        result.addAll(lines.subList(0, start));
        result.addAll(lines.subList(end + 1, lines.size()));
        return result;
    }

    /**
     * Finds the starting line index of a method by name/signature.
     *
     * @param lines source lines
     * @param methodName method name with optional signature
     * @return index of the first line of the method, or -1 if not found
     */
    static int findStartOfMethod(List<String> lines, String methodName) {
        String cleanName = stripMethodName(methodName);
        int parenthesesCount = countParametersInSignature(methodName);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("public") || line.startsWith("private") || line.startsWith("protected")) {
                if (line.contains(cleanName + "(")) {
                    int count = countOpeningParentheses(line);
                    if (count == 1 && hasMatchingParameters(line, cleanName, parenthesesCount)) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Finds the last line index (closing brace) of a method.
     *
     * @param lines source lines
     * @param methodName method name with optional signature
     * @return index of the closing brace of the method, or -1 if not found
     */
    static int findEndOfMethod(List<String> lines, String methodName) {
        int start = findStartOfMethod(lines, methodName);
        if (start == -1) {
            return -1;
        }

        int braceCount = 0;
        boolean inMethod = false;

        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            int openBraces = (int) line.chars().filter(ch -> ch == '{').count();
            int closeBraces = (int) line.chars().filter(ch -> ch == '}').count();

            if (!inMethod) {
                if (line.contains("{")) {
                    braceCount += openBraces - closeBraces;
                    if (braceCount > 0) {
                        inMethod = true;
                    }
                }
            } else {
                braceCount += openBraces - closeBraces;
                if (braceCount <= 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Determines the default insertion point before the last closing brace of the class.
     *
     * @param lines source lines
     * @return line index for insertion
     */
    static int findInsertionPointForLastMethod(List<String> lines) {
        // Find last '}' that is not inside a method
        int lastClassBrace = -1;
        int braceCount = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int open = (int) line.chars().filter(ch -> ch == '{').count();
            int close = (int) line.chars().filter(ch -> ch == '}').count();

            braceCount += open - close;

            if (braceCount == 0 && close > 0) {
                lastClassBrace = i;
            }
        }

        if (lastClassBrace > 0) {
            return lastClassBrace;
        }

        // Fallback: insert at end
        return lines.size();
    }

    /**
     * Extracts method name without signature.
     *
     * @param methodSig method name with optional signature
     * @return method name
     */
    static String stripMethodName(String methodSig) {
        int parenIndex = methodSig.indexOf('(');
        if (parenIndex != -1) {
            return methodSig.substring(0, parenIndex).trim();
        }
        return methodSig.trim();
    }

    /**
     * Counts the number of parameters in a method signature.
     *
     * @param methodSig method signature
     * @return number of parameters
     */
    static int countParametersInSignature(String methodSig) {
        int parenIndex = methodSig.indexOf('(');
        if (parenIndex == -1) {
            return 0;
        }
        String paramsPart = methodSig.substring(parenIndex + 1, methodSig.lastIndexOf(')'));
        if (paramsPart.trim().isEmpty()) {
            return 0;
        }
        return (int) IntStream.range(0, paramsPart.length())
                .filter(i -> paramsPart.charAt(i) == ',')
                .count() + 1;
    }

    /**
     * Counts opening parentheses in a line.
     *
     * @param line source line
     * @return count of '('
     */
    static int countOpeningParentheses(String line) {
        return (int) line.chars().filter(ch -> ch == '(').count();
    }

    /**
     * Checks whether the line likely contains a method declaration matching the expected name and parameter count.
     *
     * @param line source line
     * @param methodName method name
     * @param expectedParams expected number of parameters
     * @return true if likely matches
     */
    static boolean hasMatchingParameters(String line, String methodName, int expectedParams) {
        if (!line.contains(methodName + "(")) {
            return false;
        }
        int paramCount = countParametersInSignature(line.substring(line.indexOf(methodName)));
        return paramCount == expectedParams;
    }
}
