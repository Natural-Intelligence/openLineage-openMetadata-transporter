package com.ni.openlineage.openmetadata.agent.jdbc;

import com.mysql.cj.jdbc.JdbcConnection;
import com.ni.openlineage.openmetadata.transport.OpenMetadataTransport;
import io.openlineage.sql.DbTableMeta;
import io.openlineage.sql.OpenLineageSql;
import io.openlineage.sql.SqlMeta;
import net.bytebuddy.asm.Advice;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OpenMetadataJdbcLineageAdviceOld {

  @Advice.OnMethodExit
  public static void onExit(@Advice.This PreparedStatement statement) {
    try {
      String sql = ((com.mysql.cj.jdbc.ClientPreparedStatement) statement).getPreparedSql();
      System.out.println("### Executed SQL: " + sql);
      if (sql == null) {
        return;
      }

      String url = null;
      String dialect = null;
      Connection connection = statement.getConnection();
      if (connection != null && connection instanceof JdbcConnection) {
        JdbcConnection jdbcConnection = (JdbcConnection) connection;
        url = jdbcConnection.getURL();
        System.out.println("### Database URL: " + url);
        dialect = extractDialectFromJdbcUrl(url);
        System.out.println("### dialect = " + dialect);
      }

      // todo remove this
//      sql = "insert into resolver.partner_test (partner_id,name)\n" +
//          "   \n" +
//          "    select tmp.partner_id,tmp.name\n" +
//          "    from dlk_to_db.partner_test_tmp tmp\n" +
//          "\n" +
//          "  left join resolver.partner_test target\n" +
//          "    on target.partner_id <=> tmp.partner_id\n" +
//          "  where target.partner_id is null \n" +
//          "  on duplicate key update partner_test.partner_id = partner_test.partner_id";
      Optional<SqlMeta> sqlMetaOpt = OpenLineageSql.parse(Collections.singletonList(sql), dialect);
      if (sqlMetaOpt.isPresent()) {
        handleJdbcLineage(sqlMetaOpt.get(), url);
      }
    } catch (Exception ex) {
      System.out.println("Error occurred when when trying to extract lineage from JDBC query:" + ex.getMessage());
    }
  }

  public static void handleJdbcLineage(SqlMeta sqlMeta, String url) {
    System.out.println("### sqlMeta results = " + sqlMeta);
    OpenMetadataTransport openMetadataTransport = OpenMetdataJdbcAgent.getOpenMetadataTransport();
    String dbName = openMetadataTransport.extractDbNameFromUrl(url);
    System.out.println("### created openMetadataTransport, dbname = " + dbName);

    if (sqlMeta.inTables() != null && !sqlMeta.inTables().isEmpty()) {
      transportLineageToOpenMetadata(OpenMetadataTransport.LineageType.INLET, sqlMeta.inTables(), dbName, openMetadataTransport);
    }

    if (sqlMeta.outTables() != null && !sqlMeta.outTables().isEmpty()) {
      transportLineageToOpenMetadata(OpenMetadataTransport.LineageType.OUTLET, sqlMeta.outTables(), dbName, openMetadataTransport);
    }
  }

  public static String extractDialectFromJdbcUrl(String jdbcUrl) {
    if (jdbcUrl == null) {
      return null;
    }
    Pattern pattern = Pattern.compile("^jdbc:([^:]+):.*");
    Matcher matcher = pattern.matcher(jdbcUrl);
    return matcher.find() ? matcher.group(1) : null;
  }

  public static void transportLineageToOpenMetadata(OpenMetadataTransport.LineageType lineageType, List<DbTableMeta> tables,
                                                    String dbName, OpenMetadataTransport openMetadataTransport) {
    tables.forEach(table -> {
      String fullTableName = Optional.ofNullable(table.database())
          .map(db -> db + ".")
          .orElseGet(() -> Optional.ofNullable(dbName)
              .map(db -> db + ".")
              .orElse("")) + table.name();
      System.out.println("### Trying to send " + lineageType + " lineage to openmetadata for table:  " + fullTableName);
      openMetadataTransport.sendToOpenMetadata(fullTableName, lineageType);
    });
  }
}

