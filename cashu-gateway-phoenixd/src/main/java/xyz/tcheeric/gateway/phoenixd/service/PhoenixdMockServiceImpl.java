package xyz.tcheeric.gateway.phoenixd.service;

import xyz.tcheeric.phoenixd.mock.MockLnServer;

import java.io.IOException;

/**
 * PhoenixdService implementation that starts a {@link MockLnServer}
 * and delegates requests to the mock gateway endpoints.
 */
public class PhoenixdMockServiceImpl extends PhoenixdServiceImpl {

    private final MockLnServer server;

    public PhoenixdMockServiceImpl() {
        this(19740);
    }

    public PhoenixdMockServiceImpl(int port) {
        server = new MockLnServer(port);
        System.setProperty("phoenixd.base_url", "http://localhost:" + port);
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start MockLnServer", e);
        }
    }

    public void stop() {
        server.stop();
    }
}

