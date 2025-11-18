package org.rogmann.mcp.examples;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.rogmann.llmva4j.mcp.McpToolImplementation;
import org.rogmann.llmva4j.mcp.McpToolInterface;
import org.rogmann.llmva4j.mcp.McpToolInputSchema;
import org.rogmann.llmva4j.mcp.McpToolPropertyDescription;

/**
 * MCP tool implementation for reading text from a file in a project.
 * The tool ensures that only allowed projects and safe paths are accessed.
 * It supports limiting the number of lines read from the file.
 */
public class ReadTextFileTool implements McpToolImplementation {

    private static final Logger LOGGER = Logger.getLogger(ReadTextFileTool.class.getName());

    private static final String NAME = "get_file_text_by_path";

    private final McpToolInterface toolInterface;

    /**
     * Constructs the tool and initializes its interface description.
     */
    public ReadTextFileTool() {
        McpToolPropertyDescription projectNameDesc = new McpToolPropertyDescription(
            "string",
            "Name of the project"
        );
        McpToolPropertyDescription pathInProjectDesc = new McpToolPropertyDescription(
            "string",
            "Path of the file relative to the project directory"
        );
        McpToolPropertyDescription maxLinesCountDesc = new McpToolPropertyDescription(
            "integer",
            "Optional maximum number of lines to read from the file"
        );

        Map<String, McpToolPropertyDescription> properties = Map.of(
            "projectName", projectNameDesc,
            "pathInProject", pathInProjectDesc,
            "maxLinesCount", maxLinesCountDesc
        );

        List<String> requiredFields = List.of("projectName", "pathInProject");

        McpToolInputSchema inputSchema = new McpToolInputSchema("object", properties, requiredFields);

        this.toolInterface = new McpToolInterface(
            NAME,
            "Read File Text by Path",
            "Reads text content from a file in the specified project if access conditions are met.",
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
        Integer maxLinesCount = (Integer) arguments.get("maxLinesCount");

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
            result.put("error", "Project base directory does not exist: " + projectBaseDir);
            LOGGER.severe("Project base directory does not exist: " + projectBaseDir);
            return List.of(result);
        }

        String projectFilterProp = System.getProperty("IDE_PROJECT_FILTER");
        Pattern projectFilterPattern = null;
        if (projectFilterProp != null && !projectFilterProp.isBlank()) {
            try {
                projectFilterPattern = Pattern.compile(projectFilterProp);
            } catch (PatternSyntaxException e) {
                result.put("error", "Invalid regex in IDE_PROJECT_FILTER: " + e.getMessage());
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
            result.put("error", "Project directory does not exist: " + projectDir);
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
            result.put("error", "File does not exist: " + targetFile);
            LOGGER.info("File not found: " + targetFile);
            return List.of(result);
        }

        if (Files.isDirectory(targetFile)) {
            result.put("error", "Path refers to a directory, not a file: " + targetFile);
            LOGGER.info("Cannot read text from directory: " + targetFile);
            return List.of(result);
        }

        try (BufferedReader reader = Files.newBufferedReader(targetFile)) {
            StringBuilder content = new StringBuilder();
            String line;
            int linesRead = 0;
            int maxLines = Optional.ofNullable(maxLinesCount).orElse(Integer.MAX_VALUE);

            while ((line = reader.readLine()) != null && linesRead < maxLines) {
                if (linesRead > 0) {
                    content.append(System.lineSeparator());
                }
                content.append(line);
                linesRead++;
            }

            result.put("status", "success");
            result.put("text", content.toString());
            result.put("linesRead", linesRead);
            result.put("message", "Successfully read from file: " + targetFile);
            LOGGER.fine("Successfully read " + linesRead + " lines from file: " + targetFile);
        } catch (IOException e) {
            result.put("error", "Failed to read file: " + e.getMessage());
            LOGGER.severe("IOException while reading file " + targetFile + ": " + e.getMessage());
            return List.of(result);
        }

        return List.of(result);
    }
}
