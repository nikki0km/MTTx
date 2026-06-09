package test;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class MtChecker4 {

    protected Transaction tx1;
    protected Transaction tx2;
    protected Transaction tx3;
    protected Transaction tx4;
    private String bugInfo; // 用于存储错误信息的字符串
    private HashMap<Integer, ArrayList<Version>> vData; // 用于存储版本数据
    private boolean isDeadlock; // 表示是否检测到死锁

    public MtChecker4(Transaction tx1, Transaction tx2, Transaction tx3, Transaction tx4) {
        this.tx1 = tx1;
        this.tx2 = tx2;
        this.tx3 = tx3;
        this.tx4 = tx4;
    }

    private static final Set<String> COLUMNS_OF_INTEREST = new HashSet<>(
            Arrays.asList("x", "c0", "c1", "c2", "c3", "c4", "c5", "c6"));

    public void checkRandom() { // 调用 checkRandom(int count) 方法，默认使用 TableTool.CheckSize 作为参数。
        checkRandom(TableTool.CheckSize);
    }

    public void checkRandom(int count) { // 整数 count，表示随机检查的次数
        ArrayList<ArrayList<StatementCell>> submittedOrderList = ShuffleTool.sampleSubmittedTrace(tx1, tx2, count); // 调用ShuffleTool.sampleSubmittedTrace方法，生成count个随机的调度顺序，存储在submittedOrderList中。
        for (ArrayList<StatementCell> submittedOrder : submittedOrderList) { // 遍历submittedOrderList中的每个调度顺序submittedOrder
            try {
                Thread.sleep(1000); // 每次检查前暂停 1 秒（1000 毫秒），避免过快执行
            } catch (InterruptedException e) {
                log.error("Thread interrupted", e);
            } catch (Exception e) {
                log.error("Error in oracleCheck", e);
            }
            oracleCheck(submittedOrder); // 调用 oracleCheck 方法，检查当前调度顺序 submittedOrder。
        }
    }

    private boolean oracleCheck(ArrayList<StatementCell> schedule) {
        TableTool.allCase++;
        // 从调度中提取WHERE条件列名
        Set<String> whereColumns = extractWhereColumnsFromSchedule(schedule);
        log.info("Extracted WHERE columns from schedule: {}", whereColumns);
        whereColumns.add("rid");

        TableTool.recoverOriginalTable();
        View view1_Init = TableTool.tableToView();
        bugInfo = "";

        log.info("Executing schedule for tx1 and tx2");
        TxnPairExecutor executor1 = new TxnPairExecutor(scheduleClone(schedule), tx1, tx2);
        TxnPairResult execResult1 = executor1.getResult();
        View view1_Final = TableTool.tableToView();
        log.info("Executed schedule for tx1 and tx2: result={}", execResult1);

        try {
            TableTool.recoverOriginalTable2(whereColumns);
        } catch (Exception e) {
            log.warn("Failed to recover original table (clone failed), skipping this test case: {}", e.getMessage());
            TableTool.skipCase++;
            return true;
        }
        View view2_Init = TableTool.tableToView();
        TableTool.bugReport2.setInitialTable2(view2_Init.toString());
        bugInfo = "";
        log.info("Generating schedule for tx3 and tx4");
        ArrayList<StatementCell> scheduleForTx3Tx4 = generateScheduleForTx3Tx4(schedule);
        TxnPairExecutor executor2 = new TxnPairExecutor(scheduleForTx3Tx4, tx3, tx4);
        TxnPairResult execResult2 = executor2.getResult();
        View view2_Final = TableTool.tableToView();
        log.info("Executed schedule for tx3 and tx4: result={}", execResult2);
        log.info("Comparing oracles");
        if (compareOracles(execResult1, execResult2, view1_Init, view1_Final, view2_Init, view2_Final)) { // 没问题
            log.info("----------------");
            log.info("Schedule: " + schedule);
            log.info("Input schedule: " + getScheduleInputStr(schedule));
            log.info("Get tx1 result: " + execResult1);
            log.info("Get tx3 result: " + execResult2);
            log.info("-----------------------------------------------------------------------------");
            return true;
        } else {
            log.info("Oracle check passed");
            TableTool.bugReport2.setInputSchedule(getScheduleInputStr(schedule));
            TableTool.bugReport2.setSubmittedOrder(schedule.toString());
            TableTool.bugReport2.setExecRes(execResult1);
            TableTool.bugReport2.setInferredRes(execResult2);
            log.info(TableTool.bugReport2.toString());
            return false;
        }
    }

    /**
     * 根据事务1和事务2的调度顺序生成事务3和事务4的调度顺序
     */
    private ArrayList<StatementCell> generateScheduleForTx3Tx4(ArrayList<StatementCell> schedule) {
        ArrayList<StatementCell> scheduleForTx3Tx4 = new ArrayList<>();

        // 定义事务3和事务4的语句
        ArrayList<StatementCell> tx3Statements = tx3.statements;
        ArrayList<StatementCell> tx4Statements = tx4.statements;

        // 遍历事务1和事务2的调度顺序，生成事务3和事务4的调度顺序
        for (StatementCell stmt : schedule) {
            StatementCell newStmt = stmt.copy(); // 复制当前语句
            if (stmt.tx.txId == 1) {
                // 检查 tx3Statements 的长度是否足够
                if (tx3Statements.size() > stmt.statementId) {
                    newStmt.tx = tx3;
                    newStmt.statement = tx3Statements.get(stmt.statementId).statement;
                    newStmt.type = tx3Statements.get(stmt.statementId).type;
                } else {
                    System.out.println("Error: tx3Statements size is " + tx3Statements.size() + ", but index "
                            + stmt.statementId + " is required.");
                    // 处理越界情况，例如跳过或抛出异常
                    continue;
                }
            } else if (stmt.tx.txId == 2) {
                // 检查 tx4Statements 的长度是否足够
                if (tx4Statements.size() > stmt.statementId) {
                    newStmt.tx = tx4;
                    newStmt.statement = tx4Statements.get(stmt.statementId).statement;
                    newStmt.type = tx4Statements.get(stmt.statementId).type;
                } else {
                    System.out.println("Error: tx4Statements size is " + tx4Statements.size() + ", but index "
                            + stmt.statementId + " is required.");
                    // 处理越界情况，例如跳过或抛出异常
                    continue;
                }
            }

            scheduleForTx3Tx4.add(newStmt);
        }
        return scheduleForTx3Tx4;
    }

    private Set<String> extractWhereColumnsFromSchedule(ArrayList<StatementCell> schedule) {
        Set<String> allColumns = new HashSet<>();
        if (schedule == null) {
            return allColumns;
        }
        for (StatementCell stmt : schedule) {
            if (stmt.statement != null) {
                Set<String> columns = extractWhereColumns(stmt.statement);
                allColumns.addAll(columns);
            }
        }
        return allColumns;
    }

    private Set<String> extractWhereColumns(String sql) {
        Set<String> columns = new HashSet<>();
        if (sql == null || sql.trim().isEmpty()) {
            return columns;
        }
        // 将SQL转换为统一大小写方便处理
        String upperSql = sql.toUpperCase();

        // 查找WHERE关键字
        int whereIndex = upperSql.indexOf(" WHERE ");
        if (whereIndex == -1) {
            return columns;
        }

        // 提取WHERE子句
        String whereClause = sql.substring(whereIndex);

        // 简单检查每个我们关心的列名是否出现在WHERE条件中
        for (String column : COLUMNS_OF_INTEREST) {
            // 构建正则表达式，匹配列名作为独立的单词
            // 使用不区分大小写的匹配
            Pattern pattern = Pattern.compile(
                    "\\b" + Pattern.quote(column) + "\\b",
                    Pattern.CASE_INSENSITIVE);

            Matcher matcher = pattern.matcher(whereClause);
            if (matcher.find()) {
                columns.add(column);
            }
        }
        return columns;
    }

    private String getScheduleInputStr(ArrayList<StatementCell> schedule) { // 将事务调度（schedule）转换为字符串表示
        ArrayList<String> order = new ArrayList<>();
        for (StatementCell stmt : schedule) { // 遍历调度中的每个语句，提取事务 ID 并添加到 order 列表中
            order.add(Integer.toString(stmt.tx.txId));
        }
        return String.join("-", order); // 将 order 列表中的事务 ID 用 - 连接成一个字符串
    }

    private ArrayList<StatementCell> scheduleClone(ArrayList<StatementCell> schedule) { // 克隆事务调度, schedule：事务调度列表，包含多个
                                                                                        // StatementCell 对象
        ArrayList<StatementCell> copied = new ArrayList<>(); // 创建一个空的 ArrayList，用于存储克隆后的语句
        for (StatementCell stmt : schedule) {
            copied.add(stmt.copy());
        }
        return copied; // 返回克隆后的调度列表
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
        ArrayList<ArrayList<StatementCell>> submittedOrderList = ShuffleTool.genAllSubmittedTrace(tx1, tx2); // 调用ShuffleTool.genAllSubmittedTrace方法，生成所有可能的调度顺序，存储在submittedOrderList中。
        for (ArrayList<StatementCell> submittedOrder : submittedOrderList) { // 遍历submittedOrderList中的每个调度顺序submittedOrder。
            oracleCheck(submittedOrder); // 调用 oracleCheck 方法进行检查
        }
    }

    private boolean compareOracles(TxnPairResult execRes, TxnPairResult oracleRes,
            View v1Init, View v1Final, View v2Init, View v2Final) {

        // 1. 基本的死锁/阻塞/Abort 检查逻辑保持不变 (结构一致性)
        log.info("txp: {}, all case: {}, skip: {}", TableTool.txPair, TableTool.allCase, TableTool.skipCase);

        // 两个都死锁，忽略
        if ((execRes.isDeadBlock() && oracleRes.isDeadBlock())) {
            TableTool.skipCase++;
            return true;
        }

        // 死锁状态不一致，忽略 因为并发死锁本身就具有不确定性。
        if (execRes.isDeadBlock() || oracleRes.isDeadBlock()) {
            TableTool.skipCase++;
            return true;
        }

        ArrayList<StatementCell> execOrder = execRes.getOrder();
        ArrayList<StatementCell> oracleOrder = oracleRes.getOrder();
        int minLen = Math.min(execOrder.size(), oracleOrder.size());

        for (int i = 0; i < minLen; i++) {
            StatementCell oStmt = oracleOrder.get(i);
            StatementCell eStmt = execOrder.get(i);

            // Abort 一致性检查
            if (oStmt.aborted && eStmt.aborted)
                continue;
            if (oStmt.aborted || eStmt.aborted) {
                // 一个 abort 一个没 abort，通常视为不一致
                return false;
            }

            // Block 一致性检查
            if (oStmt.blocked && eStmt.blocked)
                continue;
            if (oStmt.blocked || eStmt.blocked) {
                TableTool.skipCase++;
                return false;
            }
        }

        // 2. 如果都没有死锁，进行核心的数据“变化一致性”检查
        if (!execRes.isDeadBlock() && !oracleRes.isDeadBlock()) {
            return checkMetamorphicRelation(v1Init, v1Final, v2Init, v2Final);
        }

        return true;
    }

    /**
     * 核心逻辑：验证 Table1 的变化行为是否与 Table2 的变化行为一致。
     * 规则：对于同一行数据（RowID），如果 T1 变了，T2 也必须变；如果 T1 没变，T2 也不能变。
     */
    private boolean checkMetamorphicRelation(View v1Init, View v1Final, View v2Init, View v2Final) {
        // 获取所有涉及到的 Row ID（并集）
        Set<Integer> allIds = new HashSet<>();
        allIds.addAll(v1Init.data.keySet());
        allIds.addAll(v1Final.data.keySet());
        // 通常 Table1 和 Table2 的 ID 集合是一样的，但为了严谨把所有的都加进来
        allIds.addAll(v2Init.data.keySet());
        allIds.addAll(v2Final.data.keySet());

        for (Integer id : allIds) {
            // 1. 检查 Table 1 中该行是否发生变化
            boolean changedInTable1 = hasRowChanged(id, v1Init, v1Final);

            // 2. 检查 Table 2 中该行是否发生变化
            boolean changedInTable2 = hasRowChanged(id, v2Init, v2Final);

            // 3. 验证蜕变关系：变化状态必须相同
            // 即：(变了 AND 变了) OR (没变 AND 没变) 是正确的
            // (变了 AND 没变) OR (没变 AND 变了) 是错误的
            if (changedInTable1 != changedInTable2) {
                bugInfo += String.format(
                        " -- Metamorphic Failure at Row ID %d: Table1 Changed? %b, Table2 Changed? %b\n",
                        id, changedInTable1, changedInTable2);

                // 记录详细的差异日志帮助调试
                log.info("Mismatch at RowID {}:", id);
                log.info("  T1 Init : {}", Arrays.toString(v1Init.data.get(id)));
                log.info("  T1 Final: {}", Arrays.toString(v1Final.data.get(id)));
                log.info("  T2 Init : {}", Arrays.toString(v2Init.data.get(id)));
                log.info("  T2 Final: {}", Arrays.toString(v2Final.data.get(id)));

                return false; // 发现不一致
            }
        }
        return true; // 所有行都符合蜕变关系
    }

    /**
     * 判断某一行在初始视图和结束视图之间是否发生了变化（内容改变、新增或删除）
     */
    private boolean hasRowChanged(Integer id, View vInit, View vFinal) {
        Object[] rowInit = vInit.data.get(id);
        Object[] rowFinal = vFinal.data.get(id);

        // Case A: 之前没有，现在也没有 -> 没变 (这种情况通常不会在 keySet 循环里出现，但也处理一下)
        if (rowInit == null && rowFinal == null)
            return false;

        // Case B: 之前有，现在没了 (被删除) -> 变了
        if (rowInit != null && rowFinal == null)
            return true;

        // Case C: 之前没有，现在有了 (被插入) -> 变了
        if (rowInit == null && rowFinal != null)
            return true;

        // Case D: 前后都有 -> 比较内容
        // 使用 Arrays.deepEquals 来比较 Object 数组的内容
        return !Arrays.deepEquals(rowInit, rowFinal);
    }

}
