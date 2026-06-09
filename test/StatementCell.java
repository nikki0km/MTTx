package test;

import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

enum StatementType { // StatementType 枚举：定义了所有支持的 SQL 语句类型。
    UNKNOWN, START,
    SELECT, SELECT_SHARE, SELECT_UPDATE,
    UPDATE, DELETE, INSERT, SET,
    BEGIN, COMMIT, ROLLBACK, SAVEPOINT, SHOW
}

@Slf4j
public class StatementCell { // StatementCell 类：表示一条 SQL 语句，包含语句的元数据、执行状态和结果。
    Transaction tx; // 所属事务
    int statementId; // 语句 ID
    String statement; // 原始 SQL 语句
    StatementType type; // 语句类型
    String wherePrefix = ""; // WHERE 子句前缀
    String whereClause = ""; // WHERE 子句
    String forPostfix = ""; // FOR UPDATE 或 FOR SHARE 后缀
    HashMap<String, String> values = new HashMap<>(); // 列名和值的映射
    boolean blocked; // 是否被阻塞
    boolean aborted; // 是否被中止
    // View view; // 视图
    ArrayList<Object> result; // 查询结果
    int newRowId; // 新插入行的 ID

    StatementCell(Transaction tx, int statementId) {
        this.tx = tx;
        this.statementId = statementId;
    }

    public StatementCell(Transaction tx, int statementId, String statement) { // 初始化 StatementCell 对象。
        this.tx = tx;
        this.statementId = statementId;
        this.statement = statement.replace(";", ""); // 移除 SQL 语句末尾的分号。
        this.type = StatementType.valueOf(this.statement.split(" ")[0]); // 根据语句的第一个单词确定语句类型。
        this.parseStatement(); // 调用 parseStatement 方法解析语句。
    }

    public int getStatementId() {
        return statementId;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    private void parseStatement() {
        int whereIdx, forIdx = -1;
        StatementType realType = type;
        String stmt = this.statement;
        try {
            switch (type) {
                case BEGIN:
                case START:
                case COMMIT:
                case ROLLBACK:
                case SAVEPOINT:
                case SET:
                    break;
                case SELECT: // 解析 SELECT 语句的 FOR UPDATE 或 FOR SHARE 后缀
                    forIdx = stmt.indexOf("FOR ");
                    if (forIdx == -1) {
                        forIdx = stmt.indexOf("LOCK IN SHARE MODE");
                        if (forIdx == -1) {
                            forPostfix = "";
                        }
                    }
                    if (forIdx != -1) {
                        String postfix = stmt.substring(forIdx);
                        stmt = stmt.substring(0, forIdx - 1);
                        forPostfix = " " + postfix;
                        if (postfix.equals("FOR UPDATE")) {
                            realType = StatementType.SELECT_UPDATE;
                        } else if (postfix.equals("FOR SHARE") || postfix.equals("LOCK IN SHARE MODE")) {
                            realType = StatementType.SELECT_SHARE;
                        } else {
                            throw new RuntimeException("Invalid postfix: " + this.statement);
                        }
                    }
                case UPDATE: // 解析 UPDATE 语句的 SET 子句
                    int setIdx = stmt.indexOf(" SET ");
                    if (setIdx != -1) {
                        whereIdx = stmt.indexOf(" WHERE ");
                        String setPairsStr;
                        if (whereIdx == -1) {
                            setPairsStr = stmt.substring(setIdx);
                        } else {
                            setPairsStr = stmt.substring(setIdx + 5, whereIdx);
                        }
                        setPairsStr = setPairsStr.replace(" ", "");
                        String[] setPairsList = setPairsStr.split(",");
                        for (String setPair : setPairsList) {
                            int eqIdx = setPair.indexOf("=");
                            String col = setPair.substring(0, eqIdx);
                            String val = setPair.substring(eqIdx + 1);
                            if (val.startsWith("\"") && val.endsWith("\"")) {
                                val = val.substring(1, val.length() - 1);
                            }
                            this.values.put(col, val);
                        }
                    }
                case DELETE: // 解析 DELETE 语句的 WHERE 子句
                    whereIdx = stmt.indexOf("WHERE");
                    if (whereIdx == -1) {
                        wherePrefix = stmt;
                        whereClause = (realType == StatementType.SELECT) ? "" : "TRUE";
                    } else {
                        wherePrefix = stmt.substring(0, whereIdx - 1);
                        whereClause = stmt.substring(whereIdx + 6);
                    }
                    this.type = realType;
                    recomputeStatement();
                    break;
                case INSERT: // 解析 INSERT 语句的列和值
                    Pattern pattern = Pattern.compile("INTO " + TableTool.TableName
                            + "\\s*\\((.*?)\\) VALUES\\s*\\((.*?)\\)");
                    Matcher matcher = pattern.matcher(this.statement);
                    if (!matcher.find()) {
                        throw new RuntimeException("parse INSERT statement failed");
                    }
                    String[] cols = matcher.group(1).split(",\\s*");
                    String[] vals = matcher.group(2).split(",\\s*");
                    if (cols.length != vals.length) {
                        throw new RuntimeException("Parse insert statement failed: " + this.statement);
                    }
                    for (int i = 0; i < cols.length; i++) {
                        String val = vals[i];
                        if (val.startsWith("\"") && val.endsWith("\"")) {
                            val = val.substring(1, val.length() - 1);
                        }
                        this.values.put(cols[i], val);
                    }
                    break;
                default:
                    throw new RuntimeException("Invalid statement: " + this.statement);
            }
        } catch (Exception e) {
            log.info("Parse statement failed: {}", statement);
            e.printStackTrace();
        }
    }

    public void makeChooseRow(int rowId) { // 根据行 ID 调整 WHERE 子句，确保语句只影响指定行。
        String query = null;
        Statement statement;
        ResultSet rs;
        try {
            query = String.format("SELECT * FROM %s WHERE (%s) AND %s = %d",
                    TableTool.TableName, this.whereClause, TableTool.RowIdColName, rowId); // 执行查询，检查指定行是否匹配 WHERE 子句。
            statement = TableTool.conn.createStatement();
            rs = statement.executeQuery(query);
            boolean match = rs.next();
            statement.close();
            rs.close();
            if (match)
                return;
            query = String.format("SELECT (%s) FROM %s WHERE %s = %d",
                    this.whereClause, TableTool.TableName, TableTool.RowIdColName, rowId); // 如果不匹配，调整 WHERE 子句
            statement = TableTool.conn.createStatement();
            rs = statement.executeQuery(query);
            if (!rs.next()) {
                log.info("Choose row failed, rowId:{}, statement:{}", rowId, this.statement);
                return;
            }
            Object res = rs.getObject(1);
            if (res == null) {
                this.whereClause = "(" + this.whereClause + ") IS NULL";
            } else {
                this.whereClause = "NOT (" + this.whereClause + ")";
            }
            recomputeStatement();
        } catch (SQLException e) {
            log.info("Execute query failed: {}", query);
            throw new RuntimeException("Execution failed: ", e);
        }
    }

    public void negateCondition() { // 用于对 SQL 语句的 WHERE 条件进行取反操作
        String query = "SELECT (" + whereClause + ") as yes from " + TableTool.TableName + " limit 1";
        TableTool.executeQueryWithCallback(query, (rs) -> {
            try {
                if (!rs.next()) {
                    String res = rs.getString("yes");
                    if (res == null || res.equals("null")) {
                        whereClause = "(" + whereClause + ") IS NULL";
                    } else if (res.equals("0")) {
                        whereClause = "NOT (" + whereClause + ")";
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public void recomputeStatement() { // 根据 wherePrefix、whereClause 和 forPostfix 重新生成 SQL 语句
        if (whereClause.isEmpty()) {
            this.statement = wherePrefix + forPostfix;
        } else {
            this.statement = wherePrefix + " WHERE " + whereClause + forPostfix;
        }
    }

    public String toString() { // 返回语句的字符串表示，格式为 事务ID-语句ID，如果被阻塞或中止，则添加标记。
        String res = tx.txId + "-" + statementId;
        if (blocked) {
            res += "(B)";
        }
        if (aborted) {
            res += "(A)";
        }
        return res;
    }

    public boolean equals(StatementCell that) {
        if (that == null) {
            return false;
        }
        return tx.txId == that.tx.txId && statementId == that.statementId;
    }

    public StatementCell copy() { // 创建当前语句的副本。
        StatementCell copy = new StatementCell(tx, statementId);
        copy.statement = statement;
        copy.type = type;
        copy.wherePrefix = wherePrefix;
        copy.whereClause = whereClause;
        copy.forPostfix = forPostfix;
        copy.values = values;
        copy.blocked = false;
        copy.result = null;
        return copy;
    }

}
