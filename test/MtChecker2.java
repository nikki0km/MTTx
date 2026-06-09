package test;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class MtChecker2 {

    protected Transaction tx1;
    protected Transaction tx2;
    protected Transaction tx3;
    protected Transaction tx4;
    private String bugInfo; // 用于存储错误信息的字符串
    private HashMap<Integer, ArrayList<Version>> vData; // 用于存储版本数据
    private boolean isDeadlock; // 表示是否检测到死锁

    public MtChecker2(Transaction tx1, Transaction tx2, Transaction tx3, Transaction tx4) {
        this.tx1 = tx1;
        this.tx2 = tx2;
        this.tx3 = tx3;
        this.tx4 = tx4;
    }

    public void checkFix() {
        // 获取 tx1 的所有语句
        ArrayList<StatementCell> tx1Statements = new ArrayList<>(tx1.getStatements());

        // 确保 tx1 有足够的语句（BEGIN, GLOBAL_SELECT1, 其他操作, GLOBAL_SELECT2, COMMIT）
        if (tx1Statements.size() < 4) {
            throw new IllegalStateException(
                    "tx1 must have at least 4 statements (BEGIN, GLOBAL_SELECT1, ..., GLOBAL_SELECT2, COMMIT)");
        }

        // 找到第一个全局查询后的位置（假设第一个SELECT是全局查询）
        int firstGlobalSelectIndex = 1; // BEGIN是0，假设1是第一个全局查询

        // 拆分 tx1 的语句：
        // 第一部分：BEGIN + 第一个全局查询
        ArrayList<StatementCell> tx1Part1 = new ArrayList<>();
        tx1Part1.add(tx1Statements.get(0)); // BEGIN
        tx1Part1.add(tx1Statements.get(firstGlobalSelectIndex)); // 第一个全局查询

        // 第二部分：剩余语句
        ArrayList<StatementCell> tx1Part2 = new ArrayList<>();
        for (int i = firstGlobalSelectIndex + 1; i < tx1Statements.size(); i++) {
            tx1Part2.add(tx1Statements.get(i));
        }

        // 获取 tx2 的所有语句
        ArrayList<StatementCell> tx2Statements = tx2.getStatements();

        // 构造新的执行顺序
        ArrayList<StatementCell> fixedOrder = new ArrayList<>();
        fixedOrder.addAll(tx1Part1); // tx1 的 BEGIN + 第一个全局查询

        // 随机选择调度策略:
        int firstWriteIdx = findFirstWriteIndex(tx1Part2);
        boolean useStrategyB = firstWriteIdx >= 0 && Randomly.getBoolean();

        if (useStrategyB) {
            log.info("checkFix using Strategy B: tx1 first write before tx2");
            // tx1 的首个写语句先执行（在 tx2 之前）
            fixedOrder.add(tx1Part2.get(firstWriteIdx));
            // tx2 全部
            fixedOrder.addAll(tx2Statements);
            // tx1 剩余语句（跳过分出去的写语句）
            for (int i = 0; i < tx1Part2.size(); i++) {
                if (i != firstWriteIdx) {
                    fixedOrder.add(tx1Part2.get(i));
                }
            }
        } else {
            // 策略A : tx2 的全部在 tx1 剩余语句之前
            fixedOrder.addAll(tx2Statements); // tx2 的全部
            fixedOrder.addAll(tx1Part2); // tx1 的剩余语句
        }

        // 执行检查
        try {
            Thread.sleep(1000); // 保持原有延迟
            oracleCheck(fixedOrder);
        } catch (InterruptedException e) {
            log.error("Thread interrupted", e);
        } catch (Exception e) {
            log.error("Error in oracleCheck", e);
        }
    }

    /**
     * 判断一个语句是否为写操作（UPDATE / DELETE / INSERT）
     */
    private boolean isWriteStatement(StatementCell stmt) {
        return stmt.type == StatementType.UPDATE
                || stmt.type == StatementType.DELETE
                || stmt.type == StatementType.INSERT;
    }

    /**
     * 在语句列表中查找第一个写操作的索引，没有则返回 -1
     */
    private int findFirstWriteIndex(ArrayList<StatementCell> stmts) {
        for (int i = 0; i < stmts.size(); i++) {
            if (isWriteStatement(stmts.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean oracleCheck(ArrayList<StatementCell> schedule) {
        TableTool.allCase++;
        log.info("Recovering original table");
        TableTool.recoverOriginalTable();
        bugInfo = "";

        // 1. 执行第一个事务对
        TxnPairExecutor2 executor1 = new TxnPairExecutor2(scheduleClone(schedule), tx1, tx2);
        TxnPairResult execResult1 = executor1.getResult();

        // 如果 tx1 和 tx2 死锁，忽略此用例
        if (execResult1.isDeadBlock()) {
            TableTool.skipCase++;
            log.info("Deadlock detected for tx1/tx2, skipping this case");
            return true;
        }

        TableTool.recoverOriginalTable();
        bugInfo = "";

        // 2. 执行第二个事务对
        ArrayList<StatementCell> scheduleForTx3Tx4 = generateScheduleForTx3Tx4(schedule);
        TxnPairExecutor2 executor2 = new TxnPairExecutor2(scheduleForTx3Tx4, tx3, tx4);
        TxnPairResult execResult2 = executor2.getResult();

        log.info("Comparing oracles");

        // 3. 提取tx1和tx3的第二次全局查询结果
        Object tx1SecondGlobalSelect = findLastGlobalSelectResult(execResult1.getOrder());
        Object tx3SecondGlobalSelect = findLastGlobalSelectResult(execResult2.getOrder());

        // 4. 比较两个第二次全局查询结果
        boolean isConsistent = compareSelectResults(tx1SecondGlobalSelect, tx3SecondGlobalSelect);

        if (isConsistent) {
            log.info("----------------");
            log.info("tx1 second global select result: {}", tx1SecondGlobalSelect);
            log.info("tx3 second global select result: {}", tx3SecondGlobalSelect);
            log.info("Second global select results are consistent");
            log.info("-----------------------------------------------------------------------------");
            return true;
        } else {
            log.info("tx1 second global select result: {}", tx1SecondGlobalSelect);
            log.info("tx3 second global select result: {}", tx3SecondGlobalSelect);
            log.info("Second global select results are inconsistent");
            TableTool.bugReport.setInputSchedule(getScheduleInputStr(schedule));
            TableTool.bugReport.setSubmittedOrder(schedule.toString());
            TableTool.bugReport.setExecRes(execResult1);
            TableTool.bugReport.setInferredRes(execResult2);
            log.info(TableTool.bugReport.toString());
            return false;
        }
    }

    // 获取事务1（tx1）的最后一个SELECT语句的结果（即tx1的倒数第二个语句）
    private Object findLastGlobalSelectResult(List<StatementCell> statements) {
        if (statements.isEmpty()) {
            return null;
        }

        // 1. 先筛选出事务1的所有语句
        List<StatementCell> tx1Statements = statements.stream()
                .filter(stmt -> stmt != null && stmt.tx.txId == 1) // 只取 tx1 的语句
                .collect(Collectors.toList());

        if (tx1Statements.isEmpty()) {
            return null;
        }

        // 2. 从 tx1 的倒数第二个语句开始向前查找最后一个 SELECT
        for (int i = tx1Statements.size() - 2; i >= 0; i--) {
            StatementCell stmt = tx1Statements.get(i);
            if (stmt.type == StatementType.SELECT) {
                return stmt.result;
            }
        }

        return null;
    }

    // 安全比较
    private boolean compareSelectResults(Object result1, Object result2) {
        if (result1 == result2)
            return true;
        if (result1 == null || result2 == null)
            return false;

        if (result1 instanceof Collection && result2 instanceof Collection) {
            Collection<?> col1 = (Collection<?>) result1;
            Collection<?> col2 = (Collection<?>) result2;
            return col1.size() == col2.size() && col1.containsAll(col2);
        }

        return result1.equals(result2);
    }

    /**
     * 根据事务1和事务2的调度顺序生成事务3和事务4的调度顺序
     * tx3内容与tx1一致，tx4为null
     */
    private ArrayList<StatementCell> generateScheduleForTx3Tx4(ArrayList<StatementCell> schedule) {
        ArrayList<StatementCell> scheduleForTx3Tx4 = new ArrayList<>();

        // 定义事务3的语句（与tx1一致）
        ArrayList<StatementCell> tx3Statements = tx3.statements;

        // 遍历事务1和事务2的调度顺序，生成事务3的调度顺序（忽略tx4）
        for (StatementCell stmt : schedule) {
            StatementCell newStmt = stmt.copy(); // 复制当前语句

            // 只处理tx1的语句（txId == 1），忽略tx2 的语句（因为tx4为null）
            if (stmt.tx.txId == 1) {
                // 检查 tx3Statements 的长度是否足够
                if (tx3Statements.size() > stmt.statementId) {
                    newStmt.tx = tx3;
                    newStmt.statement = tx3Statements.get(stmt.statementId).statement;
                    newStmt.type = tx3Statements.get(stmt.statementId).type;
                    scheduleForTx3Tx4.add(newStmt);
                } else {
                    System.out.println("Error: tx3Statements size is " + tx3Statements.size() + ", but index "
                            + stmt.statementId + " is required.");
                    // 处理越界情况，例如跳过或抛出异常
                    continue;
                }
            }
            // 不处理tx2的语句（txId == 2），因为tx4为null
        }
        return scheduleForTx3Tx4;
    }

    private ArrayList<StatementCell> scheduleClone(ArrayList<StatementCell> schedule) { // 克隆事务调度, schedule：事务调度列表，包含多个
                                                                                        // StatementCell 对象
        ArrayList<StatementCell> copied = new ArrayList<>(); // 创建一个空的 ArrayList，用于存储克隆后的语句
        for (StatementCell stmt : schedule) {
            copied.add(stmt.copy());
        }
        return copied; // 返回克隆后的调度列表
    }

    private String getScheduleInputStr(ArrayList<StatementCell> schedule) { // 将事务调度（schedule）转换为字符串表示
        ArrayList<String> order = new ArrayList<>();
        for (StatementCell stmt : schedule) { // 遍历调度中的每个语句，提取事务 ID 并添加到 order 列表中
            order.add(Integer.toString(stmt.tx.txId));
        }
        return String.join("-", order); // 将 order 列表中的事务 ID 用 - 连接成一个字符串
    }

    public void checkSchedule(String scheduleStr) { // 接收一个表示调度顺序的字符串 scheduleStr。
        String[] schedule = scheduleStr.split("-"); // 将 scheduleStr 按 "-" 分割成字符串数组 schedule
        int len1 = tx1.statements.size(); // 获取 tx1 和 tx2 中语句的数量，分别存储在 len1 和 len2 中。
        int len2 = tx2.statements.size();
        if (schedule.length != len1 + len2) { // 检查 schedule 的长度是否等于 len1 + len2，如果不相等，抛出异常，表示调度无效
            throw new RuntimeException("Invalid Schedule");
        }
        ArrayList<StatementCell> submittedOrder = new ArrayList<>(); // 用于存储按调度顺序排列的语句
        int idx1 = 0, idx2 = 0; // 用于跟踪 tx1 和 tx2 中语句的索引
        for (String txId : schedule) { // 遍历 schedule 数组中的每个元素 txId
            if (txId.equals("1")) { // 如果 txId 是 "1"，则将 tx1 中的下一个语句添加到 submittedOrder 中，并递增 idx1
                submittedOrder.add(tx1.statements.get(idx1++));
            } else if (txId.equals("2")) { // 如果 txId 是 "2"，则将 tx2 中的下一个语句添加到 submittedOrder 中，并递增 idx2。
                submittedOrder.add(tx2.statements.get(idx2++));
            } else {
                throw new RuntimeException("Invalid Schedule");
            }
        }
        oracleCheck(submittedOrder);
    }

    public void checkAll() { // 用于检查所有可能的调度顺序。
        ArrayList<ArrayList<StatementCell>> submittedOrderList = ShuffleTool.genAllSubmittedTrace(tx1, tx2); // 调用
                                                                                                             // ShuffleTool.genAllSubmittedTrace
                                                                                                             // 方法，生成所有可能的调度顺序，存储在
                                                                                                             // submittedOrderList
                                                                                                             // 中。
        for (ArrayList<StatementCell> submittedOrder : submittedOrderList) { // 遍历 submittedOrderList 中的每个调度顺序
                                                                             // submittedOrder。
            oracleCheck(submittedOrder); // 调用 oracleCheck 方法进行检查
        }
    }

}
