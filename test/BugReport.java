package test;

import lombok.Data;
import java.util.List;

@Data
public class BugReport {
    private boolean bugFound = false; // 表示是否发现 bug

    private String createTableSQL; // 用于存储创建表的 SQL 语句
    private List<String> initializeStatements; // 用于存储初始化 SQL 语句
    private String initialTable; // 用于存储初始表的内容
    private Transaction tx1, tx2, tx3, tx4; // 用于存储两个事务的信息
    private String inputSchedule; // 用于存储输入的调度信息
    private String submittedOrder; // 用于存储提交的顺序
    private TxnPairResult execRes; // 用于存储执行结果
    private TxnPairResult inferredRes; // 用于存储推断结果

    public String toString() { // 重写 toString 方法，用于生成 BugReport 的字符串表示
        StringBuilder sb = new StringBuilder("============================="); // 创建一个 StringBuilder 对象，用于拼接字符串
        sb.append("BUG REPORT\n")
                .append(" -- Create Table SQL: ").append(createTableSQL).append("\n") // 添加创建表的 SQL 语句
                .append(" -- InitializeStatements:").append("\n"); // 添加初始化 SQL 语句的标题
        for (String stmt : initializeStatements) { // 遍历 initializeStatements 列表
            sb.append("\t").append(stmt).append(";\n"); // 将每条初始化 SQL 语句添加到字符串中
        }
        sb.append(" -- Initial Table: \n").append(initialTable).append("\n"); // 添加初始表的内容
        sb.append(" -- TSource transactions: ").append("\n"); // 源测试用例
        sb.append(" -- Tx1: ").append(tx1).append("\n"); // 添加事务 tx1 的信息
        sb.append(" -- Tx2: ").append(tx2).append("\n"); // 添加事务 tx2 的信息
        sb.append(" -- Follow-up transactios: ").append("\n"); // 衍生测试用例
        sb.append(" -- Tx3: ").append(tx3).append("\n"); // 添加事务 tx3 的信息
        sb.append(" -- Tx4: ").append(tx4).append("\n"); // 添加事务 tx4 的信息
        sb.append(" -- Input Schedule: ").append(inputSchedule).append("\n"); // 添加输入的调度信息
        sb.append(" -- Submitted Order: ").append(submittedOrder).append("\n"); // 添加提交的顺序
        sb.append(" -- Source Execution Result: ").append(execRes).append("\n"); // 添加源测试用例执行结果
        sb.append(" -- Follow-up Result: ").append(inferredRes).append("\n"); // 添加源测试用例执行结果
        return sb.toString(); // 返回拼接好的字符串
    }
}