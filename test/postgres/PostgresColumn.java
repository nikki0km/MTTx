package test.postgres;

import test.Randomly;
import test.TableTool;
import test.common.Column;
import test.common.Table;

import java.sql.SQLException;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PostgresColumn extends Column {

    public PostgresColumn(PostgresTable table, String columnName, PostgresDataType dataType,
            boolean primaryKey, boolean unique, boolean notNull,
            boolean defaultNull, boolean isAutoIncrement, int size) {
        super(table, columnName, dataType, primaryKey, unique, notNull, defaultNull, isAutoIncrement, size);
    }

    public PostgresColumn(Table table, String columnName, PostgresDataType dataType,
            boolean primaryKey, boolean unique, boolean notNull,
            boolean defaultNull, boolean isAutoIncrement, int size) {
        super(table, columnName, dataType, primaryKey, unique, notNull, defaultNull, isAutoIncrement, size);
    }

    @Override
    public String getRandomVal() {
        switch ((PostgresDataType) this.dataType) {
            case SMALLINT:
                return Integer.toString(TableTool.rand.getInteger(-32768, 32767));
            case INTEGER:
                return Integer.toString(TableTool.rand.getInteger(-100, 100));
            case BIGINT:
                return Long.toString(TableTool.rand.getLong(-100, 100));
            case REAL:
            case DOUBLE_PRECISION:
                float f = (float) ((TableTool.rand.getDouble() - 0.5) * 20000);
                return String.format("%.4f", f);
            case NUMERIC:
                // NUMERIC 可以非常大，但这里限制在 ±1e20 防止溢出
                double numericVal = TableTool.rand.getDouble() * 1e20;
                return Double.toString(numericVal);
            case CHAR:
            case VARCHAR:
            case TEXT:
                if (size == 0)
                    size = 10;
                String str = TableTool.rand.getString();
                if (str.length() > size) {
                    str = str.substring(0, size);
                }
                str = str.replace("'", "''");
                return "'" + str + "'";
            case BOOLEAN:
                return TableTool.rand.getBoolean() ? "TRUE" : "FALSE";
            case DATE:
                return "'" + TableTool.rand.getDateString() + "'";
            case TIME:
                return "'" + TableTool.rand.getTimeString() + "'";
            case TIMESTAMP:
                return "'" + TableTool.rand.getTimestampString() + "'";
            case JSON:
                return "'" + TableTool.rand.getString() + "'";
            case UUID:
                return "gen_random_uuid()";
            default:
                throw new IllegalStateException("Unexpected value: " + this.dataType);
        }
    }

    public void fetchAppearedValues() {
        String query = "SELECT " + columnName + " FROM " + table.getTableName();
        TableTool.executeQueryWithCallback(query, rs -> {
            try {
                while (rs.next()) {
                    String value = rs.getString(1);
                    if (value != null) {
                        appearedValues.add(value);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to fetch appeared values for column " + columnName, e);
            }
        });
    }
}