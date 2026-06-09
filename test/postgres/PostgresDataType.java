package test.postgres;

import test.Randomly;
import test.common.DataType;

import java.util.Arrays;

public enum PostgresDataType implements DataType {
    SMALLINT, INTEGER, BIGINT, // 整数类型
    REAL, DOUBLE_PRECISION, NUMERIC, // 浮点/数值类型
    CHAR, VARCHAR, TEXT, // 字符串类型
    DATE, TIME, TIMESTAMP, // 日期时间类型
    BOOLEAN, JSON, UUID; // 其他类型

    // 随机获取一个常用数据类型
    public static PostgresDataType getRandomDataType() {
        return Randomly.fromOptions(INTEGER, REAL, DOUBLE_PRECISION, CHAR, VARCHAR, TEXT);
    }

    @Override
    public boolean isNumeric() {
        return Arrays.asList(SMALLINT, INTEGER, BIGINT, REAL, DOUBLE_PRECISION, NUMERIC).contains(this);
    }

    @Override
    public boolean isString() {
        return Arrays.asList(CHAR, VARCHAR, TEXT).contains(this);
    }

    @Override
    public boolean hasLen() {
        // PostgreSQL 只有 CHAR 和 VARCHAR 支持长度定义
        return this == CHAR || this == VARCHAR;
    }

}