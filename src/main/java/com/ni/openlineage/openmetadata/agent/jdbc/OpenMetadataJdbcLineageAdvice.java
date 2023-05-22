package com.ni.openlineage.openmetadata.agent.jdbc;

import com.ni.openlineage.openmetadata.transport.OpenMetadataTransport;
import io.openlineage.sql.DbTableMeta;
import io.openlineage.sql.OpenLineageSql;
import io.openlineage.sql.SqlMeta;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

//@Slf4j
public class OpenMetadataJdbcLineageAdvice {

  private static final String REDSHIFT = "redshift";
  private static final String MYSQL = "mysql";

  @Advice.OnMethodExit
  public static void onExit(@Advice.This PreparedStatement statement) {
    try {
      String sql = null;
      String dialect = extractDialect(statement);
//      log.error("### dialect = " + dialect);
      System.out.println("### dialect = " + dialect);
      if (dialect == null) {
        return;
      }
      if (MYSQL.equals(dialect)) {
        sql = ((com.mysql.cj.jdbc.ClientPreparedStatement) statement).getPreparedSql();
      } else if (REDSHIFT.equals(dialect)) {
        sql = extractSqlFromRedshiftConnection(statement);
      }
      System.out.println("### Executed SQL: " + sql);
      if (sql == null) {
        return;
      }

      String url = extractUrl(statement);
      System.out.println("### url = " + url);

      Optional<SqlMeta> sqlMetaOpt = OpenLineageSql.parse(Collections.singletonList(sql), dialect);
      if (sqlMetaOpt.isPresent()) {
        handleJdbcLineage(sqlMetaOpt.get(), url);
      }
    } catch (Exception ex) {
      System.out.println("Error occurred when when trying to extract lineage from JDBC query:" + ex.getMessage());
    }
  }

  public static String extractSqlFromRedshiftConnection(PreparedStatement statement) {
    System.out.println("### redshift connection");
    System.out.println("### declared fields = " + statement.getClass().getDeclaredFields());
    Class<?> clazz = statement.getClass();
    Field m_preparedSqlField = null;
    while (clazz != null) {
      try {
        m_preparedSqlField = clazz.getDeclaredField("m_preparedSql");
        break;
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }

    if (m_preparedSqlField == null) {
      System.out.println("### Field not found");
      return null;
    }

    try {
      m_preparedSqlField.setAccessible(true);
      return (String) m_preparedSqlField.get(statement);
    } catch (IllegalAccessException e) {
      System.out.println("### failed to get sql  = " + e.getMessage());
      e.printStackTrace();
      return null;
    }
  }

  public static void handleJdbcLineage(SqlMeta sqlMeta, String url) {
    System.out.println("### sqlMeta results = " + sqlMeta);
    OpenMetadataTransport openMetadataTransport = OpenMetdataJdbcAgent.getOpenMetadataTransport();
    String dbName = openMetadataTransport.extractDbNameFromUrl(url.replace("jdbc:", ""));
    System.out.println("### created openMetadataTransport, dbname = " + dbName);

    if (sqlMeta.inTables() != null && !sqlMeta.inTables().isEmpty()) {
      transportLineageToOpenMetadata(OpenMetadataTransport.LineageType.INLET, sqlMeta.inTables(), dbName, openMetadataTransport);
    }

    if (sqlMeta.outTables() != null && !sqlMeta.outTables().isEmpty()) {
      transportLineageToOpenMetadata(OpenMetadataTransport.LineageType.OUTLET, sqlMeta.outTables(), dbName, openMetadataTransport);
    }
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

  public static String extractDialect(PreparedStatement statement) {
    System.out.println("### statement class = " + statement.getClass().getName() + ", simple name = " + statement.getClass().getSimpleName());
    // TODO use consts
    if (statement.getClass().getName().equals("com.amazon.redshift.core.jdbc42.PGJDBC42PreparedStatement")) {
      return REDSHIFT;
    } else if (statement.getClass().getName().equals("com.mysql.cj.jdbc.ClientPreparedStatement")) {
      return MYSQL;
    }
    return null;
  }

  public static String extractUrl(PreparedStatement statement) throws Exception {
    Connection conn = statement.getConnection();
    DatabaseMetaData metaData = conn.getMetaData();
    return metaData.getURL();
  }
}

