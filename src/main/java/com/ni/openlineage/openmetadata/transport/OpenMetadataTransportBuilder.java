package com.ni.openlineage.openmetadata.transport;

import io.openlineage.client.transports.Transport;
import io.openlineage.client.transports.TransportBuilder;
import io.openlineage.client.transports.TransportConfig;

public class OpenMetadataTransportBuilder implements TransportBuilder {
    @Override
    public TransportConfig getConfig() {
        return new OpenMetadataConfig();
    }

    @Override
    public Transport build(TransportConfig config) {
        return new OpenMetadataTransport((OpenMetadataConfig) config);
    }

    @Override
    public String getType() {
        return "openMetadata";
    }
}