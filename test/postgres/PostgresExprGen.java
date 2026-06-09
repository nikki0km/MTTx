package test.postgres;

import lombok.extern.slf4j.Slf4j;
import test.Randomly;
import test.TableTool;
import test.common.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class PostgresExprGen extends ExprGen {
    private static final double FLOAT_EPSILON = 1e-5;
    private static final double DOUBLE_EPSILON = 1e-10;

    public PostgresExprGen(Table table) {
        super(table);
    }

    @Override
    public String genPredicate() {
        // 使用基类的数据驱动策略：40% 点查 / 30% 范围查 / 10% IN 查 / 20% 复杂 AST
        int choice = Randomly.getNextInt(0, 100);
        String expr;

        if (choice < 40) {
            expr = genPointQuery(); // 使用基类方法
        } else if (choice < 70) {
            expr = genRangeQuery(); // 使用基类方法
        } else if (choice < 80) {
            expr = genInQuery(); // 使用基类方法
        } else {
            expr = genExpr(0);
        }

        return isInvalidPredicate(expr) ? "TRUE" : expr;
    }

    @Override
    protected boolean isFloatingPoint(DataType dataType) {
        if (dataType instanceof PostgresDataType) {
            PostgresDataType t = (PostgresDataType) dataType;
            return t == PostgresDataType.REAL || t == PostgresDataType.DOUBLE_PRECISION;
        }
        return false;
    }

    @Override
    protected double getEpsilonForFloatingPoint(DataType dataType) {
        if (dataType instanceof PostgresDataType) {
            PostgresDataType t = (PostgresDataType) dataType;
            return t == PostgresDataType.DOUBLE_PRECISION ? DOUBLE_EPSILON : FLOAT_EPSILON;
        }
        return FLOAT_EPSILON;
    }

    public String genPredicate2() {
        String expr = genSimplePredicate2();
        return isInvalidPredicate(expr) ? "TRUE" : expr;
    }

    public String genSimplePredicate2() {
        return Randomly.fromOptions(
                genColumnEqualsActualValue2(),
                genTruePredicate());
    }

    public String genColumnEqualsActualValue2() {
        String columnName = Randomly.fromList(new ArrayList<>(columns.keySet()));
        Column column = columns.get(columnName);
        column.markAsSelected();
        List<String> appearedValues = column.getAppearedValues();
        if (appearedValues.isEmpty())
            return "TRUE";

        String value = Randomly.fromList(appearedValues);
        String escapedValue = formatValueBasedOnColumnType(value, column);

        if (isFloatingPoint(column.getDataType())) {
            int precision = 6;
            return "ROUND(" + columnName + ", " + precision + ") = ROUND(" + escapedValue + ", " + precision + ")";
        }
        return columnName + " = " + escapedValue;
    }

    public String genTruePredicate() {
        return "TRUE";
    }

    // =========================================================================
    // 第二部分：原汁原味的 AST 语法树生成器 (保持高变异度)
    // =========================================================================

    public String genExpr(int depth) {
        if (Randomly.getBoolean() || depth > depthLimit)
            return genLeaf();

        String opName = Randomly.fromList(Arrays.asList(
                "genColumn", "genConstant", "genUaryPrefixOp", "genUaryPostfixOp",
                "genBinaryLogicalOp", "genBinaryBitOp", "genBinaryMathOp", "genBinaryCompOp",
                "genInOp", "genBetweenOp", "genCastOp", "genFunction"));
        try {
            Method method = this.getClass().getMethod(opName, int.class);
            return (String) method.invoke(this, depth);
        } catch (Exception e) {
            throw new RuntimeException("Gen expr by reflection failed: ", e);
        }
    }

    public String genLeaf() {
        return Randomly.getBoolean() ? genColumn(0) : genConstant(0);
    }

    public String genColumn(int depth) {
        return Randomly.fromList(new ArrayList<>(columns.keySet()));
    }

    public String genConstant(int depth) {
        String constType = Randomly.fromOptions("INT", "NULL", "STRING", "DOUBLE");
        switch (constType) {
            case "INT":
                return Long.toString(TableTool.rand.getInteger());
            case "NULL":
                return Randomly.getBoolean() ? "NULL" : Long.toString(TableTool.rand.getInteger());
            case "STRING":
                // Postgres 字符串常数处理
                return "'" + TableTool.rand.getString().replace("'", "''") + "'";
            case "DOUBLE":
                return Double.toString(TableTool.rand.getDouble());
        }
        return "0";
    }

    public String genUaryPrefixOp(int depth) {
        String op = Randomly.fromOptions("NOT", "!", "+", "-");
        return op + "(" + genExpr(depth + 1) + ")";
    }

    public String genUaryPostfixOp(int depth) {
        String op = Randomly.fromOptions("IS NULL", "IS FALSE", "IS TRUE");
        return "(" + genExpr(depth + 1) + ")" + op;
    }

    public String genBinaryLogicalOp(int depth) {
        String op = Randomly.fromOptions("AND", "OR");
        return "(" + genExpr(depth + 1) + ") " + op + " (" + genExpr(depth + 1) + ")";
    }

    public String genBinaryBitOp(int depth) {
        // Postgres 特有：使用 # 作为异或运算符 (XOR)
        String op = Randomly.fromOptions("&", "|", "#", ">>", "<<");
        return "(" + genExpr(depth + 1) + ") " + op + " (" + genExpr(depth + 1) + ")";
    }

    public String genBinaryMathOp(int depth) {
        String op = Randomly.fromOptions("+", "-", "*", "/", "%");
        return "(" + genExpr(depth + 1) + ") " + op + " (" + genExpr(depth + 1) + ")";
    }

    public String genBinaryCompOp(int depth) {
        String op = Randomly.fromOptions("=", "!=", "<", "<=", ">", ">=", "LIKE");
        String left = genExpr(depth + 1);
        String right = genExpr(depth + 1);

        String leftColumn = Randomly.fromList(new ArrayList<>(columns.keySet()));
        String rightColumn = Randomly.fromList(new ArrayList<>(columns.keySet()));
        Column lCol = columns.get(leftColumn);
        Column rCol = columns.get(rightColumn);

        if ((lCol != null && isFloatingPoint(lCol.getDataType())) ||
                (rCol != null && isFloatingPoint(rCol.getDataType()))) {
            double epsilon = getEpsilonForComparison(lCol, rCol);
            if (op.equals("="))
                return String.format("(ABS(%s - %s) < %f)", left, right, epsilon);
            if (op.equals("!="))
                return String.format("(ABS(%s - %s) >= %f)", left, right, epsilon);
        }
        return "(" + left + ") " + op + " (" + right + ")";
    }

    private double getEpsilonForComparison(Column col1, Column col2) {
        if (col1 != null && col1.getDataType() == PostgresDataType.DOUBLE_PRECISION)
            return DOUBLE_EPSILON;
        if (col2 != null && col2.getDataType() == PostgresDataType.DOUBLE_PRECISION)
            return DOUBLE_EPSILON;
        return FLOAT_EPSILON;
    }

    public String genInOp(int depth) {
        List<String> exprList = new ArrayList<>();
        exprList.add("0");
        for (int i = 0; i < Randomly.baseInt() + 1; i++)
            exprList.add(genExpr(depth + 1));
        return "(" + genExpr(depth + 1) + ") IN (" + String.join(", ", exprList) + ")";
    }

    public String genBetweenOp(int depth) {
        return "(" + genExpr(depth + 1) + ") BETWEEN (" + genExpr(depth + 1) + ") AND (" + genExpr(depth + 1) + ")";
    }

    public String genCastOp(int depth) {
        String castedExpr = genExpr(depth + 1);
        // Postgres 原生类型 Cast
        String castType = Randomly.fromOptions("INTEGER", "REAL", "DOUBLE PRECISION", "TEXT");
        return "CAST((" + castedExpr + ") AS " + castType + ")";
    }

    public String genFunction(int depth) {
        PostgresFunction func = PostgresFunction.getRandomFunc();
        List<String> args = new ArrayList<>();
        for (int i = 0; i < func.getArgCnt(); i++)
            args.add(genExpr(depth + 1));
        return func.name() + "(" + String.join(", ", args) + ")";
    }

    // =========================================================================
    // 第三部分：通用工具方法
    // =========================================================================

    public boolean isInvalidPredicate(String expr) {
        return expr == null || expr.equals("NULL") || expr.trim().isEmpty() || expr.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }
}
