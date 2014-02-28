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
import io.netty.channel.ChannelFlushPromiseNotifier;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.internal.StringUtil;

import static io.netty.handler.codec.http.HttpConstants.*;

/**
 * Encodes an {@link HttpResponse} or an {@link HttpContent} into
 * a {@link ByteBuf}.
 */
public class HttpResponseEncoder extends HttpObjectEncoder<HttpResponse> {
    private static final byte[] CRLF = { CR, LF };
    private final ChannelFlushPromiseNotifier notifier = new ChannelFlushPromiseNotifier();
    private final int bufferSize;
    private ByteBuf buffer;

    public HttpResponseEncoder() {
        this(4096);
    }

    public HttpResponseEncoder(int bufferSize) {
        if (bufferSize < 0) {
            throw new IllegalArgumentException(
                    "bufferSize must be a >= 0 ");
        }
        this.bufferSize = bufferSize;
    }

    @Override
    public boolean acceptOutboundMessage(Object msg) throws Exception {
        return super.acceptOutboundMessage(msg) && !(msg instanceof HttpRequest);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (bufferSize != 0) {
            if (msg instanceof FullHttpResponse) {
                if (state() != ST_INIT) {
                    throw new IllegalStateException("unexpected message type: " + StringUtil.simpleClassName(msg));
                }

                // fast path as this is the one which is most of the time used by the user
                FullHttpResponse response = (FullHttpResponse) msg;
                int state = HttpHeaders.isTransferEncodingChunked(response) ? ST_CONTENT_CHUNK : ST_CONTENT_NON_CHUNK;
                if (state == ST_CONTENT_NON_CHUNK) {
                    if (buffer == null) {
                        buffer = ctx.alloc().buffer(bufferSize);
                    }
                    ByteBuf buf = buffer;
                    int readable = buf.readableBytes();

                    // Encode the request.
                    encodeInitialLine(buf, response);
                    HttpHeaders.encode(response.headers(), buf);
                    buf.writeBytes(CRLF);
                    buf.writeBytes(response.content());
                    response.release();

                    // add the promise to the notifier so it is notified later once something was written.
                    notifier.add(promise, buf.readableBytes() - readable);

                    return;
                }
            }
            if (buffer != null) {
                // something is in the buffer so we write it now to garanteer the correct order
                writeBufferedData(ctx);
            }
        }
        super.write(ctx, msg, promise);
    }

    /**
     * Write the buffered data and notify the futures once it was written.
     */
    private void writeBufferedData(ChannelHandlerContext ctx) {
        ByteBuf buf = buffer;
        final int length = buf.readableBytes();
        buffer = null;
        ctx.write(buf).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                notifier.increaseWriteCounter(length);
                if (future.isSuccess()) {
                    notifier.notifyFlushFutures();
                } else {
                    notifier.notifyFlushFutures(future.cause());
                }
            }
        });
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (buffer != null) {
            // something is in the buffer write it now and then flush
            writeBufferedData(ctx);
        }
        super.flush(ctx);
    }

    @Override
    protected void encodeInitialLine(ByteBuf buf, HttpResponse response) throws Exception {
        response.getProtocolVersion().encode(buf);
        buf.writeByte(SP);
        response.getStatus().encode(buf);
        buf.writeBytes(CRLF);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (buffer != null) {
            writeBufferedData(ctx);
        }
        super.handlerRemoved(ctx);
    }
}
