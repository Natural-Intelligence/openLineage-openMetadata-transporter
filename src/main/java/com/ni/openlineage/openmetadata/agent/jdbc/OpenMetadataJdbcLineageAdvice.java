package com.ni.openlineage.openmetadata.agent.jdbc;

import com.ni.openlineage.openmetadata.transport.OpenMetadataTransport;
import io.openlineage.sql.DbTableMeta;
import io.openlineage.sql.OpenLineageSql;
import io.openlineage.sql.SqlMeta;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class OpenMetadataJdbcLineageAdvice {

  private static final String REDSHIFT = "redshift";
  private static final String MYSQL = "mysql";

  @Advice.OnMethodExit
  public static void onExit(@Advice.This PreparedStatement statement) {
    try {
      String dialect = extractDialect(statement);
      if (dialect == null) {
        return;
      }

      String sql = extractSql(dialect, statement);
      if (sql == null) {
        return;
      }

      Optional<SqlMeta> sqlMetaOpt = OpenLineageSql.parse(Collections.singletonList(sql), dialect);
      if (sqlMetaOpt.isPresent()) {
        String url = extractUrl(statement);
        System.out.println(String.format("Extracting lineage from %s jdbc query: %s", dialect, sql));
        handleLineage(sqlMetaOpt.get(), url);
      }
    } catch (Throwable ex) {
      System.out.println("Error occurred when trying to extract lineage from JDBC query: " + ex.getMessage());
    }
  }

  public static String extractSqlFromMysqlConnection(PreparedStatement statement) {
    String preparedSql = null;
    try {
      Method method = statement.getClass().getMethod("getPreparedSql");
      preparedSql = (String) method.invoke(statement);
    } catch (Exception e) {
      System.out.println("Could not get sql query for redshift connection: " + e.getMessage());
    }
    return preparedSql;
  }

  public static String extractSqlFromRedshiftConnection(PreparedStatement statement) {
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
      System.out.println("Could not find sql query for redshift connection");
      return null;
    }

    try {
      m_preparedSqlField.setAccessible(true);
      return (String) m_preparedSqlField.get(statement);
    } catch (IllegalAccessException e) {
      System.out.println("Could not get sql query for redshift connection");
      e.printStackTrace();
      return null;
    }
  }

  public static String extractSql(String dialect, PreparedStatement statement) {
    if (MYSQL.equals(dialect)) {
      return extractSqlFromMysqlConnection(statement);
    } else if (REDSHIFT.equals(dialect)) {
      return extractSqlFromRedshiftConnection(statement);
    }
    return null;
  }

  public static void handleLineage(SqlMeta sqlMeta, String url) {
    OpenMetadataTransport openMetadataTransport = OpenMetdataJdbcAgent.getOpenMetadataTransport();
    String dbName = openMetadataTransport.extractDbNameFromUrl(url.replace("jdbc:", ""));

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
      openMetadataTransport.sendToOpenMetadata(fullTableName, lineageType);
    });
  }

  public static String extractDialect(PreparedStatement statement) {
    if (statement.getClass().getName().contains(REDSHIFT)) {
      return REDSHIFT;
    } else if (statement.getClass().getName().contains(MYSQL)) {
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

