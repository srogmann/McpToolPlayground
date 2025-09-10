package org.rogmann.mcp.playground;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.rogmann.llmva4j.LightweightJsonHandler;
import org.rogmann.llmva4j.mcp.McpHttpClient;
import org.rogmann.llmva4j.mcp.McpHttpClient.McpToolWithUri;
import org.rogmann.llmva4j.mcp.McpHttpServer;
import org.rogmann.llmva4j.mcp.McpToolImplementation;
import org.rogmann.llmva4j.mcp.McpToolInputSchema;
import org.rogmann.llmva4j.mcp.McpToolInterface;
import org.rogmann.llmva4j.mcp.McpToolPropertyDescription;
import org.rogmann.llmva4j.server.IHttpExchange;
import org.rogmann.llmva4j.server.RequestForwarder;
import org.rogmann.llmva4j.server.UiServer;
import org.rogmann.mcp.server.HttpExchangeDecorator;
import org.rogmann.tcpipproxy.WebSocketServer;
import org.rogmann.tcpipproxy.WebSocketServer.WebSocketConnection;
import org.rogmann.tcpipproxy.WebSocketServer.WebSocketHandler;
import org.rogmann.tcpipproxy.http.HttpHandler;
import org.rogmann.tcpipproxy.http.HttpServerDispatchExchange;

/**
 * Main server class for the MCP (Model Control Protocol) Playground application.
 * <p>
 * This class implements a combined HTTP and WebSocket server that allows users to interactively
 * start, manage, and communicate with MCP tool servers through a web-based interface.
 * </p>
 * <p>
 * Key features:
 * <ul>
 *   <li>HTTP server serving static web resources (HTML, JS, icons)</li>
 *   <li>WebSocket endpoint for real-time communication between browser clients and MCP tools</li>
 *   <li>Dynamic creation and management of MCP tool servers based on client configuration</li>
 *   <li>Relay mechanism for tool calls and responses between LLM-driven tools and human operators</li>
 * </ul>
 * </p>
 * <p>
 * The server accepts the following command-line arguments:
 * <ol>
 *   <li>Host IP for the playground web server</li>
 *   <li>Port for the playground web server</li>
 *   <li>Host IP for the MCP tool server to be controlled</li>
 *   <li>Port for the MCP tool server to be controlled</li>
 *   <li>(Optional) URL of the LLM service</li>
 * </ol>
 * </p>
 * <p>
 * Clients can send JSON messages via WebSocket with actions like:
 * <ul>
 *   <li>{@code startMcp} - Start an MCP tool server with specified tool definition</li>
 *   <li>{@code stopMcp} - Stop the running MCP tool server</li>
 *   <li>{@code toolResponse} - Send a response to a tool call initiated by the MCP server</li>
 * </ul>
 * </p>
 */
public class McpPlaygroundServerMain {
    /** logger */
    private static final Logger LOG = Logger.getLogger(McpPlaygroundServerMain.class.getName());

    /** Pattern of a valid path */
    private static final Pattern P_PATH = Pattern.compile("/([A-Za-z0-9_-]+[.]([A-Za-z0-9]+))");

    /** path-prefix "/chat/" */
    private static final String PREFIX_CHAT = "/chat/";

    /** path-prefix "/mcp/" */
    private static final String PREFIX_MCP = "/mcp/";

    /** path-prefix "/stop.do" */
    private static final String PREFIX_STOP = "/stop.do";

    /** Prefix of user-cookie */
    private static final String PREFIX_COOKIE_USER = "MCP_PLAYGROUND_USER_ID=";

    private static final Map<String, String> MIME_TYPES = new HashMap<>(); 

    /** initial user counter */
    private static final AtomicLong USER_COUNTER = new AtomicLong();

    private final WebSocketServer server;

    private final RequestForwarder requestForwarder;

    private final String llmUrl;
    private final String publicPath;

    /** Map from user-id to tool-implementation */
    private ConcurrentMap<String, McpToolImplementation> mapMcpTools = new ConcurrentHashMap<>();

    /** Map from user-id to mcp-client */
    private final ConcurrentMap<String, McpHttpClient> mapMcpClient = new ConcurrentHashMap<>();

    /** tool-responses */
    private final BlockingQueue<Map<String, Object>> queueToolResponses = new LinkedBlockingQueue<>();

    static {
        MIME_TYPES.put("html", "text/html; charset=UTF-8");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("js", "text/javascript; charset=UTF-8");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java McpDemoServerMain <server-ip> <server-port> [<llm-url> <public-path>]");
            System.exit(1);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String llmUrl = (args.length > 3) ? args[2] : null;
        String publicPath = (args.length > 3) ? args[3] : null;
        if (publicPath != null && !new File(publicPath).isDirectory()) {
            throw new IllegalArgumentException("Illegal path directory (web-content): " + publicPath);
        }
        var server = new McpPlaygroundServerMain(host, port, llmUrl, publicPath);
        LOG.info("Server: " + server);
    }

    private McpPlaygroundServerMain(String host, int port, String llmUrl, String publicPath) {
        try {
            HttpHandler httpHandler = exchange -> handleRequest(exchange, llmUrl);
            WebSocketHandler wsHandler = createWsHandler();
            server = new WebSocketServer(host, port, httpHandler, "/WebSocketServlet", wsHandler);
            LOG.info("Server started on " + host + ":" + port);
        } catch (IOException e) {
            throw new RuntimeException("IO-error while starting HTTP-server", e);
        }

        this.llmUrl = llmUrl;
        this.publicPath = publicPath;
        ServiceLoader<RequestForwarder> slRequestForwarder = ServiceLoader.load(RequestForwarder.class);
        requestForwarder = slRequestForwarder.findFirst()
                .orElse((requestMap, messagesWithTools, listOpenAITools, url) -> UiServer.forwardRequest(requestMap, messagesWithTools, listOpenAITools, url));
        LOG.info("RequestForwarder: " + requestForwarder);
    }

    private WebSocketHandler createWsHandler() {
        return new WebSocketHandler() {

            @Override
            public void onOpen(WebSocketConnection client) {
                LOG.info("open " + client);
            }

            @Override
            public void onMessage(WebSocketConnection wsConn, String message) {
                LOG.info("message: " + message);
                Map<String, Object> mapMessage;
                try {
                    mapMessage = LightweightJsonHandler.parseJsonDict(message);
                } catch (IOException e) {
                    LOG.severe("Invalid JSON-message: " + message);
                    return;
                }
                String action = LightweightJsonHandler.getJsonValue(mapMessage, "action", String.class);
                String userName = LightweightJsonHandler.getJsonValue(mapMessage, "userName", String.class);
                String respMessage = null;
                Map<String, Object> mapResponse = new LinkedHashMap<String, Object>();
                String actionResponse = "message";
                if ("initUser".equals(action)) {
                    if (userName == null || userName.isBlank()) {
                        String initialUser = createInitialUser();
                        respMessage = "Initial user: " + initialUser;
                        actionResponse = "initUser";
                        mapResponse.put("userId", initialUser);
                    }
                }
                else if ("startMcp".equals(action)) {
                    updateMcpServer(userName, mapMessage, wsConn);

                    respMessage = "Hi " + userName + "! MCP-server has been started.";
                    actionResponse = "uiServerStarted";
                    mapResponse.put("url", "/chat/");
                }
                else if ("toolResponse".equals(action)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> toolResponse = LightweightJsonHandler.getJsonValue(mapMessage, "toolResponse", Map.class);
                    if (toolResponse == null) {
                        LOG.severe("tool-response without response: " + mapMessage);
                    } else {
                        queueToolResponses.add(toolResponse);
                    }
                }
                if (respMessage != null) {
                    mapResponse.put("action", actionResponse);
                    mapResponse.put("message", respMessage);
                    try {
                        wsConn.send(LightweightJsonHandler.dumpJson(mapResponse));
                    } catch (IOException e) {
                        LOG.severe("IO-error while creating JSON-message: " + mapResponse);
                        return;
                    }
                }
            }

            @Override
            public void onError(WebSocketConnection client, Throwable t) {
                LOG.log(Level.SEVERE, "error in ws-connection", t);
            }

            @Override
            public void onClose(WebSocketConnection client, int code, String reason) {
                LOG.info("close: " + reason);
            }
        };
    }

    private static String createInitialUser() {
        // Wir schlagen eine User-Id vor.
        return String.format("user_%d", USER_COUNTER.incrementAndGet() * 7 + 2);
    }

    private void updateMcpServer(String userName, Map<String, Object> mapMessage,
            WebSocketConnection wsConn) {
        @SuppressWarnings("unchecked")
        Map<String, Object> mapTool = LightweightJsonHandler.getJsonValue(mapMessage, "tool", Map.class);
        String toolTitle = LightweightJsonHandler.getJsonValue(mapTool, "title", String.class);
        String toolDescription = LightweightJsonHandler.getJsonValue(mapTool, "description", String.class);
        LOG.info(String.format("updateMcpServer: userId=%s, tool=%s", userName, toolTitle));

        @SuppressWarnings("unchecked")
        Map<String, Object> mapProperties = LightweightJsonHandler.getJsonValue(mapTool, "properties", Map.class);

        Map<String, McpToolPropertyDescription> propertyDescriptions = new HashMap<>();
        List<String> requiredFields = new ArrayList<>();

        for (Map.Entry<String, Object> propEntry : mapProperties.entrySet()) {
            String propName = propEntry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> propDef = (Map<String, Object>) propEntry.getValue();

            String propType = LightweightJsonHandler.getJsonValue(propDef, "type", String.class);
            String propDesc = LightweightJsonHandler.getJsonValue(propDef, "description", String.class);

            // Optional: itemsType
            String itemsType = (String) propDef.get("itemsType"); // may be null

            McpToolPropertyDescription propDescObj;
            if (itemsType != null) {
                propDescObj = new McpToolPropertyDescription(propType, propDesc, itemsType);
            } else {
                propDescObj = new McpToolPropertyDescription(propType, propDesc);
            }

            propertyDescriptions.put(propName, propDescObj);
            requiredFields.add(propName);
        }

        McpToolInputSchema inputSchema = new McpToolInputSchema(
            "string",
            propertyDescriptions,
            requiredFields
        );

        McpToolInterface mcpInterface = new McpToolInterface(
            toolTitle,
            toolTitle,
            toolDescription,
            inputSchema
        );

        McpToolImplementation toolImpl = createMcpToolImplementation(mcpInterface, wsConn, queueToolResponses);
        mapMcpTools.put(userName, toolImpl);

        McpHttpClient mcpClient = new McpHttpClient();
        InetSocketAddress serverAddress = server.getServerAddress();
        URI toolUri = URI.create(String.format("http://%s:%d%s", serverAddress.getHostString(), serverAddress.getPort(), PREFIX_MCP));
        URL toolUrl;
        try {
            toolUrl = toolUri.toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid tool-URL: " + toolUri, e);
        }
        McpToolWithUri toolWithUrl = new McpToolWithUri(mcpInterface, toolUrl);
        mcpClient.registerTool(toolWithUrl);
        mapMcpClient.put(userName, mcpClient);
    }

    private void handleRequest(HttpServerDispatchExchange exchange, String llmUrl) {
        LOG.info(String.format("%s %s request %s", LocalDateTime.now(), exchange.getRequestMethod(), exchange.getRequestURI()));

        String rawPath = exchange.getRequestRawPath();
        if (rawPath.startsWith(PREFIX_CHAT)) {
            if ((PREFIX_CHAT + "props").equals(exchange.getRequestRawPath())) {
                processPropsRequest(exchange, llmUrl);
                return;
            }
            processChatRequest(exchange);
            return;
        }
        if (rawPath.startsWith(PREFIX_MCP)) {
            processMcpRequest(exchange);
            return;
        }
        
        if (rawPath.startsWith(PREFIX_STOP)) {
            sendError(exchange, 500, "Starting shutdown");
            try {
                server.stop(1000);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "IO-error at shutdown", e);
            }
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            processGetRequest(exchange, llmUrl);
            return;
        }

        LOG.severe(String.format("handleRequest: invalid method (%s) calling (%s)", exchange.getRequestMethod(), exchange.getRequestRawPath()));
        sendError(exchange, 405, "Method Not Allowed");
    }

    private void processChatRequest(HttpServerDispatchExchange exchange) {

        IHttpExchange exchangeDecorated = new HttpExchangeDecorator(exchange, PREFIX_CHAT);
        String userId = lookupUserId(exchange);
        if (userId == null) {
            return;
        }
        McpHttpClient mcpClient = mapMcpClient.get(userId);
        if (mcpClient == null) {
            LOG.info("Missing mcp-clien for user: " + userId);
            sendError(exchange, 500, "Missing mcp-client for user");
        } else {
            UiServer.handleRequest(exchangeDecorated, llmUrl, publicPath, mcpClient, requestForwarder);
        }
    }

    /**
     * This method does a lookup of the user-id in a cookie.
     * In case of a missing user-id a HTTP error will be sent.
     * @param exchange HTTP exchange
     * @return user-id or null
     */
    private String lookupUserId(HttpServerDispatchExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        String userId = null;
        if (cookie == null) {
            sendError(exchange, 400, "Missing cookie in request");
            return null;
        }
        if (cookie.startsWith(PREFIX_COOKIE_USER)) {
            userId = cookie.substring(PREFIX_COOKIE_USER.length());
        }
        if (userId == null) {
            LOG.info("Cookie: " + cookie);
            sendError(exchange, 400, "Missing user-cookie in request");
            return null;
        }
        return userId;
    }

    private void processMcpRequest(HttpServerDispatchExchange exchange) {
        IHttpExchange mcpExchange = new HttpExchangeDecorator(exchange, PREFIX_MCP);
        String userId = lookupUserId(exchange);
        if (userId == null) {
            return;
        }
        McpToolImplementation toolImpl = mapMcpTools.get(userId);
        if (toolImpl == null) {
            LOG.severe("Missing tool implementation for user: " + userId);
            sendError(exchange, 400, "unknown user-id");
            return;
        }

        ConcurrentMap<String, McpToolImplementation> mapTools = new ConcurrentHashMap<String, McpToolImplementation>();
        mapTools.put(toolImpl.getName(), toolImpl);

        McpHttpServer.handleRequest(mcpExchange, mapTools);
    }

    private static void processGetRequest(HttpServerDispatchExchange exchange, String llmUrl) {
        try {
            String path = exchange.getRequestRawPath().replaceFirst("[?].*", "");
            if ("/".equals(path)) {
                path = "/index.html";
            }
            Matcher mPath = P_PATH.matcher(path);
            if (!mPath.matches()) {
                LOG.warning(String.format("Invalid path (%s)", path));
                sendError(exchange, 404, "File not found");
                return;
            }
            String fileName = mPath.group(1);
            String extension = mPath.group(2);
            String contentType = MIME_TYPES.get(extension);
            if (contentType == null) {
                LOG.warning(String.format("Invalid file-type (%s) in path (%s)", extension, path));
                sendError(exchange, 404, "File not found");
                return;
            }
            final byte[] bufResponse;
            try (var baos = new ByteArrayOutputStream();
                    var is = McpPlaygroundServerMain.class.getResourceAsStream(fileName)) {
                if (is == null) {
                    LOG.warning(String.format("Ressource (%s) not found at %s", path, McpPlaygroundServerMain.class));
                    sendError(exchange, 404, "File not found");
                    return;
                }
                byte[] buf = new byte[4096];
                while (true) {
                    int len = is.read(buf);
                    if (len == -1) {
                        break;
                    }
                    baos.write(buf, 0, len);
                }
                bufResponse = baos.toByteArray();
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bufResponse.length);
            exchange.getResponseBody().write(bufResponse);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO-error while generating response", e);
            sendError(exchange, 500, "Internal Server Error");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to generate response", e);
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    private void processPropsRequest(HttpServerDispatchExchange exchange, String llmUrl2) {
        Map<String, Object> mapProps = new LinkedHashMap<>();
        mapProps.put("id", 0);
        mapProps.put("model_path", "remote model");
        mapProps.put("build_info", "unknown");
        String jsonProps = LightweightJsonHandler.dumpJson(mapProps);
        byte[] bufJson = jsonProps.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "text/json");
        try {
            exchange.sendResponseHeaders(200, bufJson.length);
            exchange.getResponseBody().write(bufJson);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO-error while sending LLM-props", e);
        }
    }

    private static McpToolImplementation createMcpToolImplementation(McpToolInterface toolInterface,
            WebSocketConnection wsConn, BlockingQueue<Map<String, Object>> queueToolResponses) {
        McpToolImplementation toolImpl = new McpToolImplementation() {

            @Override
            public McpToolInterface getTool() {
                return toolInterface;
            }

            @Override
            public String getName() {
                return toolInterface.name();
            }

            @Override
            public List<Map<String, Object>> call(Map<String, Object> params) {
                Map<String, Object> mapJson = new LinkedHashMap<>();
                mapJson.put("action", "toolCall");
                mapJson.put("toolRequest", params);
                String jsonMsg = LightweightJsonHandler.dumpJson(mapJson);
                LOG.info("toolCall: " + jsonMsg);

                // Send tool-call to the web-socket client.
                try {
                    wsConn.send(jsonMsg);
                } catch (IOException e) {
                    LOG.severe("IO-error while seding tool-call: " + jsonMsg);
                }

                // We wait for an answer of the tool-call.
                Map<String, Object> response = null;
                for (int i = 0; i < 60; i++) {
                    if (wsConn.isClosed()) {
                        LOG.warning("WebSocket connection closed, stopping tool response check.");
                        break;
                    }
                    try {
                        response = queueToolResponses.poll(1000, TimeUnit.MILLISECONDS);
                        if (response != null) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        LOG.warning("Interrupted while waiting for tool response: " + e.getMessage());
                        break;
                    }
                }
                if (response == null) {
                    LOG.info("No tool-response");
                    return Collections.emptyList();
                }
                LOG.info("Received tool-response: " + response);
                return Collections.singletonList(response);
            }
        };
        return toolImpl;
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    private static void sendError(HttpServerDispatchExchange exchange, int statusCode, String message) {
        try {
            LOG.severe(String.format("Error (%s): %d - %s", exchange.getRequestRawPath(), statusCode, message));
            String errorResponse = "<html><body><h1>Error " + statusCode + "</h1><p>" + escapeHtml(message) + "</p></body></html>";
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, errorResponse, 0);
            try (var os = exchange.getResponseBody()) {
                os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO-error while sending error message", e);
        }
    }
}
