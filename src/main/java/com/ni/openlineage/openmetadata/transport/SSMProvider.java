package com.ni.openlineage.openmetadata.transport;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public final class SSMProvider {
    @Getter @Setter private String region;
    @Getter @Setter private String environment;
    @Getter @Setter private String serviceName;
}