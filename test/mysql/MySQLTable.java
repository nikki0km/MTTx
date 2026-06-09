package test.mysql;

import test.*;
import test.common.Table;

//import static test.common.Table.log;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Slf4j
public class MySQLTable extends Table {
    public MySQLTable(String tableName) {
        super(tableName);
        this.exprGenerator = new MySQLExprGen(this);
    }

    @Override
    protected String getColumn(int idx) { // 生成一个列的定义，并将其添加到表中。
        String columnName = "c" + idx; // 生成列名，格式为 c 加上索引值（如 c1, c2）
        this.columnNames.add(columnName); // 将列名添加到 columnNames 列表中。
        MySQLDataType dataType = MySQLDataType.getRandomDataType(); // 随机获取一个 MySQL 数据类型
        String typeLen = ""; // 如果数据类型需要长度（如 VARCHAR(20)），随机生成长度并添加到类型定义中。
        int size = 0;
        if (dataType.hasLen()) {
            size = 1 + Randomly.getNextInt(0, 20);
            typeLen = "(" + size + ")";
        }
        boolean isPrimaryKey = false; // 是否是主键
        String primaryKey = "";
        if (allPrimaryKey && !hasPrimaryKey && dataType.isNumeric() && Randomly.baseInt() == 1) { // 随机决定是否将该列设为主键
            primaryKey = " PRIMARY KEY";
            hasPrimaryKey = true;
            isPrimaryKey = true;
        }
        boolean isUnique = false;
        String unique = "";
        if (primaryKey.equals("") && dataType.isNumeric() && Randomly.baseInt() == 1) { // 随机决定是否为该列添加 UNIQUE 约束。
            unique = " UNIQUE";
            isUnique = true;
        }
        boolean isNotNull = false;
        String notNull = "";
        if (Randomly.baseInt() == 1) { // 随机决定是否为该列添加 NOT NULL 约束
            notNull = " NOT NULL";
            isNotNull = true;
        }
        boolean isDefaultNull = false;
        String defaultNull = "";
        if (!isPrimaryKey && !isUnique && !isNotNull && Randomly.baseInt() == 1) { // 随机决定是否为该列添加 DEFAULT NULL 约束
            defaultNull = " DEFAULT NULL";
            isDefaultNull = true;
        }
        // 随机决定是否为该列添加 AUTO_INCREMENT 约束
        boolean isAutoIncrement = false;
        String autoIncrement = "";
        if (dataType.isNumeric() && Randomly.getBooleanWithRatherLowProbability() && !hasAutoIncrementColumn) { // 只有数值类型可以添加AUTO_INCREMENT，且表不能已经有AUTO_INCREMENT列
            autoIncrement = " AUTO_INCREMENT";
            isAutoIncrement = true;

            // 如果列是 AUTO_INCREMENT，则确保其是主键或唯一键
            if (!isPrimaryKey && !isUnique) {
                if (hasPrimaryKey) {
                    unique = " UNIQUE";
                    isUnique = true;
                } else {
                    primaryKey = " PRIMARY KEY";
                    isPrimaryKey = true;
                    hasPrimaryKey = true;
                }
            }
        }

        columns.put(columnName, new MySQLColumn(this, columnName, dataType, // 将列信息添加到 columns 映射中
                isPrimaryKey, isUnique, isNotNull, isDefaultNull, isAutoIncrement, size));
        return columnName + " " + dataType.name() + typeLen + primaryKey + unique + notNull + defaultNull
                + autoIncrement; // 返回完整的列定义字符串。
    }

    private enum MySQLTableOptions { // 定义 MySQL 表选项的枚举，并随机生成一组表选项
        AUTO_INCREMENT, AVG_ROW_LENGTH, CHECKSUM, COMPRESSION, DELAY_KEY_WRITE, INSERT_METHOD,
        KEY_BLOCK_SIZE, MAX_ROWS, MIN_ROWS, PACK_KEYS, STATS_AUTO_RECALC, STATS_PERSISTENT,
        STATS_SAMPLE_PAGES, COMMENT;

        public static List<MySQLTableOptions> getRandomTableOptions() { // 随机生成一组表选项
            List<MySQLTableOptions> allowedOptions = Arrays.asList(MySQLTableOptions.values());
            if (TableTool.dbms.equals(DBMS.TIDB)) { // 如果数据库是 TiDB，则只允许 AUTO_INCREMENT 和 COMMENT。
                allowedOptions = Arrays.asList(AUTO_INCREMENT, COMMENT);
            }
            List<MySQLTableOptions> options;
            if (Randomly.getBooleanWithSmallProbability()) { // 根据概率决定是否生成表选项，以及生成的数量。
                options = Randomly.subset(allowedOptions);
            } else {
                if (Randomly.getBoolean()) {
                    options = Collections.emptyList();
                } else {
                    int subsetSize = Math.min(Randomly.smallNumber(), allowedOptions.size());
                    options = Randomly.nonEmptySubset(allowedOptions, subsetSize);
                    // options = Randomly.nonEmptySubset(allowedOptions, Randomly.smallNumber());
                }
            }
            return options;
        }
    }

    @Override
    protected String getTableOption() { // 生成 MySQL 表的选项字符串
        StringBuilder sb = new StringBuilder();
        List<MySQLTableOptions> tableOptions = MySQLTableOptions.getRandomTableOptions();
        int i = 0;
        for (MySQLTableOptions o : tableOptions) { // 遍历 tableOptions，根据枚举值生成对应的表选项字符串。
            if (i++ != 0) {
                sb.append(", ");
            }
            switch (o) {
                case AUTO_INCREMENT:
                    sb.append("AUTO_INCREMENT = ");
                    sb.append(1);
                    break;
                case AVG_ROW_LENGTH:
                    sb.append("AVG_ROW_LENGTH = ");
                    sb.append(TableTool.rand.getPositiveInteger());
                    break;
                case CHECKSUM:
                    sb.append("CHECKSUM = 1");
                    break;
                case COMPRESSION:
                    sb.append("COMPRESSION = '");
                    sb.append(Randomly.fromOptions("ZLIB", "LZ4", "NONE"));
                    sb.append("'");
                    break;
                case DELAY_KEY_WRITE:
                    sb.append("DELAY_KEY_WRITE = ");
                    sb.append(Randomly.fromOptions(0, 1));
                    break;
                case INSERT_METHOD:
                    sb.append("INSERT_METHOD = ");
                    sb.append(Randomly.fromOptions("NO", "FIRST", "LAST"));
                    break;
                case KEY_BLOCK_SIZE:
                    sb.append("KEY_BLOCK_SIZE = ");
                    sb.append(TableTool.rand.getPositiveInteger());
                    break;
                case MAX_ROWS:
                    sb.append("MAX_ROWS = ");
                    sb.append(TableTool.rand.getLong(0, Long.MAX_VALUE));
                    break;
                case MIN_ROWS:
                    sb.append("MIN_ROWS = ");
                    sb.append(TableTool.rand.getLong(1, Long.MAX_VALUE));
                    break;
                case PACK_KEYS:
                    sb.append("PACK_KEYS = ");
                    sb.append(Randomly.fromOptions("1", "0", "DEFAULT"));
                    break;
                case STATS_AUTO_RECALC:
                    sb.append("STATS_AUTO_RECALC = ");
                    sb.append(Randomly.fromOptions("1", "0", "DEFAULT"));
                    break;
                case STATS_PERSISTENT:
                    sb.append("STATS_PERSISTENT = ");
                    sb.append(Randomly.fromOptions("1", "0", "DEFAULT"));
                    break;
                case STATS_SAMPLE_PAGES:
                    sb.append("STATS_SAMPLE_PAGES = ");
                    sb.append(TableTool.rand.getInteger(1, Short.MAX_VALUE));
                    break;
                case COMMENT:
                    sb.append("COMMENT = 'comment info'");
                    break;
                default:
                    throw new AssertionError(o);
            }
        }
        return sb.toString();
    }
}
