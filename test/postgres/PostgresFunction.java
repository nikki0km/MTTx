package test.postgres;

import test.Randomly;

import java.util.Arrays;

public enum PostgresFunction {
    ABS(1), ACOS(1), ASIN(1), ATAN(1), ATAN2(2),
    CEIL(1), CEILING(1), COS(1), COT(1),
    EXP(1), FLOOR(1), LN(1), LOG(1), LOG10(1),
    MOD(2), POWER(2), ROUND(1), SIGN(1), SQRT(1), TAN(1),

    LENGTH(1), LOWER(1), UPPER(1), TRIM(1), LTRIM(1), RTRIM(1),
    LEFT(2), RIGHT(2), CONCAT(2), SUBSTRING(3), REPLACE(3),

    NOW(0), CURRENT_DATE(0), CURRENT_TIME(0), CURRENT_TIMESTAMP(0),
    TO_CHAR(2), CAST(2), COALESCE(2), NULLIF(2); // , RANDOM(0)删了

    private final int argCnt;

    PostgresFunction(int cnt) {
        this.argCnt = cnt;
    }

    public int getArgCnt() {
        return argCnt;
    }

    public static PostgresFunction getRandomFunc() {
        return Randomly.fromList(Arrays.asList(PostgresFunction.values()));
    }
}
