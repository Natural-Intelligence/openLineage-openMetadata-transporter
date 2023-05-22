package com.ni.openlineage.openmetadata.transport;


import org.junit.Assert;
import org.junit.Test;

public class OpenMetadataTransportTest {
  @Test
  public void testExtractDbNameFromRedshiftUrl() {
    String result = createOpenMetadataTransport().extractDbNameFromUrl("redshift://localhost:5439/warehouse");
    Assert.assertEquals("public", result);
  }

  @Test
  public void testExtractDbNameFromMysqlUrl() {
    String result = createOpenMetadataTransport().extractDbNameFromUrl("mysql://localhost:3306/experiments?serverTimezone=UTC&rewriteBatchedStatements=true&useSSL=false");
    Assert.assertEquals("experiments", result);
  }

  private OpenMetadataTransport createOpenMetadataTransport() {
    return new OpenMetadataTransport(new OpenMetadataConfig());
  }
}