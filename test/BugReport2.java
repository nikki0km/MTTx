package test;

import lombok.Data;
import java.util.List;

@Data
public class BugReport2 {
    private boolean bugFound = false;

    private String createTableSQL;
    // 【新增】用于存储 Table 2 的建表语句
    private String createTableSQL2;

    private List<String> initializeStatements;
    // 【新增】用于存储 Table 2 的初始化语句
    private List<String> initializeStatements2;

    private String initialTable;
    private String initialTable2;
    private Transaction tx1, tx2, tx3, tx4;
    private String inputSchedule;
    private String submittedOrder;
    private TxnPairResult execRes;
    private TxnPairResult inferredRes;

    public String toString() {
        StringBuilder sb = new StringBuilder("=============================");
        sb.append("BUG REPORT\n")
                .append(" -- Create Table 1 SQL: ").append(createTableSQL).append("\n")
                // 【修改】打印 Table 2 的建表语句
                .append(" -- Create Table 2 SQL: ").append(createTableSQL2).append("\n")
                .append(" -- Initialize Statements 1:").append("\n");

        if (initializeStatements != null) {
            for (String stmt : initializeStatements) {
                sb.append("\t").append(stmt).append(";\n");
            }
        }

        // 【新增】打印 Table 2 的初始化语句
        sb.append(" -- Initialize Statements 2:").append("\n");
        if (initializeStatements2 != null) {
            for (String stmt : initializeStatements2) {
                sb.append("\t").append(stmt).append(";\n");
            }
        }

        sb.append(" -- Initial Table 1 View: \n").append(initialTable).append("\n");
        sb.append(" -- Initial Table 2 View: \n").append(initialTable2).append("\n");
        sb.append(" -- TSource transactions: ").append("\n");
        sb.append(" -- Tx1: ").append(tx1).append("\n");
        sb.append(" -- Tx2: ").append(tx2).append("\n");
        sb.append(" -- Follow-up transactios: ").append("\n");
        sb.append(" -- Tx3: ").append(tx3).append("\n");
        sb.append(" -- Tx4: ").append(tx4).append("\n");
        sb.append(" -- Input Schedule: ").append(inputSchedule).append("\n");
        sb.append(" -- Submitted Order: ").append(submittedOrder).append("\n");
        sb.append(" -- Source Execution Result: ").append(execRes).append("\n");
        sb.append(" -- Follow-up Result: ").append(inferredRes).append("\n");
        return sb.toString();
    }
}