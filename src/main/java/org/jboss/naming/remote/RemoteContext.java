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

import java.io.IOException;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.ObjectTable;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.remoting3.MessageOutputStream;
import org.xnio.IoUtils;

import javax.naming.Binding;
import javax.naming.CompositeName;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.event.EventContext;
import javax.naming.event.NamingListener;

import static org.jboss.naming.remote.Log.log;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class RemoteContext implements EventContext {

    private static final Name ROOT_NAME = new CompositeName();
    private static final ListenerRegistration[] NO_REGISTRATIONS = new ListenerRegistration[0];

    private static final String FQCN = RemoteContext.class.getName();

    @SuppressWarnings("unused")
    private volatile int state;
    private volatile ListenerRegistration[] listenerRegistrations = NO_REGISTRATIONS;

    private static final AtomicIntegerFieldUpdater<RemoteContext> stateUpdater = AtomicIntegerFieldUpdater.newUpdater(RemoteContext.class, "state");
    private static final AtomicReferenceFieldUpdater<RemoteContext, ListenerRegistration[]> listenerRegistrationsUpdater = AtomicReferenceFieldUpdater.newUpdater(RemoteContext.class, ListenerRegistration[].class, "listenerRegistrations");

    /** Absolute name of this context. */
    private final Name name;
    private final Hashtable<Object, Object> environment;
    private final NamingClient client;
    private final MarshallingConfiguration contextConfig;

    private static final int CLOSED = (1 << 31);

    RemoteContext(final Name name, final Hashtable<Object, Object> environment, final NamingClient client) {
        this.name = name;
        this.environment = environment;
        this.client = client;
        final MarshallingConfiguration contextConfig = new MarshallingConfiguration();
        contextConfig.setVersion(3);
        contextConfig.setClassTable(NamingClassTable.getInstance());
        contextConfig.setObjectTable(new ContextObjectTable());
        this.contextConfig = contextConfig;
    }

    RemoteContext(final Name name, final NamingClient client) {
        this(name, new Hashtable<Object, Object>(), client);
    }

    RemoteContext(final NamingClient client) {
        this(ROOT_NAME, client);
    }

    private boolean tryEnter() throws NamingException {
        int oldState, newState;
        do {
            oldState = state;
            if ((state & CLOSED) != 0) {
                return false;
            }
            newState = oldState + 1;
        } while (! stateUpdater.compareAndSet(this, oldState, newState));
        log.entered(METHOD_GETTER, this);
        return true;
    }

    private void enter() throws NamingException {
        if (! tryEnter()) {
            throw log.closedContext();
        }
    }

    private void exit() {
        log.exited(METHOD_GETTER, this);
        if (stateUpdater.decrementAndGet(this) == CLOSED) {
            // we're the last caller to exit
            client.contextClosed(this);
        }
    }

    private void addNamingListener(final Object target, final int scope, final NamingListener l) throws NamingException {
        enter();
        try {
            final ListenerRegistration registration = client.addListener(this, target, scope, l);
            boolean ok = false;
            try {
                ListenerRegistration[] oldList, newList;
                do {
                    oldList = listenerRegistrations;
                    newList = Arrays.copyOf(oldList, oldList.length + 1);
                    newList[oldList.length] = registration;
                } while (! listenerRegistrationsUpdater.compareAndSet(this, oldList, newList));
                ok = true;
            } finally {
                if (! ok) {
                    registration.cancel();
                }
            }
        } finally {
            exit();
        }
    }

    public void addNamingListener(final Name target, final int scope, final NamingListener l) throws NamingException {
        addNamingListener((Object) target, scope, l);
    }

    public void addNamingListener(final String target, final int scope, final NamingListener l) throws NamingException {
        addNamingListener((Object) target, scope, l);
    }

    public void removeNamingListener(final NamingListener l) throws NamingException {
        enter();
        try {
            ListenerRegistration[] oldList, newList;
            do {
                int matchCount = 0;
                oldList = listenerRegistrations;
                for (ListenerRegistration registration : oldList) {
                    if (registration.getListener() == l) {
                        matchCount ++;
                    }
                }
                if (matchCount == 0) {
                    return;
                }
                final int oldLength = oldList.length;
                if (matchCount == oldLength) {
                    newList = NO_REGISTRATIONS;
                } else {
                    int j = 0;
                    newList = new ListenerRegistration[oldLength - matchCount];
                    for (ListenerRegistration registration : oldList) {
                        if (registration.getListener() != l) {
                            newList[j ++] = registration;
                        }
                    }
                }
            } while (! listenerRegistrationsUpdater.compareAndSet(this, oldList, newList));
            // only cancel them once we succeed with the CAS
            for (ListenerRegistration registration : oldList) {
                if (registration.getListener() == l) {
                    registration.cancel();
                }
            }
        } finally {
            exit();
        }
    }

    public boolean targetMustExist() throws NamingException {
        return false;
    }

    private <T> T sendBasicRequest(final Class<T> replyType, final int msg, Object... args) throws NamingException {
        final ResultHolder<T> resultHolder;
        try {
            resultHolder = client.createResultHolder(replyType, contextConfig);
            final MessageOutputStream outputStream = client.sendRequest(msg, resultHolder);
            boolean ok = false;
            try {
                final Marshaller marshaller = client.createMarshaller(contextConfig);
                marshaller.start(Marshalling.createByteOutput(outputStream));
                for (Object arg : args) {
                    marshaller.writeObject(arg);
                }
                marshaller.finish();
                outputStream.close();
                ok = true;
            } finally {
                if (! ok) {
                    outputStream.cancel();
                    resultHolder.setCancelled();
                }
                IoUtils.safeClose(outputStream);
            }
        } catch (IOException e) {
            throw log.errorSendingRequest(e);
        }
        if (! resultHolder.await()) {
            resultHolder.setCancelled();
            throw log.interrupted();
        }
        return resultHolder.getResult();
    }

    private <T> NamingEnumeration<T> sendEnumerationRequest(final Class<T> replyType, final int msg, Object... args) throws NamingException {
        final ResultHolder<T> resultHolder;
        try {
            resultHolder = client.createResultHolder(replyType, contextConfig);
            final MessageOutputStream outputStream = client.sendRequest(msg, resultHolder);
            boolean ok = false;
            try {
                final Marshaller marshaller = client.createMarshaller(contextConfig);
                marshaller.start(Marshalling.createByteOutput(outputStream));
                for (Object arg : args) {
                    marshaller.writeObject(arg);
                }
                marshaller.finish();
                outputStream.close();
                ok = true;
            } finally {
                if (! ok) {
                    outputStream.cancel();
                    resultHolder.setCancelled();
                }
                IoUtils.safeClose(outputStream);
            }
        } catch (IOException e) {
            throw log.errorSendingRequest(e);
        }
        if (! resultHolder.await()) {
            resultHolder.setCancelled();
            throw log.interrupted();
        }
        return resultHolder.getResultAsEnumeration();
    }

    private Object lookup(final Object name) throws NamingException {
        enter();
        try {
            return sendBasicRequest(Object.class, Protocol.MSG_LOOKUP, name);
        } finally {
            exit();
        }
    }

    public Object lookup(final Name name) throws NamingException {
        return name.isEmpty() ? new RemoteContext(this.name, new Hashtable<Object, Object>(environment), client) : lookup((Object) name);
    }

    public Object lookup(final String name) throws NamingException {
        return name.isEmpty() ? new RemoteContext(this.name, new Hashtable<Object, Object>(environment), client) : lookup((Object) name);
    }

    private void bind(final Object name, final Object obj) throws NamingException {
        enter();
        try {
            sendBasicRequest(Void.class, Protocol.MSG_BIND, name, obj);
        } finally {
            exit();
        }
    }

    public void bind(final Name name, final Object obj) throws NamingException {
        bind((Object) name, obj);
    }

    public void bind(final String name, final Object obj) throws NamingException {
        bind((Object) name, obj);
    }

    private void rebind(final Object name, final Object obj) throws NamingException {
        enter();
        try {
            sendBasicRequest(Void.class, Protocol.MSG_REBIND, name, obj);
        } finally {
            exit();
        }
    }

    public void rebind(final Name name, final Object obj) throws NamingException {
        rebind((Object) name, obj);
    }

    public void rebind(final String name, final Object obj) throws NamingException {
        rebind((Object) name, obj);
    }

    private void unbind(final Object name) throws NamingException {
        enter();
        try {
            sendBasicRequest(Void.class, Protocol.MSG_UNBIND, name);
        } finally {
            exit();
        }
    }

    public void unbind(final Name name) throws NamingException {
        unbind((Object) name);
    }

    public void unbind(final String name) throws NamingException {
        unbind((Object) name);
    }

    private void rename(final Object oldName, final Object newName) throws NamingException {
        enter();
        try {
            sendBasicRequest(Void.class, Protocol.MSG_RENAME, oldName, newName);
        } finally {
            exit();
        }
    }

    public void rename(final Name oldName, final Name newName) throws NamingException {
        rename((Object) oldName, newName);
    }

    public void rename(final String oldName, final String newName) throws NamingException {
        rename((Object) oldName, newName);
    }

    private NamingEnumeration<NameClassPair> list(final Object name) throws NamingException {
        enter();
        try {
            return sendEnumerationRequest(NameClassPair.class, Protocol.MSG_LIST, name);
        } finally {
            exit();
        }
    }

    public NamingEnumeration<NameClassPair> list(final Name name) throws NamingException {
        return list((Object) name);
    }

    public NamingEnumeration<NameClassPair> list(final String name) throws NamingException {
        return list((Object) name);
    }

    private NamingEnumeration<Binding> listBindings(final Object name) throws NamingException {
        enter();
        try {
            return sendEnumerationRequest(Binding.class, Protocol.MSG_LIST_BINDINGS, name);
        } finally {
            exit();
        }
    }

    public NamingEnumeration<Binding> listBindings(final Name name) throws NamingException {
        return listBindings((Object) name);
    }

    public NamingEnumeration<Binding> listBindings(final String name) throws NamingException {
        return listBindings((Object) name);
    }

    private void destroySubcontext(final Object name) throws NamingException {
        enter();
        try {
            sendBasicRequest(Void.class, Protocol.MSG_DESTROY_SUBCONTEXT, name);
        } finally {
            exit();
        }
    }

    public void destroySubcontext(final Name name) throws NamingException {
        destroySubcontext((Object) name);
    }

    public void destroySubcontext(final String name) throws NamingException {
        destroySubcontext((Object) name);
    }

    private Context createSubcontext(final Object name) throws NamingException {
        enter();
        try {
            final Name newName = sendBasicRequest(Name.class, Protocol.MSG_CREATE_SUBCONTEXT, name);
            return new RemoteContext(newName, client);
        } finally {
            exit();
        }
    }

    public Context createSubcontext(final Name name) throws NamingException {
        return createSubcontext((Object) name);
    }

    public Context createSubcontext(final String name) throws NamingException {
        return createSubcontext((Object) name);
    }

    private Object lookupLink(final Object name) throws NamingException {
        enter();
        try {
            return sendBasicRequest(Object.class, Protocol.MSG_LOOKUP_LINK, name);
        } finally {
            exit();
        }
    }

    public Object lookupLink(final Name name) throws NamingException {
        return lookupLink((Object) name);
    }

    public Object lookupLink(final String name) throws NamingException {
        return lookupLink((Object) name);
    }

    public NameParser getNameParser(final Name name) throws NamingException {
        throw new UnsupportedOperationException("getNameParser");
    }

    public NameParser getNameParser(final String name) throws NamingException {
        throw new UnsupportedOperationException("getNameParser");
    }

    public Name composeName(final Name name, final Name prefix) throws NamingException {
        throw new UnsupportedOperationException("composeName");
    }

    public String composeName(final String name, final String prefix) throws NamingException {
        throw new UnsupportedOperationException("composeName");
    }

    public Object addToEnvironment(final String propName, final Object propVal) throws NamingException {
        return environment.put(propName, propVal);
    }

    public Object removeFromEnvironment(final String propName) throws NamingException {
        return environment.remove(propName);
    }

    public Hashtable<?, ?> getEnvironment() throws NamingException {
        return environment;
    }

    public void close() throws NamingException {
        if (tryEnter()) try {
            client.contextClosing(this);
        } finally {
            exit();
        }
    }

    public String getNameInNamespace() throws NamingException {
        return name.toString();
    }

    private static final Object METHOD_GETTER = new Object() {
        public String toString() {
            final StackTraceElement[] trace = Thread.currentThread().getStackTrace();
            for (int i = 1; i < trace.length; i ++) {
                if (trace[i].getClassName().equals(FQCN)) {
                    while (trace[i ++].getClassName().equals(FQCN));
                    return trace[i - 1].getMethodName();
                }
            }
            return "(unknown)";
        }
    };

    protected void finalize() throws Throwable {
        try {
            close();
        } catch (Throwable ignored) {
        } finally {
            super.finalize();
        }
    }

    private class ContextObjectTable implements ObjectTable {

        public Writer getObjectWriter(final Object object) throws IOException {
            return null;
        }

        public Object readObject(final Unmarshaller unmarshaller) throws IOException, ClassNotFoundException {
            return null;
        }
    }
}
