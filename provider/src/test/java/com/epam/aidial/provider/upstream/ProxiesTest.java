package com.epam.aidial.provider.upstream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.api.ConfigException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxiesTest {

    private static Proxies proxies(Map<String, String> settings) {
        return new Proxies(settings.get("http_proxy"), settings.get("https_proxy"), settings.get("no_proxy"));
    }

    @Test
    void schemePicksTheProxy() {
        Proxies p = proxies(Map.of("http_proxy", "plain.test:3128", "https_proxy", "https://user:pw@secure.test"));
        assertEquals("plain.test", p.forRequest(false, "up.test", 80).getHost());
        assertEquals(3128, p.forRequest(false, "up.test", 80).getPort());
        assertEquals("secure.test", p.forRequest(true, "up.test", 443).getHost());
        assertEquals(443, p.forRequest(true, "up.test", 443).getPort());
        assertEquals("user", p.forRequest(true, "up.test", 443).getUsername());
        assertEquals("pw", p.forRequest(true, "up.test", 443).getPassword());
    }

    @Test
    void emptyIsUnset() {
        assertNull(proxies(Map.of("http_proxy", "")).forRequest(false, "up.test", 80));
        assertNull(proxies(Map.of()).forRequest(true, "up.test", 443));
    }

    @Test
    void noProxyNamesPortsAndWildcard() {
        Proxies p = proxies(
                Map.of("http_proxy", "proxy.test:1", "no_proxy", " Plain.Test, .sub.test, port.test:8080, [::2]:9, "));
        assertNull(p.forRequest(false, "plain.test", 80));
        assertNull(p.forRequest(false, "deep.plain.test", 80));
        assertNull(p.forRequest(false, "PLAIN.test", 80));
        assertNotNull(p.forRequest(false, "notplain.test", 80));
        assertNull(p.forRequest(false, "a.sub.test", 80));
        assertNotNull(p.forRequest(false, "sub.test", 80));
        assertNull(p.forRequest(false, "port.test", 8080));
        assertNotNull(p.forRequest(false, "port.test", 80));
        assertNotNull(proxies(Map.of("http_proxy", "proxy.test:1", "no_proxy", "a.test"))
                .forRequest(false, "b.test", 80));
        assertNull(proxies(Map.of("http_proxy", "proxy.test:1", "no_proxy", "x.test, *"))
                .forRequest(false, "b.test", 80));
    }

    @Test
    void loopbackIsNeverProxied() {
        Proxies p = proxies(Map.of("http_proxy", "proxy.test:1"));
        assertNull(p.forRequest(false, "localhost", 80));
        assertNull(p.forRequest(false, "127.0.0.1", 80));
        assertNull(p.forRequest(false, "[::1]", 80));
        assertNotNull(p.forRequest(false, "10.0.0.1", 80));
        assertNotNull(p.forRequest(false, "example.test", 80));
    }

    @Test
    void selectorAnswersForTheTokenClients() {
        ProxySelector selector = proxies(Map.of("https_proxy", "proxy.test:3128", "no_proxy", "skip.test"))
                .selector();
        Proxy via = selector.select(URI.create("https://login.test/token")).get(0);
        assertEquals(Proxy.Type.HTTP, via.type());
        assertEquals(InetSocketAddress.createUnresolved("proxy.test", 3128), via.address());
        assertEquals(
                Proxy.NO_PROXY,
                selector.select(URI.create("https://skip.test/x")).get(0));
        assertEquals(
                Proxy.NO_PROXY,
                selector.select(URI.create("http://login.test/x")).get(0));
    }

    @Test
    void noProxyPortAbove65535RefusesTheSetting() {
        ConfigException e = assertThrows(
                ConfigException.class,
                () -> proxies(Map.of("http_proxy", "proxy.test:1", "no_proxy", "host.test:99999999999")));
        assertEquals("no_proxy", e.setting());
        assertEquals("no_proxy has an entry with a port above 65535", e.getMessage());
        assertEquals(
                "no_proxy has an entry with a port above 65535",
                assertThrows(ConfigException.class, () -> proxies(Map.of("no_proxy", "host.test:65536")))
                        .getMessage());
        assertNull(proxies(Map.of("http_proxy", "proxy.test:1", "no_proxy", "host.test:65535"))
                .forRequest(false, "host.test", 65535));
    }

    @Test
    void proxyWithoutHostRefusesTheSetting() {
        for (String url : new String[] {"http://:8080", "http://proxy:99999999999"}) {
            ConfigException e = assertThrows(ConfigException.class, () -> proxies(Map.of("http_proxy", url)), url);
            assertEquals("http_proxy", e.setting());
            assertEquals("http_proxy has no host", e.getMessage(), url);
        }
    }

    @Test
    void unreadableProxyUrlIsRefusedWithoutItsValue() {
        ConfigException e = assertThrows(
                ConfigException.class, () -> proxies(Map.of("https_proxy", "http://user:p@ss word@proxy:3128")));
        assertEquals("https_proxy is not a valid proxy URL", e.getMessage());
        assertNull(e.getCause());
    }
}
