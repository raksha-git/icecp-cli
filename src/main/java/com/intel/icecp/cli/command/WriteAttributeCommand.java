/*
 * Copyright (c) 2017 Intel Corporation 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.icecp.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intel.icecp.cli.exceptions.FailedCommandException;
import com.intel.icecp.core.Channel;
import com.intel.icecp.core.attributes.AttributesNamespace;
import com.intel.icecp.core.management.Channels;
import com.intel.icecp.core.messages.BytesMessage;
import com.intel.icecp.core.metadata.Persistence;
import com.intel.icecp.core.misc.ChannelIOException;
import com.intel.icecp.core.misc.ChannelLifetimeException;
import com.intel.icecp.node.utils.ChannelUtils;
import com.intel.icecp.node.utils.StreamUtils;
import com.intel.icecp.cli.Command;
import com.intel.icecp.cli.util.Validation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 */
public class WriteAttributeCommand implements Command<byte[]> {
    private static final Logger LOGGER = LogManager.getLogger();
    private final Channels channels;
    private final URI uri;
    private final int timeoutMs;
    private final String attributeName;
    private final byte[] attributeValue;

    private WriteAttributeCommand(Channels channels, URI uri, String attributeName, byte[] attributeValue, int timeoutMs) {
        this.channels = channels;
        this.uri = uri;
        this.timeoutMs = timeoutMs;
        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
    }

    @Override
    public byte[] execute() throws FailedCommandException {
        LOGGER.info("Writing attribute '{}' to URI {} with value: {}", attributeName, uri, new String(attributeValue));

        // build URIs
        URI readUri = ChannelUtils.join(uri, AttributesNamespace.READ_SUFFIX, attributeName);
        URI writeUri = ChannelUtils.join(uri, AttributesNamespace.WRITE_SUFFIX, attributeName);

        // open channels and publish
        try (Channel<BytesMessage> readChannel = channels.openChannel(readUri, BytesMessage.class, new Persistence(timeoutMs)); Channel<BytesMessage> writeChannel = channels.openChannel(writeUri, BytesMessage.class, new Persistence(timeoutMs))) {
            CompletableFuture<byte[]> future = changeAttributeAndMonitor(readChannel, writeChannel, attributeValue);

            // verify that the value was changed
            byte[] changedValue = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (!compareAttributeReadWrite(changedValue, attributeValue)) {
                throw new FailedCommandException("The value to write was different than the one read: '" + new String(attributeValue) + "' != '" + new String(changedValue) + "'");
            }
            return changedValue;
        } catch (ChannelLifetimeException | ChannelIOException | InterruptedException | ExecutionException | TimeoutException e) {
            throw new FailedCommandException(e);
        }
    }

    static boolean compareAttributeReadWrite(final byte[] valueRead, final byte[] valueWritten) {
        return (Arrays.equals(valueRead, valueWritten) || compareAttributeMessages(valueRead, valueWritten));
    }
    
    private static boolean compareAttributeMessages(final byte[] a, final byte[] b) {
        try { // TODO -- what happens when we use other serialization formats?
            ObjectMapper x = new ObjectMapper();
            final JsonNode changedNode = x.readTree(a);
            final JsonNode valueNode = x.readTree(b);
            return changedNode.get("d").equals(valueNode.get("d"));
        } catch (IOException e) { // if we can't read as json
            LOGGER.catching(e); // make SonarQube happy
            return false;
        }
    }

    /**
     * @param readChannel the channel for observing remote attribute values
     * @param writeChannel the channel for setting remote attribute values
     * @param newValue the value to set
     * @return a future to the changed value of the attribute
     * @throws ChannelIOException if the publish fails
     */
    CompletableFuture<byte[]> changeAttributeAndMonitor(Channel<BytesMessage> readChannel, Channel<BytesMessage> writeChannel, byte[] newValue) throws ChannelIOException {
        CompletableFuture<byte[]> future = ChannelUtils.nextMessage(readChannel).thenApply(BytesMessage::getBytes);
        writeChannel.publish(new BytesMessage(newValue));
        return future;
    }

    @Override
    public String toString() {
        return "WriteAttributeCommand{" + "channels=" + channels + ", uri=" + uri + ", attributeName='" + attributeName +
                ", attributeValue=" + new String(attributeValue) + ", timeoutMs=" + timeoutMs + '}';
    }

    /**
     * Builder pattern: use this to correctly assemble the data required for a {@link WriteAttributeCommand}. Usage
     * typically looks like: {@code new Builder().set...().set...()...set().build(); }. TODO factor out the common
     * parts of this and other builders.
     */
    public static class Builder {
        @Validation.NotNull
        private Channels channels;
        @Validation.NotNull
        private URI uri;
        @Validation.NotNull
        private int timeoutMs = 10000;
        @Validation.NotNull
        private String attributeName;
        private byte[] attributeValue;

        public Builder setChannels(Channels channels) {
            this.channels = channels;
            return this;
        }

        public Builder setUri(URI uri) {
            this.uri = uri;
            return this;
        }

        public Builder setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder setAttributeName(String attributeName) {
            this.attributeName = attributeName;
            return this;
        }

        public Builder setAttributeValue(InputStream attributeValueStream) {
            try {
                this.attributeValue = StreamUtils.readAll(attributeValueStream);
                return this;
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read attribute value from input stream.", e);
            }
        }

        public WriteAttributeCommand build() {
            LOGGER.debug("Building new 'write' command");
            Validation.checkForNull(this);
            return new WriteAttributeCommand(channels, uri, attributeName, attributeValue, timeoutMs);
        }
    }
}
