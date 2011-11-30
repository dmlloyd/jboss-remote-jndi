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

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class Protocol {

    /**
     * Look up a name. Type: request-response.  Request format:
     * <p><code><i>&lt;name&gt;</i></code>
     * <p>Response format:
     * <p><code>0 <i>&lt;value&gt;</i> |</code><br>
     * <p><code><i>&lt;errcode&gt;</i> <i>[&lt;cause&gt;]</i></code>
     */
    static final int MSG_LOOKUP = 1;

    /**
     * Bind a name. Type: request-response.  Request format:
     * <p><code><i>&lt;name&gt;</i> <i>&lt;value&gt;</i></code>
     * <p>Response format:
     * <p><code>0 |</code><br>
     * <p><code><i>&lt;errcode&gt;</i> <i>[&lt;cause&gt;]</i></code>
     */
    static final int MSG_BIND = 2;
    static final int MSG_REBIND = 3;
    static final int MSG_UNBIND = 4;
    static final int MSG_RENAME = 5;
    static final int MSG_LIST = 6;
    static final int MSG_LIST_BINDINGS = 7;
    static final int MSG_DESTROY_SUBCONTEXT = 8;
    static final int MSG_CREATE_SUBCONTEXT = 9;
    static final int MSG_LOOKUP_LINK = 10;

    static final int MSG_RESPONSE = 0x80;
    static final int ERR_OTHER = 1;
}
