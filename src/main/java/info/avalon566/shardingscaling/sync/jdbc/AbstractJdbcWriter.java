package info.avalon566.shardingscaling.sync.jdbc;

import info.avalon566.shardingscaling.sync.core.Channel;
import info.avalon566.shardingscaling.sync.core.FinishedRecord;
import info.avalon566.shardingscaling.sync.core.RdbmsConfiguration;
import info.avalon566.shardingscaling.sync.core.Writer;
import lombok.var;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;

/**
 * @author avalon566
 */
public abstract class AbstractJdbcWriter implements Writer {

    private final RdbmsConfiguration rdbmsConfiguration;
    private DbMetaDataUtil dbMetaDataUtil;
    private final SqlBuilder sqlBuilder;

    public AbstractJdbcWriter(RdbmsConfiguration rdbmsConfiguration) {
        this.rdbmsConfiguration = rdbmsConfiguration;
        this.dbMetaDataUtil = new DbMetaDataUtil(rdbmsConfiguration);
        this.sqlBuilder = new SqlBuilder(rdbmsConfiguration);
    }

    @Override
    public void write(Channel channel) {
        var buffer = new ArrayList<DataRecord>(2000);
        var lastFlushTime = System.currentTimeMillis();
        try {
            while (true) {
                var record = channel.popRecord();
                if (null == record) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    continue;
                }
                if (FinishedRecord.class.equals(record.getClass())) {
                    break;
                }
                if (DataRecord.class.equals(record.getClass())) {
                    buffer.add((DataRecord) record);
                }
                if (100 <= buffer.size() || 5 * 1000 < (System.currentTimeMillis() - lastFlushTime)) {
                    flush(rdbmsConfiguration, buffer);
                }
            }
            if (0 < buffer.size()) {
                flush(rdbmsConfiguration, buffer);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void flush(RdbmsConfiguration config, ArrayList<DataRecord> buffer) throws SQLException {
        try (var connection = DriverManager.getConnection(config.getJdbcUrl(), config.getUsername(), config.getPassword())) {
            connection.setAutoCommit(false);
            for (int ij = 0; ij < buffer.size(); ij++) {
                var record = buffer.get(ij);
                if ("bootstrap-insert".equals(record.getType())
                        || "insert".equals(record.getType())) {
                    var insertSql = sqlBuilder.buildInsertSql(record.getTableName());
                    PreparedStatement ps = connection.prepareStatement(insertSql);
                    ps.setQueryTimeout(30);
                    buffer.forEach(r -> {
                        try {
                            for (int i = 0; i < r.getColumnCount(); i++) {
                                ps.setObject(i + 1, r.getColumn(i).getValue());
                            }
                            ps.execute();
                        } catch (SQLIntegrityConstraintViolationException ex) {
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    });
                } else if ("update".equals(record.getType())) {
                    var metaData = dbMetaDataUtil.getColumNames(record.getTableName());
                    var primaryKeys = dbMetaDataUtil.getPrimaryKeys(record.getTableName());
                    var updatedColumns = new StringBuilder();
                    var values = new ArrayList<Column>();
                    for (int i = 0; i < metaData.size(); i++) {
                        if (record.getColumn(i).isUpdated()) {
                            updatedColumns.append(String.format("%s = ?,", metaData.get(i).getColumnName()));
                            values.add(record.getColumn(i));
                        }
                    }
                    for (int i = 0; i < primaryKeys.size(); i++) {
                        var index = DbMetaDataUtil.findColumnIndex(metaData, primaryKeys.get(i));
                        values.add(record.getColumn(index));
                    }
                    var updateSql = sqlBuilder.buildUpdateSql(record.getTableName());
                    var sql = String.format(updateSql, updatedColumns.substring(0, updatedColumns.length() - 1));
                    PreparedStatement ps = connection.prepareStatement(sql);
                    for (int i = 0; i < values.size(); i++) {
                        ps.setObject(i + 1, values.get(i).getValue());
                    }
                    ps.execute();
                } else if ("delete".equals(record.getType())) {
                    var metaData = dbMetaDataUtil.getColumNames(record.getTableName());
                    var primaryKeys = dbMetaDataUtil.getPrimaryKeys(record.getTableName());
                    var deleteSql = sqlBuilder.buildDeleteSql(record.getTableName());
                    PreparedStatement ps = connection.prepareStatement(deleteSql);
                    for (int i = 0; i < primaryKeys.size(); i++) {
                        var index = DbMetaDataUtil.findColumnIndex(metaData, primaryKeys.get(i));
                        ps.setObject(i + 1, record.getColumn(index).getValue());
                    }
                    ps.execute();
                }
            }
            connection.commit();
        }
        buffer.clear();
    }
}