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
        return new Proxies(settings::get);
    }

    @Test
    void schemePicksTheProxy() {
        Proxies p = proxies(Map.of("HTTP_PROXY", "plain.test:3128", "https_proxy", "https://user:pw@secure.test"));
        assertEquals("plain.test", p.forRequest(false, "up.test", 80).getHost());
        assertEquals(3128, p.forRequest(false, "up.test", 80).getPort());
        assertEquals("secure.test", p.forRequest(true, "up.test", 443).getHost());
        assertEquals(443, p.forRequest(true, "up.test", 443).getPort());
        assertEquals("user", p.forRequest(true, "up.test", 443).getUsername());
        assertEquals("pw", p.forRequest(true, "up.test", 443).getPassword());
    }

    @Test
    void uppercaseNameWinsAndEmptyIsUnset() {
        assertEquals(
                "upper.test",
                proxies(Map.of("HTTP_PROXY", "upper.test", "http_proxy", "lower.test"))
                        .forRequest(false, "up.test", 80)
                        .getHost());
        assertEquals(
                "lower.test",
                proxies(Map.of("HTTP_PROXY", "", "http_proxy", "lower.test"))
                        .forRequest(false, "up.test", 80)
                        .getHost());
        assertNull(proxies(Map.of("HTTP_PROXY", "")).forRequest(false, "up.test", 80));
        assertNull(proxies(Map.of()).forRequest(true, "up.test", 443));
    }

    @Test
    void noProxyNamesPortsAndWildcard() {
        Proxies p = proxies(
                Map.of("HTTP_PROXY", "proxy.test:1", "NO_PROXY", " Plain.Test, .sub.test, port.test:8080, [::2]:9, "));
        assertNull(p.forRequest(false, "plain.test", 80));
        assertNull(p.forRequest(false, "deep.plain.test", 80));
        assertNull(p.forRequest(false, "PLAIN.test", 80));
        assertNotNull(p.forRequest(false, "notplain.test", 80));
        assertNull(p.forRequest(false, "a.sub.test", 80));
        assertNotNull(p.forRequest(false, "sub.test", 80));
        assertNull(p.forRequest(false, "port.test", 8080));
        assertNotNull(p.forRequest(false, "port.test", 80));
        assertNotNull(proxies(Map.of("HTTP_PROXY", "proxy.test:1", "NO_PROXY", "a.test"))
                .forRequest(false, "b.test", 80));
        assertNull(proxies(Map.of("HTTP_PROXY", "proxy.test:1", "no_proxy", "x.test, *"))
                .forRequest(false, "b.test", 80));
    }

    @Test
    void loopbackIsNeverProxied() {
        Proxies p = proxies(Map.of("HTTP_PROXY", "proxy.test:1"));
        assertNull(p.forRequest(false, "localhost", 80));
        assertNull(p.forRequest(false, "127.0.0.1", 80));
        assertNull(p.forRequest(false, "[::1]", 80));
        assertNotNull(p.forRequest(false, "10.0.0.1", 80));
        assertNotNull(p.forRequest(false, "example.test", 80));
    }

    @Test
    void selectorAnswersForTheTokenClients() {
        ProxySelector selector = proxies(Map.of("HTTPS_PROXY", "proxy.test:3128", "NO_PROXY", "skip.test"))
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
                () -> proxies(Map.of("HTTP_PROXY", "proxy.test:1", "NO_PROXY", "host.test:99999999999")));
        assertEquals("NO_PROXY", e.setting());
        assertEquals("NO_PROXY has an entry with a port above 65535", e.getMessage());
        assertEquals(
                "NO_PROXY has an entry with a port above 65535",
                assertThrows(ConfigException.class, () -> proxies(Map.of("NO_PROXY", "host.test:65536")))
                        .getMessage());
        assertNull(proxies(Map.of("HTTP_PROXY", "proxy.test:1", "NO_PROXY", "host.test:65535"))
                .forRequest(false, "host.test", 65535));
    }

    @Test
    void proxyWithoutHostRefusesTheSetting() {
        for (String url : new String[] {"http://:8080", "http://proxy:99999999999"}) {
            ConfigException e = assertThrows(ConfigException.class, () -> proxies(Map.of("HTTP_PROXY", url)), url);
            assertEquals("HTTP_PROXY", e.setting());
            assertEquals("HTTP_PROXY has no host", e.getMessage(), url);
        }
    }

    @Test
    void unreadableProxyUrlIsRefusedWithoutItsValue() {
        ConfigException e = assertThrows(
                ConfigException.class, () -> proxies(Map.of("HTTPS_PROXY", "http://user:p@ss word@proxy:3128")));
        assertEquals("HTTPS_PROXY is not a valid proxy URL", e.getMessage());
        assertNull(e.getCause());
    }
}
