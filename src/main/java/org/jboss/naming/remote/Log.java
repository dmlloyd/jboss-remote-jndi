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
import org.jboss.logging.Cause;
import org.jboss.logging.LogMessage;
import org.jboss.logging.Logger;
import org.jboss.logging.Message;
import org.jboss.logging.MessageLogger;
import org.jboss.remoting3.Channel;

import javax.naming.CommunicationException;
import javax.naming.InterruptedNamingException;
import javax.naming.InvalidNameException;
import javax.naming.NamingException;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
@MessageLogger(projectCode = "JNDI")
interface Log {
    Log log = Logger.getMessageLogger(Log.class, "org.jboss.naming.remote");

    // Protocol exceptions

    @Message(id = 100, value = "Error reading reply")
    CommunicationException errorReadingReply(@Cause Throwable cause);

    @Message(id = 101, value = "Unknown server-side exception occurred")
    NamingException errOther(@Cause Throwable cause);

    @Message(id = 102, value = "Failed to send a request")
    NamingException errorSendingRequest(@Cause IOException e);

    @Message(id = 103, value = "Naming operation was interrupted locally")
    InterruptedNamingException interrupted();

    // Connection events

    @LogMessage(level = Logger.Level.ERROR)
    @Message(id = 200, value = "Received an error on channel %s (closing channel)")
    void channelError(Channel channel, @Cause IOException error);

    // Local state

    @Message(id = 300, value = "Context is closed")
    NamingException closedContext();


    // Validation

    @Message(id = 400, value = "Name \"%s\" has an invalid empty segment at offset %d")
    InvalidNameException invalidSegment(String name, int idx);

    // Marshalling

    @Message(id = 500, value = "Failed to unmarshall a value")
    CommunicationException unmarshallProblem(@Cause Throwable throwable);

    // debug and trace messages

    @LogMessage(level = Logger.Level.TRACE)
    @Message(/* loggerClass = RemoteContext.class, */value = "Entering method %s on %s")
    void entered(Object entered, RemoteContext context);

    @LogMessage(level = Logger.Level.TRACE)
    @Message(/* loggerClass = RemoteContext.class, */value = "Exiting method %s on %s")
    void exited(Object exited, RemoteContext context);

    @LogMessage(level = Logger.Level.DEBUG)
    @Message(value = "Finished stream processing on channel %s")
    void channelEOF(Channel channel);
}
