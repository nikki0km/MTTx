package test.common;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import test.Randomly;
import test.TableTool;

@Slf4j
public abstract class ExprGen {
    protected HashMap<String, Column> columns;
    protected int depthLimit = 3; // 控制生成表达式的复杂度，避免生成过于复杂或嵌套过深的表达式。（例如 (a > 10 AND (b < 20 OR c = 30))）
    protected Table table; // 引用 Table 对象以获取列的实际值

    public ExprGen() {
    }

    public ExprGen(Table table) {
        this.table = table;
    }

    public void setColumns(HashMap<String, Column> columns) {
        this.columns = columns;
    }

    public abstract String genPredicate();

    // public abstract String genPredicate2();
    // public abstract String genPredicate3();

    // =========================================================================
    // 通用数据驱动谓词生成方法（从 MySQLExprGen 抽象而来）
    // =========================================================================

    /**
     * 生成点查 (Point Query): 例如 c0 = 88
     * 用于精准命中 Record Lock，极易制造死锁与 Write Skew
     */
    protected String genPointQuery() {
        String columnName = Randomly.fromList(new ArrayList<>(columns.keySet()));
        Column column = columns.get(columnName);
        column.markAsSelected();

        String value = getSemanticConstant(column);

        if (value.equals("NULL")) {
            return columnName + " IS NULL";
        }

        // 浮点数防精度丢失比对
        if (isFloatingPoint(column.getDataType())) {
            double epsilon = getEpsilonForFloatingPoint(column.getDataType());
            return String.format("(ABS(%s - %s) < %f)", columnName, value, epsilon);
        }
        return columnName + " = " + value;
    }

    /**
     * 生成范围查 (Range Query): 例如 c0 BETWEEN 10 AND 50
     * 这是触发 Gap Lock 和 Next-Key Lock 的绝对杀手锏！
     */
    protected String genRangeQuery() {
        String columnName = Randomly.fromList(new ArrayList<>(columns.keySet()));
        Column column = columns.get(columnName);
        column.markAsSelected();

        // 如果是字符串类型，范围查询容易退化为全表扫描，改回等值查询
        if (column.getDataType().isString()) {
            return genPointQuery();
        }

        String val1 = getSemanticConstant(column);
        String val2 = getSemanticConstant(column);

        if (val1.equals("NULL") || val2.equals("NULL")) {
            return columnName + " > " + getSemanticConstant(column); // 其中一个是NULL则退化为单边范围
        }

        // 使用 LEAST 和 GREATEST 防止 BETWEEN 的左边大于右边导致查不到数据
        return columnName + " BETWEEN " + getLeastFunction() + "(" + val1 + ", " + val2 + ") AND "
                + getGreatestFunction() + "(" + val1 + ", " + val2 + ")";
    }

    /**
     * 生成集合查 (IN Query): 例如 c0 IN (10, 20, 30)
     */
    protected String genInQuery() {
        String columnName = Randomly.fromList(new ArrayList<>(columns.keySet()));
        Column column = columns.get(columnName);
        column.markAsSelected();

        int inSize = Randomly.getNextInt(2, 6); // 2到5个元素
        List<String> values = new ArrayList<>();
        for (int i = 0; i < inSize; i++) {
            String val = getSemanticConstant(column);
            if (!val.equals("NULL")) {
                values.add(val);
            }
        }

        if (values.isEmpty()) {
            return "TRUE";
        }
        return columnName + " IN (" + String.join(", ", values) + ")";
    }

    /**
     * 【极其关键：TxCheck 数据驱动思想】
     * 90% 概率从真实存在的历史数据中捞取（保证能查到数据，命中锁）。
     * 10% 概率生成随机边界值（防止只测老数据）。
     */
    protected String getSemanticConstant(Column column) {
        List<String> appearedValues = column.getAppearedValues();

        if (!appearedValues.isEmpty() && Randomly.getNextInt(0, 100) < 90) {
            String realValue = Randomly.fromList(appearedValues);
            return formatValueBasedOnColumnType(realValue, column);
        }

        return genTypeAwareRandomConstant(column.getDataType());
    }

    /**
     * 【SQLancer 类型安全思想】
     * 根据列的底层类型生成对应的常量，死守索引，防止隐式类型转换导致全表扫描
     */
    protected String genTypeAwareRandomConstant(DataType dataType) {
        if (Randomly.getNextInt(0, 100) < 5)
            return "NULL";

        if (dataType.isNumeric()) {
            if (isFloatingPoint(dataType)) {
                return Double.toString(TableTool.rand.getDouble());
            } else {
                return Long.toString(TableTool.rand.getInteger());
            }
        } else if (dataType.isString()) {
            return "'" + TableTool.rand.getString().replace("'", "''") + "'";
        }
        return "0";
    }

    /**
     * 根据列类型格式化值
     */
    protected String formatValueBasedOnColumnType(String value, Column column) {
        if (value == null || value.equalsIgnoreCase("NULL"))
            return "NULL";
        DataType dataType = column.getDataType();

        if (dataType.isString()) {
            String escapedValue = value.replace("'", "''");
            return "'" + escapedValue + "'";
        }

        if (dataType.isNumeric()) {
            return value;
        }

        String escapedValue = value.replace("'", "''");
        return "'" + escapedValue + "'";
    }

    // =========================================================================
    // 抽象方法：由子类实现数据库特定的逻辑
    // =========================================================================

    /**
     * 判断数据类型是否为浮点数
     */
    protected abstract boolean isFloatingPoint(DataType dataType);

    /**
     * 获取浮点数比较的 epsilon 值
     */
    protected abstract double getEpsilonForFloatingPoint(DataType dataType);

    /**
     * 获取数据库特定的 LEAST 函数名（MySQL: LEAST, PostgreSQL: LEAST）
     */
    protected String getLeastFunction() {
        return "LEAST";
    }

    /**
     * 获取数据库特定的 GREATEST 函数名（MySQL: GREATEST, PostgreSQL: GREATEST）
     */
    protected String getGreatestFunction() {
        return "GREATEST";
    }
}
