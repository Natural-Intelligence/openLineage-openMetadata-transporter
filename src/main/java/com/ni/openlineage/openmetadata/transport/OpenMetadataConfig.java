package com.ni.openlineage.openmetadata.transport;

import io.openlineage.client.transports.TokenProvider;
import io.openlineage.client.transports.TransportConfig;
import lombok.*;

import javax.annotation.Nullable;
import java.net.URI;

@NoArgsConstructor
@ToString
public final class OpenMetadataConfig implements TransportConfig {
    @Getter @Setter private URI url;
    @Getter @Setter private @Nullable Double timeout;
    @Getter @Setter private @Nullable TokenProvider auth;
    @Getter @Setter private String pipelineName;
    @Getter @Setter private String pipelineServiceUrl;
    @Getter @Setter private @Nullable String pipelineDescription;
}