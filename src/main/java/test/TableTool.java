package test;

import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
public class TableTool {

    static public final String RowIdColName = "rid"; // 用于唯一标识表中的每一行。
    static private final String MtTablePrefix = "_mt_"; // 定义测试表的前缀，（用途：用于生成临时表或备份表的名称）
    static private final String BackupName = "backup"; // 定义备份表的名称, (用途：在测试过程中保存表的备份数据)
    static private final String OriginalName = "origin"; // 定义原始表的名称, (用途：在测试过程中保存表的初始状态)
    static public final int TxSizeMin = 2; // 定义事务中 SQL 语句数量的最小值
    static public final int TxSizeMax = 5;
    static public final int CheckSize = 10; // 定义每次测试中检查的事务对数量
    static public final Transaction txInit = new Transaction(0); // 初始化一个事务对象，ID 为 0。
    static public final Randomly rand = new Randomly();
    static public final BugReport bugReport = new BugReport(); // 错误报告工具
    static public final BugReport2 bugReport2 = new BugReport2(); // 错误报告工具2.由于改变表数据的bug输出
    static public int txPair = 0; // 记录已测试的事务对数量
    static public int allCase = 0; // 记录总测试用例数量
    static public int skipCase = 0; // 记录跳过的测试用例数量 (统计无效或无法执行的测试用例)

    static public List<IsolationLevel> possibleIsolationLevels; // 记录支持的隔离级别（如 READ COMMITTED, REPEATABLE READ）
    static public SQLConnection conn; // 数据库连接对象
    static public Options options; // 存储测试参数（如数据库类型、主机地址、端口等）
    static public String TableName = "mtest"; // 定义测试表的名称
    static public String DatabaseName = "test"; // 定义测试数据库的名称
    static public DBMS dbms; // 数据库管理系统类型（如 MySQL、MariaDB）
    static int ColCount; // 记录表的列数
    static int rowIdColIdx; // 记录 RowId 列的索引 (用于快速定位 RowId 列)
    static ArrayList<String> colNames; // 记录表的列名 (用于动态生成 SQL 语句)
    static ArrayList<String> colTypeNames; // 记录表的列类型 (用于动态生成 SQL 语句)
    static HashMap<String, Index> indexes; // 记录表的索引信息
    static String insertPrefix; // 记录 INSERT 语句的前缀（如 INSERT INTO table (col1, col2)）
    static int nextRowId; // 记录下一个可用的 RowId
    static Transaction firstTxnInSerOrder; // 记录在串行化顺序中的第一个事务。
    public static List<String> executedStatements = new ArrayList<>(); // 当预设测试用例时候用到

    // 新增：记录当前事务对x列的增量值（专用于METAMORPHIC6）
    static public int lastXIncrement = 0;
    static public int firstXIncrement = 0;
    static public int secondXIncrement = 0;
    static public String predicate_X = "";

    static void initialize(Options options) { // 初始化测试工具的核心配置
        // java -jar mtest*.jar --dbms mariadb --host 127.0.0.1 --port 10004 --username
        // root --password root --table t
        dbms = DBMS.valueOf(options.getDBMS().toUpperCase()); // mariadb
        TableName = options.getTableName(); // t
        DatabaseName = options.getDbName(); // test
        TableTool.options = options;
        if (TableTool.dbms == DBMS.POSTGRES) {
            TableTool.conn = getConnectionFromOptions_Post(options);
        } else {
            TableTool.conn = getConnectionFromOptions(options);
        }
        logConnectionInfo(); // 输出连接信息
        possibleIsolationLevels = new ArrayList<>(
                Arrays.asList(IsolationLevel.READ_COMMITTED, IsolationLevel.REPEATABLE_READ)); // 初始化支持的隔离级别列表，默认包含
                                                                                               // READ_COMMITTED 和
                                                                                               // REPEATABLE_READ。
        if (TableTool.dbms == DBMS.MYSQL || TableTool.dbms == DBMS.MARIADB) { // 如果数据库类型是MySQL或MariaDB，额外添加READ_UNCOMMITTED和SERIALIZABLE隔离级别。
            possibleIsolationLevels.add(IsolationLevel.READ_UNCOMMITTED);
            possibleIsolationLevels.add(IsolationLevel.SERIALIZABLE);
        }

    }

    static SQLConnection getConnectionFromOptions(Options options) {
        Connection con;
        try {
            // 构建基础 URL（不包含数据库名）
            String baseUrl = "jdbc:mysql://" + options.getHost() + ":" + options.getPort() +
                    "/?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8";

            // 第一次连接（无数据库）
            con = DriverManager.getConnection(baseUrl, options.getUserName(), options.getPassword());

            Statement statement = con.createStatement();
            statement.execute("DROP DATABASE IF EXISTS " + options.getDbName());
            statement.execute("CREATE DATABASE " + options.getDbName());
            statement.close();
            con.close();

            // 第二次连接（指定数据库）
            String fullUrl = "jdbc:mysql://" + options.getHost() + ":" + options.getPort() +
                    "/" + options.getDbName() +
                    "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8";

            con = DriverManager.getConnection(fullUrl, options.getUserName(), options.getPassword());
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to database: ", e);
        }
        return new SQLConnection(con);
    }

    static SQLConnection getConnectionFromOptions_Post(Options options) {
        Connection con;
        try {
            // PostgreSQL JDBC URL
            String baseUrl = "jdbc:postgresql://" + options.getHost() + ":" + options.getPort() +
                    "/postgres?ssl=false&stringtype=unspecified";

            // 第一次连接（连接到默认的 postgres 数据库）
            con = DriverManager.getConnection(baseUrl, options.getUserName(), options.getPassword());

            Statement statement = con.createStatement();
            statement.execute("DROP DATABASE IF EXISTS " + options.getDbName());
            statement.execute("CREATE DATABASE " + options.getDbName());
            statement.close();
            con.close();

            // 第二次连接（连接到新创建的数据库）
            String fullUrl = "jdbc:postgresql://" + options.getHost() + ":" + options.getPort() +
                    "/" + options.getDbName() + "?ssl=false&stringtype=unspecified";

            con = DriverManager.getConnection(fullUrl, options.getUserName(), options.getPassword());
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to database: ", e);
        }
        return new SQLConnection(con);
    }

    private static void logConnectionInfo() { // ------------------------------------------log--------------
        if (conn == null) {
            log.info("Connection is null.");
            return;
        }

        try {
            // 获取数据库元数据
            DatabaseMetaData metaData = conn.getMetaData();
            log.info("Database URL: {}", metaData.getURL());
            log.info("Database Product Name: {}", metaData.getDatabaseProductName());
            log.info("Database Product Version: {}", metaData.getDatabaseProductVersion());
            log.info("Driver Name: {}", metaData.getDriverName());
            log.info("Driver Version: {}", metaData.getDriverVersion());
            log.info("User Name: {}", metaData.getUserName());
        } catch (SQLException e) {
            log.error("Failed to retrieve connection information: {}", e.getMessage());
        }
    }

    public static boolean executeWithConn(SQLConnection conn, String sql) {
        Statement statement;
        try {
            statement = conn.createStatement();
            statement.execute(sql);
            statement.close();
        } catch (SQLException e) {
            log.info("Execute SQL failed: {}", sql);
            log.info(e.getMessage());
            return false;
        }
        return true;
    }

    public static boolean executeOnTable(String sql) {
        return executeWithConn(conn, sql);
    }

    public static boolean checkSyntax(String query) { // 检查 SQL 查询的语法是否正确
        try {
            Statement statement = conn.createStatement();
            statement.execute(query);
            statement.close();
        } catch (SQLException e) {
            return false;
        }
        return true;
    }

    public static SQLConnection genConnection() { // 生成一个新的数据库连接
        Connection con;
        try {
            con = DriverManager.getConnection(conn.getConnectionURL(), options.getUserName(),
                    options.getPassword());
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to database: ", e);
        }
        return new SQLConnection(con);
    }

    public static void preProcessTable() { // 对数据库表进行预处理，包括添加 RowId 列、备份原始表和填充表元数据。
        if (TableTool.dbms == DBMS.POSTGRES) {
            addRowIdColumnAndFill_Postgres();
            backupOriginalTable(); // 备份原始表。
            fillTableMetaData_Postgres();
        } else {
            addRowIdColumnAndFill();
            backupOriginalTable();
            fillTableMetaData(); // 填充表的元数据（如列名、列类型等）。
        }
    }

    private static void addRowIdColumnAndFill_Postgres() {
        AtomicBoolean hasRowIdCol = new AtomicBoolean(false);
        String query = "SELECT * FROM " + TableName + " LIMIT 0";
        TableTool.executeQueryWithCallback(query, rs -> {
            try {
                final ResultSetMetaData metaData = rs.getMetaData();
                final int columnCount = metaData.getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    if (metaData.getColumnName(i).equals(RowIdColName)) {
                        hasRowIdCol.set(true);
                        break;
                    }
                }
                rs.close();
            } catch (SQLException e) {
                throw new RuntimeException("Check RowId column failed: ", e);
            }
        });

        if (!hasRowIdCol.get()) {
            String sql = String.format("ALTER TABLE %s ADD COLUMN %s INT", TableName, RowIdColName);
            TableTool.executeOnTable(sql);
        }

        nextRowId = 1;
        fillAllRowId_Postgres();
    }

    static ArrayList<Integer> fillAllRowId_Postgres() {
        ArrayList<Integer> filledRowIds = new ArrayList<>();
        while (true) {
            int rowId = fillOneRowId_Postgres();
            if (rowId < 0)
                break;
            filledRowIds.add(rowId);
        }
        return filledRowIds;
    }

    static int fillOneRowId_Postgres() {
        int rowId = getNewRowId();

        // PostgreSQL 不支持 UPDATE ... LIMIT
        String sql = String.format(
                "WITH cte AS (SELECT ctid FROM %s WHERE %s IS NULL LIMIT 1) " +
                        "UPDATE %s SET %s = %d FROM cte WHERE %s.ctid = cte.ctid",
                TableName, RowIdColName,
                TableName, RowIdColName, rowId,
                TableName);

        try (Statement statement = conn.createStatement()) {
            int ret = statement.executeUpdate(sql);
            if (ret == 1) {
                return rowId;
            } else if (ret == 0) {
                return -1; // 没有行需要填充
            } else {
                throw new RuntimeException("Unexpected affected rows: " + ret);
            }
        } catch (SQLException e) {
            throw new RuntimeException("fillOneRowId_Postgres failed", e);
        }
    }

    private static void addRowIdColumnAndFill() { // 检查表是否包含 RowId 列，如果没有则添加，并填充 RowId 值。
        AtomicBoolean hasRowIdCol = new AtomicBoolean(false);
        String query = "SELECT * FROM " + TableName;
        TableTool.executeQueryWithCallback(query, rs -> {
            try {
                // log.info("ResultSetMetaData: {}", rs.getMetaData());
                // //-----------------------------------------------
                final ResultSetMetaData metaData = rs.getMetaData();
                final int columnCount = metaData.getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    if (metaData.getColumnName(i).equals(RowIdColName)) {
                        hasRowIdCol.set(true);
                        break;
                    }
                }
                rs.close(); // 关闭结果集，释放资源
            } catch (SQLException e) {
                throw new RuntimeException("Add RowId column failed: ", e);
            }
        });
        if (!hasRowIdCol.get()) { // 如果表不包含 RowId 列，则通过 ALTER TABLE 语句添加
            String sql = "ALTER TABLE " + TableName + " ADD COLUMN " + RowIdColName + " INT";
            TableTool.executeOnTable(sql);
        }
        nextRowId = 1;
        fillAllRowId(); // 为表中的每一行填充 RowId 值
    }

    private static void fillTableMetaData() {
        nextRowId = getMaxRowId() + 1;
        colNames = new ArrayList<>();
        colTypeNames = new ArrayList<>();
        indexes = new HashMap<>();
        Statement statement;
        ResultSet rs;
        try {
            String query = "SELECT * FROM " + TableName;
            statement = conn.createStatement();
            rs = statement.executeQuery(query);
            ResultSetMetaData metaData = rs.getMetaData();
            ColCount = metaData.getColumnCount();
            for (int i = 1; i <= ColCount; i++) {
                String colName = metaData.getColumnName(i);
                if (colName.equals(RowIdColName)) {
                    rowIdColIdx = i;
                } else {
                    colNames.add(colName);
                    colTypeNames.add(metaData.getColumnTypeName(i));
                }
            }
            statement.close();
            rs.close();
            String ignore = "";
            if (dbms.getProtocol().equals("mysql")) { // 如果是 MySQL 数据库，则在插入语句中添加 IGNORE 关键字（用于忽略重复键错误）
                ignore = "IGNORE ";
            }
            insertPrefix = "INSERT " + ignore + "INTO " + TableName + "(" + RowIdColName + ", "
                    + String.join(", ", colNames) + ") VALUES ";
            query = String.format("select * from information_schema.statistics " +
                    "where table_schema = '%s' and table_name = '%s'", DatabaseName, TableName);
            statement = conn.createStatement();
            rs = statement.executeQuery(query);
            while (rs.next()) {
                String index_name = rs.getString("INDEX_NAME");
                if (!indexes.containsKey(index_name)) { // 如果索引名称不存在于 indexes 中，则创建一个新的 Index 对象，并设置其是否为主键或唯一索引。
                    boolean isPrimary = index_name.equals("PRIMARY");
                    boolean isUnique = rs.getInt("NON_UNIQUE") == 0;
                    indexes.put(index_name, new Index(index_name, isPrimary, isUnique));
                }
                indexes.get(index_name).indexedCols.add(rs.getString("COLUMN_NAME"));
            }
            statement.close();
            rs.close();
        } catch (SQLException e) {
            throw new RuntimeException("Fetch metadata of table failed:", e);
        }
    }

    static void fillTableMetaData_Postgres() {
        try (Statement stmt = conn.createStatement()) {
            nextRowId = getMaxRowId() + 1;
            colNames = new ArrayList<>();
            colTypeNames = new ArrayList<>();
            indexes = new HashMap<>();
            rowIdColIdx = -1;
            ColCount = 0;

            // ===== 1️⃣ 获取列信息（顺序非常重要）=====
            String colSQL = "SELECT column_name, data_type " +
                    "FROM information_schema.columns " +
                    "WHERE table_schema = 'public' " +
                    "AND table_name = '" + TableName + "' " +
                    "ORDER BY ordinal_position";

            ResultSet rsCol = stmt.executeQuery(colSQL);

            int colIdx = 1;
            while (rsCol.next()) {
                String colName = rsCol.getString("column_name");
                String typeName = rsCol.getString("data_type");

                ColCount++;

                if (colName.equals(RowIdColName)) {
                    rowIdColIdx = colIdx;
                } else {
                    colNames.add(colName);
                    colTypeNames.add(typeName);
                }
                colIdx++;
            }
            rsCol.close();

            if (ColCount == 0) {
                throw new IllegalStateException("No columns found for table " + TableName);
            }

            if (rowIdColIdx == -1) {
                throw new IllegalStateException(
                        "RowId column '" + RowIdColName + "' not found in table " + TableName);
            }

            // ===== 2️⃣ 构造 INSERT 前缀（Postgres 没有 IGNORE）=====
            insertPrefix = "INSERT INTO " + TableName + "("
                    + RowIdColName + ", "
                    + String.join(", ", colNames)
                    + ") VALUES ";

            // ===== 3️⃣ 获取索引信息 =====
            String idxSQL = "SELECT i.relname AS index_name, " +
                    "       a.attname AS column_name, " +
                    "       ix.indisunique AS is_unique, " +
                    "       ix.indisprimary AS is_primary " +
                    "FROM pg_class t " +
                    "JOIN pg_index ix ON t.oid = ix.indrelid " +
                    "JOIN pg_class i ON i.oid = ix.indexrelid " +
                    "JOIN pg_attribute a ON a.attrelid = t.oid " +
                    "WHERE a.attnum = ANY(ix.indkey) " +
                    "AND t.relname = '" + TableName + "'";

            ResultSet rsIdx = stmt.executeQuery(idxSQL);
            while (rsIdx.next()) {
                String indexName = rsIdx.getString("index_name");
                String columnName = rsIdx.getString("column_name");
                boolean isUnique = rsIdx.getBoolean("is_unique");
                boolean isPrimary = rsIdx.getBoolean("is_primary");

                indexes
                        .computeIfAbsent(indexName,
                                k -> new Index(indexName, isPrimary, isUnique)).indexedCols
                        .add(columnName);
            }
            rsIdx.close();

        } catch (SQLException e) {
            throw new RuntimeException("Fetch metadata of table failed (Postgres): " + TableName, e);
        }
    }

    public static void executeQueryWithCallback(String query, ResultSetHandler handler) {
        Statement statement;
        ResultSet resultSet;
        try {
            statement = conn.createStatement();
            resultSet = statement.executeQuery(query);
            handler.handle(resultSet);
            resultSet.close();
            statement.close();
        } catch (SQLException e) {
            log.info("Execute query failed: {}", query);
            e.printStackTrace();
        }
    }

    static ArrayList<Integer> fillAllRowId() {
        ArrayList<Integer> filledRowIds = new ArrayList<>();
        while (true) {
            int rowId = fillOneRowId();
            if (rowId < 0)
                break;
            filledRowIds.add(rowId);
        }
        return filledRowIds;
    }

    static int fillOneRowId() {
        int rowId = getNewRowId();
        String sql = String.format("UPDATE %s SET %s = %d WHERE %s IS NULL LIMIT 1",
                TableName, RowIdColName, rowId, RowIdColName);
        Statement statement;
        int ret = -1;
        try {
            statement = conn.createStatement();
            ret = statement.executeUpdate(sql);
            statement.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (ret == 1) {
            return rowId;
        } else if (ret == 0) {
            return -1;
        } else {
            throw new RuntimeException("Insert more than one row?");
        }
    }

    static int getNewRowId() { // 生成一个新的行 ID
        return nextRowId++;
    }

    static int getMaxRowId() { // 获取表中最大的行 ID
        HashSet<Integer> rowIds = getRowIdsFromWhere("true");
        int maxRowId = 0;
        for (int rowId : rowIds) {
            if (rowId > maxRowId) {
                maxRowId = rowId;
            }
        }
        return maxRowId;
    }

    static HashSet<Integer> getRowIdsFromWhere(String whereClause) {
        HashSet<Integer> res = new HashSet<>();
        String query = "SELECT " + RowIdColName + " FROM " + TableName + " WHERE " + whereClause;
        TableTool.executeQueryWithCallback(query, rs -> {
            try {
                while (rs.next()) {
                    res.add(rs.getInt(RowIdColName));
                }
                rs.close();
            } catch (SQLException e) {
                throw new RuntimeException("Get affected rows from where failed: ", e);
            }
        });
        return res;
    }

    static View tableToView() {
        View view = new View();
        String query = "SELECT * FROM " + TableName;
        TableTool.executeQueryWithCallback(query, rs -> {
            try {
                while (rs.next()) {
                    int rowId = 0;
                    Object[] data = new Object[ColCount - 1];
                    int idx = 0;
                    for (int i = 1; i <= ColCount; i++) {
                        if (i == rowIdColIdx) {
                            rowId = rs.getInt(i);
                        } else {
                            data[idx++] = rs.getObject(i);
                        }
                    }
                    view.data.put(rowId, data);
                }
                rs.close();
            } catch (SQLException e) {
                throw new RuntimeException("Table to view failed: ", e);
            }
        });
        return view;
    }

    static void recoverTableFromSnapshot(String snapshotName) { // 从快照中恢复表的数据
        String mtTableName = MtTablePrefix + snapshotName; // 生成快照表的名称，格式为 _mt_快照名称
        if (TableTool.dbms == DBMS.POSTGRES) {
            cloneTable_Postgres(mtTableName, TableName);
        } else {
            cloneTable(mtTableName, TableName); // 将快照表的数据和结构克隆回当前表
        }
    }

    static void recoverTableFromSnapshot2(String snapshotName, Set<String> excludedColumns) {
        // 从快照中恢复表的数据
        String mtTableName = MtTablePrefix + snapshotName; // 生成快照表的名称，格式为 _mt_快照名称
        if (TableTool.dbms == DBMS.POSTGRES) {
            cloneTable2_Postgres(mtTableName, TableName, excludedColumns);
        } else {
            cloneTable2(mtTableName, TableName, excludedColumns); // 将快照表的数据和结构克隆回当前表
        }
    }

    static void backupCurTable() {
        takeSnapshotForTable(BackupName);
    }

    static void recoverCurTable() {
        recoverTableFromSnapshot(BackupName);
    }

    static void backupOriginalTable() {
        takeSnapshotForTable(OriginalName);
    }

    static void recoverOriginalTable() {
        recoverTableFromSnapshot(OriginalName);
    }

    static void recoverOriginalTable2(Set<String> excludedColumns) { // 蜕变关系4，不同的表：从备份中恢复原始表，再改变
        recoverTableFromSnapshot2(OriginalName, excludedColumns);
    }

    static void takeSnapshotForTable(String snapshotName) {
        String mtTableName = MtTablePrefix + snapshotName;
        if (TableTool.dbms == DBMS.POSTGRES) {
            cloneTable_Postgres(TableName, mtTableName);
        } else {
            cloneTable(TableName, mtTableName);
        }
    }

    // static void cloneTable(String tableName, String newTableName) { //
    // 克隆一个表，包括表结构和数据
    // try {
    // Statement statement = conn.createStatement();
    // // statement.execute("SET SESSION innodb_lock_wait_timeout=5;");
    // statement.execute(String.format("DROP TABLE IF EXISTS %s", newTableName)); //
    // 如果目标表 newTableName 已存在，则删除它
    // statement.close();
    // log.info("Dropped original table");
    // statement = conn.createStatement();
    // ResultSet rs = statement.executeQuery(String.format("SHOW CREATE TABLE %s",
    // tableName)); // 通过SHOW CREATE
    // // TABLE查询源表的创建SQL语句
    // rs.next(); // 将游标移动到结果集的第一行
    // String createSQL = rs.getString("Create Table"); // 获取 Create Table
    // 列的值，即源表的创建 SQL 语句
    // rs.close();
    // statement.close();
    // createSQL = createSQL.replace("\n", ""). // 移除 SQL 中的换行符，并将表名从 tableName 替换为
    // newTableName。
    // replace("CREATE TABLE `" + tableName + "`", "CREATE TABLE `" + newTableName +
    // "`");
    // statement = conn.createStatement();
    // statement.execute(createSQL); // 使用修改后的 SQL 创建新表。
    // statement.close();
    // statement = conn.createStatement();
    // statement.execute(String.format("INSERT INTO %s SELECT * FROM %s",
    // newTableName, tableName)); // 将源表的数据插入到新表中。
    // statement.close();
    // } catch (SQLException e) {
    // e.printStackTrace();
    // }
    // }

    // 在 TableTool.java 中

    static void cloneTable(String tableName, String newTableName) {
        Statement stmt = null;
        ResultSet rs = null;
        boolean success = false;
        for (int attempt = 0; attempt < 5 && !success; attempt++) {
            if (attempt > 0) {
                log.info("Clone table retry {}/5, waiting for lingering locks to release...", attempt);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                }
            }
            try {
                log.info("Dropping table {} if exists...", newTableName);
                stmt = conn.createStatement();
                stmt.execute(String.format("DROP TABLE IF EXISTS %s", newTableName));
                stmt.close();

                // 验证 DROP 是否真的生效（MDL残留锁可能导致 DROP 静默失败）
                stmt = conn.createStatement();
                rs = stmt.executeQuery(String.format(
                        "SELECT COUNT(*) FROM information_schema.tables " +
                                "WHERE table_schema = DATABASE() AND table_name = '%s'",
                        newTableName));
                rs.next();
                if (rs.getInt(1) > 0) {
                    throw new SQLException("Table " + newTableName + " still exists after DROP (MDL lock held)");
                }
                rs.close();
                stmt.close();
                log.info("Dropped original table");

                stmt = conn.createStatement();
                rs = stmt.executeQuery(String.format("SHOW CREATE TABLE %s", tableName));
                rs.next();
                String createSQL = rs.getString("Create Table");
                rs.close();
                stmt.close();

                createSQL = createSQL.replace("\r", "").replace("\n", "")
                        .replaceFirst("(?i)CREATE TABLE [`\"]?" + tableName + "[`\"]?",
                                "CREATE TABLE `" + newTableName + "`");

                stmt = conn.createStatement();
                stmt.execute(createSQL);
                stmt.close();

                // 复制数据
                stmt = conn.createStatement();
                stmt.execute(String.format("INSERT INTO %s SELECT * FROM %s", newTableName, tableName));
                stmt.close();

                success = true;
            } catch (SQLException e) {
                log.warn("Clone table attempt {} failed: {}", attempt + 1, e.getMessage());
                if (e.getMessage().contains("already exists") ||
                        e.getMessage().contains("Lock wait timeout") ||
                        e.getMessage().contains("still exists after DROP")) {
                    continue;
                }
                log.error("Clone table failed.", e);
                e.printStackTrace();
                break;
            } finally {
                try {
                    if (rs != null)
                        rs.close();
                } catch (Exception ignored) {
                }
                try {
                    if (stmt != null)
                        stmt.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (!success) {
            throw new RuntimeException(
                    "Clone table " + tableName + " -> " + newTableName + " failed after all retries.");
        }
    }

    static void cloneTable_Postgres(String tableName, String newTableName) {
        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            log.info("Dropping table {} if exists...", newTableName);
            stmt.execute(String.format("DROP TABLE IF EXISTS %s", newTableName));
            stmt.close();

            stmt = conn.createStatement();
            String createSQL = String.format("CREATE TABLE %s (LIKE %s INCLUDING ALL)", newTableName, tableName);
            stmt.execute(createSQL);
            stmt.close();

            stmt = conn.createStatement();
            String insertSQL = String.format("INSERT INTO %s SELECT * FROM %s", newTableName, tableName);
            stmt.execute(insertSQL);
            stmt.close();

            log.info("Table cloned successfully: {} -> {}", tableName, newTableName);
        } catch (SQLException e) {
            log.error("Clone table failed.", e);
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null)
                    stmt.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static void cloneTable2(String tableName, String newTableName, Set<String> excludedColumns) {
        Statement stmt = null;
        ResultSet rs = null;

        // 处理排除列
        Set<String> safeExcluded = new HashSet<>();
        if (excludedColumns != null) {
            for (String col : excludedColumns) {
                safeExcluded.add(col.toLowerCase());
            }
        }

        try {
            log.info("Starting safe clone table from {} to {}", tableName, newTableName);

            stmt = conn.createStatement();
            stmt.execute(String.format("DROP TABLE IF EXISTS `%s`", newTableName));
            stmt.close();

            stmt = conn.createStatement();
            rs = stmt.executeQuery(String.format("SHOW CREATE TABLE `%s`", tableName));
            if (rs.next()) {
                String createSQL = rs.getString("Create Table")
                        .replace("CREATE TABLE `" + tableName + "`", "CREATE TABLE `" + newTableName + "`");
                bugReport2.setCreateTableSQL2(createSQL);
                stmt.execute(createSQL);
            }
            rs.close();
            stmt.close();

            DatabaseMetaData metaData = conn.getMetaData();

            // 获取主键列（不被保护，允许修改以暴露隔离异常）
            Set<String> pkColumns = new HashSet<>();
            rs = metaData.getPrimaryKeys(null, null, tableName);
            while (rs.next()) {
                pkColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            rs.close();

            // 获取唯一索引列（排除主键）
            Set<String> protectedColumns = new HashSet<>();
            rs = metaData.getIndexInfo(null, null, tableName, true, false);
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (col != null && !pkColumns.contains(col.toLowerCase()))
                    protectedColumns.add(col.toLowerCase());
            }
            rs.close();
            log.info("PK columns (modifiable): {}", pkColumns);
            log.info("Protected columns (Unique, non-PK): {}", protectedColumns);

            List<String> columnNames = new ArrayList<>();
            List<String> selectExpressions = new ArrayList<>();

            rs = metaData.getColumns(null, null, tableName, null);
            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                String colType = rs.getString("TYPE_NAME").toUpperCase();
                int colSize = rs.getInt("COLUMN_SIZE");
                String colNameLower = colName.toLowerCase();
                String isAutoInc = rs.getString("IS_AUTOINCREMENT");

                columnNames.add("`" + colName + "`");

                if (safeExcluded.contains(colNameLower) ||
                        protectedColumns.contains(colNameLower) ||
                        "YES".equals(isAutoInc)) {
                    // 排除列/主键/唯一/自增 -> 原值
                    selectExpressions.add("`" + colName + "`");
                } else {
                    selectExpressions.add(generateRandomSqlExpression(colName, colType, colSize));
                }
            }
            rs.close();

            String insertSql = String.format(
                    "INSERT INTO `%s` (%s) SELECT %s FROM `%s`",
                    newTableName,
                    String.join(", ", columnNames),
                    String.join(", ", selectExpressions),
                    tableName);

            stmt = conn.createStatement();
            int rows = stmt.executeUpdate(insertSql);
            stmt.close();
            log.info("Cloned {} rows.", rows);

            if (rows == 0) {
                log.info("Source table is empty, fallback: clone original INSERT statements");
                List<String> fallbackInserts = generateInsertStatementsFromTable(tableName);
                stmt = conn.createStatement();
                for (String sql : fallbackInserts) {
                    String newSql = sql.replace("`" + tableName + "`", "`" + newTableName + "`");
                    stmt.executeUpdate(newSql);
                }
                stmt.close();
                log.info("Inserted {} fallback rows.", fallbackInserts.size());
            }

            List<String> insertStatements2 = generateInsertStatementsFromTable(newTableName);
            bugReport2.setInitializeStatements2(insertStatements2);

            if (!conn.getAutoCommit())
                conn.commit();

        } catch (SQLException e) {
            log.error("Failed to clone table", e);
            e.printStackTrace();
        } finally {
            try {
                if (rs != null)
                    rs.close();
            } catch (Exception ignored) {
            }
            try {
                if (stmt != null)
                    stmt.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String generateRandomSqlExpression(String colName, String colType, int colSize) {
        String col = "`" + colName + "`";
        String typeUpper = colType.toUpperCase();

        if (typeUpper.contains("INT")) {
            // 获取该类型的最大值 (近似)
            long maxVal;
            long minVal;
            if (typeUpper.contains("TINY")) {
                maxVal = 127;
                minVal = -128;
            } else if (typeUpper.contains("SMALL")) {
                maxVal = 32767;
                minVal = -32768;
            } else if (typeUpper.contains("MEDIUM")) {
                maxVal = 8388607;
                minVal = -8388608;
            } else if (typeUpper.contains("BIGINT")) {
                // BIGINT 太大了，很难溢出，但为了保险还是处理一下
                // 这里用一个相对安全的极大值
                maxVal = 9000000000000000000L;
                minVal = -9000000000000000000L;
            } else {
                // 普通 INT
                maxVal = 2147483647;
                minVal = -2147483648;
            }

            return String.format(
                    "IF(%s >= %d, %s - FLOOR(RAND() * 10), %s + FLOOR(RAND() * 10))",
                    col, maxVal - 20, col, col);

        }

        else if (typeUpper.contains("DECIMAL") || typeUpper.contains("NUMERIC")) {
            return col;
        } else if (typeUpper.contains("FLOAT") || typeUpper.contains("DOUBLE") || typeUpper.contains("REAL")) {
            return String.format(
                    "IF(ABS(%s) > 100000, %s * 0.5, %s * (1 + (RAND() * 0.01)))",
                    col, col, col);
        } else if (typeUpper.contains("CHAR") || typeUpper.contains("VARCHAR") || typeUpper.contains("TEXT")) {
            int suffixLen = 7;
            if (colSize <= suffixLen)
                return col;
            int safePrefixLen = Math.min(20, colSize - suffixLen);
            return String.format("CONCAT(LEFT(%s, %d), '_', SUBSTRING(MD5(RAND()), 1, 6))", col, safePrefixLen);
        } else if (typeUpper.contains("DATE") || typeUpper.contains("TIME")) {
            if (typeUpper.contains("DATE") || typeUpper.contains("TIMESTAMP")) {
                return String.format("IF(YEAR(%s) > 9900, %s, DATE_ADD(%s, INTERVAL FLOOR(RAND() * 10) DAY))", col, col,
                        col);
            }
            return String.format("DATE_ADD(%s, INTERVAL FLOOR(RAND() * 10) DAY)", col);
        }

        return col;
    }

    public static void cloneTable2_Postgres(String tableName, String newTableName, Set<String> excludedColumns) {
        Statement stmt = null;
        ResultSet rs = null;

        Set<String> safeExcluded = new HashSet<>();
        if (excludedColumns != null) {
            for (String c : excludedColumns)
                safeExcluded.add(c.toLowerCase());
        }

        try {
            log.info("Starting Postgres clone table from {} to {}", tableName, newTableName);

            stmt = conn.createStatement();
            stmt.execute("DROP TABLE IF EXISTS \"" + newTableName + "\"");
            stmt.execute("CREATE TABLE \"" + newTableName + "\" (LIKE \"" + tableName + "\" INCLUDING ALL)");
            stmt.close();

            DatabaseMetaData meta = conn.getMetaData();

            // 获取主键列（不被保护，允许修改以暴露隔离异常）
            Set<String> pkCols = new HashSet<>();
            rs = meta.getPrimaryKeys(null, null, tableName);
            while (rs.next())
                pkCols.add(rs.getString("COLUMN_NAME").toLowerCase());
            rs.close();

            // 获取唯一索引列（排除主键）
            Set<String> protectedCols = new HashSet<>();
            rs = meta.getIndexInfo(null, null, tableName, true, false);
            while (rs.next()) {
                String c = rs.getString("COLUMN_NAME");
                if (c != null && !pkCols.contains(c.toLowerCase()))
                    protectedCols.add(c.toLowerCase());
            }
            rs.close();
            log.info("PK columns (modifiable): {}", pkCols);
            log.info("Protected columns (Unique, non-PK): {}", protectedCols);

            List<String> cols = new ArrayList<>();
            List<String> exprs = new ArrayList<>();

            rs = meta.getColumns(null, null, tableName, null);
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                String type = rs.getString("TYPE_NAME").toUpperCase();
                int size = rs.getInt("COLUMN_SIZE");
                boolean autoInc = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));

                cols.add("\"" + col + "\"");

                String lower = col.toLowerCase();

                if (safeExcluded.contains(lower) || autoInc) {
                    exprs.add("\"" + col + "\"");
                } else if (protectedCols.contains(lower)) {
                    exprs.add(genUniqueSafe(col, type, size) + " AS \"" + col + "\"");
                } else {
                    exprs.add(generateRandomSqlExpression_Postgres(col, type, size) + " AS \"" + col + "\"");
                }
            }
            rs.close();

            String sql = String.format(
                    "INSERT INTO \"%s\" (%s) SELECT %s FROM \"%s\"",
                    newTableName,
                    String.join(", ", cols),
                    String.join(", ", exprs),
                    tableName);

            log.info("Executing Postgres SAFE data clone...");
            stmt = conn.createStatement();
            stmt.executeUpdate(sql);
            stmt.close();

            if (!conn.getAutoCommit())
                conn.commit();

            List<String> insertStatements2 = generateInsertStatementsFromTable_Postgres(newTableName);
            bugReport2.setInitializeStatements2(insertStatements2);

        } catch (SQLException e) {
            log.error("Postgres SAFE cloneTable2 failed", e);
        } finally {
            try {
                if (rs != null)
                    rs.close();
            } catch (Exception ignored) {
            }
            try {
                if (stmt != null)
                    stmt.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static List<String> generateInsertStatementsFromTable_Postgres(String tableName) {
        List<String> insertStatements = new ArrayList<>();
        String query = "SELECT * FROM " + tableName;

        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(query)) {

            java.sql.ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            StringBuilder columns = new StringBuilder("(");
            for (int i = 1; i <= columnCount; i++) {
                columns.append(metaData.getColumnName(i));

                if (i < columnCount)
                    columns.append(", ");
            }
            columns.append(")");

            while (rs.next()) {
                StringBuilder values = new StringBuilder("VALUES (");
                for (int i = 1; i <= columnCount; i++) {
                    Object value = rs.getObject(i);

                    if (value == null) {
                        values.append("NULL");
                    } else if (value instanceof Number) {
                        values.append(value);
                    } else if (value instanceof Boolean) {
                        values.append(value);
                    } else {
                        String strValue = value.toString().replace("'", "''");
                        values.append("'").append(strValue).append("'");
                    }

                    if (i < columnCount)
                        values.append(", ");
                }
                values.append(")");

                String insertSQL = "INSERT INTO " + tableName + " " + columns + " " + values;
                insertStatements.add(insertSQL);
            }
        } catch (SQLException e) {
            log.error("Failed to generate INSERT statements from table " + tableName, e);
            e.printStackTrace();
        }
        return insertStatements;
    }

    private static String generateRandomSqlExpression_Postgres(String colName, String colType, int colSize) {

        String col = "\"" + colName + "\"";
        String t = colType.toUpperCase();

        if (t.equals("SMALLINT")) {
            return "((abs(hashtext(" + col + "::text || ctid::text)) % 65535) - 32768)::smallint";
        }

        if (t.equals("INTEGER") || t.equals("INT")) {
            return "((abs(hashtext(" + col + "::text || ctid::text)) % 4294967295) - 2147483648)::integer";
        }

        if (t.equals("BIGINT")) {
            return "(hashtext(" + col + "::text || ctid::text)::bigint)";
        }

        if (t.contains("REAL") || t.contains("DOUBLE") || t.contains("NUMERIC")) {
            return "((abs(hashtext(" + col + "::text || ctid::text)) % 1000000) / 1000.0)";
        }

        if (t.contains("CHAR") || t.contains("VARCHAR") || t.contains("TEXT")) {

            int suffixLen = 8;
            int keep = colSize > 0 ? Math.max(colSize - suffixLen, 0) : 20;

            String base = colSize > 0
                    ? "left(" + col + "," + keep + ")"
                    : col;

            String expr = base + " || substr(md5(ctid::text),1," + suffixLen + ")";

            return colSize > 0
                    ? "left(" + expr + "," + colSize + ")"
                    : expr;
        }

        if (t.contains("DATE") || t.contains("TIME") || t.contains("TIMESTAMP")) {
            return col + " + ((abs(hashtext(ctid::text)) % 7) || ' days')::interval";
        }

        return col;
    }

    private static String genUniqueSafe(String col, String type, int size) {
        String c = "\"" + col + "\"";
        String t = type.toUpperCase();

        if (t.equals("SMALLINT")) {
            return "((" +
                    "((hashtext(" + c + "::text || ctid::text)::bigint % 65535) - 32768)" +
                    ")::smallint)";
        }

        if (t.equals("INTEGER") || t.equals("INT")) {
            return "(hashtext(" + c + "::text || ctid::text))";
        }

        if (t.equals("BIGINT")) {
            return "(hashtext(" + c + "::text || ctid::text)::bigint)";
        }

        if (t.contains("CHAR") || t.contains("VARCHAR") || t.contains("TEXT")) {

            int suffixLen = 8;
            int keep = size > 0 ? Math.max(size - suffixLen, 0) : 20;

            String base = size > 0
                    ? "left(" + c + "," + keep + ")"
                    : c;

            String expr = base + " || substr(md5(ctid::text),1," + suffixLen + ")";

            return size > 0
                    ? "left(" + expr + "," + size + ")"
                    : expr;
        }

        return c;
    }

    public static List<String> generateInsertStatementsFromTable(String tableName) {
        List<String> insertStatements = new ArrayList<>();
        String query = "SELECT * FROM " + tableName;

        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(query)) {

            java.sql.ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            StringBuilder columns = new StringBuilder("(");
            for (int i = 1; i <= columnCount; i++) {
                columns.append("`").append(metaData.getColumnName(i)).append("`");
                if (i < columnCount)
                    columns.append(", ");
            }
            columns.append(")");

            while (rs.next()) {
                StringBuilder values = new StringBuilder("VALUES (");
                for (int i = 1; i <= columnCount; i++) {
                    Object value = rs.getObject(i);
                    if (value == null) {
                        values.append("NULL");
                    } else if (value instanceof Number) {
                        values.append(value);
                    } else {
                        String strValue = value.toString().replace("'", "''"); // SQL转义单引号
                        values.append("'").append(strValue).append("'");
                    }
                    if (i < columnCount)
                        values.append(", ");
                }
                values.append(")");

                String insertSQL = "INSERT INTO `" + tableName + "` " + columns + " " + values;
                insertStatements.add(insertSQL);
            }
        } catch (SQLException e) {
            log.error("Failed to generate INSERT statements from table " + tableName, e);
            e.printStackTrace();
        }
        return insertStatements;
    }

    static void makeConflict(Transaction tx1, Transaction tx2) {
        StatementCell stmt1 = randomStmtWithCondition(tx1);
        StatementCell stmt2 = randomStmtWithCondition(tx2);
        int n = getNewRowId();
        if (Randomly.getBoolean() || n == 0) {
            stmt1.whereClause = stmt2.whereClause;
            stmt1.recomputeStatement();
        } else {
            int rowId = Randomly.getNextInt(1, n);
            try {
                stmt1.makeChooseRow(rowId);
                stmt2.makeChooseRow(rowId);
            } catch (Exception e) {
            }
        }
    }

    static private StatementCell randomStmtWithCondition(Transaction tx) {
        ArrayList<StatementCell> candidates = new ArrayList<>();
        for (StatementCell stmt : tx.statements) {
            if (stmt.type == StatementType.UPDATE || stmt.type == StatementType.DELETE
                    || stmt.type == StatementType.SELECT || stmt.type == StatementType.SELECT_SHARE
                    || stmt.type == StatementType.SELECT_UPDATE) {
                candidates.add(stmt);
            }
        }
        return Randomly.fromList(candidates);
    }

    public static void setIsolationLevel(Transaction tx) {
        String levelName = tx.isolationlevel.toString().replace("_", " ");
        String sql = "SET SESSION TRANSACTION ISOLATION LEVEL " + levelName;
        TableTool.executeWithConn(tx.conn, sql);
    }

    static ArrayList<Object> getQueryResultAsList(String query) { // 将查询结果转换为列表。
        return getQueryResultAsList(conn, query);
    }

    static ArrayList<Object> getQueryResultAsListWithException(SQLConnection conn, String query) throws SQLException {
        ArrayList<Object> res = new ArrayList<>();
        int columns;
        int rowIdIdx = 0;
        ResultSet rs = conn.createStatement().executeQuery(query);
        ResultSetMetaData metaData = rs.getMetaData();
        columns = metaData.getColumnCount();
        for (int i = 1; i <= columns; i++) {
            String colName = metaData.getColumnName(i);
            if (colName.equals(TableTool.RowIdColName)) {
                rowIdIdx = i;
            }
        }
        while (rs.next()) { // 遍历结果集
            int rid = 0;
            for (int i = 1; i <= columns; i++) {
                if (i == rowIdIdx) {
                    rid = rs.getInt(i);
                } else {
                    Object cell = rs.getObject(i);
                    if (cell instanceof byte[]) {
                        cell = byteArrToHexStr((byte[]) cell);
                    }
                    res.add(cell);
                }
            }
        }
        rs.close();
        return res;
    }

    static ArrayList<Object> getQueryResultAsList(SQLConnection conn, String query) {
        ArrayList<Object> res;
        try {
            res = getQueryResultAsListWithException(conn, query); // 执行查询
        } catch (SQLException e) {
            log.info(" -- get query result SQL exception: " + e.getMessage());
            res = new ArrayList<>();
        }
        return res;
    }

    static String byteArrToHexStr(byte[] bytes) {
        if (bytes.length == 0) {
            return "0";
        }
        final String HEX = "0123456789ABCDEF";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        sb.append("0x");
        for (byte b : bytes) {
            sb.append(HEX.charAt((b >> 4) & 0x0F));
            sb.append(HEX.charAt(b & 0x0F));
        }
        return sb.toString();
    }

    // public static boolean executeWithConn(SQLConnection conn, String sql) {
    // //在给定的数据库连接上执行 SQL 语句。
    // Statement statement;
    // try {
    // statement = conn.createStatement(); //创建 Statement 对象
    // statement.execute(sql); //执行 SQL 语句
    // statement.close();
    // } catch (SQLException e) {
    // log.info("Execute SQL failed: {}", sql);
    // log.info(e.getMessage());
    // return false;
    // }
    // return true;
    // }

    static void prepareTableFromScanner(Scanner input) {
        TableTool.executeOnTable("DROP TABLE IF EXISTS " + TableName); // 删除当前表
        String sql;
        do {
            sql = input.nextLine();
            if (sql.equals(""))
                break;
            TableTool.executedStatements.add(sql);
            TableTool.executeOnTable(sql);
        } while (true);
    }

    static Transaction readTransactionFromScanner(Scanner input, int txId) {
        Transaction tx = new Transaction(txId);
        tx.conn = genConnection();
        String isolationAlias = input.nextLine();
        tx.isolationlevel = IsolationLevel.getFromAlias(isolationAlias);
        String sql;
        int cnt = 0;
        do {
            if (!input.hasNext())
                break;
            sql = input.nextLine();
            if (sql.equals("") || sql.equals("END"))
                break;
            tx.statements.add(new StatementCell(tx, cnt++, sql));
        } while (true);
        return tx;
    }

    static String readScheduleFromScanner(Scanner input) {
        do {
            if (!input.hasNext())
                break;
            String scheduleStr = input.nextLine();
            if (scheduleStr.equals(""))
                continue;
            if (scheduleStr.equals("END"))
                break;
            return scheduleStr;
        } while (true);
        return "";
    }

    static public void setFirstXIncrement() {
        TableTool.firstXIncrement = new Random().nextInt(20) + 1;
    }

    static public void setSecondXIncrement() {
        TableTool.secondXIncrement = new Random().nextInt(20) + 1;
    }

    static public void setLastXIncrement() {
        TableTool.lastXIncrement = new Random().nextInt(20) + 1;
    }

}
