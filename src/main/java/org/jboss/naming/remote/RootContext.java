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

import java.util.Hashtable;

import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.event.EventContext;
import javax.naming.event.NamingListener;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class RootContext implements EventContext {

    private static final RootContext INSTANCE = new RootContext();

    public static RootContext getInstance() {
        return INSTANCE;
    }

    public void addNamingListener(final Name target, final int scope, final NamingListener l) throws NamingException {
    }

    public void addNamingListener(final String target, final int scope, final NamingListener l) throws NamingException {
    }

    public void removeNamingListener(final NamingListener l) throws NamingException {
    }

    public boolean targetMustExist() throws NamingException {
        return false;
    }

    public Object lookup(final Name name) throws NamingException {
        return null;
    }

    public Object lookup(final String name) throws NamingException {
        return null;
    }

    public void bind(final Name name, final Object obj) throws NamingException {
    }

    public void bind(final String name, final Object obj) throws NamingException {
    }

    public void rebind(final Name name, final Object obj) throws NamingException {
    }

    public void rebind(final String name, final Object obj) throws NamingException {
    }

    public void unbind(final Name name) throws NamingException {
    }

    public void unbind(final String name) throws NamingException {
    }

    public void rename(final Name oldName, final Name newName) throws NamingException {
    }

    public void rename(final String oldName, final String newName) throws NamingException {
    }

    public NamingEnumeration<NameClassPair> list(final Name name) throws NamingException {
        return null;
    }

    public NamingEnumeration<NameClassPair> list(final String name) throws NamingException {
        return null;
    }

    public NamingEnumeration<Binding> listBindings(final Name name) throws NamingException {
        return null;
    }

    public NamingEnumeration<Binding> listBindings(final String name) throws NamingException {
        return null;
    }

    public void destroySubcontext(final Name name) throws NamingException {
    }

    public void destroySubcontext(final String name) throws NamingException {
    }

    public Context createSubcontext(final Name name) throws NamingException {
        return null;
    }

    public Context createSubcontext(final String name) throws NamingException {
        return null;
    }

    public Object lookupLink(final Name name) throws NamingException {
        return null;
    }

    public Object lookupLink(final String name) throws NamingException {
        return null;
    }

    public NameParser getNameParser(final Name name) throws NamingException {
        return null;
    }

    public NameParser getNameParser(final String name) throws NamingException {
        return null;
    }

    public Name composeName(final Name name, final Name prefix) throws NamingException {
        return null;
    }

    public String composeName(final String name, final String prefix) throws NamingException {
        return null;
    }

    public Object addToEnvironment(final String propName, final Object propVal) throws NamingException {
        return null;
    }

    public Object removeFromEnvironment(final String propName) throws NamingException {
        return null;
    }

    public Hashtable<?, ?> getEnvironment() throws NamingException {
        return null;
    }

    public void close() throws NamingException {
    }

    public String getNameInNamespace() throws NamingException {
        return null;
    }
}
