package test;

import com.beust.jcommander.Parameter;
import lombok.Data;

@Data
public class Options {
    public enum TestCaseGenerationMode {
        PRESET, // 预设测试用例
        METAMORPHIC1, // 蜕变关系1
        METAMORPHIC2, // 蜕变关系2
        METAMORPHIC3, // 蜕变关系3
        METAMORPHIC4 // 蜕变关系4
    }

    @Parameter(names = { "--dbms" }, description = "Specifies the target DBMS")
    private String DBMS = "mysql";

    @Parameter(names = { "--set-case" }, description = "Whether use a specified case")
    private boolean setCase = false;

    @Parameter(names = { "--case-file" }, description = "Specifies the input file of the specified case")
    private String caseFile = "";

    @Parameter(names = { "--db" }, description = "Specifies the test database")
    private String dbName = "test";

    @Parameter(names = { "--table" }, description = "Specifies the test table")
    private String tableName = "mtest";

    @Parameter(names = "--username", description = "The user name used to log into the DBMS")
    private String userName = "root";

    @Parameter(names = "--password", description = "The password used to log into the DBMS")
    private String password = "";

    @Parameter(names = "--host", description = "The host used to log into the DBMS")
    private String host = "127.0.0.1";

    @Parameter(names = "--port", description = "The port used to log into the DBMS")
    private int port = 3306;

    @Parameter(names = {
            "--mode" }, description = "Test case generation mode: metamorphic1, metamorphic2, metamorphic3")
    private String generationMode = "mt1";

    public TestCaseGenerationMode getGenerationMode() {
        if (setCase) {
            return TestCaseGenerationMode.PRESET;
        }
        switch (generationMode.toLowerCase()) {
            case "mt1":
                return TestCaseGenerationMode.METAMORPHIC1;
            case "mt2":
                return TestCaseGenerationMode.METAMORPHIC2;
            case "mt3":
                return TestCaseGenerationMode.METAMORPHIC3;
            case "mt4":
                return TestCaseGenerationMode.METAMORPHIC4;
            default:
                return TestCaseGenerationMode.METAMORPHIC1;
        }
    }
}
