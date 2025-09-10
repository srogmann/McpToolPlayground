package org.rogmann.mcp.server;

import org.rogmann.llmva4j.server.IHeaders;
import org.rogmann.tcpipproxy.http.HttpHeaders;

import com.sun.net.httpserver.Headers;

/**
 * Decorator of {@link Headers}.
 */
public class HeadersDecorator implements IHeaders {

    private final HttpHeaders headers;

    /**
     * Constructs a new instance with modifiable headers.
     */
    public HeadersDecorator() {
        this.headers = new HttpHeaders(false);
    }

    /**
     * Constructor
     * @param httpHeaders HTTP headers to be decorated
     */
    public HeadersDecorator(HttpHeaders httpHeaders) {
        this.headers = httpHeaders;
    }

    /** {@inheritDoc} */
    @Override
    public String getFirst(String key) {
        return headers.getFirst(key);
    }

    /** {@inheritDoc} */
    @Override
    public void add(String key, String value) {
        headers.add(key, value);
    }

    /** {@inheritDoc} */
    @Override
    public void set(String key, String value) {
        headers.set(key, value);
    }

}
