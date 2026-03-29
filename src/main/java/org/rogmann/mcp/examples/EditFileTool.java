package org.rogmann.mcp.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.rogmann.llmva4j.mcp.McpToolImplementation;
import org.rogmann.llmva4j.mcp.McpToolInterface;
import org.rogmann.llmva4j.mcp.McpToolInputSchema;
import org.rogmann.llmva4j.mcp.McpToolPropertyDescription;

/**
 * MCP tool implementation for editing a file in a project using search-replace.
 * The tool ensures that only allowed projects and safe paths are handled.
 */
public class EditFileTool implements McpToolImplementation {

    private static final Logger LOGGER = Logger.getLogger(EditFileTool.class.getName());

    private static final String NAME = "edit_file";

    private final McpToolInterface toolInterface;

    /**
     * Constructs the tool and initializes its interface description.
     */
    public EditFileTool() {
        McpToolPropertyDescription projectNameDesc = new McpToolPropertyDescription(
            "string",
            "Name of the project"
        );
        McpToolPropertyDescription pathInProjectDesc = new McpToolPropertyDescription(
            "string",
            "Path of the file relative to the project directory"
        );
        McpToolPropertyDescription oldStringDesc = new McpToolPropertyDescription(
            "string",
            "The string to search for in the file"
        );
        McpToolPropertyDescription newStringDesc = new McpToolPropertyDescription(
            "string",
            "The string to replace the old string with"
        );
        McpToolPropertyDescription replaceAllDesc = new McpToolPropertyDescription(
            "boolean",
            "Whether to replace all occurrences (default: false)"
        );

        Map<String, McpToolPropertyDescription> properties = Map.of(
            "projectName", projectNameDesc,
            "pathInProject", pathInProjectDesc,
            "oldString", oldStringDesc,
            "newString", newStringDesc,
            "replaceAll", replaceAllDesc
        );

        List<String> requiredFields = List.of("projectName", "pathInProject", "oldString", "newString");

        McpToolInputSchema inputSchema = new McpToolInputSchema("object", properties, requiredFields);

        this.toolInterface = new McpToolInterface(
            NAME,
            "Edit File",
            "Edits a file in the specified project by searching and replacing text.",
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
        String oldString = (String) arguments.get("oldString");
        String newString = (String) arguments.get("newString");
        Boolean replaceAll = Optional.ofNullable((Boolean) arguments.get("replaceAll")).orElse(false);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "failed");

        // Validate oldString is not empty to prevent infinite loops or unintended behavior
        if (oldString == null || oldString.isEmpty()) {
            result.put("error", "oldString cannot be empty");
            LOGGER.warning("EditFileTool called with empty oldString");
            return List.of(result);
        }

        String projectDirProp = System.getProperty("IDE_PROJECT_DIR");
        if (projectDirProp == null || projectDirProp.isBlank()) {
            result.put("error", "System property IDE_PROJECT_DIR is not set");
            LOGGER.severe("IDE_PROJECT_DIR system property is not defined.");
            return List.of(result);
        }

        Path projectBaseDir = Paths.get(projectDirProp).toAbsolutePath().normalize();
        if (!Files.exists(projectBaseDir)) {
            result.put("error", "Project base directory does not exist");
            LOGGER.severe("Project base directory does not exist: " + projectBaseDir);
            return List.of(result);
        }

        String projectFilterProp = System.getProperty("IDE_PROJECT_FILTER");
        Pattern projectFilterPattern = null;
        if (projectFilterProp != null && !projectFilterProp.isBlank()) {
            try {
                projectFilterPattern = Pattern.compile(projectFilterProp);
            } catch (PatternSyntaxException e) {
                result.put("error", "Invalid regex in IDE_PROJECT_FILTER");
                LOGGER.severe("Invalid regex in IDE_PROJECT_FILTER: " + e.getMessage());
                return List.of(result);
            }
        }

        if (projectFilterPattern != null && !projectFilterPattern.matcher(projectName).matches()) {
            result.put("error", "Project name '" + projectName + "' is not allowed by filter");
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
            result.put("error", "Project directory does not exist: " + projectName);
            LOGGER.severe("Project directory does not exist: " + projectDir);
            return List.of(result);
        }

        Path targetFile = projectDir.resolve(pathInProject).normalize();
        if (!targetFile.startsWith(projectDir)) {
            result.put("error", "Path traversal detected in pathInProject: " + pathInProject);
            LOGGER.warning("Path traversal attempt detected: " + pathInProject);
            return List.of(result);
        }

        if (!Files.exists(targetFile)) {
            result.put("error", "File does not exist in project " + projectName + ": " + projectDir.relativize(targetFile));
            LOGGER.info("File not found for editing: " + targetFile);
            return List.of(result);
        }

        if (Files.isDirectory(targetFile)) {
            result.put("error", "Path refers to a directory, not a file: " + projectDir.relativize(targetFile));
            LOGGER.info("Cannot edit directory: " + targetFile);
            return List.of(result);
        }

        try {
            String content = Files.readString(targetFile);
            String newContent;
            int replacements = 0;

            if (replaceAll) {
                // Count occurrences
                int index = 0;
                while ((index = content.indexOf(oldString, index)) != -1) {
                    replacements++;
                    index += oldString.length();
                }
                
                if (replacements > 0) {
                    newContent = content.replace(oldString, newString);
                } else {
                    newContent = content;
                }
            } else {
                // Replace first occurrence only
                int index = content.indexOf(oldString);
                if (index != -1) {
                    replacements = 1;
                    newContent = content.substring(0, index) + newString + content.substring(index + oldString.length());
                } else {
                    newContent = content;
                }
            }

            if (replacements > 0) {
                Files.writeString(targetFile, newContent);
                result.put("status", "success");
                result.put("message", "Successfully replaced " + replacements + " occurrence(s) in file: " + projectDir.relativize(targetFile));
                LOGGER.info("Successfully edited file: " + targetFile + " (" + replacements + " replacements)");
            } else {
                // Find longest matching prefix and mismatch details for debugging
                findLongestPrefix(projectDir, targetFile, content, oldString, result);
            }
            
            result.put("replacements", replacements);

        } catch (IOException e) {
            result.put("error", "Failed to edit file: " + e.getMessage());
            LOGGER.log(Level.SEVERE, "IOException while editing file " + targetFile, e);
            return List.of(result);
        }

        return List.of(result);
    }

    private void findLongestPrefix(Path projectDir, Path targetFile, String content, String oldString,
            Map<String, Object> result) {
        int maxPrefixLength = 0;
        char searchedChar = 0;
        char actualChar = 0;
        int searchedCharUnicode = 0;
        int actualCharUnicode = 0;

        for (int i = 0; i < content.length(); i++) {
            int prefixLen = 0;
            for (int j = 0; j < oldString.length() && (i + j) < content.length(); j++) {
                char oldChar = oldString.charAt(j);
                char contentChar = content.charAt(i + j);
                
                if (oldChar == contentChar) {
                    prefixLen++;
                } else {
                    // Mismatch found
                    if (prefixLen >= maxPrefixLength) {
                        maxPrefixLength = prefixLen;
                        searchedChar = oldChar;
                        actualChar = contentChar;
                        searchedCharUnicode = (int) oldChar;
                        actualCharUnicode = (int) contentChar;
                    }
                    break;
                }
            }
            if (prefixLen == oldString.length()) {
                // Full match found (shouldn't happen since indexOf returned -1)
                break;
            }
        }

        result.put("status", "success");
        result.put("message", "No occurrences of oldString found in file: " + projectDir.relativize(targetFile) + 
            ". Longest matching prefix length: " + maxPrefixLength + 
            ", searched char: '" + searchedChar + "' (U+" + String.format("%04X", searchedCharUnicode) + ")" +
            ", actual char: '" + actualChar + "' (U+" + String.format("%04X", actualCharUnicode) + ")");
        LOGGER.info("No matches found in file: " + targetFile + 
            " (longest prefix: " + maxPrefixLength + 
            ", searched: U+" + String.format("%04X", searchedCharUnicode) + 
            ", actual: U+" + String.format("%04X", actualCharUnicode) + ")");
    }
}
