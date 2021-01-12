package org.bsc.rmi.jetty_websocket.client;

import lombok.NonNull;
import org.bsc.rmi.proxy.socket.debug.RMIDebugSocketFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.RMISocketFactory;
import java.util.Optional;

import static java.util.Optional.empty;

public class RMIWebsocketFactoryClient extends RMISocketFactory {

    private final RMIClientSocketFactory client;
    private final RMIServerSocketFactory server;

    public static class Builder {

        private Optional<RMIClientSocketFactory> client = empty();
        private Optional<RMIServerSocketFactory> server = empty();
        private boolean debug = false;

        public Builder clientSocketFactory( @NonNull RMIClientWebsocketFactory client) {
            if( this.client.isPresent() ) throw new IllegalStateException( "RMI Client Socket Factory already set!");
            this.client = Optional.of(client);
            return this;
        }
        public Builder serverSocketFactory( @NonNull RMIEventHandlerWebsocketFactory server) {
            if( this.server.isPresent() ) throw new IllegalStateException( "RMI Server Socket Factory already set!");
            this.server = Optional.of(server);
            return this;
        }
        public Builder debug( boolean value) {
            debug = value;
            return this;
        }

        private RMIServerSocketFactory setServerDebug( RMIServerSocketFactory sf) {
            if( debug && sf instanceof RMIEventHandlerWebsocketFactory ) {
                ((RMIEventHandlerWebsocketFactory)sf).setDelegate( RMISocketFactory.getDefaultSocketFactory() );
            }
            return sf;
        }

        public  RMISocketFactory build() {
            RMISocketFactory def = (debug) ? new RMIDebugSocketFactory() : RMISocketFactory.getDefaultSocketFactory();
            return new RMIWebsocketFactoryClient(
                client.orElse( def ),
                server.map( this::setServerDebug ).orElse( def )
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    protected RMIWebsocketFactoryClient(RMIClientSocketFactory client, RMIServerSocketFactory server) {
        this.client = client;
        this.server = server;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return client.createSocket(host, port);
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        return server.createServerSocket(port);
    }
}
