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
 * MCP tool implementation for creating a new file in a project.
 * The tool ensures that only allowed projects and safe paths are handled.
 */
public class CreateNewFileTool implements McpToolImplementation {

    private static final Logger LOGGER = Logger.getLogger(CreateNewFileTool.class.getName());

    private static final String NAME = "create_new_file";

    private final McpToolInterface toolInterface;

    /**
     * Constructs the tool and initializes its interface description.
     */
    public CreateNewFileTool() {
        McpToolPropertyDescription projectNameDesc = new McpToolPropertyDescription(
            "string",
            "Name of the project"
        );
        McpToolPropertyDescription pathInProjectDesc = new McpToolPropertyDescription(
            "string",
            "Path of the file relative to the project directory"
        );
        McpToolPropertyDescription textDesc = new McpToolPropertyDescription(
            "string",
            "Content of the file (e.g. Java source code or HTML)"
        );
        McpToolPropertyDescription overwriteDesc = new McpToolPropertyDescription(
            "boolean",
            "Whether an existing file may be overwritten"
        );

        Map<String, McpToolPropertyDescription> properties = Map.of(
            "projectName", projectNameDesc,
            "pathInProject", pathInProjectDesc,
            "text", textDesc,
            "overwrite", overwriteDesc
        );

        List<String> requiredFields = List.of("projectName", "pathInProject", "text");

        McpToolInputSchema inputSchema = new McpToolInputSchema("object", properties, requiredFields);

        this.toolInterface = new McpToolInterface(
            NAME,
            "Create New File",
            "Creates a new file in the specified project and path if conditions are met.",
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
        String text = (String) arguments.get("text");
        Boolean overwrite = Optional.ofNullable((Boolean) arguments.get("overwrite")).orElse(false);

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

        if (Files.exists(targetFile) && !overwrite) {
            result.put("error", "File already exists and overwrite is not allowed: " + projectBaseDir.relativize(targetFile));
            LOGGER.info("File exists, overwrite=false: " + targetFile);
            return List.of(result);
        }

        try {
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, text.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            result.put("status", "success");
            result.put("message", "File written in project " + projectName + ": " + projectDir.relativize(targetFile));
            LOGGER.info("Successfully created file: " + targetFile);
        } catch (IOException e) {
            result.put("error", "Failed to write file '" + projectBaseDir.relativize(targetFile) + "'");
            LOGGER.log(Level.SEVERE, "IOException while writing file " + targetFile, e);
            throw new UncheckedIOException(e);
        }

        return List.of(result);
    }
}
