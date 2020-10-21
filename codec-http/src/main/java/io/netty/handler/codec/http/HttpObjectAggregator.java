/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.MessageAggregator;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static io.netty.handler.codec.http.HttpUtil.getContentLength;

/**
 * A {@link ChannelHandler} that aggregates an {@link HttpMessage}
 * and its following {@link HttpContent}s into a single {@link FullHttpRequest}
 * or {@link FullHttpResponse} (depending on if it used to handle requests or responses)
 * with no following {@link HttpContent}s.  It is useful when you don't want to take
 * care of HTTP messages whose transfer encoding is 'chunked'.  Insert this
 * handler after {@link HttpResponseDecoder} in the {@link ChannelPipeline} if being used to handle
 * responses, or after {@link HttpRequestDecoder} and {@link HttpResponseEncoder} in the
 * {@link ChannelPipeline} if being used to handle requests.
 * <blockquote>
 *  <pre>
 *  {@link ChannelPipeline} p = ...;
 *  ...
 *  p.addLast("decoder", <b>new {@link HttpRequestDecoder}()</b>);
 *  p.addLast("encoder", <b>new {@link HttpResponseEncoder}()</b>);
 *  p.addLast("aggregator", <b>new {@link HttpObjectAggregator}(1048576)</b>);
 *  ...
 *  p.addLast("handler", new HttpRequestHandler());
 *  </pre>
 * </blockquote>
 * <p>
 * For convenience, consider putting a {@link HttpServerCodec} before the {@link HttpObjectAggregator}
 * as it functions as both a {@link HttpRequestDecoder} and a {@link HttpResponseEncoder}.
 * </p>
 * Be aware that {@link HttpObjectAggregator} may end up sending a {@link HttpResponse}:
 * <table border summary="Possible Responses">
 *   <tbody>
 *     <tr>
 *       <th>Response Status</th>
 *       <th>Condition When Sent</th>
 *     </tr>
 *     <tr>
 *       <td>100 Continue</td>
 *       <td>A '100-continue' expectation is received and the 'content-length' doesn't exceed maxContentLength</td>
 *     </tr>
 *     <tr>
 *       <td>417 Expectation Failed</td>
 *       <td>A '100-continue' expectation is received and the 'content-length' exceeds maxContentLength</td>
 *     </tr>
 *     <tr>
 *       <td>413 Request Entity Too Large</td>
 *       <td>Either the 'content-length' or the bytes received so far exceed maxContentLength</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * @see FullHttpRequest
 * @see FullHttpResponse
 * @see HttpResponseDecoder
 * @see HttpServerCodec
 * 消息聚合器
 * 可以把消息聚合成一个复合缓冲区，一起给后面的处理器，直接封装成AggregatedFullHttpRequest类型传递下去。
 * 这样就不需要在处理器中判断是否接受到一半的逻辑了。但是这个有一个指定接受的长度限制，超过长度了直接就丢弃不处理了。
 * 主要可以用于接收一些相对较小的消息，不用后面处理器再去判断是否是个完整的包，但是如果包很大可能占用很大内存
 */
public class HttpObjectAggregator
    //有4个泛型，分别对应是聚合HTTP类型的，HTTP通用消息请求行和请求头的，HTTP消息体，HTTP完整通用消息，包括消息体
        extends MessageAggregator<HttpObject, HttpMessage, HttpContent, FullHttpMessage> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(HttpObjectAggregator.class);

    //接受100-continue，响应状态码100
    //Except:100-continue的响应
    private static final FullHttpResponse CONTINUE =
            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER);
    //不接受，响应状态码417 不支持
    private static final FullHttpResponse EXPECTATION_FAILED = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.EXPECTATION_FAILED, Unpooled.EMPTY_BUFFER);

    //不接受，响应状态码413 消息体太大而关闭连接
    private static final FullHttpResponse TOO_LARGE_CLOSE = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER);

    //不接受，响应状态码413 消息体太大，没关闭连接
    private static final FullHttpResponse TOO_LARGE = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER);

    static {
        //设定头消息
        EXPECTATION_FAILED.headers().set(CONTENT_LENGTH, 0);
        TOO_LARGE.headers().set(CONTENT_LENGTH, 0);

        TOO_LARGE_CLOSE.headers().set(CONTENT_LENGTH, 0);
        //关闭头信息
        TOO_LARGE_CLOSE.headers().set(CONNECTION, HttpHeaderValues.CLOSE);
    }

    //如果消息过大是否关闭连接，报异常
    private final boolean closeOnExpectationFailed;

    /**
     * Creates a new instance.
     * @param maxContentLength the maximum length of the aggregated content in bytes.
     * If the length of the aggregated content exceeds this value,
     * {@link #handleOversizedMessage(ChannelHandlerContext, HttpMessage)} will be called.
     */
    public HttpObjectAggregator(int maxContentLength) {
        this(maxContentLength, false);
    }

    /**
     * Creates a new instance.
     * @param maxContentLength the maximum length of the aggregated content in bytes.
     * If the length of the aggregated content exceeds this value,
     * {@link #handleOversizedMessage(ChannelHandlerContext, HttpMessage)} will be called.
     * @param closeOnExpectationFailed If a 100-continue response is detected but the content length is too large
     * then {@code true} means close the connection. otherwise the connection will remain open and data will be
     * consumed and discarded until the next request is received.
     */
    public HttpObjectAggregator(int maxContentLength, boolean closeOnExpectationFailed) {
        super(maxContentLength);
        this.closeOnExpectationFailed = closeOnExpectationFailed;
    }

    //对HTTP来说其实就是判断是否是通用的消息行和消息头信息
    @Override
    protected boolean isStartMessage(HttpObject msg) throws Exception {
        return msg instanceof HttpMessage;
    }

    @Override
    protected boolean isContentMessage(HttpObject msg) throws Exception {
        return msg instanceof HttpContent;
    }

    //是否是最后的内容。
    @Override
    protected boolean isLastContentMessage(HttpContent msg) throws Exception {
        return msg instanceof LastHttpContent;
    }

    //是否聚合好了。
    @Override
    protected boolean isAggregated(HttpObject msg) throws Exception {
        return msg instanceof FullHttpMessage;
    }

    @Override
    protected boolean isContentLengthInvalid(HttpMessage start, int maxContentLength) {
        try {
            return getContentLength(start, -1L) > maxContentLength;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    //根据是否支持100-continue，是否长度超过限制等进行响应
    private static Object continueResponse(HttpMessage start, int maxContentLength, ChannelPipeline pipeline) {
        //不支持Expect头
        if (HttpUtil.isUnsupportedExpectation(start)) {
            // if the request contains an unsupported expectation, we return 417
            pipeline.fireUserEventTriggered(HttpExpectationFailedEvent.INSTANCE);
            return EXPECTATION_FAILED.retainedDuplicate();
        //支持100-continue请求
        } else if (HttpUtil.is100ContinueExpected(start)) {
            // if the request contains 100-continue but the content-length is too large, we return 413
            if (getContentLength(start, -1L) <= maxContentLength) {
                return CONTINUE.retainedDuplicate();//继续
            }
            pipeline.fireUserEventTriggered(HttpExpectationFailedEvent.INSTANCE);
            return TOO_LARGE.retainedDuplicate();//消息体太大
        }

        return null;
    }

    //如果需要100-continue响应的话，要把100-continue头设置去掉，不往后传播了
    @Override
    protected Object newContinueResponse(HttpMessage start, int maxContentLength, ChannelPipeline pipeline) {
        Object response = continueResponse(start, maxContentLength, pipeline);
        // we're going to respond based on the request expectation so there's no
        // need to propagate the expectation further.
        if (response != null) {
            start.headers().remove(EXPECT);//如果有100-continue响应，就不用再传播下去了
        }
        return response;
    }

    @Override
    protected boolean closeAfterContinueResponse(Object msg) {
        return closeOnExpectationFailed && ignoreContentAfterContinueResponse(msg);
    }

    @Override
    protected boolean ignoreContentAfterContinueResponse(Object msg) {
        if (msg instanceof HttpResponse) {
            final HttpResponse httpResponse = (HttpResponse) msg;
            return httpResponse.status().codeClass().equals(HttpStatusClass.CLIENT_ERROR);
        }
        return false;
    }

    //创建一个聚合的类，根据不同情况创建请求还是响应的完整类型
    @Override
    protected FullHttpMessage beginAggregation(HttpMessage start, ByteBuf content) throws Exception {
        assert !(start instanceof FullHttpMessage);

        HttpUtil.setTransferEncodingChunked(start, false);

        AggregatedFullHttpMessage ret;
        if (start instanceof HttpRequest) {
            ret = new AggregatedFullHttpRequest((HttpRequest) start, content, null);//聚合请求
        } else if (start instanceof HttpResponse) {
            ret = new AggregatedFullHttpResponse((HttpResponse) start, content, null);//聚合响应
        } else {
            throw new Error();
        }
        return ret;
    }

    //整合尾部的头信息，因为chunk协议可能会有尾部头信息的
    @Override
    protected void aggregate(FullHttpMessage aggregated, HttpContent content) throws Exception {
        //如果是最后的尾部内容就整合尾部头信息
        if (content instanceof LastHttpContent) {
            // Merge trailing headers into the message.
            ((AggregatedFullHttpMessage) aggregated).setTrailingHeaders(((LastHttpContent) content).trailingHeaders());
        }
    }

    @Override
    protected void finishAggregation(FullHttpMessage aggregated) throws Exception {
        // Set the 'Content-Length' header. If one isn't already set.
        // This is important as HEAD responses will use a 'Content-Length' header which
        // does not match the actual body, but the number of bytes that would be
        // transmitted if a GET would have been used.
        //
        // See rfc2616 14.13 Content-Length
        //没设置Content-Length头的话要设置
        if (!HttpUtil.isContentLengthSet(aggregated)) {
            aggregated.headers().set(
                    CONTENT_LENGTH,
                    String.valueOf(aggregated.content().readableBytes()));
        }
    }

    @Override
    protected void handleOversizedMessage(final ChannelHandlerContext ctx, HttpMessage oversized) throws Exception {
        if (oversized instanceof HttpRequest) {
            // send back a 413 and close the connection

            // If the client started to send data already, close because it's impossible to recover.
            // If keep-alive is off and 'Expect: 100-continue' is missing, no need to leave the connection open.
            if (oversized instanceof FullHttpMessage ||
                !HttpUtil.is100ContinueExpected(oversized) && !HttpUtil.isKeepAlive(oversized)) {
                ChannelFuture future = ctx.writeAndFlush(TOO_LARGE_CLOSE.retainedDuplicate());
                future.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            logger.debug("Failed to send a 413 Request Entity Too Large.", future.cause());
                        }
                        ctx.close();
                    }
                });
            } else {
                ctx.writeAndFlush(TOO_LARGE.retainedDuplicate()).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            logger.debug("Failed to send a 413 Request Entity Too Large.", future.cause());
                            ctx.close();
                        }
                    }
                });
            }
        } else if (oversized instanceof HttpResponse) {
            ctx.close();
            throw new TooLongFrameException("Response entity too large: " + oversized);
        } else {
            throw new IllegalStateException();
        }
    }

    private abstract static class AggregatedFullHttpMessage implements FullHttpMessage {
        protected final HttpMessage message;
        private final ByteBuf content;
        private HttpHeaders trailingHeaders;

        AggregatedFullHttpMessage(HttpMessage message, ByteBuf content, HttpHeaders trailingHeaders) {
            this.message = message;
            this.content = content;
            this.trailingHeaders = trailingHeaders;
        }

        @Override
        public HttpHeaders trailingHeaders() {
            HttpHeaders trailingHeaders = this.trailingHeaders;
            if (trailingHeaders == null) {
                return EmptyHttpHeaders.INSTANCE;
            } else {
                return trailingHeaders;
            }
        }

        void setTrailingHeaders(HttpHeaders trailingHeaders) {
            this.trailingHeaders = trailingHeaders;
        }

        @Override
        public HttpVersion getProtocolVersion() {
            return message.protocolVersion();
        }

        @Override
        public HttpVersion protocolVersion() {
            return message.protocolVersion();
        }

        @Override
        public FullHttpMessage setProtocolVersion(HttpVersion version) {
            message.setProtocolVersion(version);
            return this;
        }

        @Override
        public HttpHeaders headers() {
            return message.headers();
        }

        @Override
        public DecoderResult decoderResult() {
            return message.decoderResult();
        }

        @Override
        public DecoderResult getDecoderResult() {
            return message.decoderResult();
        }

        @Override
        public void setDecoderResult(DecoderResult result) {
            message.setDecoderResult(result);
        }

        @Override
        public ByteBuf content() {
            return content;
        }

        @Override
        public int refCnt() {
            return content.refCnt();
        }

        @Override
        public FullHttpMessage retain() {
            content.retain();
            return this;
        }

        @Override
        public FullHttpMessage retain(int increment) {
            content.retain(increment);
            return this;
        }

        @Override
        public FullHttpMessage touch(Object hint) {
            content.touch(hint);
            return this;
        }

        @Override
        public FullHttpMessage touch() {
            content.touch();
            return this;
        }

        @Override
        public boolean release() {
            return content.release();
        }

        @Override
        public boolean release(int decrement) {
            return content.release(decrement);
        }

        @Override
        public abstract FullHttpMessage copy();

        @Override
        public abstract FullHttpMessage duplicate();

        @Override
        public abstract FullHttpMessage retainedDuplicate();
    }

    private static final class AggregatedFullHttpRequest extends AggregatedFullHttpMessage implements FullHttpRequest {

        AggregatedFullHttpRequest(HttpRequest request, ByteBuf content, HttpHeaders trailingHeaders) {
            super(request, content, trailingHeaders);
        }

        @Override
        public FullHttpRequest copy() {
            return replace(content().copy());
        }

        @Override
        public FullHttpRequest duplicate() {
            return replace(content().duplicate());
        }

        @Override
        public FullHttpRequest retainedDuplicate() {
            return replace(content().retainedDuplicate());
        }

        @Override
        public FullHttpRequest replace(ByteBuf content) {
            DefaultFullHttpRequest dup = new DefaultFullHttpRequest(protocolVersion(), method(), uri(), content,
                    headers().copy(), trailingHeaders().copy());
            dup.setDecoderResult(decoderResult());
            return dup;
        }

        @Override
        public FullHttpRequest retain(int increment) {
            super.retain(increment);
            return this;
        }

        @Override
        public FullHttpRequest retain() {
            super.retain();
            return this;
        }

        @Override
        public FullHttpRequest touch() {
            super.touch();
            return this;
        }

        @Override
        public FullHttpRequest touch(Object hint) {
            super.touch(hint);
            return this;
        }

        @Override
        public FullHttpRequest setMethod(HttpMethod method) {
            ((HttpRequest) message).setMethod(method);
            return this;
        }

        @Override
        public FullHttpRequest setUri(String uri) {
            ((HttpRequest) message).setUri(uri);
            return this;
        }

        @Override
        public HttpMethod getMethod() {
            return ((HttpRequest) message).method();
        }

        @Override
        public String getUri() {
            return ((HttpRequest) message).uri();
        }

        @Override
        public HttpMethod method() {
            return getMethod();
        }

        @Override
        public String uri() {
            return getUri();
        }

        @Override
        public FullHttpRequest setProtocolVersion(HttpVersion version) {
            super.setProtocolVersion(version);
            return this;
        }

        @Override
        public String toString() {
            return HttpMessageUtil.appendFullRequest(new StringBuilder(256), this).toString();
        }
    }

    private static final class AggregatedFullHttpResponse extends AggregatedFullHttpMessage
            implements FullHttpResponse {

        AggregatedFullHttpResponse(HttpResponse message, ByteBuf content, HttpHeaders trailingHeaders) {
            super(message, content, trailingHeaders);
        }

        @Override
        public FullHttpResponse copy() {
            return replace(content().copy());
        }

        @Override
        public FullHttpResponse duplicate() {
            return replace(content().duplicate());
        }

        @Override
        public FullHttpResponse retainedDuplicate() {
            return replace(content().retainedDuplicate());
        }

        @Override
        public FullHttpResponse replace(ByteBuf content) {
            DefaultFullHttpResponse dup = new DefaultFullHttpResponse(getProtocolVersion(), getStatus(), content,
                    headers().copy(), trailingHeaders().copy());
            dup.setDecoderResult(decoderResult());
            return dup;
        }

        @Override
        public FullHttpResponse setStatus(HttpResponseStatus status) {
            ((HttpResponse) message).setStatus(status);
            return this;
        }

        @Override
        public HttpResponseStatus getStatus() {
            return ((HttpResponse) message).status();
        }

        @Override
        public HttpResponseStatus status() {
            return getStatus();
        }

        @Override
        public FullHttpResponse setProtocolVersion(HttpVersion version) {
            super.setProtocolVersion(version);
            return this;
        }

        @Override
        public FullHttpResponse retain(int increment) {
            super.retain(increment);
            return this;
        }

        @Override
        public FullHttpResponse retain() {
            super.retain();
            return this;
        }

        @Override
        public FullHttpResponse touch(Object hint) {
            super.touch(hint);
            return this;
        }

        @Override
        public FullHttpResponse touch() {
            super.touch();
            return this;
        }

        @Override
        public String toString() {
            return HttpMessageUtil.appendFullResponse(new StringBuilder(256), this).toString();
        }
    }
}
