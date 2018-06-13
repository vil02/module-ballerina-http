/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.transport.http.netty.sender.websocket;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.contract.websocket.ClientHandshakeFuture;
import org.wso2.transport.http.netty.contractimpl.websocket.DefaultClientHandshakeFuture;
import org.wso2.transport.http.netty.contractimpl.websocket.DefaultWebSocketConnection;
import org.wso2.transport.http.netty.listener.WebSocketFramesBlockingHandler;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/**
 * WebSocket client for sending and receiving messages in WebSocket as a client.
 */
public class WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClient.class);

    private WebSocketClientHandshakeHandler clientHandshakeHandler;

    private final String url;
    private final String subProtocols;
    private final int idleTimeout;
    private final HttpHeaders headers;
    private final EventLoopGroup wsClientEventLoopGroup;
    private final boolean autoRead;

    /**
     * @param url                    url of the remote endpoint
     * @param subProtocols           subProtocols the negotiable sub-protocol if server is asking for it
     * @param idleTimeout            Idle timeout of the connection
     * @param wsClientEventLoopGroup of the client connector
     * @param headers                any specific headers which need to send to the server
     * @param autoRead               sets the read interest
     */
    public WebSocketClient(String url, String subProtocols, int idleTimeout, EventLoopGroup wsClientEventLoopGroup,
                           HttpHeaders headers, boolean autoRead) {
        this.url = url;
        this.subProtocols = subProtocols;
        this.idleTimeout = idleTimeout;
        this.headers = headers;
        this.wsClientEventLoopGroup = wsClientEventLoopGroup;
        this.autoRead = autoRead;
    }

    /**
     * Handle the handshake with the server.
     *
     * @return handshake future for connection.
     */
    public ClientHandshakeFuture handshake() {
        ClientHandshakeFuture handshakeFuture = new DefaultClientHandshakeFuture();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "ws" : uri.getScheme();
            final String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
            final int port = getPort(uri);

            if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
                log.error("Only WS(S) is supported.");
                throw new URISyntaxException(url, "WebSocket client supports only WS(S) scheme");
            }
            final boolean ssl = "wss".equalsIgnoreCase(scheme);
            WebSocketClientHandshaker webSocketHandshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    uri, WebSocketVersion.V13, subProtocols, true, headers);
            WebSocketFramesBlockingHandler blockingHandler = new WebSocketFramesBlockingHandler();
            clientHandshakeHandler = new WebSocketClientHandshakeHandler(webSocketHandshaker, blockingHandler, ssl,
                    autoRead, url, handshakeFuture);
            Bootstrap clientBootstrap = initClientBootstrap(host, port, getSslContext(ssl));
            clientBootstrap.connect(uri.getHost(), port).sync().channel();
            clientHandshakeHandler.handshakeFuture().addListener(clientHandshakeFuture -> {
                Throwable cause = clientHandshakeFuture.cause();
                if (clientHandshakeFuture.isSuccess() && cause == null) {
                    DefaultWebSocketConnection webSocketConnection =
                            clientHandshakeHandler.getInboundFrameHandler().getWebSocketConnection();
                    String actualSubProtocol = webSocketHandshaker.actualSubprotocol();
                    webSocketConnection.getDefaultWebSocketSession().setNegotiatedSubProtocol(actualSubProtocol);
                    handshakeFuture.notifySuccess(webSocketConnection, clientHandshakeHandler.getHttpCarbonResponse());
                } else {
                    handshakeFuture.notifyError(cause, clientHandshakeHandler.getHttpCarbonResponse());
                }
            });
        } catch (Throwable throwable) {
            if (clientHandshakeHandler != null) {
                handshakeFuture.notifyError(throwable, clientHandshakeHandler.getHttpCarbonResponse());
            } else {
                handshakeFuture.notifyError(throwable, null);
            }
        }
        return handshakeFuture;
    }

    private Bootstrap initClientBootstrap(String host, int port, SslContext sslCtx) {
        Bootstrap clientBootstrap = new Bootstrap();
        clientBootstrap.group(wsClientEventLoopGroup).channel(NioSocketChannel.class).handler(
                new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        if (sslCtx != null) {
                            pipeline.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                        }
                        pipeline.addLast(new HttpClientCodec());
                        // Assuming that WebSocket Handshake messages will not be large than 8KB
                        pipeline.addLast(new HttpObjectAggregator(8192));
                        pipeline.addLast(WebSocketClientCompressionHandler.INSTANCE);
                        if (idleTimeout > 0) {
                            pipeline.addLast(new IdleStateHandler(idleTimeout, idleTimeout,
                                    idleTimeout, TimeUnit.MILLISECONDS));
                        }
                        pipeline.addLast(Constants.WEBSOCKET_CLIENT_HANDSHAKE_HANDLER, clientHandshakeHandler);
                    }
                });
        return clientBootstrap;
    }

    private int getPort(URI uri) {
        String scheme = uri.getScheme();
        if (uri.getPort() == -1) {
            switch (scheme) {
                case "ws":
                    return 80;
                case "wss":
                    return 443;
                default:
                    return -1;
            }
        } else {
            return uri.getPort();
        }
    }

    private SslContext getSslContext(boolean ssl) throws SSLException {
        if (ssl) {
            return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        return null;
    }
}
