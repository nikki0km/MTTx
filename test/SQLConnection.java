package test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;

public class SQLConnection {
    private final Connection connection; // 一个 final 的 Connection 对象，表示与数据库的连接

    public SQLConnection(Connection connection) { // 初始化 SQLConnection 对象。
        this.connection = connection;
    }

    public String getDatabaseVersion() throws SQLException { // 获取数据库的版本信息
        DatabaseMetaData meta = connection.getMetaData(); // DatabaseMetaData 获取数据库的元数据
        return meta.getDatabaseProductVersion(); // 数据库的版本号（如 "5.7.34"）。
    }

    public String getConnectionURL() throws SQLException { // 获取数据库的连接 URL
        DatabaseMetaData meta = connection.getMetaData();
        return meta.getURL();
    }

    public String getDatabaseName() throws SQLException { // 获取数据库的名称
        DatabaseMetaData meta = connection.getMetaData();
        return meta.getDatabaseProductName().toLowerCase();
    }

    public void close() throws SQLException { // 关闭数据库连接
        connection.close();
    }

    public Statement prepareStatement(String arg) throws SQLException { // 创建一个预编译的 Statement 对象
        return connection.prepareStatement(arg);
    }

    public Statement createStatement() throws SQLException { // 创建一个普通的 Statement 对象
        return connection.createStatement();
    }

    public DatabaseMetaData getMetaData() { // 获取数据库的元数据对象
        try {
            return connection.getMetaData();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // 添加 isClosed() 方法
    public boolean isClosed() throws SQLException {
        return connection.isClosed();
    }

    // 添加 getAutoCommit() 方法
    public boolean getAutoCommit() throws SQLException {
        return connection.getAutoCommit();
    }

    // 添加 commit() 方法
    public void commit() throws SQLException {
        connection.commit();
    }

    // 添加 commit() 方法
    public void rollback() throws SQLException {
        connection.rollback();
    }

    // 添加 setAutoCommit() 方法
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        connection.setAutoCommit(autoCommit);
    }
}
