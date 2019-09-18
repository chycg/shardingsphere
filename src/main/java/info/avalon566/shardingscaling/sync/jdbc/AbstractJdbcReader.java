package info.avalon566.shardingscaling.sync.jdbc;

import info.avalon566.shardingscaling.sync.core.Channel;
import info.avalon566.shardingscaling.sync.core.FinishedRecord;
import info.avalon566.shardingscaling.sync.core.RdbmsConfiguration;
import info.avalon566.shardingscaling.sync.core.Reader;
import lombok.var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.sql.ResultSet.TYPE_FORWARD_ONLY;

/**
 * @author avalon566
 */
public abstract class AbstractJdbcReader implements Reader {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractJdbcReader.class);

    private boolean running = true;

    protected final RdbmsConfiguration rdbmsConfiguration;

    public AbstractJdbcReader(RdbmsConfiguration rdbmsConfiguration) {
        this.rdbmsConfiguration = rdbmsConfiguration;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    @Override
    public void read(Channel channel) {
        try {
            Connection conn = DriverManager.getConnection(
                    rdbmsConfiguration.getJdbcUrl(),
                    rdbmsConfiguration.getUsername(),
                    rdbmsConfiguration.getPassword());
            var sql = String.format("select * from %s %s", rdbmsConfiguration.getTableName(), rdbmsConfiguration.getWhereCondition());
            var ps = conn.prepareStatement(sql, TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setFetchSize(Integer.MIN_VALUE);
            ps.setFetchDirection(ResultSet.FETCH_REVERSE);
            var rs = ps.executeQuery();
            var metaData = rs.getMetaData();
            while (running && rs.next()) {
                var record = new DataRecord(metaData.getColumnCount());
                record.setType("bootstrap-insert");
                record.setFullTableName(String.format("%s.%s", conn.getCatalog(), rdbmsConfiguration.getTableName()));
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    record.addColumn(new Column(rs.getObject(i), true));
                }
                channel.pushRecord(record);
            }
            channel.pushRecord(new FinishedRecord());
        } catch (SQLException e) {
            // make sure writer thread can exit
            channel.pushRecord(new FinishedRecord());
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<RdbmsConfiguration> split(int concurrency) {
        var primaryKeys = new DbMetaDataUtil(rdbmsConfiguration).getPrimaryKeys(rdbmsConfiguration.getTableName());
        if (1 < primaryKeys.size()) {
            LOGGER.warn("%s 为联合主键,不支持并发执行");
            return Arrays.asList(rdbmsConfiguration);
        }
        var metaData = new DbMetaDataUtil(rdbmsConfiguration).getColumNames(rdbmsConfiguration.getTableName());
        var index = DbMetaDataUtil.findColumnIndex(metaData, primaryKeys.get(0));
        try {
            if (Types.INTEGER != metaData.get(index).getColumnType()) {
                LOGGER.warn("%s 不是整形,不支持并发执行");
                return Arrays.asList(rdbmsConfiguration);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var pk = primaryKeys.get(0);
        try {
            try (var connection = DriverManager.getConnection(rdbmsConfiguration.getJdbcUrl(), rdbmsConfiguration.getUsername(), rdbmsConfiguration.getPassword())) {
                var ps = connection.prepareStatement(String.format("select min(%s),max(%s) from %s limit 1", pk, pk, rdbmsConfiguration.getTableName()));
                var rs = ps.executeQuery();
                rs.next();
                var min = rs.getInt(1);
                var max = rs.getInt(2);
                var step = (max - min) / concurrency;
                var configs = new ArrayList<RdbmsConfiguration>(concurrency);
                for (int i = 0; i < concurrency; i++) {
                    var tmp = rdbmsConfiguration.clone();
                    if (i < concurrency - 1) {
                        tmp.setWhereCondition(String.format("where id between %d and %d", min, min + step));
                        min = min + step + 1;
                    } else {
                        tmp.setWhereCondition(String.format("where id between %d and %d", min, max));
                    }
                    configs.add(tmp);
                }
                return configs;
            }
        } catch (Exception e) {
            throw new RuntimeException("getTableNames error", e);
        }
    }
}