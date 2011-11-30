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
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.LockSupport;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.remoting3.MessageInputStream;
import org.xnio.IoUtils;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.ServiceUnavailableException;

import static org.jboss.naming.remote.Log.log;

/**
 * A result holder which supports up to one waiting thread.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class ResultHolder<T> {
    private static final Object WAITING = new Object();
    private static final Object CANCELLED = new Object();

    private final Class<T> resultType;
    private final MarshallerFactory factory;
    private final MarshallingConfiguration configuration;

    /**
     * Contains either {@code WAITING} thread (waiting with a waiter), an exception, or {@link #CANCELLED}, or the actual result (may be {@code null}).
     */
    private volatile Object v = WAITING;

    private static final AtomicReferenceFieldUpdater<ResultHolder, Object> resultUpdater = AtomicReferenceFieldUpdater.newUpdater(ResultHolder.class, Object.class, "v");

    ResultHolder(final Class<T> resultType, final MarshallerFactory factory, final MarshallingConfiguration configuration) {
        this.resultType = resultType;
        this.factory = factory;
        this.configuration = configuration;
    }

    boolean setException(NamingException e) {
        Object old;
        do {
            old = v;
            if (old == CANCELLED || old instanceof Throwable) {
                return false;
            }
        } while (! resultUpdater.compareAndSet(this, old, e));
        if (old != null) {
            LockSupport.unpark((Thread) old);
        }
        return true;
    }

    boolean setCancelled() {
        Object old;
        do {
            old = v;
            if (old == CANCELLED) {
                return true;
            }
            if (old != WAITING && ! (old instanceof Thread)) {
                return false;
            }
        } while (! resultUpdater.compareAndSet(this, old, CANCELLED));
        if (old != null) {
            LockSupport.unpark((Thread) old);
        }
        return true;
    }

    /**
     * Wait for the result.
     *
     * @return {@code false} if interrupted, {@code true} if completed
     */
    boolean await() {
        final Thread myThread = Thread.currentThread();
        if (myThread.isInterrupted()) {
            return false;
        }
        Object old;
        do {
            old = v;
            if (old instanceof Thread) {
                throw new IllegalStateException("Waiter exists!");
            }
            if (old != WAITING) {
                return true;
            }
        } while (! resultUpdater.compareAndSet(this, old, myThread));
        do {
            LockSupport.park(this);
            if (myThread.isInterrupted()) {
                return ! resultUpdater.compareAndSet(this, myThread, null);
            }
        } while (v == myThread);
        return true;
    }

    private static int readByte(ByteInput input) throws IOException {
        final int v = input.read();
        if (v == -1) {
            throw new EOFException();
        }
        return v;
    }

    T getResult() throws NamingException {
        Object old;
        old = resultUpdater.getAndSet(this, null);
        if (old == CANCELLED) {
            throw new ServiceUnavailableException("Operation was cancelled by the user");
        }
        if (old instanceof NamingException) {
            throw (NamingException) old;
        }
        final MessageInputStream message = (MessageInputStream) old;
        try {
            final ByteInput input = Marshalling.createByteInput(message);
            int b = readByte(input);
            if (b == 0) {
                // success!
                if (resultType == Void.class) {
                    return null;
                } else {
                    final Unmarshaller unmarshaller = factory.createUnmarshaller(configuration);
                    unmarshaller.start(input);
                    return unmarshaller.readObject(resultType);
                }
            } else {
                // error; construct a new exception.
                Throwable cause = null;
                try {
                    final Unmarshaller unmarshaller = factory.createUnmarshaller(configuration);
                    unmarshaller.start(input);
                    cause = unmarshaller.readObject(Throwable.class);
                    unmarshaller.finish();
                } catch (Throwable t) {
                    // no cause is retrievable
                }
                throw decode(b, cause);
            }
        } catch (ClassNotFoundException e) {
            throw log.errorReadingReply(e);
        } catch (IOException e) {
            throw log.errorReadingReply(e);
        } finally {
            IoUtils.safeClose(message);
        }
    }

    private NamingException decode(final int code, final Throwable cause) {
        switch (code) {
            default: {
                return log.errOther(cause);
            }
        }
    }

    boolean setStreamResult(final MessageInputStream message) {
        Object old;
        do {
            old = v;
            if (old == CANCELLED || old instanceof Throwable) {
                return false;
            }
        } while (! resultUpdater.compareAndSet(this, old, message));
        if (old != null) {
            LockSupport.unpark((Thread) old);
        }
        return true;
    }

    NamingEnumeration<T> getResultAsEnumeration() throws NamingException {
        Object old;
        old = resultUpdater.getAndSet(this, null);
        if (old == CANCELLED) {
            throw new ServiceUnavailableException("Operation was cancelled by the user");
        }
        if (old instanceof NamingException) {
            throw (NamingException) old;
        }
        final MessageInputStream message = (MessageInputStream) old;
        try {
            final ByteInput input = Marshalling.createByteInput(message);
            int b = readByte(input);
            if (b == 0) {
                // success!
                if (resultType == Void.class) {
                    return null;
                } else {
                    final Unmarshaller unmarshaller = factory.createUnmarshaller(configuration);
                    unmarshaller.start(input);
                    return new NamingEnumeration<T>() {
                        private T next;

                        public T next() throws NamingException {
                            if (! hasMore()) {
                                throw new NoSuchElementException("Moved past the end of the enumeration");
                            }
                            return next;
                        }

                        public boolean hasMore() throws NamingException {
                            while (next == null) {
                                try {
                                    next = unmarshaller.readObject(resultType);
                                } catch (ClassNotFoundException e) {
                                    throw log.unmarshallProblem(e);
                                } catch (IOException e) {
                                    throw log.unmarshallProblem(e);
                                }
                            }
                            return true;
                        }

                        public void close() throws NamingException {
                            try {
                                message.close();
                            } catch (IOException e) {
                                throw log.errorReadingReply(e);
                            }
                        }

                        public boolean hasMoreElements() {
                            try {
                                return hasMore();
                            } catch (NamingException e) {
                                return false;
                            }
                        }

                        public T nextElement() {
                            if (! hasMoreElements()) {
                                throw new NoSuchElementException("Moved past the end of the enumeration");
                            }
                            return next;
                        }
                    };
                }
            } else {
                // error; construct a new exception.
                Throwable cause = null;
                try {
                    final Unmarshaller unmarshaller = factory.createUnmarshaller(configuration);
                    unmarshaller.start(input);
                    cause = unmarshaller.readObject(Throwable.class);
                    unmarshaller.finish();
                } catch (Throwable t) {
                    // no cause is retrievable
                }
                throw decode(b, cause);
            }
        } catch (ClassNotFoundException e) {
            throw log.errorReadingReply(e);
        } catch (IOException e) {
            throw log.errorReadingReply(e);
        } finally {
            IoUtils.safeClose(message);
        }
    }
}
