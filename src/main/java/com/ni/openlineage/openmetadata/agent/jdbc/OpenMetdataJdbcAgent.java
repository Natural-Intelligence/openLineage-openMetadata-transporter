package com.ni.openlineage.openmetadata.agent.jdbc;

import com.ni.openlineage.openmetadata.transport.OpenMetadataConfig;
import com.ni.openlineage.openmetadata.transport.OpenMetadataTransport;
import com.ni.openlineage.openmetadata.transport.OpenMetadataTransportBuilder;
import io.openlineage.client.transports.ApiKeyTokenProvider;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import static net.bytebuddy.matcher.ElementMatchers.named;

@Slf4j
public class OpenMetdataJdbcAgent {

  private static OpenMetadataTransport openMetadataTransport;
  private static final String MYSQL_CLASS_NAME = "com.mysql.cj.jdbc.ClientPreparedStatement";
  private static final String REDSHIFT_CLASS_NAME = "com.amazon.jdbc.common.SPreparedStatement";

  public static void premain(String agentArgs, Instrumentation inst) {

    try {
      generateOpenMetadataTransport(agentArgs);
      attachJdbcLineageAdvice(inst, MYSQL_CLASS_NAME);
      attachJdbcLineageAdvice(inst, REDSHIFT_CLASS_NAME);

    } catch (Throwable e) {
      System.out.println("Failed to create jdbc query transformer");
    }
  }

  private static void generateOpenMetadataTransport(String agentArgs) throws Exception {
    try {
      Map<String, String> agentArgsMap = parseAgentArgs(agentArgs);
      OpenMetadataConfig openMetadataConfig = new OpenMetadataConfig();
      ApiKeyTokenProvider apiKeyTokenProvider = new ApiKeyTokenProvider();
      apiKeyTokenProvider.setApiKey(agentArgsMap.get("transport.auth.apiKey"));
      openMetadataConfig.setAuth(apiKeyTokenProvider);
      openMetadataConfig.setPipelineServiceUrl(agentArgsMap.get("transport.pipelineServiceUrl"));
      openMetadataConfig.setPipelineName(agentArgsMap.get("transport.pipelineName"));
      openMetadataConfig.setUrl(new URI(agentArgsMap.get("transport.url")));
      openMetadataTransport = (OpenMetadataTransport) new OpenMetadataTransportBuilder().build(openMetadataConfig);
    } catch (Exception e) {
      System.out.println("Unable to parse open lineage endpoint. Lineage events will not be collected due to " + e.getMessage());
      throw e;
    }
  }

  private static Map<String, String> parseAgentArgs(String agentArgs) {
    if (agentArgs == null) {
      return Collections.emptyMap();
    }

    return Arrays.stream(agentArgs.split(","))
        .map(s -> s.split("="))
        .filter(a -> a.length == 2)
        .collect(Collectors.toMap(a -> a[0], a -> a[1]));
  }

  public static OpenMetadataTransport getOpenMetadataTransport() {
    return openMetadataTransport;
  }

  private static void attachJdbcLineageAdvice(Instrumentation inst, String className) {
    new AgentBuilder.Default()
        .type(named(className))
        .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) ->
            builder
                .method(named("executeUpdate"))
                .intercept(Advice.to(OpenMetadataJdbcLineageAdvice.class))
                .method(named("executeQuery"))
                .intercept(Advice.to(OpenMetadataJdbcLineageAdvice.class))
                .method(named("execute"))
                .intercept(Advice.to(OpenMetadataJdbcLineageAdvice.class))
        ).installOn(inst);
  }
}
