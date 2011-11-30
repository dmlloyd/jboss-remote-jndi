/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.naming.remote;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.MessageInputStream;
import org.jboss.remoting3.MessageOutputStream;

import org.xnio.IoUtils;

import javax.naming.CommunicationException;
import javax.naming.InsufficientResourcesException;
import javax.naming.NamingException;
import javax.naming.event.NamingListener;

import static org.jboss.naming.remote.Log.log;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class NamingClient {
    private final Channel channel;

    @SuppressWarnings("unused")
    private volatile int state;
    private volatile long requestIds = 0xFFFFFFFFFFFFFFFFL;
    private final AtomicReferenceArray<ResultHolder<?>> holders = new AtomicReferenceArray<ResultHolder<?>>(64);

    private static final AtomicIntegerFieldUpdater<NamingClient> stateUpdater = AtomicIntegerFieldUpdater.newUpdater(NamingClient.class, "state");
    private static final AtomicLongFieldUpdater<NamingClient> requestIdsUpdater = AtomicLongFieldUpdater.newUpdater(NamingClient.class, "requestIds");

    private static final int CLOSED = (1 << 31);
    private static final int FLAGS_MASK = (CLOSED);
    private static final int COUNT_MASK = ~FLAGS_MASK;
    private final MarshallerFactory factory;

    NamingClient(final Channel channel, final MarshallerFactory factory) {
        this.channel = channel;
        this.factory = factory;
    }

    private static int readByte(InputStream is) throws IOException {
        final int v = is.read();
        if (v == -1) {
            throw new EOFException();
        }
        return v;
    }

    void start() {
        channel.receiveMessage(new Channel.Receiver() {
            public void handleError(final Channel channel, final IOException error) {
                IoUtils.safeClose(channel);
                log.channelError(channel, error);
            }

            public void handleEnd(final Channel channel) {
                log.channelEOF(channel);
            }

            public void handleMessage(final Channel channel, final MessageInputStream message) {
                boolean ok = false;
                try {
                    final int msg = readByte(message);
                    switch (msg) {
                        case Protocol.MSG_RESPONSE: {
                            int id = readByte(message);
                            if (id > 63) {
                                // ignore!
                                return;
                            }
                            final ResultHolder<?> resultHolder = releaseRequestId(id);
                            if (resultHolder == null) {
                                // ignore!
                                return;
                            }
                            resultHolder.setStreamResult(message);
                            ok = true;
                            break;
                        }
                        default: {
                            // unknown!
                            return;
                        }
                    }
                } catch (IOException e) {
                    // todo: log it
                } finally {
                    if (! ok) {
                        IoUtils.safeClose(message);
                    }
                    channel.receiveMessage(this);
                }
            }
        });
    }

    private void enter() throws NamingException {
        int oldState, newState;
        do {
            oldState = state;
            if ((state & CLOSED) != 0) {
                throw new NamingException("Context has been closed");
            }
            newState = oldState + 1;
        } while (! stateUpdater.compareAndSet(this, oldState, newState));
    }

    private void exit() {
        if (stateUpdater.decrementAndGet(this) == CLOSED) {
            // we're the last caller to exit
            IoUtils.safeClose(channel);
        }
    }

    private int getRequestId() {
        long old, bit;
        do {
            old = requestIds;
            if (old == 0L) {
                return -1;
            }
            bit = Long.lowestOneBit(old);
        } while (! requestIdsUpdater.compareAndSet(this, old, old ^ bit));
        return Long.numberOfTrailingZeros(bit);
    }

    ResultHolder<?> releaseRequestId(final int requestId) {
        long old, bit = (1L << (long) requestId);
        final ResultHolder<?> holder = holders.getAndSet(requestId, null);
        do {
            old = requestIds;
            if ((old | bit) == old) {
                // ????????
                return holder;
            }
        } while (! requestIdsUpdater.compareAndSet(this, old, old | bit));
        exit();
        return holder;
    }

    <T> MessageOutputStream sendRequest(int msg, ResultHolder<T> resultHolder) {
        try {
            enter();
        } catch (NamingException e) {
            resultHolder.setException(e);
            return null;
        }
        final int requestId = getRequestId();
        if (requestId == -1) {
            resultHolder.setException(new InsufficientResourcesException("Too many concurrent outstanding requests"));
        }
        holders.set(requestId, resultHolder);
        boolean ok = false;
        try {
            final MessageOutputStream stream = channel.writeMessage();
            try {
                stream.write(msg);
                stream.write(requestId);
                ok = true;
                return stream;
            } finally {
                if (! ok) {
                    stream.cancel();
                    IoUtils.safeClose(stream);
                }
            }
        } catch (IOException e) {
            resultHolder.setException(new CommunicationException("Failed to send request: " + e.toString()));
            return null;
        } finally {
            if (! ok) {
                releaseRequestId(requestId);
            }
        }
    }

    void contextClosing(final RemoteContext context) {

    }

    void contextClosed(final RemoteContext context) {

    }

    ListenerRegistration addListener(final RemoteContext context, final Object name, final int scope, final NamingListener listener) {
        return null;
    }

    public <T> ResultHolder<T> createResultHolder(final Class<T> type, final MarshallingConfiguration configuration) {
        return new ResultHolder<T>(type, factory, configuration);
    }

    public Marshaller createMarshaller(final MarshallingConfiguration config) throws IOException {
        return factory.createMarshaller(config);
    }
}
