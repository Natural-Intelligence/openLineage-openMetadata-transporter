package io.openlineage.client.transports;

import lombok.*;

import javax.annotation.Nullable;
import java.net.URI;

@NoArgsConstructor
@ToString
public final class OpenMetadataConfig implements TransportConfig {
    @Getter @Setter private URI url;
    @Getter @Setter private @Nullable Double timeout;
    @Getter @Setter private @Nullable TokenProvider auth;
    @Getter @Setter private String pipelineServiceName;
    @Getter @Setter private String pipelineName;
    @Getter @Setter private String airflowHost;
    @Getter @Setter private @Nullable String pipelineUrl;
    @Getter @Setter private @Nullable String pipelineDescription;
}