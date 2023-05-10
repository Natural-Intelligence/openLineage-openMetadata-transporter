package io.openlineage.client.transports;

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