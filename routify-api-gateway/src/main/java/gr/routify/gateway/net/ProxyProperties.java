package gr.routify.gateway.net;

import lombok.Data;

/**
 * Optional HTTP/HTTPS/SOCKS5 proxy host, port, and credentials for outbound WebClient connections.
 */
@Data
public class ProxyProperties {

    private String host;
    private Integer port;
    private String username;
    private String password;
    /** Proxy protocol type: HTTP, HTTPS, or SOCKS5. Defaults to HTTP. */
    private String type = "HTTP";
    /** Host patterns that bypass the proxy (e.g. "localhost", "*.internal"). */
    private java.util.List<String> nonProxyHosts = new java.util.ArrayList<>();

    public boolean isProxyConfigured() {
        return host != null && !host.isBlank() && port != null;
    }
}

