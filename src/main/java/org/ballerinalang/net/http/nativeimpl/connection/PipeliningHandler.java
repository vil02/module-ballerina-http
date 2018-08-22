package org.ballerinalang.net.http.nativeimpl.connection;

import org.ballerinalang.mime.util.EntityBodyHandler;
import org.ballerinalang.mime.util.HeaderUtil;
import org.ballerinalang.mime.util.MimeUtil;
import org.ballerinalang.mime.util.MultipartDataSource;
import org.ballerinalang.model.util.JsonGenerator;
import org.ballerinalang.model.values.BMap;
import org.ballerinalang.model.values.BRefValueArray;
import org.ballerinalang.model.values.BValue;
import org.ballerinalang.net.http.DataContext;
import org.ballerinalang.net.http.HttpConstants;
import org.ballerinalang.net.http.HttpUtil;
import org.ballerinalang.util.exceptions.BallerinaException;
import org.wso2.transport.http.netty.contract.HttpConnectorListener;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.io.IOException;
import java.io.OutputStream;

import static org.ballerinalang.net.http.HttpUtil.extractEntity;

/**
 * Created by rukshani on 8/21/18.
 */
public class PipeliningHandler {

    public static void sendOutboundResponseRobust(DataContext dataContext, HttpCarbonMessage requestMessage,
                                                  BMap<String, BValue> outboundResponseStruct,
                                                  HttpCarbonMessage responseMessage) {
        String contentType = HttpUtil.getContentTypeFromTransportMessage(responseMessage);
        String boundaryString = null;
        if (HeaderUtil.isMultipart(contentType)) {
            boundaryString = HttpUtil.addBoundaryIfNotExist(responseMessage, contentType);
        }

        HttpMessageDataStreamer outboundMsgDataStreamer = getMessageDataStreamer(responseMessage);
        HttpResponseFuture outboundRespStatusFuture = HttpUtil.sendOutboundResponse(requestMessage, responseMessage);
        HttpConnectorListener outboundResStatusConnectorListener =
                new ConnectionAction.HttpResponseConnectorListener(dataContext, outboundMsgDataStreamer);
        outboundRespStatusFuture.setHttpConnectorListener(outboundResStatusConnectorListener);

        OutputStream messageOutputStream = outboundMsgDataStreamer.getOutputStream();
        BMap<String, BValue> entityStruct = extractEntity(outboundResponseStruct);
        if (entityStruct != null) {
            if (boundaryString != null) {
                serializeMultiparts(boundaryString, entityStruct, messageOutputStream);
            } else {
                BValue outboundMessageSource = EntityBodyHandler.getMessageDataSource(entityStruct);
                serializeMsgDataSource(outboundMessageSource, entityStruct, messageOutputStream);
            }
        }
    }

    /**
     * Serialize multipart entity body. If an array of body parts exist, encode body parts else serialize body content
     * if it exist as a byte channel.
     *
     * @param boundaryString      Boundary string that should be used in encoding body parts
     * @param entityStruct        Represent the entity that holds the actual body
     * @param messageOutputStream Represent the output stream
     */
    static void serializeMultiparts(String boundaryString, BMap<String, BValue> entityStruct,
                                    OutputStream messageOutputStream) {
        BRefValueArray bodyParts = EntityBodyHandler.getBodyPartArray(entityStruct);
        if (bodyParts != null && bodyParts.size() > 0) {
            MultipartDataSource multipartDataSource = new MultipartDataSource(entityStruct, boundaryString);
            serializeMsgDataSource(multipartDataSource, entityStruct, messageOutputStream);
            HttpUtil.closeMessageOutputStream(messageOutputStream);
        } else {
            try {
                EntityBodyHandler.writeByteChannelToOutputStream(entityStruct, messageOutputStream);
                HttpUtil.closeMessageOutputStream(messageOutputStream);
            } catch (IOException e) {
                throw new BallerinaException("Error occurred while serializing byte channel content : " +
                        e.getMessage());
            }
        }
    }

    static void setResponseConnectorListener(DataContext dataContext, HttpResponseFuture outResponseStatusFuture) {
        HttpConnectorListener outboundResStatusConnectorListener =
                new ConnectionAction.HttpResponseConnectorListener(dataContext);
        outResponseStatusFuture.setHttpConnectorListener(outboundResStatusConnectorListener);
    }

    static void serializeMsgDataSource(BValue outboundMessageSource, BMap<String, BValue> entityStruct,
                                       OutputStream messageOutputStream) {
        try {
            if (outboundMessageSource != null) {
                if (MimeUtil.generateAsJSON(outboundMessageSource, entityStruct)) {
                    JsonGenerator gen = new JsonGenerator(messageOutputStream);
                    gen.serialize(outboundMessageSource);
                    gen.flush();
                } else {
                    outboundMessageSource.serialize(messageOutputStream);
                }
                HttpUtil.closeMessageOutputStream(messageOutputStream);
            } else { //When the entity body is a byte channel
                EntityBodyHandler.writeByteChannelToOutputStream(entityStruct, messageOutputStream);
                HttpUtil.closeMessageOutputStream(messageOutputStream);
            }
        } catch (IOException e) {
            throw new BallerinaException("Error occurred while serializing message data source : " + e.getMessage());
        }
    }

    static HttpMessageDataStreamer getMessageDataStreamer(HttpCarbonMessage outboundResponse) {
        final HttpMessageDataStreamer outboundMsgDataStreamer;
        final PooledDataStreamerFactory pooledDataStreamerFactory = (PooledDataStreamerFactory)
                outboundResponse.getProperty(HttpConstants.POOLED_BYTE_BUFFER_FACTORY);
        if (pooledDataStreamerFactory != null) {
            outboundMsgDataStreamer = pooledDataStreamerFactory.createHttpDataStreamer(outboundResponse);
        } else {
            outboundMsgDataStreamer = new HttpMessageDataStreamer(outboundResponse);
        }
        return outboundMsgDataStreamer;
    }

    public boolean isBlocking() {
        return false;
    }

    static class HttpResponseConnectorListener implements HttpConnectorListener {

        private final DataContext dataContext;
        private HttpMessageDataStreamer outboundMsgDataStreamer;

        HttpResponseConnectorListener(DataContext dataContext) {
            this.dataContext = dataContext;
        }

        HttpResponseConnectorListener(DataContext dataContext, HttpMessageDataStreamer outboundMsgDataStreamer) {
            this.dataContext = dataContext;
            this.outboundMsgDataStreamer = outboundMsgDataStreamer;
        }

        @Override
        public void onMessage(HttpCarbonMessage httpCarbonMessage) {
            this.dataContext.notifyOutboundResponseStatus(null);
        }

        @Override
        public void onError(Throwable throwable) {
            BMap<String, BValue> httpConnectorError = HttpUtil.getError(dataContext.context, throwable);
            if (outboundMsgDataStreamer != null) {
                if (throwable instanceof IOException) {
                    this.dataContext.getOutboundRequest().setIoException((IOException) throwable);
                } else {
                    this.dataContext.getOutboundRequest()
                            .setIoException(new IOException(throwable.getMessage(), throwable));
                }
            }
            this.dataContext.notifyOutboundResponseStatus(httpConnectorError);
        }
    }

}
