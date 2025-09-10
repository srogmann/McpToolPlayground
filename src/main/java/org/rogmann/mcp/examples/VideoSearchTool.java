package org.rogmann.mcp.examples;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.rogmann.llmva4j.LightweightJsonHandler;
import org.rogmann.llmva4j.mcp.McpToolImplementation;
import org.rogmann.llmva4j.mcp.McpToolInputSchema;
import org.rogmann.llmva4j.mcp.McpToolInterface;
import org.rogmann.llmva4j.mcp.McpToolPropertyDescription;

public class VideoSearchTool implements McpToolImplementation {
    static final String PROP_FOLDER = "mcp.videosearch.folder";

    private final McpToolInterface mcpTool;

    private final File folderVideos;

    public VideoSearchTool() {
        mcpTool = createToolDescription();
        var folderName = System.getProperty(PROP_FOLDER);
        if (folderName == null) {
            throw new RuntimeException("Folder property is not set: " + PROP_FOLDER);
        }
        folderVideos = new File(folderName);
        if (!folderVideos.isDirectory()) {
            throw new RuntimeException("Invalid folder: " + folderVideos);
        }
    }

   static McpToolInterface createToolDescription() {
        Map<String, McpToolPropertyDescription> mapProps = new LinkedHashMap<>();
        McpToolPropertyDescription descKeyword = new McpToolPropertyDescription("array", "One or more keywords which might be in the title of description of a video.", "string");
        mapProps.put("keywords", descKeyword);
        List<String> aRequired = List.of("keywords");
        McpToolInputSchema inputSchema = new McpToolInputSchema("object", mapProps, aRequired);
        McpToolInterface mcpTool = new McpToolInterface("video-search", "Filesystem Video Files Provider", "Search for local video files by keyword", inputSchema);
        return mcpTool;
   }

    @Override
    public List<Map<String, Object>> call(Map<String, Object> params) {
        @SuppressWarnings("unchecked")
        Map<String, Object> mapArgs = LightweightJsonHandler.getJsonValue(params, "arguments", Map.class);
        if (mapArgs == null) {
            throw new RuntimeException("Missing arguments in tool-call");
        }
        @SuppressWarnings("unchecked")
        List<String> keywords = LightweightJsonHandler.getJsonValue(mapArgs, "keywords", List.class);
        if (keywords == null || keywords.isEmpty()) {
            throw new RuntimeException("Missing keywords in tool-call");
        }
        List<String> lKeywords = keywords.stream().map(s -> s.replace(" ", "")).map(String::toLowerCase).toList();
        List<Map<String, Object>> listResults = new ArrayList<>();
        File[] files = folderVideos.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                String name = file.getName().toLowerCase().replaceAll("_", "").replaceAll(" ", "");
                if (name.endsWith(".mp4") || name.endsWith(".webm")) {
                    for (String lKeyword : lKeywords) {
                        if (name.contains(lKeyword)) {
                            Map<String, Object> mapDetails = new LinkedHashMap<>();
                            mapDetails.put("type", "text");
                            mapDetails.put("text", "File-name of a local video: " + file.getName());
                            listResults.add(mapDetails);
                            break;
                        }
                    }
                }
            }
        }
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
