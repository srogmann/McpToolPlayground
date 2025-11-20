package org.rogmann.mcp.examples;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 * MCP tool implementation for finding files in a project using a glob pattern.
 * The tool ensures that only allowed projects and safe paths are accessed.
 * It supports limiting the number of returned files.
 */
public class FindFilesByGlobTool implements McpToolImplementation {

    private static final Logger LOGGER = Logger.getLogger(FindFilesByGlobTool.class.getName());

    private static final String NAME = "find_files_by_glob";

    private final McpToolInterface toolInterface;

    /**
     * Constructs the tool and initializes its interface description.
     */
    public FindFilesByGlobTool() {
        McpToolPropertyDescription projectNameDesc = new McpToolPropertyDescription(
            "string",
            "Name of the project"
        );
        McpToolPropertyDescription globPatternDesc = new McpToolPropertyDescription(
            "string",
            "Glob pattern for matching files relative to the project directory, e.g. **/*.java"
        );
        McpToolPropertyDescription fileCountLimitDesc = new McpToolPropertyDescription(
            "integer",
            "Optional maximum number of files to return"
        );
        McpToolPropertyDescription subDirectoryRelativePathDesc = new McpToolPropertyDescription(
            "string",
            "Optional subdirectory path relative to the project root where the search should be limited to, e.g. a source folder src/main/java"
        );

        Map<String, McpToolPropertyDescription> properties = Map.of(
            "projectName", projectNameDesc,
            "globPattern", globPatternDesc,
            "fileCountLimit", fileCountLimitDesc,
            "subDirectoryRelativePath", subDirectoryRelativePathDesc
        );

        List<String> requiredFields = List.of("projectName", "globPattern");

        McpToolInputSchema inputSchema = new McpToolInputSchema("object", properties, requiredFields);

        this.toolInterface = new McpToolInterface(
            NAME,
            "Find Files by Glob",
            "Finds files in the specified project matching the given glob pattern if access conditions are met.",
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
        String globPattern = (String) arguments.get("globPattern");
        if (globPattern == null) {
            globPattern = "**/*.*";
        }
        Integer fileCountLimit = (Integer) arguments.get("fileCountLimit");
        String subDirectoryRelativePath = (String) arguments.get("subDirectoryRelativePath");

        Map<String, Object> result = new HashMap<>();
        result.put("status", "failed");

        String projectDirProp = System.getProperty("IDE_PROJECT_DIR");
        if (projectDirProp == null || projectDirProp.isBlank()) {
            result.put("error", getClass().getSimpleName() + " is missing IDE_PROJECT_DIR");
            LOGGER.severe("IDE_PROJECT_DIR system property is not defined.");
            return List.of(result);
        }

        Path projectBaseDir = Paths.get(projectDirProp).toAbsolutePath().normalize();
        if (!Files.exists(projectBaseDir)) {
            result.put("error", getClass().getSimpleName() + " didn't find project base directory");
            LOGGER.severe("Project base directory does not exist: " + projectBaseDir);
            return List.of(result);
        }

        String projectFilterProp = System.getProperty("IDE_PROJECT_FILTER");
        Pattern projectFilterPattern = null;
        if (projectFilterProp != null && !projectFilterProp.isBlank()) {
            try {
                projectFilterPattern = Pattern.compile(projectFilterProp);
            } catch (PatternSyntaxException e) {
                result.put("error", getClass().getSimpleName() + " has invalid IDE_PROJECT_FILTER");
                LOGGER.severe("Invalid regex in IDE_PROJECT_FILTER: " + e.getMessage());
                return List.of(result);
            }
        }

        if (projectFilterPattern != null && !projectFilterPattern.matcher(projectName).matches()) {
            result.put("error", "Project name '" + projectName + "' is not allowed by filter");
            LOGGER.warning("Access denied to project '" + projectName + "' due to filter.");
            return List.of(result);
        }

        if (projectName == null) {
            result.put("error", "projectName is missing");
            return List.of(result);
        }
        Path projectDir = projectBaseDir.resolve(projectName).normalize();
        if (!projectDir.startsWith(projectBaseDir)) {
            result.put("error", "Project directory is outside base directory, access denied");
            LOGGER.warning("Attempted directory traversal in project name: " + projectName);
            return List.of(result);
        }

        if (!Files.exists(projectDir)) {
            result.put("error", getClass().getSimpleName() + " didn't find the project directory");
            LOGGER.severe("Project directory does not exist: " + projectDir);
            return List.of(result);
        }

        Path patternRoot = projectDir.resolve(".").normalize();
        if (!patternRoot.startsWith(projectDir)) {
            result.put("error", "Invalid pattern root path after resolution of " + projectDir);
            LOGGER.warning("Pattern root path resolves outside project directory: " + patternRoot);
            return List.of(result);
        }

        // Compute search-root directory.
        Path searchRoot;
        if (subDirectoryRelativePath != null && !subDirectoryRelativePath.isBlank()) {
            Path subDirPath = projectDir.resolve(subDirectoryRelativePath).normalize();
            if (!subDirPath.startsWith(projectDir)) {
                result.put("error", "Subdirectory path resolves outside project directory, access denied");
                LOGGER.warning("Attempted directory traversal in subDirectoryRelativePath: " + subDirectoryRelativePath);
                return List.of(result);
            }
            if (!Files.exists(subDirPath)) {
                result.put("error", "Subdirectory does not exist: " + subDirPath);
                LOGGER.severe("Subdirectory does not exist: " + subDirPath);
                return List.of(result);
            }
            if (!Files.isDirectory(subDirPath)) {
                result.put("error", "Subdirectory path is not a directory: " + subDirPath);
                LOGGER.severe("Subdirectory path is not a directory: " + subDirPath);
                return List.of(result);
            }
            searchRoot = subDirPath;
        } else {
            searchRoot = projectDir;
        }

        int limit = Optional.ofNullable(fileCountLimit).orElse(Integer.MAX_VALUE);
        if (limit <= 0) {
            result.put("files", new ArrayList<>());
            result.put("status", "success");
            result.put("message", "No files returned due to zero or negative limit");
            LOGGER.fine("File count limit is non-positive: " + limit);
            return List.of(result);
        }

        List<Map<String, Object>> matchingFiles = new ArrayList<>();
        try {
            FileSystem fileSystem = FileSystems.getDefault();
            String qualifiedPattern = "glob:" + globPattern;
            DirectoryStream.Filter<Path> filter = fileSystem.getPathMatcher(qualifiedPattern)::matches;

            walkAndMatch(searchRoot, projectDir, filter, matchingFiles, limit);

            result.put("status", "success");
            result.put("files", matchingFiles);
            result.put("fileCount", matchingFiles.size());
            result.put("message", "Found " + matchingFiles.size() + " file(s) matching pattern");
            LOGGER.fine("Found " + matchingFiles.size() + " file(s) matching pattern '" + globPattern +
                    "' in project '" + projectName + "' under subdirectory '" + subDirectoryRelativePath + "'");
        } catch (Exception e) {
            result.put("error", String.format("Failed to process glob pattern '%s' for '%s'", globPattern, projectName));
            LOGGER.log(Level.SEVERE, "Exception during glob processing for pattern '" + globPattern + "' in: " + projectName + " / " + searchRoot, e);
            return List.of(result);
        }

        return List.of(result);
    }

    /**
     * Recursively walks through the directory tree and collects files matching the filter up to the limit.
     *
     * @param basePath base path to start walking from
     * @param rootDir base directory of the search (for relativization)
     * @param filter filter to apply on paths
     * @param result list to collect matched files
     * @param limit maximum number of files to collect
     * @throws IOException if an I/O error occurs
     */
    private void walkAndMatch(Path basePath, Path rootDir, DirectoryStream.Filter<Path> filter,
                             List<Map<String, Object>> result, int limit) throws IOException {
        if (result.size() >= limit) {
            return;
        }

        if (Files.isDirectory(basePath)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath)) {
                for (Path entry : stream) {
                    if (result.size() >= limit) {
                        break;
                    }

                    Path relativePath = rootDir.relativize(entry);
                    if (filter.accept(relativePath)) {
                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("path", relativePath.toString());
                        try {
                            fileInfo.put("size", Files.size(entry));
                        } catch (IOException e) {
                            fileInfo.put("size", -1L);
                        }
                        result.add(fileInfo);
                    }

                    if (Files.isDirectory(entry)) {
                        walkAndMatch(entry, rootDir, filter, result, limit);
                    }
                }
            }
        } else {
            if (filter.accept(basePath)) {
                Map<String, Object> fileInfo = new HashMap<>();
                Path relativePath = rootDir.relativize(basePath);
                fileInfo.put("path", relativePath.toString());
                try {
                    fileInfo.put("size", Files.size(basePath));
                } catch (IOException e) {
                    fileInfo.put("size", -1L);
                }
                result.add(fileInfo);
            }
        }
    }

}
