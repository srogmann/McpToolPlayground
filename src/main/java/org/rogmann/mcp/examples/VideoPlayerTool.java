package org.rogmann.mcp.examples;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.rogmann.llmva4j.LightweightJsonHandler;
import org.rogmann.llmva4j.mcp.McpToolImplementation;
import org.rogmann.llmva4j.mcp.McpToolInputSchema;
import org.rogmann.llmva4j.mcp.McpToolInterface;
import org.rogmann.llmva4j.mcp.McpToolPropertyDescription;

/**
 * MCP-tool to play a video.
 */
public class VideoPlayerTool implements McpToolImplementation {
    private static final DateTimeFormatter DF_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    static final String PROP_FOLDER = "mcp.videosearch.folder";
    static final String PROP_PLAYER = "mcp.videoplayer.executable";
    static final String PROP_ARGS = "mcp.videoplayer.arguments";

    private final McpToolInterface mcpTool;

    private final File folderVideos;
    private final File fileExecutable;
    private final String[] playerArgs;

    public VideoPlayerTool() {
        mcpTool = createToolDescription();

        var folderName = System.getProperty(PROP_FOLDER);
        if (folderName == null) {
            throw new RuntimeException("Folder property is not set: " + PROP_FOLDER);
        }
        folderVideos = new File(folderName);
        if (!folderVideos.isDirectory()) {
            throw new RuntimeException("Invalid folder: " + folderVideos);
        }

        var pathExecutable = System.getProperty(PROP_PLAYER);
        if (pathExecutable == null) {
            throw new RuntimeException("Player-property is not set: " + PROP_PLAYER);
        }
        fileExecutable = new File(pathExecutable);
        if (!fileExecutable.isFile()) {
            throw new RuntimeException("Invalid file: " + fileExecutable);
        }

        var sArgs = System.getProperty(PROP_ARGS);
        if (sArgs == null) {
            throw new RuntimeException("Missing arguments-property: " + PROP_ARGS);
        }
        playerArgs = sArgs.split(" +");
    }

   static McpToolInterface createToolDescription() {
        Map<String, McpToolPropertyDescription> mapProps = new LinkedHashMap<>();
        McpToolPropertyDescription descKeyword = new McpToolPropertyDescription("string", "file-name of a video to be played.");
        mapProps.put("file_name", descKeyword);
        List<String> aRequired = List.of("file_name");
        McpToolInputSchema inputSchema = new McpToolInputSchema("object", mapProps, aRequired);
        McpToolInterface mcpTool = new McpToolInterface("video-player", "Filesystem Video Player", "Play a local video with a given file-name", inputSchema);
        return mcpTool;
   }

    @Override
    public List<Map<String, Object>> call(Map<String, Object> params) {
        @SuppressWarnings("unchecked")
        Map<String, Object> mapArgs = LightweightJsonHandler.getJsonValue(params, "arguments", Map.class);
        if (mapArgs == null) {
            throw new RuntimeException("Missing arguments in tool-call");
        }
        String fileName = LightweightJsonHandler.getJsonValue(mapArgs, "file_name", String.class);
        if (fileName == null) {
            throw new RuntimeException("Missing file-name in tool-call");
        }
        if (fileName.contains(File.separator)) {
            throw new RuntimeException("Invalid file-name");
        }
        final File fileVideo = new File(folderVideos, fileName);
        String status;
        if (fileVideo.isFile()) {
            List<String> cmdArgs = new ArrayList<>();
            cmdArgs.add(fileExecutable.getAbsolutePath());
            cmdArgs.addAll(Arrays.asList(playerArgs));
            cmdArgs.add(fileVideo.getAbsolutePath());
            Process process;
            try {
                process = new ProcessBuilder(cmdArgs).start();
            } catch (IOException e) {
                throw new RuntimeException("IO-error after starting the player with: " + cmdArgs, e);
            }

            Instant tsFile = Instant.ofEpochMilli(fileVideo.lastModified());
            LocalDateTime ldtFile = LocalDateTime.ofInstant(tsFile, ZoneId.of("Europe/Berlin"));
            status = String.format("Started playing file \"%s\" (pid %d, size %d bytes, saved on %s)",
                    fileName, process.pid(), fileVideo.length(), DF_DATE.format(ldtFile));
        } else {
            status = String.format("No such file: %s", fileName);
        }
        List<Map<String, Object>> listResults = new ArrayList<>();
        Map<String, Object> mapDetails = new LinkedHashMap<>();
        mapDetails.put("type",  "text");
        mapDetails.put("text", status);
        listResults.add(mapDetails);
        return listResults;
    }

    @Override
    public String getName() {
        return mcpTool.name();
    }

    @Override
    public McpToolInterface getTool() {
        return mcpTool;
    }

}
