package com.epam.aidial.provider.upstream;

import com.epam.aidial.provider.api.ConfigException;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class Proxies {

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final int ANY_PORT = -1;
    private static final int MAX_PORT = 65535;

    private record Exclusion(String name, int port) {}

    private final ProxyOptions http;
    private final ProxyOptions https;
    private final List<Exclusion> noProxy;
    private final boolean noProxyAll;

    /**
     * @param httpProxy null or empty when not set
     * @param httpsProxy null or empty when not set
     * @param noProxy null or empty when not set
     * @throws ConfigException for a proxy URL or a no_proxy port Java cannot read; the message names the setting, not
     *     its value
     */
    public Proxies(@Nullable String httpProxy, @Nullable String httpsProxy, @Nullable String noProxy) {
        this.http = proxy("http_proxy", httpProxy);
        this.https = proxy("https_proxy", httpsProxy);
        boolean all = false;
        List<Exclusion> entries = new ArrayList<>();
        if (noProxy != null) {
            for (String entry : noProxy.split(",")) {
                String e = entry.strip().toLowerCase(Locale.ROOT);
                if (e.equals("*")) {
                    all = true;
                } else if (!e.isEmpty()) {
                    entries.add(exclusion(e));
                }
            }
        }
        this.noProxy = List.copyOf(entries);
        this.noProxyAll = all;
    }

    private static Exclusion exclusion(String entry) {
        int colon = entry.lastIndexOf(':');
        if (colon > 0
                && entry.indexOf(']') < colon
                && DIGITS.matcher(entry.substring(colon + 1)).matches()) {
            return new Exclusion(entry.substring(0, colon), port(entry.substring(colon + 1)));
        }
        return new Exclusion(entry, ANY_PORT);
    }

    private static int port(String digits) {
        try {
            int port = Integer.parseInt(digits);
            if (port <= MAX_PORT) {
                return port;
            }
        } catch (NumberFormatException e) {
            // too many digits for a port: refused below, with no word of the value
        }
        throw new ConfigException("no_proxy", "no_proxy has an entry with a port above " + MAX_PORT);
    }

    /** Null for a direct connection. */
    ProxyOptions forRequest(boolean ssl, String host, int port) {
        ProxyOptions proxy = ssl ? https : http;
        if (proxy == null || noProxyAll || isLoopback(host) || excluded(host.toLowerCase(Locale.ROOT), port)) {
            return null;
        }
        return proxy;
    }

    /** Limit: proxy credentials are not sent by these java.net clients; add an Authenticator when needed. */
    public ProxySelector selector() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                boolean ssl = "https".equalsIgnoreCase(uri.getScheme());
                int port = uri.getPort() > 0 ? uri.getPort() : ssl ? 443 : 80;
                ProxyOptions p = uri.getHost() == null ? null : forRequest(ssl, uri.getHost(), port);
                return List.of(
                        p == null
                                ? Proxy.NO_PROXY
                                : new Proxy(
                                        Proxy.Type.HTTP, InetSocketAddress.createUnresolved(p.getHost(), p.getPort())));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e) {}
        };
    }

    /**
     * A value Java cannot read leaves the {@link ConfigException}'s message and cause without it: it may hold the
     * proxy's password.
     */
    private static ProxyOptions proxy(String setting, String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(url.contains("://") ? url : "http://" + url);
            if (uri.getHost() == null) {
                throw new ConfigException(setting, setting + " has no host");
            }
            ProxyOptions options = new ProxyOptions()
                    .setType(ProxyType.HTTP)
                    .setHost(uri.getHost())
                    .setPort(uri.getPort() > 0 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80);
            if (uri.getUserInfo() != null) {
                String[] user = uri.getUserInfo().split(":", 2);
                options.setUsername(user[0]).setPassword(user.length > 1 ? user[1] : "");
            }
            return options;
        } catch (IllegalArgumentException e) {
            throw new ConfigException(setting, setting + " is not a valid proxy URL");
        }
    }

    private static boolean isLoopback(String host) {
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        String literal = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        if (!isAddressLiteral(literal)) {
            return false; // a name: no lookup here
        }
        try {
            return InetAddress.getByName(literal).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isAddressLiteral(String s) {
        boolean colon = false;
        boolean letter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':') {
                colon = true;
            } else if (c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
                letter = true;
            } else if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return !s.isEmpty() && (colon || !letter);
    }

    /** CIDR ranges are not read. */
    private boolean excluded(String host, int port) {
        for (Exclusion entry : noProxy) {
            if (entry.port != ANY_PORT && entry.port != port) {
                continue;
            }
            String name = entry.name;
            if (name.startsWith(".") ? host.endsWith(name) : host.equals(name) || host.endsWith("." + name)) {
                return true;
            }
        }
        return false;
    }
}
