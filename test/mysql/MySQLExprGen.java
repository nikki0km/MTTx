package test.mysql;

import lombok.extern.slf4j.Slf4j;

import test.Randomly;
import test.TableTool;
import test.common.*;

import java.lang.reflect.Method;
import java.util.ArrayList;

@Slf4j
public class MySQLExprGen extends ExprGen {
    private static final double FLOAT_EPSILON = 1e-5;
    private static final double DOUBLE_EPSILON = 1e-10;

    public MySQLExprGen(Table table) {
        super(table);
    }

    @Override
    public String genPredicate() {
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
        if (dataType instanceof MySQLDataType) {
            MySQLDataType mysqlDataType = (MySQLDataType) dataType;
            return mysqlDataType == MySQLDataType.FLOAT || mysqlDataType == MySQLDataType.DOUBLE;
        }
        return false;
    }

    @Override
    protected double getEpsilonForFloatingPoint(DataType dataType) {
        if (dataType instanceof MySQLDataType) {
            MySQLDataType mysqlDataType = (MySQLDataType) dataType;
            return mysqlDataType == MySQLDataType.DOUBLE ? DOUBLE_EPSILON : FLOAT_EPSILON;
        }
        return FLOAT_EPSILON;
    }

    // =========================================================================
    // 第二部分：原汁原味的 AST 语法树生成器 (用于兜底，保证超高变异度)
    // =========================================================================

    public String genExpr(int depth) {
        if (Randomly.getBoolean() || depth > depthLimit) {
            return genLeaf(); // 如果随机返回 true 或深度超过限制，生成一个叶子节点
        }
        String opName = Randomly.fromOptions( // 随机选择一个操作类型
                "genColumn", "genConstant", "genUaryPrefixOp", "genUaryPostfixOp",
                "genBinaryLogicalOp", "genBinaryBitOp", "genBinaryMathOp", "genBinaryCompOp",
                "genInOp", "genBetweenOp", "genCastOp", "genFunction");
        String expr;
        try {
            Method method = this.getClass().getMethod(opName, int.class);
            expr = (String) method.invoke(this, depth);
        } catch (Exception e) {
            throw new RuntimeException("Gen expr by reflection failed: ", e);
        }
        return expr;
    }

    public String genLeaf() {
        if (Randomly.getBoolean()) {
            return genColumn(0);
        } else {
            return genConstant(0);
        }
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
                return "\"" + TableTool.rand.getString() + "\"";
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
        String op = Randomly.fromOptions("AND", "OR", "XOR");
        return "(" + genExpr(depth + 1) + ") " + op + " (" + genExpr(depth + 1) + ")";
    }

    public String genBinaryBitOp(int depth) {
        String op = Randomly.fromOptions("&", "|", "^", ">>", "<<");
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

        if (op.equals("=") || op.equals("!=")) {
            String leftcolumnName = Randomly.fromList(new ArrayList<>(columns.keySet()));
            String rightcolumnName = Randomly.fromList(new ArrayList<>(columns.keySet()));
            Column leftCol = columns.get(leftcolumnName);
            Column rightCol = columns.get(rightcolumnName);

            if ((leftCol != null && isFloatingPoint(leftCol.getDataType())) ||
                    (rightCol != null && isFloatingPoint(rightCol.getDataType()))) {

                double epsilon = getEpsilonForComparison(leftCol, rightCol);

                if (op.equals("=")) {
                    return String.format("(ABS(%s - %s) < %f)", left, right, epsilon);
                } else {
                    return String.format("(ABS(%s - %s) >= %f)", left, right, epsilon);
                }
            }
        }
        return "(" + left + ") " + op + " (" + right + ")";
    }

    private double getEpsilonForComparison(Column col1, Column col2) {
        if (col1 != null && col1.getDataType() == MySQLDataType.DOUBLE)
            return DOUBLE_EPSILON;
        if (col2 != null && col2.getDataType() == MySQLDataType.DOUBLE)
            return DOUBLE_EPSILON;
        return FLOAT_EPSILON;
    }

    public String genInOp(int depth) {
        ArrayList<String> exprList = new ArrayList<>();
        exprList.add("0");
        for (int i = 0; i < Randomly.baseInt() + 1; i++) {
            exprList.add(genExpr(depth + 1));
        }
        return "(" + genExpr(depth + 1) + ") IN ((" + String.join("), (", exprList) + "))";
    }

    public String genBetweenOp(int depth) {
        String fromExpr = genExpr(depth + 1);
        String toExpr = genExpr(depth + 1);
        return "(" + genExpr(depth + 1) + ") BETWEEN (" + fromExpr + ") AND (" + toExpr + ")";
    }

    public String genCastOp(int depth) {
        String castedExpr = genExpr(depth + 1);
        String castType = Randomly.fromOptions("INT", "FLOAT", "DOUBLE", "CHAR");
        return "CAST((" + castedExpr + ") AS " + castType + ")";
    }

    public String genFunction(int depth) {
        MySQLFunction function = MySQLFunction.getRandomFunc();
        ArrayList<String> argList = new ArrayList<>();
        for (int i = 0; i < function.getArgCnt(); i++) {
            argList.add(genExpr(depth + 1));
        }
        return function.name() + "((" + String.join("), (", argList) + "))";
    }

    public boolean isInvalidPredicate(String expr) {
        return expr == null || expr.equals("NULL") || expr.trim().isEmpty() || expr.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }
}