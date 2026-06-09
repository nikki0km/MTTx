package test;

//import com.mysql.cj.xdevapi.Table;
import test.common.Table;
import test.mysql.MySQLTable;
import test.postgres.PostgresTable;

public enum DBMS { // 枚举类 DBMS
    MYSQL("mysql"), MARIADB("mysql"), TIDB("mysql"), POSTGRES("postgresql");

    private final String protocol;

    DBMS(String protocol) {
        this.protocol = protocol; // protocol：数据库的协议（如 mysql）,用于构建 JDBC 连接 URL。
    }

    public String getProtocol() {
        return protocol;
    }

    public Table buildTable(String tableName) {
        switch (TableTool.dbms) {
            case MYSQL:
            case MARIADB:
            case TIDB:
                return new MySQLTable(tableName); // 如果当前数据库是 MYSQL、MARIADB 或 TIDB，则返回一个 MySQLTable 对象。
            case POSTGRES:
                return new PostgresTable(tableName);
            default:
                throw new IllegalStateException("Unexpected value: " + TableTool.dbms); // 如果当前数据库不支持，则抛出
                                                                                        // IllegalStateException 异常。
        }
    }

}
