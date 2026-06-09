package test;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class MtChecker1 {

    protected Transaction tx1;
    protected Transaction tx2;
    protected Transaction tx3;
    protected Transaction tx4;
    private String bugInfo; // 用于存储错误信息的字符串
    private HashMap<Integer, ArrayList<Version>> vData; // 用于存储版本数据
    private boolean isDeadlock; // 表示是否检测到死锁

    public MtChecker1(Transaction tx1, Transaction tx2, Transaction tx3, Transaction tx4) {
        this.tx1 = tx1;
        this.tx2 = tx2;
        this.tx3 = tx3;
        this.tx4 = tx4;
    }

    public void checkFixedOrder(String scheduleStr) { // 预设测试用例
        ArrayList<StatementCell> submittedOrder = new ArrayList<>();
        TableTool.allCase++;
        String[] parts = scheduleStr.trim().split("-");
        int tx1StmtIndex = 0, tx2StmtIndex = 0;

        for (String p : parts) {
            int txId;
            try {
                txId = Integer.parseInt(p.trim());
            } catch (NumberFormatException e) {
                log.error("Invalid schedule element: {}", p);
                return;
            }

            StatementCell stmt;
            if (txId == 1) {
                if (tx1StmtIndex >= tx1.statements.size()) {
                    log.warn("Transaction 1 statement exhausted at {}", tx1StmtIndex);
                    continue;
                }
                stmt = tx1.statements.get(tx1StmtIndex).copy();
                stmt.tx = tx1;
                stmt.statementId = tx1StmtIndex;
                tx1StmtIndex++;
            } else if (txId == 2) {
                if (tx2StmtIndex >= tx2.statements.size()) {
                    log.warn("Transaction 2 statement exhausted at {}", tx2StmtIndex);
                    continue;
                }
                stmt = tx2.statements.get(tx2StmtIndex).copy();
                stmt.tx = tx2;
                stmt.statementId = tx2StmtIndex;
                tx2StmtIndex++;
            } else {
                log.error("Unsupported tx id: {}", txId);
                return;
            }

            submittedOrder.add(stmt);
        }
        // 1. 执行tx1（直接顺序执行）
        TableTool.recoverOriginalTable();
        ArrayList<StatementCell> tx1Schedule = new ArrayList<>(tx1.statements);
        TxnPairExecutor executor1 = new TxnPairExecutor(tx1Schedule, tx1, tx2);
        TxnPairResult tx1Result = executor1.getResult();
        // 2. 执行tx3+tx4混合（tx4插入到指定位置）
        TxnPairExecutor executor2 = new TxnPairExecutor(submittedOrder, tx3, tx4);
        TxnPairResult mixedResult = executor2.getResult();
        // 3. 比较结果
        if (compareOracles(tx1Result, mixedResult)) { // 相同则正确
            log.info("----------------");
            log.info("Schedule: " + submittedOrder);
            log.info("Input schedule: " + getScheduleInputStr(submittedOrder));
            log.info("Get tx1 result: " + tx1Result);
            log.info("Get tx3 result: " + mixedResult);
            log.info("-----------------------------------------------------------------------------");
        } else {
            log.info("Oracle check passed");
            TableTool.bugReport.setInputSchedule(getScheduleInputStr(submittedOrder));
            TableTool.bugReport.setSubmittedOrder(submittedOrder.toString());
            TableTool.bugReport.setExecRes(tx1Result);
            TableTool.bugReport.setInferredRes(mixedResult);
            log.info(TableTool.bugReport.toString());
        }
    }

    public void checkAllInsertPositions() {
        // 遍历所有可能的插入位置（从0到tx3语句数）
        for (int insertPos = 0; insertPos <= tx3.statements.size(); insertPos++) {
            try {
                log.info("=== Testing tx4 inserted at position {} ===", insertPos);

                // 1. 执行tx1（直接顺序执行）
                TableTool.recoverOriginalTable();
                ArrayList<StatementCell> tx1Schedule = new ArrayList<>(tx1.statements);
                TxnPairExecutor executor1 = new TxnPairExecutor(tx1Schedule, tx1, tx2);
                TxnPairResult tx1Result = executor1.getResult();

                // 2. 执行tx3+tx4混合（tx4插入到指定位置）
                TableTool.recoverOriginalTable();
                ArrayList<StatementCell> mixedSchedule = generateMixedSchedule(tx3, tx4, insertPos);
                TxnPairExecutor executor2 = new TxnPairExecutor(mixedSchedule, tx3, tx4);
                TxnPairResult mixedResult = executor2.getResult();

                // 3. 比较结果
                if (compareOracles(tx1Result, mixedResult)) { // 相同则正确
                    log.info("----------------");
                    log.info("Schedule: " + mixedSchedule);
                    log.info("Input schedule: " + getScheduleInputStr(mixedSchedule));
                    log.info("Get tx1 result: " + tx1Result);
                    log.info("Get tx3 result: " + mixedResult);
                    log.info("-----------------------------------------------------------------------------");
                } else {
                    log.info("Oracle check passed");
                    TableTool.bugReport.setInputSchedule(getScheduleInputStr(mixedSchedule));
                    TableTool.bugReport.setSubmittedOrder(mixedSchedule.toString());
                    TableTool.bugReport.setExecRes(tx1Result);
                    TableTool.bugReport.setInferredRes(mixedResult);
                    log.info(TableTool.bugReport.toString());
                }
                Thread.sleep(1000); // 间隔1秒
            } catch (InterruptedException e) {
                log.error("Thread interrupted", e);
            }
        }
    }

    private ArrayList<StatementCell> generateMixedSchedule(Transaction tx3, Transaction tx4, int insertPos) {
        ArrayList<StatementCell> schedule = new ArrayList<>();

        // 1. 添加tx3的前insertPos个语句
        for (int i = 0; i < insertPos; i++) {
            schedule.add(tx3.statements.get(i).copy());
        }

        // 2. 插入tx4的全部语句
        for (StatementCell stmt : tx4.statements) {
            schedule.add(stmt.copy());
        }

        // 3. 添加tx3的剩余语句
        for (int i = insertPos; i < tx3.statements.size(); i++) {
            schedule.add(tx3.statements.get(i).copy());
        }

        return schedule;
    }

    private String getScheduleInputStr(ArrayList<StatementCell> schedule) { // 将事务调度（schedule）转换为字符串表示
        ArrayList<String> order = new ArrayList<>();
        for (StatementCell stmt : schedule) { // 遍历调度中的每个语句，提取事务 ID 并添加到 order 列表中
            order.add(Integer.toString(stmt.tx.txId));
        }
        return String.join("-", order);
    }

    private boolean compareOracles(TxnPairResult execRes, TxnPairResult oracleRes) { // 比较实际执行结果与预期结果，判断是否存在不一致。
                                                                                     // false代表不一致
        log.info("txp: {}, all case: {}, skip: {}", TableTool.txPair, TableTool.allCase, TableTool.skipCase);
        ArrayList<StatementCell> execOrder = execRes.getOrder();
        ArrayList<StatementCell> oracleOrder = oracleRes.getOrder();
        int minLen = Math.min(execOrder.size(), oracleOrder.size());
        if (execRes.isDeadBlock() || oracleRes.isDeadBlock()) { // 如果有死锁，则忽略。
            log.info("Ignore: Undecided");
            bugInfo += " -- Ignore: Undecided";
            TableTool.skipCase++;
            return true; // 忽略
        }
        // if ((execRes.isDeadBlock() && !oracleRes.isDeadBlock() )||(
        // !execRes.isDeadBlock() && oracleRes.isDeadBlock())) {
        // //如果测试用例1结果有死锁，而测试用例2没有死锁，或者反之
        // // log.info("Ignore: Undecided");
        // log.info("1");
        // // bugInfo += " -- Ignore: Undecided";
        // TableTool.skipCase++;
        // return false; //是正确，返回false（不一致）
        // }

        for (int i = 0; i < minLen; i++) { // 遍历操作顺序
            StatementCell oStmt = oracleOrder.get(i);
            StatementCell eStmt = execOrder.get(i);
            if (oStmt.blocked && !eStmt.blocked) { // 如果case2被阻塞而case1没有被阻塞，说明rollback事务被阻塞了，导致rollback不能及时执行完，这种情况忽略。
                return true;
            }
            if (oStmt.type == StatementType.SELECT && oStmt.equals(eStmt)) { // 对于 SELECT 操作，比较查询结果是否一致。
                if (compareResultSets(oStmt.result, eStmt.result)) {
                    // log.info("Error: Consistent query result");
                    // log.info("query: " + oStmt.statement);
                    // bugInfo += " -- Error: Consistent query result \n";
                    // bugInfo += " -- query: " + oStmt.statement;
                } else {
                    return false; // 查询结果是不一致的，是错误的，返回true（一致）
                }
            }
        }

        if (!execRes.isDeadBlock() && !oracleRes.isDeadBlock()) {
            if (!compareResultSets(execRes.getFinalState(), oracleRes.getFinalState())) { // 比较case1和case2的最终数据库状态。
                log.info("Error: Consistent final database state");
                bugInfo += " -- Error: Consistent final database state";
                return false; // 不一致，返回true
            }
        }
        return true;
    }

    private boolean shouldNotBlock(StatementCell stmt) { // 判断当前操作是否 不应该被阻塞。
        return false;
    }

    private boolean shouldNotAbort(StatementCell stmt) { // 判断当前操作是否 不应该被中止。
        return false;
    }

    private boolean compareResultSets(ArrayList<Object> resultSet1, ArrayList<Object> resultSet2) { // 比较两个查询结果集是否一致。
        if (resultSet1 == null && resultSet2 == null) { // 如果两个结果集都为 null，则一致。
            return true;
        } else if (resultSet1 == null || resultSet2 == null) { // 如果其中一个为 null，则不一致。
            bugInfo += " -- One result is NULL\n";
            return false;
        }
        if (resultSet1.size() != resultSet2.size()) { // 如果两个结果集的大小不同，则不一致。
            bugInfo += " -- Number Of Data Different\n";
            return false;
        }
        List<String> rs1 = preprocessResultSet(resultSet1); // 调用 preprocessResultSet 方法，将结果集转换为字符串列表并排序。
        List<String> rs2 = preprocessResultSet(resultSet2);
        for (int i = 0; i < rs1.size(); i++) { // 逐行比较两个结果集的值。
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

}
