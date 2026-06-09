package test.common;

import lombok.extern.slf4j.Slf4j;
import test.*;
import test.mysql.MySQLColumn;
import test.mysql.MySQLDataType;
import test.postgres.PostgresColumn;
import test.postgres.PostgresDataType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
public abstract class Table {
    protected final String tableName; // 表名
    protected final boolean allPrimaryKey; // 是否所有列都是主键
    protected boolean hasPrimaryKey; // 表是否有主键
    protected boolean hasAutoIncrementColumn;
    protected String createTableSql; // 创建表的 SQL 语句
    protected final ArrayList<String> initializeStatements; // 初始化表的 SQL 语句列表
    protected final ArrayList<String> columnNames; // 列名列表
    protected final HashMap<String, Column> columns; // 列名到列对象的映射
    protected int indexCnt = 0; // 索引计数器
    protected ExprGen exprGenerator; // 表达式生成器
    protected boolean useIncrement; // 蜕变6的参数，用于判断更新x语句是直接赋值，还是增加。

    public Table(String tableName) {
        this.tableName = tableName;
        this.allPrimaryKey = Randomly.getBoolean(); // 随机决定是否所有列都是主键
        this.hasPrimaryKey = false; // 默认没有主键
        this.hasAutoIncrementColumn = false; // 默认没有自增列
        createTableSql = ""; // 初始化创建表的 SQL 语句为空
        initializeStatements = new ArrayList<>(); // 初始化 SQL 语句列表
        columnNames = new ArrayList<>(); // 初始化列名列表
        columns = new HashMap<>(); // 初始化列映射
    }

    public String getCreateTableSql() {
        return createTableSql; // 返回创建表的 SQL 语句。
    }

    public List<String> getInitializeStatements() {
        return initializeStatements; // 返回初始化表的 SQL 语句列表
    }

    public String getTableName() {
        return tableName;
    }

    public boolean create() {
        this.drop(); // 删除表（如果存在）
        columnNames.clear();
        columns.clear();

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append("(");

        // 随机生成列数
        int columnCnt = 1 + Randomly.getNextInt(0, 6);

        // 生成列定义
        for (int i = 0; i < columnCnt; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append(getColumn(i));
        }

        sb.append(")");

        // 【差异处理】只有 MySQL 需要追加表选项 (Engine, Charset 等)
        if (TableTool.dbms == DBMS.MYSQL) {
            sb.append(" ").append(getTableOption());
        }

        createTableSql = sb.toString();

        // 【修复】针对 PostgreSQL 的补丁：修复枚举 toString 带下划线导致 SQL 报错的问题
        if (TableTool.dbms == DBMS.POSTGRES) {
            createTableSql = createTableSql.replace("DOUBLE_PRECISION", "DOUBLE PRECISION");
        }

        exprGenerator.setColumns(columns);

        // 执行建表
        return TableTool.executeOnTable(createTableSql);
    }

    protected abstract String getTableOption(); // 抽象方法，用于获取表的选项（如存储引擎、字符集等）。

    protected abstract String getColumn(int idx); // 抽象方法，用于获取指定索引的列定义。 在子class

    public void drop() {
        TableTool.executeOnTable("DROP TABLE IF EXISTS " + tableName); // 删除表，执行删除表的 SQL 语句。
    }

    public void initialize() {
        // 1. 创建表（通用逻辑）
        // 如果创建失败，记录日志并重试，直到成功
        while (!this.create()) {
            log.info("Create table failed, {}", getCreateTableSql());
        }

        // 2. 生成初始化 SQL 语句 (INSERT / INDEX)
        for (int i = 0; i < Randomly.getNextInt(5, 15); i++) {
            String initSQL;

            // 随机决定是加索引还是插入数据
            if (Randomly.getNextInt(0, 15) == 10) {
                initSQL = genAddIndexStatement();
            } else {
                // 根据数据库类型分发生成插入语句的逻辑
                if (TableTool.dbms == DBMS.POSTGRES) {
                    initSQL = genInsertStatement_Postgres();
                } else {
                    initSQL = genInsertStatement();
                }
            }

            initializeStatements.add(initSQL);
            TableTool.executeOnTable(initSQL);
        }

        // 3. 获取列的实际值（用于后续数据生成）
        for (Column column : columns.values()) {
            if (TableTool.dbms == DBMS.POSTGRES) {
                ((PostgresColumn) column).fetchAppearedValues();
            } else {
                ((MySQLColumn) column).fetchAppearedValues();
            }
        }
    }

    public String genSelectStatement() { // 生成 SELECT 语句。
        String predicate = exprGenerator.genPredicate();
        List<String> selectedColumns = Randomly.nonEmptySubset(columnNames);
        String limitClause = "";
        String lockClause = "";

        // // 随机决定是否添加LIMIT子句
        // if (Randomly.getBoolean()) {
        // int limitValue = Randomly.getNextInt(1, 4); // 生成1-3之间的随机数
        // limitClause = " LIMIT " + limitValue;
        // }

        // 随机决定是否添加LOCK子句
        if (Randomly.getBoolean()) {
            if (Randomly.getBoolean()) {
                lockClause = " FOR UPDATE";
            } else {
                if (TableTool.dbms != DBMS.TIDB) {
                    lockClause = " LOCK IN SHARE MODE";
                }
            }
        }

        return "SELECT " + String.join(", ", selectedColumns) + " FROM "
                + tableName + " WHERE " + predicate + limitClause + lockClause;
    }

    // public String genSelectStatement_X() { // 生成 SELECT 语句，where后与x无关。
    // String predicate = exprGenerator.genPredicate3();
    // List<String> selectedColumns = Randomly.nonEmptySubset(columnNames);
    // String postfix = "";
    // if (Randomly.getBoolean()) {
    // if (Randomly.getBoolean()) {
    // postfix = " FOR UPDATE";
    // } else {
    // if (TableTool.dbms != DBMS.TIDB) {
    // postfix = " LOCK IN SHARE MODE";
    // }
    // }
    // }
    // return "SELECT " + String.join(", ", selectedColumns) + " FROM "
    // + tableName + " WHERE " + predicate + postfix;
    // }

    public String genSelectStatement2() { // 生成 SELECT 语句。
        String predicate = exprGenerator.genPredicate();
        List<String> selectedColumns = Randomly.nonEmptySubset(columnNames);
        // String postfix = "";
        // if (Randomly.getBoolean()) {
        // if (Randomly.getBoolean()) {
        // postfix = " FOR UPDATE";
        // } else {
        // if (TableTool.dbms != DBMS.TIDB) {
        // postfix = " LOCK IN SHARE MODE";
        // }
        // }
        // }
        return "SELECT " + String.join(", ", selectedColumns) + " FROM "
                + tableName + " WHERE " + predicate;
    }

    public String genInsertStatement() { // 生成 INSERT 语句
        List<String> insertedCols = Randomly.nonEmptySubset(columnNames); // 从 columnNames 中随机选取一个非空子集，作为要插入的列名列表。

        // if (Randomly.getPercentage(30)) { // 30%概率插入x
        // insertedCols.add("x");
        // }

        for (String colName : columns.keySet()) { // 遍历 columns（一个包含列名和对应 Column 对象的映射）。
            Column column = columns.get(colName);
            if ((column.isPrimaryKey() || column.isNotNull()) && !insertedCols.contains(colName)) { // 如果某列是主键（isPrimaryKey()）或非空列（isNotNull()），并且该列还未被包含在insertedCols中，则将其添加到
                                                                                                    // insertedCols 中。
                insertedCols.add(colName);
            }
        }
        List<String> insertedVals = new ArrayList<>(); // 创建一个空列表 insertedVals，用于存储插入的值
        for (String colName : insertedCols) { // 遍历 insertedCols
            Column column = columns.get(colName);
            if (column.isAutoIncrement()) { // 如果列是自增列，插入 NULL
                insertedVals.add("NULL");
            } else {
                insertedVals.add(column.getRandomVal()); // 为每一列生成一个随机值（通过 column.getRandomVal()），并将其添加到 insertedVals 中。
            }
        }
        String ignore = "";
        if (Randomly.getBoolean()) { // 使用Randomly.getBoolean()随机决定是否在INSERT语句中包含IGNORE关键字。
            ignore = "IGNORE "; // 如果包含 IGNORE，则在插入时忽略重复键错误（适用于 MySQL 等数据库）。
        }
        return "INSERT " + ignore + "INTO " + tableName + "(" + String.join(", ", insertedCols)
                + ") VALUES (" + String.join(", ", insertedVals) + ")";

    }

    public String genInsertStatement_Postgres() {
        // 1. 随机选择要插入的列
        List<String> insertedCols = Randomly.nonEmptySubset(columnNames);

        // // 30%概率额外插入 x 列
        // if (Randomly.getPercentage(30)) {
        // insertedCols.add("x");
        // }

        // 确保主键和非空列也会被插入
        for (String colName : columns.keySet()) {
            Column column = columns.get(colName);
            if ((column.isPrimaryKey() || column.isNotNull()) && !insertedCols.contains(colName)) {
                insertedCols.add(colName);
            }
        }

        // 2. 为每列生成值
        List<String> insertedVals = new ArrayList<>();
        for (String colName : insertedCols) {
            Column column = columns.get(colName);

            // 自增列使用 DEFAULT
            if (column.isAutoIncrement()) {
                insertedVals.add("DEFAULT");
            } else {
                String val = column.getRandomVal();
                insertedVals.add(val);
            }
        }

        // 3. 构造 INSERT 语句
        String sql = "INSERT INTO " + tableName +
                " (" + String.join(", ", insertedCols) + ") VALUES (" +
                String.join(", ", insertedVals) + ")";

        // 4. PostgreSQL 处理冲突，随机添加 ON CONFLICT DO NOTHING
        if (Randomly.getBoolean()) {
            sql += " ON CONFLICT DO NOTHING";
        }

        return sql;
    }

    // public String genUpdateStatement() { // 生成 UPDATE 语句
    // String predicate = exprGenerator.genPredicate();
    // List<String> updatedCols = Randomly.nonEmptySubset(columnNames);
    // List<String> setPairs = new ArrayList<>();
    // for (String colName : updatedCols) {
    // setPairs.add(colName + "=" + columns.get(colName).getRandomVal());
    // }
    // return "UPDATE " + tableName + " SET " + String.join(", ", setPairs) + "
    // WHERE " + predicate;
    // }
    public String genUpdateStatement() {
        String predicate = exprGenerator.genPredicate();
        List<String> updatedCols = Randomly.nonEmptySubset(columnNames);
        List<String> setPairs = new ArrayList<>();

        boolean isPostgres = TableTool.dbms.getProtocol().equals("postgres");
        boolean isTrivialPredicate = predicate.equalsIgnoreCase("TRUE");

        for (String colName : updatedCols) {
            Column col = columns.get(colName);
            String expr;

            if (isPostgres && col.isUnique() && isTrivialPredicate) {
                // 唯一列 + WHERE TRUE，按类型生成安全扰动
                switch (col.getDataType().toString().toUpperCase()) {
                    case "SMALLINT":
                    case "INTEGER":
                    case "BIGINT":
                    case "INT":
                        expr = colName + " + floor(random() * 10)::int"; // 每行 +0~9
                        break;
                    case "REAL":
                    case "DOUBLE_PRECISION":
                    case "NUMERIC":
                        expr = colName + " * (1 + random() * 0.01)"; // +1%波动
                        break;
                    case "CHAR":
                    case "VARCHAR":
                    case "TEXT":
                        expr = colName + " || '_' || rid"; // 字符拼接保证唯一
                        break;
                    case "DATE":
                        expr = colName + " + (floor(random() * 10) || ' days')::interval"; // 日期扰动 0~9 天
                        break;
                    case "TIMESTAMP":
                    case "TIMESTAMPTZ":
                        expr = colName + " + (floor(random() * 10) || ' days')::interval";
                        break;
                    default:
                        expr = colName + " = " + col.getRandomVal(); // fallback
                }
            } else {
                expr = colName + " = " + col.getRandomVal();
            }

            setPairs.add(expr);
        }

        return "UPDATE " + tableName +
                " SET " + String.join(", ", setPairs) +
                " WHERE " + predicate;
    }

    // public String genUpdateStatement2() {
    // String predicate = exprGenerator.genPredicate();
    // List<String> updatedCols = Randomly.nonEmptySubset(columnNames);
    // List<String> setPairs = new ArrayList<>();

    // for (String colName : updatedCols) {
    // Column col = columns.get(colName); // Column 类型
    // MySQLDataType type = (MySQLDataType) col.getDataType(); // 直接取 dataType 并强转

    // switch (type) {
    // case INT:
    // case BIGINT:
    // case FLOAT:
    // case DOUBLE:
    // case DECIMAL:
    // // 数值列用加法
    // setPairs.add(colName + "=" + colName + "+" + col.getRandomVal());
    // break;
    // case CHAR:
    // case VARCHAR:
    // case TINYTEXT:
    // case TEXT:
    // case MEDIUMTEXT:
    // case LONGTEXT:
    // // 字符串列直接赋随机值
    // setPairs.add(colName + "=" + col.getRandomVal());
    // break;
    // default:
    // setPairs.add(colName + "=" + col.getRandomVal());
    // }
    // }

    // if (setPairs.isEmpty()) {
    // return null;
    // }

    // return "UPDATE " + tableName + " SET " + String.join(", ", setPairs) + "
    // WHERE " + predicate;
    // }

    public String genUpdateStatement2() {
        String predicate = exprGenerator.genPredicate();
        List<String> updatedCols = Randomly.nonEmptySubset(columnNames);
        List<String> setPairs = new ArrayList<>();
        boolean isPostgres = TableTool.dbms.getProtocol().toLowerCase().contains("postgres");

        for (String colName : updatedCols) {
            Column col = columns.get(colName);
            if (isPostgres) {
                setPairs.add(colName + "=" + generateSafeUpdateExpression(col));
            } else {
                // MySQL原逻辑保持不变，但是拦截字符串类型并保证其不为空
                setPairs.add(colName + "=" + generateMySQLUpdateExpression(col));
            }
        }

        if (setPairs.isEmpty())
            return null;
        return "UPDATE " + tableName + " SET " + String.join(", ", setPairs) + " WHERE " + predicate;
    }

    private String generateMySQLUpdateExpression(Column col) {
        // 获取类型名称
        String typeName = col.getDataType().toString().toUpperCase();

        // ================= 1. 字符串类型 (特殊处理：防空、截断) =================
        // 包含 CHAR, VARCHAR, TEXT, TINYTEXT, LONGTEXT 等
        if (typeName.contains("CHAR") || typeName.contains("TEXT")) {

            String randomStr = TableTool.rand.getString();

            // A. 兜底：防止生成空值 (你的核心需求)
            if (randomStr == null || randomStr.trim().isEmpty()) {
                randomStr = "v_" + Randomly.getNextInt(0, 1000);
            }

            // B. 截断：防止超长
            int maxSize = col.getSize();
            if (maxSize > 0 && randomStr.length() > maxSize) {
                randomStr = randomStr.substring(0, maxSize);
            }

            // C. 返回
            // 保持和 MySQLColumn.getRandomVal 一致的格式 (使用双引号包裹)
            // 注意：如果随机字符串里有双引号，MySQL可能会报错，建议做个简单转义，或者假设rand.getString是安全的
            // 这里为了稳妥，替换掉可能导致 SQL 语法错误的字符
            // return "\"" + randomStr.replace("\"", "\\\"") + "\"";

            // 如果你的 rand.getString() 只生成字母数字，直接返回即可：
            return "\"" + randomStr + "\"";
        }

        // ================= 2. 其他类型 (直接使用原有逻辑) =================
        // 整数、浮点、BLOB 等直接调用原来的 getRandomVal()，不做修改
        return col.getRandomVal();
    }

    private String generateSafeUpdateExpression(Column col) {
        // 1. 准备工作
        String updateExpr; // 最终要返回的 SQL 表达式
        String typeName = col.getDataType().toString().toUpperCase().replace("_", " ");

        // 2. 根据类型生成表达式
        if (typeName.contains("CHAR") || typeName.contains("TEXT") || typeName.contains("VARCHAR")) {
            // ================= 字符串类型处理 =================
            String randomStr = TableTool.rand.getString();

            // 兜底：防止生成空值
            if (randomStr == null || randomStr.trim().isEmpty()) {
                randomStr = "v_" + Randomly.getNextInt(0, 1000);
            }

            // 截断：防止超长
            int maxSize = col.getSize();
            if (maxSize > 0 && randomStr.length() > maxSize) {
                randomStr = randomStr.substring(0, maxSize);
            }

            // 转义并包裹引号 (这是 Java 生成的字面量)
            updateExpr = "'" + randomStr.replace("'", "''") + "'";

        } else {
            // ================= 非字符串类型 switch 分流 =================
            switch (typeName) {
                // --- 整数 (使用 PG 内置函数生成) ---
                case "SMALLINT":
                    updateExpr = "(floor(random() * 1000)::int2)";
                    break;
                case "INTEGER":
                case "INT":
                    updateExpr = "(floor(random() * 100000)::int4)";
                    break;
                case "BIGINT":
                    updateExpr = "(floor(random() * 1000000)::int8)";
                    break;

                // --- 浮点数 (使用 PG 内置函数生成，避免指数溢出) ---
                case "REAL":
                case "FLOAT":
                    updateExpr = "((random() * 10000)::real)";
                    break;
                case "DOUBLE PRECISION":
                case "DOUBLE":
                    updateExpr = "((random() * 10000)::float8)";
                    break;
                case "NUMERIC":
                case "DECIMAL":
                    updateExpr = "((random() * 10000)::numeric)";
                    break;

                // --- 日期时间 (基于当前时间扰动) ---
                case "DATE":
                    updateExpr = "(CURRENT_DATE + (floor(random()*200 - 100) || ' days')::interval)";
                    break;
                case "TIME":
                    updateExpr = "CURRENT_TIME";
                    break;
                case "TIMESTAMP":
                case "TIMESTAMPTZ":
                    updateExpr = "(CURRENT_TIMESTAMP + (floor(random()*200 - 100) || ' days')::interval)";
                    break;

                // --- 布尔 (Java 生成) ---
                case "BOOLEAN":
                    updateExpr = Randomly.getBoolean() ? "TRUE" : "FALSE";
                    break;

                // --- JSON (Java 生成) ---
                case "JSON":
                case "JSONB":
                    updateExpr = "'{\"k\":\"v" + Randomly.getNextInt(0, 100) + "\"}'";
                    break;

                // --- UUID (PG 函数) ---
                case "UUID":
                    updateExpr = "gen_random_uuid()";
                    break;

                // --- 默认兜底 ---
                default:
                    updateExpr = col.getRandomVal();
                    break;
            }
        }

        // 3. 统一返回
        return updateExpr;
    }

    public String genDeleteStatement() { // 生成 DELETE 语句
        String predicate = exprGenerator.genPredicate();
        return "DELETE FROM " + tableName + " WHERE " + predicate;
    }

    // public String genDeleteStatement_X() { // 生成 DELETE 语句
    // String predicate = exprGenerator.genPredicate2();
    // return "DELETE FROM " + tableName + " WHERE " + predicate;
    // }

    public String genAddIndexStatement() { // 生成 CREATE INDEX 语句
        List<String> candidateColumns = Randomly.nonEmptySubset(columnNames); // 从 columnNames(表的列名列表)
                                                                              // 中随机选择一个非空子集。，作为候选的索引列。
        List<String> indexedColumns = new ArrayList<>();
        // 从候选列中筛选出适合创建索引的列
        for (String colName : candidateColumns) { // 遍历候选列，检查每列的数据类型
            Column column = columns.get(colName);
            if (column.getDataType().isNumeric()) { // 如果列是数值类型（如 int、float），直接将其加入索引列列表。
                indexedColumns.add(colName);
            } else if (column.getDataType().isString()) { // 如果列是字符串类型（如 varchar），根据数据库类型决定是否限制索引长度
                if (TableTool.dbms == DBMS.MYSQL || TableTool.dbms == DBMS.MARIADB || TableTool.dbms == DBMS.TIDB) { // 对于MySQL、MariaDB
                                                                                                                     // 或
                                                                                                                     // TiDB，限制索引长度为
                                                                                                                     // 5（例如
                                                                                                                     // name(5)）。
                    indexedColumns.add(colName + "(5)");
                } else { // 对于其他数据库，直接使用列名。
                    indexedColumns.add(colName); // 生成一个唯一的索引名
                }
            }
        }
        // 随机决定是否为唯一索引。
        String indexName = "i" + (indexCnt++);
        String unique = "";
        if (Randomly.getBoolean()) {
            unique = "UNIQUE ";
        }
        return "CREATE " + unique + "INDEX " + indexName + " ON " + tableName // 生成最终的 CREATE INDEX SQL 语句
                + " (" + String.join(", ", indexedColumns) + ")";
    }

    public void setIsolationLevel(SQLConnection conn, IsolationLevel isolationLevel) { // 设置事务的隔离级别
        String levelName = isolationLevel.getName().toString().replace("_", " ");
        String sql = "SET SESSION TRANSACTION ISOLATION LEVEL " + levelName;
        TableTool.executeWithConn(conn, sql);
    }

    public void setTxMode(SQLConnection conn) { // 设置事务的隔离级别
        if (TableTool.dbms == DBMS.TIDB) { // 如果是 TiDB，设置事务模式为悲观事务模式
            String sql = "SET tidb_txn_mode='pessimistic';";
            TableTool.executeWithConn(conn, sql);
        }
    }

    public Transaction genTransaction11(int txId) {
        IsolationLevel isolationLevel = Randomly.fromList(TableTool.possibleIsolationLevels);
        return genTransaction11(txId, isolationLevel);
    }

    public Transaction genTransaction11(int txId, IsolationLevel isolationLevel) {
        SQLConnection txConn = TableTool.genConnection();
        Transaction tx = new Transaction(txId, isolationLevel, txConn);
        setIsolationLevel(txConn, isolationLevel);
        int n = Randomly.getNextInt(TableTool.TxSizeMin, TableTool.TxSizeMax);
        ArrayList<StatementCell> statementList = new ArrayList<>();

        int currentStatementId = 0; // 用于跟踪当前语句的 statementId
        // 随机决定是否插入 SET autocommit = ON/OFF 语句
        if (Randomly.getPercentage(20) && TableTool.dbms != DBMS.POSTGRES) {
            String autocommitStmt = "SET autocommit = " + (Randomly.getBoolean() ? "ON" : "OFF");
            StatementCell cell = new StatementCell(tx, currentStatementId++, autocommitStmt);
            statementList.add(cell);
        }

        StatementCell cell = new StatementCell(tx, currentStatementId++, "BEGIN");
        statementList.add(cell);
        for (int i = 1; i <= n; i++) {
            cell = new StatementCell(tx, currentStatementId++, genStatement2());
            statementList.add(cell);
        }

        // 随机决定是否再次插入 SET autocommit = ON/OFF 语句
        if (Randomly.getPercentage(20) && TableTool.dbms != DBMS.POSTGRES) {
            String autocommitStmt = "SET autocommit = " + (Randomly.getBoolean() ? "ON" : "OFF");
            cell = new StatementCell(tx, currentStatementId++, autocommitStmt);
            statementList.add(cell);
        }
        String lastStmt = "COMMIT";
        if (Randomly.getBoolean()) {
            lastStmt = "ROLLBACK";
        }
        cell = new StatementCell(tx, currentStatementId++, lastStmt);
        statementList.add(cell);
        tx.setStatements(statementList);
        return tx;
    }

    public Transaction genTransaction(int txId) {
        IsolationLevel isolationLevel = Randomly.fromList(TableTool.possibleIsolationLevels);
        return genTransaction(txId, isolationLevel);
    }

    public Transaction genTransaction(int txId, IsolationLevel isolationLevel) {
        SQLConnection txConn = TableTool.genConnection();
        Transaction tx = new Transaction(txId, isolationLevel, txConn);

        int n = Randomly.getNextInt(TableTool.TxSizeMin, TableTool.TxSizeMax);
        ArrayList<StatementCell> statementList = new ArrayList<>();

        int currentStatementId = 0;
        if (TableTool.dbms != DBMS.POSTGRES) {
            setIsolationLevel(txConn, isolationLevel);
        }

        if (TableTool.dbms == DBMS.POSTGRES) {
            String beginStmt = "BEGIN ISOLATION LEVEL " + isolationLevel.name();
            statementList.add(
                    new StatementCell(tx, currentStatementId++, beginStmt));
        } else {
            statementList.add(
                    new StatementCell(tx, currentStatementId++, "BEGIN"));
        }
        for (int i = 1; i <= n; i++) {
            StatementCell cell = new StatementCell(tx, currentStatementId++, genStatement());
            statementList.add(cell);
        }
        // // 随机决定是否插入 SET autocommit = ON/OFF 语句
        // if (Randomly.getPercentage(20) && TableTool.dbms != DBMS.POSTGRES) {
        // String autocommitStmt = "SET autocommit = " + (Randomly.getBoolean() ? "ON" :
        // "OFF");
        // StatementCell cell = new StatementCell(tx, currentStatementId++,
        // autocommitStmt);
        // statementList.add(cell);
        // }
        // 结尾 COMMIT / ROLLBACK
        String lastStmt = Randomly.getBoolean() ? "COMMIT" : "ROLLBACK";
        statementList.add(
                new StatementCell(tx, currentStatementId++, lastStmt));

        tx.setStatements(statementList);
        return tx;
    }

    public Transaction genTransaction3(int txId) {
        IsolationLevel isolationLevel;
        List<IsolationLevel> safeLevels = TableTool.possibleIsolationLevels.stream()
                .filter(level -> level == IsolationLevel.REPEATABLE_READ ||
                        level == IsolationLevel.SERIALIZABLE)
                .collect(Collectors.toList());
        isolationLevel = Randomly.fromList(safeLevels);
        return genTransaction3(txId, isolationLevel);
    }

    public Transaction genTransaction3(int txId, IsolationLevel isolationLevel) {
        SQLConnection txConn = TableTool.genConnection();
        Transaction tx = new Transaction(txId, isolationLevel, txConn);
        setIsolationLevel(txConn, isolationLevel);
        int n = Randomly.getNextInt(TableTool.TxSizeMin, TableTool.TxSizeMax);
        ArrayList<StatementCell> statementList = new ArrayList<>();

        int currentStatementId = 0; // 用于跟踪当前语句的 statementId
        // 随机决定是否插入 SET autocommit = ON/OFF 语句
        if (Randomly.getPercentage(20) && TableTool.dbms != DBMS.POSTGRES) {
            String autocommitStmt = "SET autocommit = " + (Randomly.getBoolean() ? "ON" : "OFF");
            StatementCell cell = new StatementCell(tx, currentStatementId++, autocommitStmt);
            statementList.add(cell);
        }

        StatementCell cell = new StatementCell(tx, currentStatementId++, "BEGIN");
        statementList.add(cell);
        for (int i = 1; i <= n; i++) {
            cell = new StatementCell(tx, currentStatementId++, genStatement());
            statementList.add(cell);
        }

        // 随机决定是否再次插入 SET autocommit = ON/OFF 语句
        if (Randomly.getPercentage(20) && TableTool.dbms != DBMS.POSTGRES) {
            String autocommitStmt = "SET autocommit = " + (Randomly.getBoolean() ? "ON" : "OFF");
            cell = new StatementCell(tx, currentStatementId++, autocommitStmt);
            statementList.add(cell);
        }
        String lastStmt = "COMMIT";
        if (Randomly.getBoolean()) {
            lastStmt = "ROLLBACK";
        }
        cell = new StatementCell(tx, currentStatementId++, lastStmt);
        statementList.add(cell);
        tx.setStatements(statementList);
        return tx;
    }

    public Transaction genRollbackTransaction(int txId, Transaction originalTx) {
        IsolationLevel isolationLevel = originalTx.getIsolationLevel();
        SQLConnection txConn = TableTool.genConnection(); // 生成新的事务连接
        Transaction copiedTx = new Transaction(txId, isolationLevel, txConn); // 创建新的事务对象
        ArrayList<StatementCell> copiedStatementList = new ArrayList<>(); // 复制原始事务的语句列表
        ArrayList<StatementCell> originalStatements = originalTx.getStatements();

        // 查找事务1中的保存点
        String savepointName = null;
        for (StatementCell cell : originalStatements) {
            if (cell.getStatement().startsWith("SAVEPOINT")) {
                savepointName = cell.getStatement().split(" ")[1]; // 提取保存点名称
                break;
            }
        }

        // 复制除最后一个语句之外的所有语句
        for (int i = 0; i < originalStatements.size() - 1; i++) {
            StatementCell originalCell = originalStatements.get(i);
            StatementCell copiedCell = new StatementCell(copiedTx, originalCell.getStatementId(),
                    originalCell.getStatement());
            copiedStatementList.add(copiedCell);
        }

        // 如果事务1中有保存点，则回滚到保存点
        if (savepointName != null) {
            StatementCell rollbackToSavepointCell = new StatementCell(copiedTx, originalStatements.size() - 1,
                    "ROLLBACK TO SAVEPOINT " + savepointName);
            copiedStatementList.add(rollbackToSavepointCell);
        } else {
            // 如果没有保存点，则直接回滚
            StatementCell lastOriginalCell = originalStatements.get(originalStatements.size() - 1);
            StatementCell lastCopiedCell = new StatementCell(copiedTx, lastOriginalCell.getStatementId(), "ROLLBACK");
            copiedStatementList.add(lastCopiedCell);
        }

        copiedTx.setStatements(copiedStatementList); // 设置复制的事务的语句列表
        return copiedTx;
    }

    public Transaction genTransaction2(int txId) {
        IsolationLevel isolationLevel;
        List<IsolationLevel> safeLevels = TableTool.possibleIsolationLevels.stream()
                .filter(level -> level == IsolationLevel.REPEATABLE_READ ||
                        level == IsolationLevel.SERIALIZABLE)
                .collect(Collectors.toList());
        isolationLevel = Randomly.fromList(safeLevels);
        return genTransaction2(txId, isolationLevel);
    }

    public Transaction genTransaction2(int txId, IsolationLevel isolationLevel) {
        SQLConnection txConn = TableTool.genConnection(); // 生成事务连接
        Transaction tx = new Transaction(txId, isolationLevel, txConn); // 创建事务对象
        setTxMode(txConn); // 设置为悲观事务，如果是TiDB
        setIsolationLevel(txConn, isolationLevel); // 设置隔离级别
        int n = Randomly.getNextInt(1, 3);
        // 随机生成事务大小
        ArrayList<StatementCell> statementList = new ArrayList<>();
        int currentStatementId = 0; // 用于跟踪当前语句的 statementId
        String beginStatement;
        if (Randomly.getBoolean()) { // 50% 概率
            beginStatement = "START TRANSACTION WITH CONSISTENT SNAPSHOT";
        } else {
            beginStatement = "BEGIN";
        }

        StatementCell cell = new StatementCell(tx, currentStatementId++, beginStatement);
        statementList.add(cell);

        cell = new StatementCell(tx, currentStatementId++, genSelectAllStatement());
        statementList.add(cell);

        for (int i = 1; i <= n; i++) {
            cell = new StatementCell(tx, currentStatementId++, genStatement());
            statementList.add(cell);
        }

        cell = new StatementCell(tx, currentStatementId++, genSelectAllStatement());
        statementList.add(cell);

        String lastStmt = "COMMIT";
        cell = new StatementCell(tx, currentStatementId++, lastStmt);
        statementList.add(cell);
        tx.setStatements(statementList);
        return tx;
    }

    public String genSelectAllStatement() {
        StringBuilder sb = new StringBuilder("SELECT * FROM " + tableName);

        // // 40% 概率添加 LIMIT
        // boolean addLimit = Randomly.getPercentage(40);
        // 10% 概率添加 FOR UPDATE
        boolean addForUpdate = Randomly.getPercentage(10);

        // if (addLimit) {
        // // 生成一个足够大的LIMIT值，使其实际上不限制结果
        // int limitValue = 100 + Randomly.getNextInt(0, 100);
        // sb.append(" LIMIT ").append(limitValue);
        // }

        if (addForUpdate) {
            sb.append(" FOR UPDATE");
        }
        return sb.toString();
    }

    public Transaction copyTransaction(int txId, Transaction originalTx) {
        // int txId = originalTx.getTxId(); // 获取原始事务的属性
        IsolationLevel isolationLevel = originalTx.getIsolationLevel();
        SQLConnection txConn = TableTool.genConnection(); // 生成新的事务连接
        Transaction copiedTx = new Transaction(txId, isolationLevel, txConn); // 创建新的事务对象
        ArrayList<StatementCell> copiedStatementList = new ArrayList<>(); // 复制原始事务的语句列表
        ArrayList<StatementCell> originalStatements = originalTx.getStatements();

        for (int i = 0; i < originalStatements.size(); i++) { // 复制所有语句
            StatementCell originalCell = originalStatements.get(i);
            StatementCell copiedCell = new StatementCell(copiedTx, originalCell.getStatementId(),
                    originalCell.getStatement());
            copiedStatementList.add(copiedCell);
        }

        copiedTx.setStatements(copiedStatementList); // 设置复制的事务的语句列表
        return copiedTx;
    }

    public Transaction genRollbackTransaction(int txId) {
        IsolationLevel isolationLevel = Randomly.fromList(TableTool.possibleIsolationLevels);
        return genRollbackTransaction(txId, isolationLevel);
    }

    public Transaction genRollbackTransaction(int txId, IsolationLevel isolationLevel) { // 生成事务对象，包括 BEGIN、SQL
                                                                                         // 语句和COMMIT/ROLLBACK。-------------改过
        SQLConnection txConn = TableTool.genConnection(); // 生成事务连接
        Transaction tx = new Transaction(txId, isolationLevel, txConn); // 创建事务对象
        setTxMode(txConn); // 设置为悲观事务，如果是TiDB
        setIsolationLevel(txConn, isolationLevel); // 设置隔离级别
        int n = Randomly.getNextInt(TableTool.TxSizeMin, TableTool.TxSizeMax); // 随机生成事务大小
        ArrayList<StatementCell> statementList = new ArrayList<>();

        int currentStatementId = 0; // 用于跟踪当前语句的 statementId

        // 随机决定是否插入 SET autocommit = ON/OFF 语句
        if (Randomly.getPercentage(10)) {
            String autocommitStmt = "SET autocommit = " + (Randomly.getBoolean() ? "ON" : "OFF");
            StatementCell cell = new StatementCell(tx, currentStatementId++, autocommitStmt);
            statementList.add(cell);
        }

        StatementCell cell = new StatementCell(tx, currentStatementId++, "BEGIN"); // 添加 BEGIN 语句
        statementList.add(cell);

        for (int i = 1; i <= n; i++) {
            cell = new StatementCell(tx, currentStatementId++, genIUDStatement());
            statementList.add(cell);
        }

        String lastStmt = "ROLLBACK";
        // if (Randomly.getBoolean()) {
        // lastStmt = "ROLLBACK"; // 随机决定以 COMMIT 或 ROLLBACK 结束
        // }
        cell = new StatementCell(tx, currentStatementId++, lastStmt);
        statementList.add(cell);

        tx.setStatements(statementList);
        return tx;
    }

    public Transaction genNullTransaction(int txId) {
        IsolationLevel isolationLevel = Randomly.fromList(TableTool.possibleIsolationLevels);
        return genNullTransaction(txId, isolationLevel);
    }

    public Transaction genNullTransaction(int txId, IsolationLevel isolationLevel) {
        SQLConnection txConn = TableTool.genConnection();
        Transaction tx = new Transaction(txId, isolationLevel, txConn);
        setIsolationLevel(txConn, isolationLevel);
        ArrayList<StatementCell> statementList = new ArrayList<>();
        StatementCell cell = new StatementCell(tx, 0, "BEGIN");
        statementList.add(cell);
        cell = new StatementCell(tx, 1, "COMMIT");
        statementList.add(cell);
        tx.setStatements(statementList);
        return tx;
    }

    public String genStatement() { // 随机生成 SQL 语句（SELECT、INSERT、UPDATE、DELETE）。
        String statement;
        do {
            while (true) {
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    statement = genSelectStatement();
                    break;
                }
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    if (Randomly.getBooleanWithRatherLowProbability()) {
                        statement = genInsertStatement();
                    } else {
                        statement = genUpdateStatement();
                    }
                    break;
                }
                if (Randomly.getBooleanWithSmallProbability()) {
                    statement = genDeleteStatement();
                    break;
                }
            }
        } while (!TableTool.checkSyntax(statement));
        return statement;
    }

    public String genStatement2() { // 随机生成 SQL 语句（SELECT、INSERT、UPDATE、DELETE）。但是更新是更新x=x+2而不是x=2，针对改变表数据的蜕变
        String statement;
        do {
            while (true) {
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    statement = genSelectStatement();
                    break;
                }
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    if (Randomly.getBooleanWithRatherLowProbability()) {
                        statement = genInsertStatement();
                    } else {
                        statement = genUpdateStatement2();
                    }
                    break;
                }
                if (Randomly.getBooleanWithSmallProbability()) {
                    statement = genDeleteStatement();
                    break;
                }
            }
        } while (!TableTool.checkSyntax(statement));
        return statement;
    }

    public String genIUDStatement() {
        String statement;
        do {
            while (true) {
                // if (Randomly.getBooleanWithRatherLowProbability()) {
                // statement = genSelectStatement();
                // break;
                // }
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    if (Randomly.getBooleanWithRatherLowProbability()) {
                        statement = genInsertStatement();
                    } else {
                        statement = genUpdateStatement();
                    }
                    break;
                }
                if (Randomly.getBooleanWithSmallProbability()) {
                    statement = genDeleteStatement();
                    break;
                }
            }
        } while (!TableTool.checkSyntax(statement));
        return statement;
    }

    @Override
    public String toString() { // 返回表的字符串表示。
        return String.format("[Table %s in DB %s Column:%s]", tableName, TableTool.DatabaseName,
                columnNames);
    }
}
