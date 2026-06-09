package test;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class MtChecker3 {
    protected Transaction tx1;
    protected Transaction tx2;
    protected Transaction tx3;
    protected Transaction tx4;
    private String bugInfo; // 用于存储错误信息的字符串
    private HashMap<Integer, ArrayList<Version>> vData; // 用于存储版本数据
    private boolean isDeadlock; // 表示是否检测到死锁

    public MtChecker3(Transaction tx1, Transaction tx2, Transaction tx3, Transaction tx4) {
        this.tx1 = tx1;
        this.tx2 = tx2;
        this.tx3 = tx3;
        this.tx4 = tx4;
    }

    public void checkRandom() { // 调用 checkRandom(int count) 方法，默认使用 TableTool.CheckSize 作为参数。
        checkRandom(TableTool.CheckSize);
    }

    public void checkRandom(int count) { // 整数 count，表示随机检查的次数
        ArrayList<ArrayList<StatementCell>> submittedOrderList = ShuffleTool.sampleSubmittedTrace(tx1, tx2, count);
        for (ArrayList<StatementCell> submittedOrder : submittedOrderList) { // 遍历 submittedOrderList 中的每个调度顺序
                                                                             // submittedOrder
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
        log.info("Starting oracleCheck");
        TableTool.allCase++;
        log.info("Recovering original table");
        TableTool.recoverOriginalTable();
        resetAutoIncrement();
        bugInfo = "";
        log.info("Executing schedule for tx1 and tx2");
        TxnPairExecutor3 executor1 = new TxnPairExecutor3(scheduleClone(schedule), tx1, tx2);
        TxnPairResult execResult1 = executor1.getResult();
        log.info("Executed schedule for tx1 and tx2: result={}", execResult1);

        // 关键修复：强制清理 tx1 和 tx2 的连接，确保没有残留事务
        forceCleanupConnections(tx1, tx2);

        TableTool.recoverOriginalTable();
        resetAutoIncrement();
        bugInfo = "";
        log.info("Generating schedule for tx3 and tx4");
        ArrayList<StatementCell> scheduleForTx3Tx4 = generateScheduleForTx3Tx4(execResult1);
        log.info("Executing schedule for tx3 and tx4");
        TxnPairExecutor3 executor2 = new TxnPairExecutor3(scheduleForTx3Tx4, tx3, tx4);
        TxnPairResult execResult2 = executor2.getResult();
        log.info("Executed schedule for tx3 and tx4: result={}", execResult2);

        // 清理 tx3 和 tx4 的连接
        forceCleanupConnections(tx3, tx4);

        log.info("Comparing oracles");

        log.info("----------------");
        log.info("Schedule: " + schedule);
        log.info("Input schedule: " + getScheduleInputStr(schedule));
        log.info("Get tx1 result: " + execResult1);
        log.info("Get tx3 result: " + execResult2);

        if (compareOracles(execResult1, execResult2)) {
            log.info("----------------");
            log.info("Schedule: " + schedule);
            log.info("Input schedule: " + getScheduleInputStr(schedule));
            log.info("Get tx1 result: " + execResult1);
            log.info("Get tx3 result: " + execResult2);
            log.info("-----------------------------------------------------------------------------");
            return true;
        } else {
            TableTool.bugReport.setInputSchedule(getScheduleInputStr(schedule));
            TableTool.bugReport.setSubmittedOrder(schedule.toString());
            TableTool.bugReport.setExecRes(execResult1);
            TableTool.bugReport.setInferredRes(execResult2);
            log.info(TableTool.bugReport.toString());
            // System.exit(1);
            return false;
        }
    }

    // /**
    // * 根据 tx1/tx2 的实际执行结果 (result) 生成 tx3/tx4 的线性化调度顺序
    // */
    // private ArrayList<StatementCell> generateScheduleForTx3Tx4(TxnPairResult
    // execResult) {
    // ArrayList<StatementCell> scheduleForTx3Tx4 = new ArrayList<>();

    // // 获取 tx1 和 tx2 的实际执行轨迹
    // ArrayList<StatementCell> actualHistory = execResult.getOrder();

    // // 引用 tx3 和 tx4 的原始语句定义
    // ArrayList<StatementCell> tx3Statements = tx3.statements;
    // ArrayList<StatementCell> tx4Statements = tx4.statements;

    // // 2. 遍历实际执行轨迹，重构线性化顺序
    // for (StatementCell historyStmt : actualHistory) {
    // // 关键：跳过被阻塞的记录，只关心实际执行成功的时刻
    // if (historyStmt.blocked) {
    // continue;
    // }
    // // 如果事务中止了（非死锁情况下的 abort），通常也跳过，或者根据需求处理
    // if (historyStmt.aborted) {
    // continue;
    // }

    // StatementCell newStmt = null;
    // int stmtId = historyStmt.statementId;

    // // 映射 tx1 -> tx3
    // if (historyStmt.tx.txId == 1) {
    // if (stmtId < tx3Statements.size()) {
    // newStmt = tx3Statements.get(stmtId).copy();
    // newStmt.tx = tx3; // 修正归属
    // } else {
    // log.error("Index out of bound for tx3: {}", stmtId);
    // }
    // }
    // // 映射 tx2 -> tx4
    // else if (historyStmt.tx.txId == 2) {
    // if (stmtId < tx4Statements.size()) {
    // newStmt = tx4Statements.get(stmtId).copy();
    // newStmt.tx = tx4; // 修正归属
    // } else {
    // log.error("Index out of bound for tx4: {}", stmtId);
    // }
    // }

    // if (newStmt != null) {
    // scheduleForTx3Tx4.add(newStmt);
    // }
    // }

    // // 打印调试日志
    // // log.info("Re-ordered schedule for Tx3/Tx4: " +
    // // getScheduleInputStr(scheduleForTx3Tx4));
    // return scheduleForTx3Tx4;
    // }

    private void resetAutoIncrement() {
        try {
            // 查询当前表是否有 AUTO_INCREMENT 列
            ArrayList<Object> colResult = TableTool.getQueryResultAsList(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()" +
                            " AND TABLE_NAME = '" + TableTool.TableName
                            + "' AND EXTRA LIKE '%auto_increment%' LIMIT 1");
            if (colResult == null || colResult.isEmpty() || colResult.get(0) == null) {
                return; // 没有 AUTO_INCREMENT 列，跳过
            }
            String autoCol = colResult.get(0).toString();
            // 查询当前最大值，重置为 max+1
            ArrayList<Object> maxResult = TableTool.getQueryResultAsList(
                    "SELECT MAX(`" + autoCol + "`) FROM " + TableTool.TableName);
            long maxVal = 1;
            if (maxResult != null && !maxResult.isEmpty() && maxResult.get(0) != null) {
                maxVal = Long.parseLong(maxResult.get(0).toString()) + 1;
            }
            TableTool.executeOnTable(
                    "ALTER TABLE " + TableTool.TableName + " AUTO_INCREMENT = " + maxVal);
            log.debug("Reset AUTO_INCREMENT to {}", maxVal);
        } catch (Exception e) {
            log.debug("resetAutoIncrement skipped: {}", e.getMessage());
        }
    }

    private void forceCleanupConnections(Transaction tx1, Transaction tx2) {
        try {
            if (tx1 != null && tx1.conn != null) {
                try {
                    // 强制回滚任何未完成的事务
                    tx1.conn.createStatement().executeUpdate("ROLLBACK");
                    log.debug("Rolled back tx1 connection");
                } catch (SQLException e) {
                    log.debug("Rollback tx1 failed (may already be committed): {}", e.getMessage());
                }
            }

            if (tx2 != null && tx2.conn != null) {
                try {
                    // 强制回滚任何未完成的事务
                    tx2.conn.createStatement().executeUpdate("ROLLBACK");
                    log.debug("Rolled back tx2 connection");
                } catch (SQLException e) {
                    log.debug("Rollback tx2 failed (may already be committed): {}", e.getMessage());
                }
            }

            // 给数据库一点时间释放锁
            Thread.sleep(200);

        } catch (InterruptedException e) {
            log.warn("Interrupted during connection cleanup");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Error during connection cleanup: {}", e.getMessage());
        }
    }

    private ArrayList<StatementCell> generateScheduleForTx3Tx4(TxnPairResult execResult) {
        ArrayList<StatementCell> scheduleForTx3Tx4 = new ArrayList<>();

        if (execResult == null || execResult.getOrder() == null) {
            log.warn("execResult or its order is null, returning empty schedule");
            return scheduleForTx3Tx4;
        }

        // 获取实际执行轨迹
        ArrayList<StatementCell> actualHistory = execResult.getOrder();

        // 引用 tx3 和 tx4 的原始语句定义
        ArrayList<StatementCell> tx3Statements = tx3 != null ? tx3.statements : new ArrayList<>();
        ArrayList<StatementCell> tx4Statements = tx4 != null ? tx4.statements : new ArrayList<>();

        // 遍历实际执行轨迹，重构线性化顺序
        for (StatementCell historyStmt : actualHistory) {
            if (historyStmt == null || historyStmt.tx == null)
                continue;

            if (historyStmt.blocked) {
                continue;
            }

            if (historyStmt.aborted) {
                continue;
            }

            StatementCell newStmt = null;
            int stmtId = historyStmt.statementId;

            if (historyStmt.tx.txId == 1) {
                if (stmtId < tx3Statements.size() && tx3Statements.get(stmtId) != null) {
                    newStmt = tx3Statements.get(stmtId).copy();
                    if (newStmt != null) {
                        newStmt.tx = tx3;
                    }
                } else {
                    log.debug("Index out of bound for tx3: {}", stmtId);
                }
            }
            // 映射 tx2 -> tx4
            else if (historyStmt.tx.txId == 2) {
                if (stmtId < tx4Statements.size() && tx4Statements.get(stmtId) != null) {
                    newStmt = tx4Statements.get(stmtId).copy();
                    if (newStmt != null) {
                        newStmt.tx = tx4;
                    }
                } else {
                    log.debug("Index out of bound for tx4: {}", stmtId);
                }
            }

            if (newStmt != null) {
                scheduleForTx3Tx4.add(newStmt);
            }
        }

        return scheduleForTx3Tx4;
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

    private boolean compareOracles(TxnPairResult execRes, TxnPairResult oracleRes) {
        log.info("txp: {}, all case: {}, skip: {}", TableTool.txPair, TableTool.allCase, TableTool.skipCase);

        // 空值检查
        if (execRes == null && oracleRes == null)
            return true;
        if (execRes == null || oracleRes == null) {
            log.info("One result is null");
            return false;
        }

        // Tx1/Tx2 死锁，Tx3/Tx4 也死锁了，忽略
        if (execRes.isDeadBlock() && oracleRes.isDeadBlock()) {
            return true;
        }

        // 场景 1: Tx1/Tx2 发生死锁
        if (execRes.isDeadBlock()) {
            log.info("Execution (Tx1/Tx2) hit Deadlock.");

            // 比较最终数据库状态
            ArrayList<Object> execFinal = execRes.getFinalState();
            ArrayList<Object> oracleFinal = oracleRes.getFinalState();

            if (execFinal == null && oracleFinal == null)
                return true;
            if (execFinal == null || oracleFinal == null) {
                bugInfo += " -- Error: Deadlock occurred but final state is null";
                return false;
            }

            if (compareResultSets(execFinal, oracleFinal)) {
                return true;
            } else {
                bugInfo += " -- Error: Deadlock occurred but final state does not match";
                return false;
            }
        }

        // 场景 2: Tx1/Tx2 没死锁，但 Tx3/Tx4 死锁了
        if (!execRes.isDeadBlock() && oracleRes.isDeadBlock()) {
            log.info("Error: Oracle deadlocked but Execution did not.");
            bugInfo += " -- Error: Replay deadlocked while original execution succeeded";
            return false;
        }

        // 场景 3: 正常执行比较
        ArrayList<StatementCell> execOrder = execRes.getOrder();
        ArrayList<StatementCell> oracleOrder = oracleRes.getOrder();

        if (execOrder == null || oracleOrder == null) {
            log.info("Order list is null");
            return false;
        }

        int execIdx = 0;
        int oracleIdx = 0;

        while (oracleIdx < oracleOrder.size()) {
            if (execIdx >= execOrder.size()) {
                bugInfo += " -- Error: Execution trace shorter than Oracle trace";
                return false;
            }

            StatementCell eStmt = execOrder.get(execIdx);
            StatementCell oStmt = oracleOrder.get(oracleIdx);

            if (eStmt == null || oStmt == null) {
                execIdx++;
                oracleIdx++;
                continue;
            }

            if (eStmt.blocked) {
                execIdx++;
                continue;
            }

            // 不比较中间状态了，因为可能会有误报，select解除阻塞后游标也是从挂起的地方往后接着读，可能没办法读到另个事务更新的数据。幻读的一种微观表现形式，具体你可以看（mt13误报第81个bug）。
            // if (oStmt.type == StatementType.SELECT || oStmt.type ==
            // StatementType.SELECT_SHARE
            // || oStmt.type == StatementType.SELECT_UPDATE) {
            // if (!compareResultSets(oStmt.result, eStmt.result)) {
            // bugInfo += " -- Error: Query result mismatch at " + oStmt.statementId;
            // log.info("Query Mismatch: Tx/Statement: {}-{}",
            // oStmt.tx != null ? oStmt.tx.txId : "?", oStmt.statementId);
            // log.info("Exec Result: " + (eStmt.result != null ? eStmt.result : "null"));
            // log.info("Oracle Result: " + (oStmt.result != null ? oStmt.result : "null"));
            // return false;
            // }
            // }

            execIdx++;
            oracleIdx++;
        }

        // 场景 4: 最终状态比较
        ArrayList<Object> execFinal = execRes.getFinalState();
        ArrayList<Object> oracleFinal = oracleRes.getFinalState();

        if (execFinal == null && oracleFinal == null)
            return true;
        if (execFinal == null || oracleFinal == null) {
            log.info("Error: Inconsistent final database state (one is null)");
            bugInfo += " -- Error: Inconsistent final database state (one is null)";
            return false;
        }

        if (compareResultSets(execFinal, oracleFinal)) {
            return true;
        } else {
            // 检查是否存在被阻塞的 UPDATE（可能触发游标位置保留误报）
            boolean hasBlockedUpdate = execOrder.stream()
                    .anyMatch(s -> s != null && s.blocked && s.type == StatementType.UPDATE);
            log.info("Error: Inconsistent final database state");
            bugInfo += " -- Error: Inconsistent final database state";
            if (hasBlockedUpdate) {
                log.warn("[可能误报] 存在被阻塞的 UPDATE，最终状态不一致可能由 MySQL 游标位置保留行为导致（非隔离级别Bug）");
                bugInfo += " [WARNING: possible false positive due to MySQL UPDATE cursor-position-preservation]";
            }
            return false;
        }
    }

    private boolean shouldNotBlock(StatementCell stmt) { // 判断当前操作是否 不应该被阻塞。
        return false;
    }

    private boolean shouldNotAbort(StatementCell stmt) { // 判断当前操作是否 不应该被中止。
        return false;
    }

    private boolean compareResultSets(ArrayList<Object> resultSet1, ArrayList<Object> resultSet2) {
        if (resultSet1 == null && resultSet2 == null) {
            return true;
        } else if (resultSet1 == null || resultSet2 == null) {
            bugInfo += " -- One result is NULL\n";
            return false;
        }

        if (resultSet1.size() != resultSet2.size()) {
            bugInfo += " -- Number Of Data Different: " + resultSet1.size() + " vs " + resultSet2.size() + "\n";
            return false;
        }

        List<String> rs1 = preprocessResultSet(resultSet1);
        List<String> rs2 = preprocessResultSet(resultSet2);

        for (int i = 0; i < rs1.size(); i++) {
            String result1 = rs1.get(i);
            String result2 = rs2.get(i);

            if (result1 == null && result2 == null) {
                continue;
            }
            if (result1 == null || result2 == null) {
                return false;
            }
            if (!result1.equals(result2)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> preprocessResultSet(ArrayList<Object> resultSet) { // 预处理结果集，将其转换为字符串列表并排序。
        return resultSet.stream().map(o -> {
            if (o == null) {
                return "[NULL]";
            } else {
                return o.toString();
            }
        }).sorted().collect(Collectors.toList());
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
