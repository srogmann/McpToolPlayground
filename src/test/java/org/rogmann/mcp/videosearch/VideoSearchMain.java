package org.rogmann.mcp.videosearch;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import org.rogmann.llmva4j.mcp.JsonRpc.JsonRpcRequest;
import org.rogmann.llmva4j.mcp.JsonRpc.JsonRpcResponse;
import org.rogmann.llmva4j.mcp.McpHttpClient;

public class VideoSearchMain {

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("Usage: <URL_MCP-VideoSearch-Server>");
        }
        URL url;
        try {
            url = new URI(args[0]).toURL();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }

        Map<String, Object> mapParams = new LinkedHashMap<>();
        mapParams.put("name", "video-search");
        Map<String, Object> mapArgs = new LinkedHashMap<>();
        mapArgs.put("keyword", "TESLA");
        mapParams.put("arguments", mapArgs);
        JsonRpcRequest toolCallRequest = new JsonRpcRequest("tools/call", mapParams, 1);
        JsonRpcResponse response = McpHttpClient.sendJsonRpcRequest(url, toolCallRequest, null);
        System.out.format("Response: %s%n", response);
    }

}
