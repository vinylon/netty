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

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static io.netty.util.internal.StringUtil.COMMA;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.AppendableCharSequence;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Decodes {@link ByteBuf}s into {@link HttpMessage}s and
 * {@link HttpContent}s.
 *
 * <h3>Parameters that prevents excessive memory consumption</h3>
 * <table border="1">
 * <tr>
 * <th>Name</th><th>Default value</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code maxInitialLineLength}</td>
 * <td>{@value #DEFAULT_MAX_INITIAL_LINE_LENGTH}</td>
 * <td>The maximum length of the initial line
 *     (e.g. {@code "GET / HTTP/1.0"} or {@code "HTTP/1.0 200 OK"})
 *     If the length of the initial line exceeds this value, a
 *     {@link TooLongFrameException} will be raised.</td>
 * </tr>
 * <tr>
 * <td>{@code maxHeaderSize}</td>
 * <td>{@value #DEFAULT_MAX_HEADER_SIZE}</td>
 * <td>The maximum length of all headers.  If the sum of the length of each
 *     header exceeds this value, a {@link TooLongFrameException} will be raised.</td>
 * </tr>
 * <tr>
 * <td>{@code maxChunkSize}</td>
 * <td>{@value #DEFAULT_MAX_CHUNK_SIZE}</td>
 * <td>The maximum length of the content or each chunk.  If the content length
 *     (or the length of each chunk) exceeds this value, the content or chunk
 *     will be split into multiple {@link HttpContent}s whose length is
 *     {@code maxChunkSize} at maximum.</td>
 * </tr>
 * </table>
 *
 * <h3>Parameters that control parsing behavior</h3>
 * <table border="1">
 * <tr>
 * <th>Name</th><th>Default value</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code allowDuplicateContentLengths}</td>
 * <td>{@value #DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS}</td>
 * <td>When set to {@code false}, will reject any messages that contain multiple Content-Length header fields.
 *     When set to {@code true}, will allow multiple Content-Length headers only if they are all the same decimal value.
 *     The duplicated field-values will be replaced with a single valid Content-Length field.
 *     See <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230, Section 3.3.2</a>.</td>
 * </tr>
 * </table>
 *
 * <h3>Chunked Content</h3>
 *
 * If the content of an HTTP message is greater than {@code maxChunkSize} or
 * the transfer encoding of the HTTP message is 'chunked', this decoder
 * generates one {@link HttpMessage} instance and its following
 * {@link HttpContent}s per single HTTP message to avoid excessive memory
 * consumption. For example, the following HTTP message:
 * <pre>
 * GET / HTTP/1.1
 * Transfer-Encoding: chunked
 *
 * 1a
 * abcdefghijklmnopqrstuvwxyz
 * 10
 * 1234567890abcdef
 * 0
 * Content-MD5: ...
 * <i>[blank line]</i>
 * </pre>
 * triggers {@link HttpRequestDecoder} to generate 3 objects:
 * <ol>
 * <li>An {@link HttpRequest},</li>
 * <li>The first {@link HttpContent} whose content is {@code 'abcdefghijklmnopqrstuvwxyz'},</li>
 * <li>The second {@link LastHttpContent} whose content is {@code '1234567890abcdef'}, which marks
 * the end of the content.</li>
 * </ol>
 *
 * If you prefer not to handle {@link HttpContent}s by yourself for your
 * convenience, insert {@link HttpObjectAggregator} after this decoder in the
 * {@link ChannelPipeline}.  However, please note that your server might not
 * be as memory efficient as without the aggregator.
 *
 * <h3>Extensibility</h3>
 *
 * Please note that this decoder is designed to be extended to implement
 * a protocol derived from HTTP, such as
 * <a href="http://en.wikipedia.org/wiki/Real_Time_Streaming_Protocol">RTSP</a> and
 * <a href="http://en.wikipedia.org/wiki/Internet_Content_Adaptation_Protocol">ICAP</a>.
 * To implement the decoder of such a derived protocol, extend this class and
 * implement all abstract methods properly.
 */
public abstract class HttpObjectDecoder extends ByteToMessageDecoder {
    public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
    public static final int DEFAULT_MAX_HEADER_SIZE = 8192;
    public static final boolean DEFAULT_CHUNKED_SUPPORTED = true;
    public static final int DEFAULT_MAX_CHUNK_SIZE = 8192;
    public static final boolean DEFAULT_VALIDATE_HEADERS = true;
    public static final int DEFAULT_INITIAL_BUFFER_SIZE = 128;
    public static final boolean DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS = false;

    //请求头空值
    private static final String EMPTY_VALUE = "";
    private static final Pattern COMMA_PATTERN = Pattern.compile(",");

    //块的最大长度
    private final int maxChunkSize;
    //是否支持分块chunk发送
    private final boolean chunkedSupported;
    //是否验证头名字合法性
    protected final boolean validateHeaders;
    // 是否支持多长度
    private final boolean allowDuplicateContentLengths;
    //请求头解析器
    private final HeaderParser headerParser;
    //换行符解析器
    private final LineParser lineParser;

    //请求的消息，包括请求行和请求头
    private HttpMessage message;
    //保存下一次要读的消息体长度
    private long chunkSize;
    //消息体长度
    private long contentLength = Long.MIN_VALUE;
    //重置请求
    private volatile boolean resetRequested;

    // These will be updated by splitHeader(...)
    //头名字
    private CharSequence name;
    //头的值
    private CharSequence value;

    //请求体结尾
    private LastHttpContent trailer;

    /**
     * The internal state of {@link HttpObjectDecoder}.
     * <em>Internal use only</em>.
     * 状态
     */
    private enum State {
        SKIP_CONTROL_CHARS,//检查控制字符
        READ_INITIAL,//开始读取
        READ_HEADER,//读取头
        READ_VARIABLE_LENGTH_CONTENT,//读取可变长内容，用于chunk传输
        READ_FIXED_LENGTH_CONTENT,//读取固定长内容 用于Content-Length
        READ_CHUNK_SIZE,//chunk传输的每个chunk尺寸
        READ_CHUNKED_CONTENT,//每个chunk内容
        READ_CHUNK_DELIMITER,//chunk分割
        READ_CHUNK_FOOTER,//最后一个chunk
        BAD_MESSAGE,//无效消息
        UPGRADED//协议切换
    }

    // 当前状态
    private State currentState = State.SKIP_CONTROL_CHARS;

    /**
     * Creates a new instance with the default
     * {@code maxInitialLineLength (4096}}, {@code maxHeaderSize (8192)}, and
     * {@code maxChunkSize (8192)}.
     * 参数对应 一行最大长度，请求头的最大长度，请求体或者某个块的最大长度，是否支持chunk块传输
     */
    protected HttpObjectDecoder() {
        this(DEFAULT_MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_HEADER_SIZE, DEFAULT_MAX_CHUNK_SIZE,
             DEFAULT_CHUNKED_SUPPORTED);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    protected HttpObjectDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize, boolean chunkedSupported) {
        this(maxInitialLineLength, maxHeaderSize, maxChunkSize, chunkedSupported, DEFAULT_VALIDATE_HEADERS);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    protected HttpObjectDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize,
            boolean chunkedSupported, boolean validateHeaders) {
        this(maxInitialLineLength, maxHeaderSize, maxChunkSize, chunkedSupported, validateHeaders,
             DEFAULT_INITIAL_BUFFER_SIZE);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    protected HttpObjectDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize,
            boolean chunkedSupported, boolean validateHeaders, int initialBufferSize) {
        this(maxInitialLineLength, maxHeaderSize, maxChunkSize, chunkedSupported, validateHeaders, initialBufferSize,
             DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS);
    }

    protected HttpObjectDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize,
            boolean chunkedSupported, boolean validateHeaders, int initialBufferSize,
            boolean allowDuplicateContentLengths) {
        checkPositive(maxInitialLineLength, "maxInitialLineLength");
        checkPositive(maxHeaderSize, "maxHeaderSize");
        checkPositive(maxChunkSize, "maxChunkSize");

        // 可添加的字符序列
        //这个底层是一个字符数组，可以动态添加到最后
        AppendableCharSequence seq = new AppendableCharSequence(initialBufferSize);
        lineParser = new LineParser(seq, maxInitialLineLength);
        headerParser = new HeaderParser(seq, maxHeaderSize);
        this.maxChunkSize = maxChunkSize;
        this.chunkedSupported = chunkedSupported;
        this.validateHeaders = validateHeaders;
        this.allowDuplicateContentLengths = allowDuplicateContentLengths;
    }

    /**
     * 这个是最核心的方法，包括了解析请求行，请求头，请求体，
     * 但是会将请求行和请求头整合起来形成一个请求DefaultHttpRequest传递到后面，
     * 把请求体再封装成消息体传递到后面，
     * 因为请求体可能很大，所以也可能会有多次封装，那后面处理器就可能收到多次消息体。
     * 如果是GET的话是没有消息体的，首先收到一个DefaultHttpRequest，然后是一个空的LastHttpContent。
     * 如果是POST的话，先收到DefaultHttpRequest，
     * 然后可能多个内容DefaultHttpContent和一个DefaultLastHttpContent
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
        if (resetRequested) {
            resetNow();
        }

        switch (currentState) {
        //检查并略过控制字符  类似回车换行，空格这种
        case SKIP_CONTROL_CHARS:
            // Fall-through
        //开始读取
        //会开始读取一行，如果没有读到换行符，可能是因为数据还没收全，那就什么都不做，返回。
        //否则就开始分割，分割出方法，URI，协议，当然如果请求头无效，就不管了，重新返回到SKIP_CONTROL_CHARS状态。
        // 如果是有效的，就封装成请求消息HttpMessage包括请求行和请求头信息，讲状态切换到READ_HEADER读头信息
        case READ_INITIAL:
            //读取请求行
            try {
                //解析一行数据
                AppendableCharSequence line = lineParser.parse(buffer);
                //没解析到换行符
                if (line == null) {
                    return;
                }
                //行分割后的数组
                String[] initialLine = splitInitialLine(line);
                //小于3个就说明格式(方法 URI 版本)不对，直接忽略
                if (initialLine.length < 3) {
                    // Invalid initial line - ignore.
                    currentState = State.SKIP_CONTROL_CHARS;
                    return;
                }

                //创建请求消息
                message = createMessage(initialLine);
                currentState = State.READ_HEADER;
            // fall-through
            } catch (Exception e) {
                //invalidMessage无效消息
                out.add(invalidMessage(buffer, e));
                return;
            }
        // 请求头
        case READ_HEADER:
            //读取请求头
            try {
                State nextState = readHeaders(buffer);
                if (nextState == null) {
                    return;
                }
                currentState = nextState;
                switch (nextState) {
                    //没有内容，直接传递两个消息
                    case SKIP_CONTROL_CHARS:
                        // fast-path
                        // No content is expected.
                        out.add(message);
                        // 空内容
                        out.add(LastHttpContent.EMPTY_LAST_CONTENT);
                        resetNow();
                        return;
                    //块协议传递
                    case READ_CHUNK_SIZE:
                        if (!chunkedSupported) {
                            throw new IllegalArgumentException("Chunked messages not supported");
                        }
                        // Chunked encoding - generate HttpMessage first.  HttpChunks will follow.
                        out.add(message);
                        return;
                    default:
                        /**
                         * <a href="https://tools.ietf.org/html/rfc7230#section-3.3.3">RFC 7230, 3.3.3</a> states that if a
                         * request does not have either a transfer-encoding or a content-length header then the message body
                         * length is 0. However for a response the body length is the number of octets received prior to the
                         * server closing the connection. So we treat this as variable length chunked encoding.
                         */
                        //没有transfer-encoding或者content-length头 表示没消息体，比如GET请求
                        long contentLength = contentLength();
                        //没消息体，直接就补一个空消息体
                        if (contentLength == 0 || contentLength == -1 && isDecodingRequest()) {
                            out.add(message);//消息行和消息头
                            out.add(LastHttpContent.EMPTY_LAST_CONTENT);//空消息体
                            resetNow();//重置属性
                            return;
                        }

                        assert nextState == State.READ_FIXED_LENGTH_CONTENT ||
                                nextState == State.READ_VARIABLE_LENGTH_CONTENT;

                        //有消息体，就先放入行和头信息，下一次解码再进行消息体的读取
                        out.add(message);

                        if (nextState == State.READ_FIXED_LENGTH_CONTENT) {
                            // chunkSize will be decreased as the READ_FIXED_LENGTH_CONTENT state reads data chunk by chunk.
                            //如果是固定长度的消息体，要保存下一次要读的消息体长度
                            chunkSize = contentLength;
                        }

                        // We return here, this forces decode to be called again where we will decode the content
                        return;
                    }
            } catch (Exception e) {
                //异常了就无效
                out.add(invalidMessage(buffer, e));
                return;
            }
        //读取可变长内容
        case READ_VARIABLE_LENGTH_CONTENT: {
            //直接读取可读的字节，然后封装成DefaultHttpContent内容传递
            // Keep reading data as a chunk until the end of connection is reached.
            int toRead = Math.min(buffer.readableBytes(), maxChunkSize);
            if (toRead > 0) {
                ByteBuf content = buffer.readRetainedSlice(toRead);
                out.add(new DefaultHttpContent(content));
            }
            return;
        }
        //读取固定长内容
        //固定长度就是有contentLength，读取长度，
        // 如果等于记录的长度chunkSize ，就表示读完了，直接传递最后内容DefaultLastHttpContent。
        // 否则说明没读完，就传递内容DefaultHttpContent
        case READ_FIXED_LENGTH_CONTENT: {
            int readLimit = buffer.readableBytes();

            // Check if the buffer is readable first as we use the readable byte count
            // to create the HttpChunk. This is needed as otherwise we may end up with
            // create an HttpChunk instance that contains an empty buffer and so is
            // handled like it is the last HttpChunk.
            //
            // See https://github.com/netty/netty/issues/433
            if (readLimit == 0) {
                return;
            }

            //读取的个数
            int toRead = Math.min(readLimit, maxChunkSize);
            //如果大于块长度chunkSize，就读chunkSize个
            if (toRead > chunkSize) {
                toRead = (int) chunkSize;
            }
            ByteBuf content = buffer.readRetainedSlice(toRead);
            chunkSize -= toRead;

            //块全部读完了
            if (chunkSize == 0) {
                // Read all content.
                //创建最后一个内容体，返回
                out.add(new DefaultLastHttpContent(content, validateHeaders));
                //重置参数
                resetNow();
            } else {
                //还没读完，就创建一个消息体
                out.add(new DefaultHttpContent(content));
            }
            return;
        }
        /**
         * everything else after this point takes care of reading chunked content. basically, read chunk size,
         * read chunk, read and ignore the CRLF and repeat until 0
         * 读取块大小
         * 如果是chunk块传输，根据块传输协议，就应该是获取块大小
         */
        case READ_CHUNK_SIZE:
            try {
                AppendableCharSequence line = lineParser.parse(buffer);
                if (line == null) {
                    return;
                }
                //获取块尺寸
                int chunkSize = getChunkSize(line.toString());
                //块长度
                this.chunkSize = chunkSize;
                //读到块结束标记 0\r\n
                if (chunkSize == 0) {
                    currentState = State.READ_CHUNK_FOOTER;
                    return;
                }
                //继续读内容
                currentState = State.READ_CHUNKED_CONTENT;
                // fall-through
            } catch (Exception e) {
                //无效块
                out.add(invalidChunk(buffer, e));
                return;
            }
        //读取块内容
        //根据块长度chunkSize读取字节，
        // 如果读取长度等于chunkSize，表示读完了，需要读取分隔符，也就是换车换行了，
        // 状态转到READ_CHUNK_DELIMITER，
        // 否则就将读取的内容，封装成DefaultHttpContent传递下去，然后下一次继续读取内容
        case READ_CHUNKED_CONTENT: {
            //读取块内容，其实没读取，只是用切片，从切片读，不影响原来的
            assert chunkSize <= Integer.MAX_VALUE;
            int toRead = Math.min((int) chunkSize, maxChunkSize);
            toRead = Math.min(toRead, buffer.readableBytes());
            if (toRead == 0) {
                return;
            }
            //创建一个块，里面放的是切片
            HttpContent chunk = new DefaultHttpContent(buffer.readRetainedSlice(toRead));
            chunkSize -= toRead;

            out.add(chunk);

            if (chunkSize != 0) {
                //当前块还没接受完，就返回
                return;
            }
            //接受完，找到块分割符
            currentState = State.READ_CHUNK_DELIMITER;
            // fall-through
        }
        //读取块分隔符 回车换行符，找到了就转到READ_CHUNK_SIZE继续去取下一个块长度
        case READ_CHUNK_DELIMITER: {
            final int wIdx = buffer.writerIndex();
            int rIdx = buffer.readerIndex();
            while (wIdx > rIdx) {
                byte next = buffer.getByte(rIdx++);
                //找到换行符，继续读下一个块的大小
                if (next == HttpConstants.LF) {
                    // 转到READ_CHUNK_SIZE
                    currentState = State.READ_CHUNK_SIZE;
                    break;
                }
            }
            buffer.readerIndex(rIdx);
            return;
        }
        //读最后一个块
        case READ_CHUNK_FOOTER:
            //如果读取的块长度chunkSize=0的话，就说明是最后一个块了，
            // 然后要看下是否还有头信息在后面，有头信息的话会封装成DefaultLastHttpContent，
            // 如果没有的话头信息就是LastHttpContent.EMPTY_LAST_CONTENT
            try {
                //读取最后的内容，可能有头信息，也可能没有
                LastHttpContent trailer = readTrailingHeaders(buffer);
                //还没结束的，继续
                if (trailer == null) {
                    return;
                }
                //添加最后内容
                out.add(trailer);
                // 重置
                resetNow();
                return;
            } catch (Exception e) {
                out.add(invalidChunk(buffer, e));
                return;
            }
        //无效消息
        case BAD_MESSAGE: {
            // Keep discarding until disconnection.
            //坏消息，直接略过，不读
            buffer.skipBytes(buffer.readableBytes());
            break;
        }
        //协议切换
        case UPGRADED: {
            int readableBytes = buffer.readableBytes();
            if (readableBytes > 0) {
                // Keep on consuming as otherwise we may trigger an DecoderException,
                // other handler will replace this codec with the upgraded protocol codec to
                // take the traffic over at some point then.
                // See https://github.com/netty/netty/issues/2173
                out.add(buffer.readBytes(readableBytes));
            }
            break;
        }
        }
    }

    @Override
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decodeLast(ctx, in, out);

        if (resetRequested) {
            // If a reset was requested by decodeLast() we need to do it now otherwise we may produce a
            // LastHttpContent while there was already one.
            resetNow();
        }
        // Handle the last unfinished message.
        if (message != null) {
            boolean chunked = HttpUtil.isTransferEncodingChunked(message);
            if (currentState == State.READ_VARIABLE_LENGTH_CONTENT && !in.isReadable() && !chunked) {
                // End of connection.
                out.add(LastHttpContent.EMPTY_LAST_CONTENT);
                resetNow();
                return;
            }

            if (currentState == State.READ_HEADER) {
                // If we are still in the state of reading headers we need to create a new invalid message that
                // signals that the connection was closed before we received the headers.
                out.add(invalidMessage(Unpooled.EMPTY_BUFFER,
                        new PrematureChannelClosureException("Connection closed before received headers")));
                resetNow();
                return;
            }

            // Check if the closure of the connection signifies the end of the content.
            boolean prematureClosure;
            if (isDecodingRequest() || chunked) {
                // The last request did not wait for a response.
                prematureClosure = true;
            } else {
                // Compare the length of the received content and the 'Content-Length' header.
                // If the 'Content-Length' header is absent, the length of the content is determined by the end of the
                // connection, so it is perfectly fine.
                prematureClosure = contentLength() > 0;
            }

            if (!prematureClosure) {
                out.add(LastHttpContent.EMPTY_LAST_CONTENT);
            }
            resetNow();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof HttpExpectationFailedEvent) {
            switch (currentState) {
            case READ_FIXED_LENGTH_CONTENT:
            case READ_VARIABLE_LENGTH_CONTENT:
            case READ_CHUNK_SIZE:
                reset();
                break;
            default:
                break;
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    protected boolean isContentAlwaysEmpty(HttpMessage msg) {
        if (msg instanceof HttpResponse) {
            HttpResponse res = (HttpResponse) msg;
            int code = res.status().code();

            // Correctly handle return codes of 1xx.
            //
            // See:
            //     - http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html Section 4.4
            //     - https://github.com/netty/netty/issues/222
            if (code >= 100 && code < 200) {
                // One exception: Hixie 76 websocket handshake response
                return !(code == 101 && !res.headers().contains(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT)
                         && res.headers().contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true));
            }

            switch (code) {
            case 204: case 304:
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the server switched to a different protocol than HTTP/1.0 or HTTP/1.1, e.g. HTTP/2 or Websocket.
     * Returns false if the upgrade happened in a different layer, e.g. upgrade from HTTP/1.1 to HTTP/1.1 over TLS.
     */
    protected boolean isSwitchingToNonHttp1Protocol(HttpResponse msg) {
        if (msg.status().code() != HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
            return false;
        }
        String newProtocol = msg.headers().get(HttpHeaderNames.UPGRADE);
        return newProtocol == null ||
                !newProtocol.contains(HttpVersion.HTTP_1_0.text()) &&
                !newProtocol.contains(HttpVersion.HTTP_1_1.text());
    }

    /**
     * Resets the state of the decoder so that it is ready to decode a new message.
     * This method is useful for handling a rejected request with {@code Expect: 100-continue} header.
     */
    public void reset() {
        resetRequested = true;
    }

    private void resetNow() {
        HttpMessage message = this.message;
        this.message = null;
        name = null;
        value = null;
        contentLength = Long.MIN_VALUE;
        lineParser.reset();
        headerParser.reset();
        trailer = null;
        //不是请求解码，如果要升级协议
        if (!isDecodingRequest()) {
            HttpResponse res = (HttpResponse) message;
            if (res != null && isSwitchingToNonHttp1Protocol(res)) {
                currentState = State.UPGRADED;
                return;
            }
        }

        resetRequested = false;
        currentState = State.SKIP_CONTROL_CHARS;
    }

    //创建一个无效消息，
    // 状态直接为BAD_MESSAGE无效，把缓冲区内的数据直接都略过，
    // 如果请求消息没创建好，就创建一个，然后设置失败结果并带上异常信息返回。
    private HttpMessage invalidMessage(ByteBuf in, Exception cause) {
        //设置无效数据，这样后面同一个消息的数据都会被略过
        currentState = State.BAD_MESSAGE;

        // Advance the readerIndex so that ByteToMessageDecoder does not complain
        // when we produced an invalid message without consuming anything.
        //直接不可读，略过可读数据
        in.skipBytes(in.readableBytes());

        if (message == null) {
            //直接返回完整的请求消息，参数设置成有问题的就可以了. 子类实现
            message = createInvalidMessage();
        }
        //设置失败
        message.setDecoderResult(DecoderResult.failure(cause));

        HttpMessage ret = message;
        message = null;
        return ret;
    }

    private HttpContent invalidChunk(ByteBuf in, Exception cause) {
        currentState = State.BAD_MESSAGE;

        // Advance the readerIndex so that ByteToMessageDecoder does not complain
        // when we produced an invalid message without consuming anything.
        in.skipBytes(in.readableBytes());

        HttpContent chunk = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER);
        chunk.setDecoderResult(DecoderResult.failure(cause));
        message = null;
        trailer = null;
        return chunk;
    }

    //主要就是按行解析头消息，然后进行头信息分割，然后放入headers ，
    // 最后根据content-length来决定后面的状态，
    // 是读取固定长READ_FIXED_LENGTH_CONTENT
    // 还是可变长READ_VARIABLE_LENGTH_CONTENT，
    // 还是是读取块大小READ_CHUNK_SIZE

    // 如果是HTTP1.1一个头只能对应一个值，而且Content-Length和Transfer-Encoding不能同时存在。
    // http1.0以及之前或者http1.1没设置keepalive的话Content-Length可有可无
    private State readHeaders(ByteBuf buffer) {
        final HttpMessage message = this.message;
        //获得请求头
        final HttpHeaders headers = message.headers();

        //解析请求头
        AppendableCharSequence line = headerParser.parse(buffer);
        if (line == null) {
            return null;
        }
        if (line.length() > 0) {
            do {
                char firstChar = line.charAtUnsafe(0);
                if (name != null && (firstChar == ' ' || firstChar == '\t')) {
                    //please do not make one line from below code
                    //as it breaks +XX:OptimizeStringConcat optimization
                    String trimmedLine = line.toString().trim();
                    String valueStr = String.valueOf(value);
                    value = valueStr + ' ' + trimmedLine;
                } else {
                    if (name != null) {
                        //如果名字解析出来表示值也出来了，就添加进去
                        headers.add(name, value);
                    }
                    //分割请求头
                    splitHeader(line);
                }

                //继续解析头
                line = headerParser.parse(buffer);
                if (line == null) {
                    return null;
                }
            } while (line.length() > 0);
        }

        // Add the last header.
        //添加最后一个
        if (name != null) {
            headers.add(name, value);
        }

        // reset name and value fields
        // 重置
        name = null;
        value = null;

        //找content-length头信息
        List<String> contentLengthFields = headers.getAll(HttpHeaderNames.CONTENT_LENGTH);

        // 有content-length
        if (!contentLengthFields.isEmpty()) {
            // Guard against multiple Content-Length headers as stated in
            // https://tools.ietf.org/html/rfc7230#section-3.3.2:
            //
            // If a message is received that has multiple Content-Length header
            //   fields with field-values consisting of the same decimal value, or a
            //   single Content-Length header field with a field value containing a
            //   list of identical decimal values (e.g., "Content-Length: 42, 42"),
            //   indicating that duplicate Content-Length header fields have been
            //   generated or combined by an upstream message processor, then the
            //   recipient MUST either reject the message as invalid or replace the
            //   duplicated field-values with a single valid Content-Length field
            //   containing that decimal value prior to determining the message body
            //   length or forwarding the message.
            // 是否多个
            boolean multipleContentLengths =
                    contentLengthFields.size() > 1 || contentLengthFields.get(0).indexOf(COMMA) >= 0;
            // 多个，并且版本是1.1
            if (multipleContentLengths && message.protocolVersion() == HttpVersion.HTTP_1_1) {
                // 支持多长度
                if (allowDuplicateContentLengths) {
                    // Find and enforce that all Content-Length values are the same
                    String firstValue = null;
                    for (String field : contentLengthFields) {
                        // 分割
                        String[] tokens = COMMA_PATTERN.split(field, -1);
                        for (String token : tokens) {
                            String trimmed = token.trim();
                            if (firstValue == null) {
                                firstValue = trimmed;
                            } else if (!trimmed.equals(firstValue)) {
                                throw new IllegalArgumentException(
                                        "Multiple Content-Length values found: " + contentLengthFields);
                            }
                        }
                    }
                    // Replace the duplicated field-values with a single valid Content-Length field
                    // 取一个
                    headers.set(HttpHeaderNames.CONTENT_LENGTH, firstValue);
                    // 设置消息体长度
                    contentLength = Long.parseLong(firstValue);
                } else {
                    // Reject the message as invalid
                    throw new IllegalArgumentException(
                            "Multiple Content-Length values found: " + contentLengthFields);
                }
            } else {
                // 只有一个，设置消息体长度
                contentLength = Long.parseLong(contentLengthFields.get(0));
            }
        }

        //空内容
        if (isContentAlwaysEmpty(message)) {
            HttpUtil.setTransferEncodingChunked(message, false);//不开启块传输
            return State.SKIP_CONTROL_CHARS;
        } else if (HttpUtil.isTransferEncodingChunked(message)) {
            //HTTP_1_1如果开启了块协议，就不能设置Content-Length了
            if (!contentLengthFields.isEmpty() && message.protocolVersion() == HttpVersion.HTTP_1_1) {
                handleTransferEncodingChunkedWithContentLength(message);
            }
            //块传输，要获取大小
            return State.READ_CHUNK_SIZE;
        } else if (contentLength() >= 0) {
            //可以固定长度解析消息体
            return State.READ_FIXED_LENGTH_CONTENT;
        } else {
            //可变长度解析，或者没有Content-Length，http1.0以及之前或者1.1 非keep alive,Content-Length可有可无
            return State.READ_VARIABLE_LENGTH_CONTENT;
        }
    }

    /**
     * Invoked when a message with both a "Transfer-Encoding: chunked" and a "Content-Length" header field is detected.
     * The default behavior is to <i>remove</i> the Content-Length field, but this method could be overridden
     * to change the behavior (to, e.g., throw an exception and produce an invalid message).
     * <p>
     * See: https://tools.ietf.org/html/rfc7230#section-3.3.3
     * <pre>
     *     If a message is received with both a Transfer-Encoding and a
     *     Content-Length header field, the Transfer-Encoding overrides the
     *     Content-Length.  Such a message might indicate an attempt to
     *     perform request smuggling (Section 9.5) or response splitting
     *     (Section 9.4) and ought to be handled as an error.  A sender MUST
     *     remove the received Content-Length field prior to forwarding such
     *     a message downstream.
     * </pre>
     * Also see:
     * https://github.com/apache/tomcat/blob/b693d7c1981fa7f51e58bc8c8e72e3fe80b7b773/
     * java/org/apache/coyote/http11/Http11Processor.java#L747-L755
     * https://github.com/nginx/nginx/blob/0ad4393e30c119d250415cb769e3d8bc8dce5186/
     * src/http/ngx_http_request.c#L1946-L1953
     */
    protected void handleTransferEncodingChunkedWithContentLength(HttpMessage message) {
        message.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        contentLength = Long.MIN_VALUE;
    }

    private long contentLength() {
        if (contentLength == Long.MIN_VALUE) {
            contentLength = HttpUtil.getContentLength(message, -1L);
        }
        return contentLength;
    }

    //会去读取一行，如果没读出来换行，表示可能没收到数据，也就是没读完，那就返回，继续下一次。
    //如果读出来发现就只有回车换行，那就说明没有头信息，结束了，就返回一个 LastHttpContent.EMPTY_LAST_CONTENT，
    // 否则的话就创建一个DefaultLastHttpContent内容，然后进行头信息的解析，解析出来的头信息就放入内容中，并返回内容
    private LastHttpContent readTrailingHeaders(ByteBuf buffer) {
        AppendableCharSequence line = headerParser.parse(buffer);
        //没有换行，表示没读完呢
        if (line == null) {
            return null;
        }
        LastHttpContent trailer = this.trailer;
        //直接读到\r\n 即读到空行，表示结束,无头信息，返回空内容
        if (line.length() == 0 && trailer == null) {
            // We have received the empty line which signals the trailer is complete and did not parse any trailers
            // before. Just return an empty last content to reduce allocations.
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }

        CharSequence lastHeader = null;
        if (trailer == null) {
            trailer = this.trailer = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, validateHeaders);//空内容
        }
        //chunk最后可能还有头信息 key: v \r\n
        while (line.length() > 0) {
            char firstChar = line.charAtUnsafe(0);
            if (lastHeader != null && (firstChar == ' ' || firstChar == '\t')) {
                List<String> current = trailer.trailingHeaders().getAll(lastHeader);
                if (!current.isEmpty()) {
                    int lastPos = current.size() - 1;
                    //please do not make one line from below code
                    //as it breaks +XX:OptimizeStringConcat optimization
                    String lineTrimmed = line.toString().trim();
                    String currentLastPos = current.get(lastPos);
                    current.set(lastPos, currentLastPos + lineTrimmed);
                }
            } else {
                //解析头信息
                splitHeader(line);
                CharSequence headerName = name;
                if (!HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(headerName) &&
                        !HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(headerName) &&
                        !HttpHeaderNames.TRAILER.contentEqualsIgnoreCase(headerName)) {
                    trailer.trailingHeaders().add(headerName, value);
                }
                lastHeader = name;
                // reset name and value fields
                name = null;
                value = null;
            }
            line = headerParser.parse(buffer);
            if (line == null) {
                return null;
            }
        }

        this.trailer = null;
        return trailer;
    }

    protected abstract boolean isDecodingRequest();
    protected abstract HttpMessage createMessage(String[] initialLine) throws Exception;
    protected abstract HttpMessage createInvalidMessage();

    //这里连;，空格，控制字符都算截止符了
    private static int getChunkSize(String hex) {
        hex = hex.trim();
        for (int i = 0; i < hex.length(); i ++) {
            char c = hex.charAt(i);
            if (c == ';' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                hex = hex.substring(0, i);
                break;
            }
        }

        return Integer.parseInt(hex, 16);
    }

    //执行了3次检测，刚好把请求行给分割出来，最后用字符串切割出来封装成数组返回
    private static String[] splitInitialLine(AppendableCharSequence sb) {
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;

        //找出不是空格的第一个索引
        aStart = findNonSPLenient(sb, 0);
        //找出空格索引
        aEnd = findSPLenient(sb, aStart);

        bStart = findNonSPLenient(sb, aEnd);
        bEnd = findSPLenient(sb, bStart);

        cStart = findNonSPLenient(sb, bEnd);
        cEnd = findEndOfString(sb);

        return new String[] {
                sb.subStringUnsafe(aStart, aEnd),
                sb.subStringUnsafe(bStart, bEnd),
                cStart < cEnd? sb.subStringUnsafe(cStart, cEnd) : "" };
    }

    private void splitHeader(AppendableCharSequence sb) {
        final int length = sb.length();
        int nameStart;
        int nameEnd;
        int colonEnd;
        int valueStart;
        int valueEnd;

        nameStart = findNonWhitespace(sb, 0, false);
        for (nameEnd = nameStart; nameEnd < length; nameEnd ++) {
            char ch = sb.charAtUnsafe(nameEnd);
            // https://tools.ietf.org/html/rfc7230#section-3.2.4
            //
            // No whitespace is allowed between the header field-name and colon. In
            // the past, differences in the handling of such whitespace have led to
            // security vulnerabilities in request routing and response handling. A
            // server MUST reject any received request message that contains
            // whitespace between a header field-name and colon with a response code
            // of 400 (Bad Request). A proxy MUST remove any such whitespace from a
            // response message before forwarding the message downstream.
            if (ch == ':' ||
                    // In case of decoding a request we will just continue processing and header validation
                    // is done in the DefaultHttpHeaders implementation.
                    //
                    // In the case of decoding a response we will "skip" the whitespace.
                    (!isDecodingRequest() && isOWS(ch))) {
                break;
            }
        }

        if (nameEnd == length) {
            // There was no colon present at all.
            throw new IllegalArgumentException("No colon found");
        }

        for (colonEnd = nameEnd; colonEnd < length; colonEnd ++) {
            if (sb.charAtUnsafe(colonEnd) == ':') {
                colonEnd ++;
                break;
            }
        }

        name = sb.subStringUnsafe(nameStart, nameEnd);
        valueStart = findNonWhitespace(sb, colonEnd, true);
        if (valueStart == length) {
            value = EMPTY_VALUE;
        } else {
            valueEnd = findEndOfString(sb);
            value = sb.subStringUnsafe(valueStart, valueEnd);
        }
    }

    private static int findNonSPLenient(AppendableCharSequence sb, int offset) {
        for (int result = offset; result < sb.length(); ++result) {
            char c = sb.charAtUnsafe(result);
            // See https://tools.ietf.org/html/rfc7230#section-3.5
            if (isSPLenient(c)) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                // Any other whitespace delimiter is invalid
                throw new IllegalArgumentException("Invalid separator");
            }
            return result;
        }
        return sb.length();
    }

    private static int findSPLenient(AppendableCharSequence sb, int offset) {
        for (int result = offset; result < sb.length(); ++result) {
            if (isSPLenient(sb.charAtUnsafe(result))) {
                return result;
            }
        }
        return sb.length();
    }

    private static boolean isSPLenient(char c) {
        // See https://tools.ietf.org/html/rfc7230#section-3.5
        return c == ' ' || c == (char) 0x09 || c == (char) 0x0B || c == (char) 0x0C || c == (char) 0x0D;
    }

    private static int findNonWhitespace(AppendableCharSequence sb, int offset, boolean validateOWS) {
        for (int result = offset; result < sb.length(); ++result) {
            char c = sb.charAtUnsafe(result);
            if (!Character.isWhitespace(c)) {
                return result;
            } else if (validateOWS && !isOWS(c)) {
                // Only OWS is supported for whitespace
                throw new IllegalArgumentException("Invalid separator, only a single space or horizontal tab allowed," +
                        " but received a '" + c + "'");
            }
        }
        return sb.length();
    }

    private static int findEndOfString(AppendableCharSequence sb) {
        for (int result = sb.length() - 1; result > 0; --result) {
            if (!Character.isWhitespace(sb.charAtUnsafe(result))) {
                return result + 1;
            }
        }
        return 0;
    }

    private static boolean isOWS(char ch) {
        return ch == ' ' || ch == (char) 0x09;
    }

    private static class HeaderParser implements ByteProcessor {
        //可添加的字符序列
        private final AppendableCharSequence seq;
        //最大长度
        private final int maxLength;
        //索引
        private int size;

        HeaderParser(AppendableCharSequence seq, int maxLength) {
            this.seq = seq;
            this.maxLength = maxLength;
        }

        //解析缓冲区
        public AppendableCharSequence parse(ByteBuf buffer) {
            final int oldSize = size;
            seq.reset();
            // 这里将处理器传进去，会调用下面的process方法，如果遇到换行符，会返回相应的索引，如果没有返回-1
            int i = buffer.forEachByte(this);
            // 没读到换行，或者报异常了
            if (i == -1) {
                size = oldSize;
                return null;
            }
            buffer.readerIndex(i + 1);
            return seq;
        }

        //读到的字符个数清零
        public void reset() {
            size = 0;
        }

        //处理数据，遇到换行了就结束
        @Override
        public boolean process(byte value) throws Exception {
            char nextByte = (char) (value & 0xFF);
            //遇到换行符
            if (nextByte == HttpConstants.LF) {
                int len = seq.length();
                // Drop CR if we had a CRLF pair
                //遇到回车符
                if (len >= 1 && seq.charAtUnsafe(len - 1) == HttpConstants.CR) {
                    -- size;
                    seq.setLength(len - 1);
                }
                return false;
            }

            increaseCount();

            //添加
            seq.append(nextByte);
            return true;
        }

        protected final void increaseCount() {
            //溢出了
            if (++ size > maxLength) {
                // TODO: Respond with Bad Request and discard the traffic
                //    or close the connection.
                //       No need to notify the upstream handlers - just log.
                //       If decoding a response, just throw an exception.
                throw newException(maxLength);
            }
        }

        // 头消息过大异常
        protected TooLongFrameException newException(int maxLength) {
            return new TooLongFrameException("HTTP header is larger than " + maxLength + " bytes.");
        }
    }

    //继承了头解析器，只是解析的时候要reset一下，就是把读到的个数清0，因为是一行行读，每次读完一行就得清理个数
    private final class LineParser extends HeaderParser {

        LineParser(AppendableCharSequence seq, int maxLength) {
            super(seq, maxLength);
        }

        @Override
        public AppendableCharSequence parse(ByteBuf buffer) {
            //从头开始，要重置索引
            reset();
            return super.parse(buffer);
        }

        @Override
        public boolean process(byte value) throws Exception {
            if (currentState == State.SKIP_CONTROL_CHARS) {
                char c = (char) (value & 0xFF);
                if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                    increaseCount();
                    return true;
                }
                currentState = State.READ_INITIAL;
            }
            return super.process(value);
        }

        @Override
        protected TooLongFrameException newException(int maxLength) {
            return new TooLongFrameException("An HTTP line is larger than " + maxLength + " bytes.");
        }
    }
}
