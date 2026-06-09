package test.postgres;

import test.*;
import test.common.Table;
import lombok.extern.slf4j.Slf4j;

import java.util.Random;

@Slf4j
public class PostgresTable extends Table {

    private boolean hasPrimaryKey = false;

    public PostgresTable(String tableName) {
        super(tableName);
        this.exprGenerator = new PostgresExprGen(this);
    }

    @Override
    protected String getColumn(int idx) {
        String columnName = "c" + idx;
        columnNames.add(columnName);

        PostgresDataType dataType = PostgresDataType.getRandomDataType();
        int size = 0;
        String typeDef = dataType.name();

        if (dataType.hasLen()) {
            size = 1 + Randomly.getNextInt(1, 10);
            typeDef += "(" + size + ")";
        }

        boolean isPrimaryKey = false;
        boolean isUnique = false;
        boolean isNotNull = false;

        // ====== Primary Key（最多一个）======
        if (!hasPrimaryKey && dataType.isNumeric() && Randomly.baseInt() == 1) {
            isPrimaryKey = true;
            isNotNull = true;
            hasPrimaryKey = true;
        }

        // ====== UNIQUE（不能是 PK）======
        if (!isPrimaryKey && Randomly.baseInt() == 1) {
            isUnique = true;
        }

        // ====== NOT NULL（独立随机）======
        if (!isPrimaryKey && Randomly.baseInt() == 1) {
            isNotNull = true;
        }

        columns.put(columnName, new PostgresColumn(
                this, columnName, dataType,
                isPrimaryKey, isUnique, isNotNull,
                false, false, size));

        StringBuilder sb = new StringBuilder();
        sb.append(columnName).append(" ").append(typeDef);

        if (isPrimaryKey)
            sb.append(" PRIMARY KEY");
        if (isUnique)
            sb.append(" UNIQUE");
        if (isNotNull)
            sb.append(" NOT NULL");

        return sb.toString();
    }

    @Override
    protected String getTableOption() {
        // PostgreSQL 基本不需要表选项
        // 可以返回空字符串或一些 PostgreSQL 支持的选项，如 TABLESPACE
        return "";
    }
}
