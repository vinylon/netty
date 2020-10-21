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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 * 这个是换行符"\r\n"或者"\n"的分割
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /** Maximum length of a frame we're willing to decode.  */
    private final int maxLength;
    /** Whether or not to throw an exception as soon as we exceed maxLength. */
    //超过长度是否马上抛出异常，无论是不是把数据全读完了，true超过就抛，false读完整个数据后抛
    private final boolean failFast;
    //解码后的数据是否要去除分割符
    private final boolean stripDelimiter;

    /** True if we're discarding input because we're already over maxLength.  */
    //是否正在丢弃输入数据
    private boolean discarding;
    //丢弃的数据长度
    private int discardedBytes;

    /** Last scan position. */
    //最后一次扫描的索引位置
    private int offset;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * 上一次没有丢弃可读数据过的。如果找到分割符了，如果长度没超出，就根据是否要略过分隔符返回相应长度的切片，
     * 如果超出了就设置读索引到分隔符之后并抛出异常。
     *
     * 上一次没有丢弃可读数据过的。如果没找到分隔符，长度又超过了最大长度就丢弃，设置丢弃数量，设置读索引到最后。
     * 根据需求抛出异常。
     *
     * 上一次有丢弃可读数据过的。如果找到分割符了，不处理，直接设置读索引到分隔符之后，
     * 因为这个是上一次丢弃的那部分所属的同一个消息的，都不要了。
     *
     * 上一次有丢弃可读数据过的。如果没有找到分割符了，继续丢弃，直接略过可读的数据。
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        final int eol = findEndOfLine(buffer);
        if (!discarding) {//没有丢弃过可读的
            if (eol >= 0) {//找到分割符了
                final ByteBuf frame;
                final int length = eol - buffer.readerIndex();
                //如果是'\r'，分割符是2个字节'\r\n',否则是一个'\n'
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;

                //可读数据超过最大长度了，直接不要了
                if (length > maxLength) {
                    //设置读索引为分隔符索引之后
                    buffer.readerIndex(eol + delimLength);
                    //直接抛异常
                    fail(ctx, length);
                    return null;
                }

                //如果略过分隔符的
                if (stripDelimiter) {
                    //获取长度为length的切片
                    frame = buffer.readRetainedSlice(length);
                    //buffer略过分隔符
                    buffer.skipBytes(delimLength);
                } else {
                    //包括分隔符在内的切片
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                return frame;
            } else {
                //没找到分割符，不会读取，不改变读索引
                final int length = buffer.readableBytes();
                //超过最大长度，也没找到分隔符
                if (length > maxLength) {
                    //丢弃可读的
                    discardedBytes = length;
                    //直接略过可读的，设置为不可读
                    buffer.readerIndex(buffer.writerIndex());
                    //有丢弃了
                    discarding = true;
                    offset = 0;
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null;
            }
        } else {
            //前面有丢弃的话，后面跟着的也不要了
            if (eol >= 0) {
                final int length = discardedBytes + eol - buffer.readerIndex();
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                //直接略过前面的一部分了
                buffer.readerIndex(eol + delimLength);
                discardedBytes = 0;
                discarding = false;
                if (!failFast) {
                    fail(ctx, length);
                }
            } else {//还是没找到分隔符
                //增加丢弃数量
                discardedBytes += buffer.readableBytes();
                //直接略过可读的，设置为不可读
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    //超出长度报异常
    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     * 可能是换行符"\r\n"或者"\n"，所以位置会相差1，如果找到了索引offset 就从0开始，否则就从上一次长度的最后开始
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        if (i >= 0) {//找到了换行符
            offset = 0;
            if (i > 0 && buffer.getByte(i - 1) == '\r') {//如果索引不是0，且前一个是'\r'，就返回前一个的索引
                i--;
            }
        } else {
            offset = totalLength;
        }
        return i;
    }
}
