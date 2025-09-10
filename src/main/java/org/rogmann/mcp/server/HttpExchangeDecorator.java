package org.rogmann.mcp.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import org.rogmann.llmva4j.server.IHeaders;
import org.rogmann.llmva4j.server.IHttpExchange;
import org.rogmann.tcpipproxy.http.HttpServerDispatchExchange;

/**
 * Decorator of {@link com.sun.net.httpserver.HttpExchange} to implement {@link IHttpExchange}.
 */
public class HttpExchangeDecorator implements IHttpExchange {

    /** decorated exchange */
    private final HttpServerDispatchExchange exchange;
    private final String pathPrefix;

    /**
     * Constructor
     * @param exchange HTTP exchange
     * @param pathPrefix path-prefix to be removed
     */
    public HttpExchangeDecorator(HttpServerDispatchExchange exchange, String pathPrefix) {
        this.exchange = exchange;
        this.pathPrefix = pathPrefix;
    }

    @Override
    public URI getRequestURI() {
        return exchange.getRequestURI(pathPrefix);
    }

    @Override
    public String getRequestMethod() {
        return exchange.getRequestMethod();
    }

    @Override
    public IHeaders getRequestHeaders() {
        return new HeadersDecorator(exchange.getRequestHeaders());
    }

    @Override
    public InputStream getRequestBody() {
        return exchange.getRequestBody();
    }

    @Override
    public IHeaders getResponseHeaders() {
        return new HeadersDecorator(exchange.getResponseHeaders());
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
        exchange.sendResponseHeaders(rCode, responseLength);
    }

    @Override
    public OutputStream getResponseBody() throws IOException {
        return exchange.getResponseBody();
    }

    @Override
    public void close() throws IOException {
        exchange.close();
    }
}
