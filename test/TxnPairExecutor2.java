package test;

import lombok.extern.slf4j.Slf4j;
import test.TxnPairExecutor.Consumer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TxnPairExecutor2 { // 模拟两个事务（tx1 和 tx2）的并发执行，并记录执行结果。

    private final ArrayList<StatementCell> submittedOrder; // 提交的事务调度顺序。
    private final Transaction tx1;
    private final Transaction tx2;

    private TxnPairResult result; // 执行结果，存储在 TxnPairResult 对象中。
    private ArrayList<StatementCell> actualSchedule; // 实际执行的调度顺序
    private ArrayList<Object> finalState; // 执行完成后数据库的最终状态

    private boolean isDeadLock = false; // 标记是否检测到死锁
    private boolean timeout = false; // 标记是否发生超时
    private String exceptionMessage = ""; /// 记录执行过程中发生的异常信息。
    private final Map<Integer, Boolean> txnAbort = new HashMap<>(); // 记录每个事务是否被中止。

    public TxnPairExecutor2(ArrayList<StatementCell> schedule, Transaction tx1, Transaction tx2) {
        this.submittedOrder = schedule;
        this.tx1 = tx1;
        this.tx2 = tx2;
    }

    TxnPairResult getResult() { // 获取事务对的执行结果
        if (result == null) { // 如果结果尚未计算，则调用 execute 方法执行调度
            execute();
            result = new TxnPairResult(actualSchedule, finalState, isDeadLock);
        }
        return result;
    }

    private void execute() { // 执行事务调度，模拟并发执行
        TableTool.setIsolationLevel(tx1); // 设置事务的隔离级别
        TableTool.setIsolationLevel(tx2);
        actualSchedule = new ArrayList<>(); // 初始化实际调度顺序
        txnAbort.put(1, false); // 初始化事务中止状态
        txnAbort.put(2, false);
        BlockingQueue<StatementCell> queue1 = new ArrayBlockingQueue<>(10);
        BlockingQueue<StatementCell> queue2 = new ArrayBlockingQueue<>(10);
        BlockingQueue<StatementCell> communicationID = new ArrayBlockingQueue<>(20);
        // BlockingQueue<StatementCell> queue1 = new SynchronousQueue<>();
        // BlockingQueue<StatementCell> queue2 = new SynchronousQueue<>();
        // BlockingQueue<StatementCell> communicationID = new SynchronousQueue<>(); //
        // 创建一个通信队列（communicationID）
        Thread producer = new Thread(new Producer(queue1, queue2, submittedOrder, communicationID)); // 创建三个线程：Producer线程负责按调度顺序生成语句
        Thread consumer1 = new Thread(new Consumer(1, queue1, communicationID, submittedOrder.size())); // Consumer1 和
                                                                                                        // Consumer2线程分别代表两个事务的执行。
        Thread consumer2 = new Thread(new Consumer(2, queue2, communicationID, submittedOrder.size()));
        producer.start(); // 启动线程并等待 Producer 线程完成
        consumer1.start();
        consumer2.start();
        try {
            producer.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        finalState = TableTool.getQueryResultAsList("SELECT * FROM " + TableTool.TableName); // 获取最终的数据库状态
        // log.info("-- finalState--{}", finalState);
    }

    class Producer implements Runnable { // 按调度顺序生成语句，并将语句放入对应的队列中。
        private final BlockingQueue<StatementCell> queue1;
        private final BlockingQueue<StatementCell> queue2;
        private final ArrayList<StatementCell> schedule;
        private final BlockingQueue<StatementCell> communicationID; // represent the execution feedback

        public Producer(BlockingQueue<StatementCell> queue1, BlockingQueue<StatementCell> queue2,
                ArrayList<StatementCell> schedule, BlockingQueue<StatementCell> communicationID) {
            this.queue1 = queue1;
            this.queue2 = queue2;
            this.schedule = schedule;
            this.communicationID = communicationID;
        }

        public void run() { // 主线程逻辑：按调度顺序生成语句并放入队列，同时监听子线程的反馈
            Map<Integer, Boolean> txnBlock = new HashMap<>(); // 记录子线程是否被阻塞
            txnBlock.put(1, false); // 事务 1 未被阻塞
            txnBlock.put(2, false); // 事务 2 未被阻塞
            Map<Integer, ArrayList<StatementCell>> blockedStmts = new HashMap<>(); // 记录被阻塞的语句
            blockedStmts.put(1, null);
            blockedStmts.put(2, null);
            Map<Integer, BlockingQueue<StatementCell>> queues = new HashMap<>(); // 记录队列
            queues.put(1, queue1);
            queues.put(2, queue2);
            int queryID; // 语句 ID
            long startTs; // 记录时间阈值
            timeout = false;
            isDeadLock = false;
            out: for (queryID = 0; queryID < schedule.size(); queryID++) {
                int txn = schedule.get(queryID).tx.txId; // 当前事务 ID
                StatementCell statementCell = schedule.get(queryID).copy(); // 当前语句
                int otherTxn = (txn == 1) ? 2 : 1; // 另一个事务 ID
                if (txnBlock.get(txn)) { // 如果当前事务被阻塞
                    ArrayList<StatementCell> stmts = blockedStmts.get(txn);
                    stmts.add(statementCell);
                    blockedStmts.put(txn, stmts); // 保存被阻塞的语句
                    continue;
                }
                try {
                    // 原：queues.get(txn).put(statementCell);
                    if (!queues.get(txn).offer(statementCell, 1, TimeUnit.SECONDS)) {
                        log.warn("Failed to offer statementCell to queue{}, possible thread stall.", txn);
                    }

                } catch (InterruptedException e) {
                    log.info(" -- MainThread run exception");
                    log.info("Query: " + statementCell.statement);
                    log.info("Interrupted Exception: " + e.getMessage());
                }
                StatementCell queryReturn = communicationID.poll(); // 从子线程获取反馈
                startTs = System.currentTimeMillis();
                while (queryReturn == null) { // 等待 2 秒 。如果 2 秒内未收到反馈，则认为子线程被阻塞，记录阻塞点。
                    if (System.currentTimeMillis() - startTs > 2000) { // 子线程被阻塞
                        log.info(txn + "-" + statementCell.statementId + ": time out");
                        txnBlock.put(txn, true); // 记录当前事务被阻塞
                        StatementCell blockPoint = statementCell.copy();
                        blockPoint.blocked = true;
                        actualSchedule.add(blockPoint);
                        ArrayList<StatementCell> stmts = new ArrayList<>();
                        stmts.add(statementCell);
                        blockedStmts.put(txn, stmts); // 保存被阻塞的语句
                        break;
                    }
                    queryReturn = communicationID.poll();
                }
                if (queryReturn != null) { // 成功收到反馈，则处理反馈结果。
                    if ((statementCell.type == StatementType.COMMIT || statementCell.type == StatementType.ROLLBACK) // 如果是提交或回滚操作，且另一个事务被阻塞，则处理阻塞事务。
                            && txnBlock.get(otherTxn)) {
                        StatementCell nextReturn = communicationID.poll();
                        while (nextReturn == null) {
                            if (System.currentTimeMillis() - startTs > 15000) { // 子线程被阻塞
                                log.info(" -- " + txn + "." + statementCell.statementId + ": time out");
                                timeout = true;
                                break;
                            }
                            nextReturn = communicationID.poll();
                        }
                        if (nextReturn != null) {
                            if (queryReturn.statement.equals(statementCell.statement)) {
                                statementCell.result = queryReturn.result;
                                blockedStmts.get(otherTxn).get(0).result = nextReturn.result;
                            } else {
                                statementCell.result = nextReturn.result;
                                blockedStmts.get(otherTxn).get(0).result = queryReturn.result;
                            }
                            actualSchedule.add(statementCell);
                            actualSchedule.add(blockedStmts.get(otherTxn).get(0));
                        } else {
                            log.info(" -- next return failed: " + statementCell.statement);
                            break;
                        }
                    } else if (queryReturn.statement.equals(statementCell.statement)) {
                        statementCell.result = queryReturn.result;
                        actualSchedule.add(statementCell);
                    } else { // 如果发生死锁，则记录死锁并退出循环。
                        isDeadLock = true;
                        log.info(" -- DeadLock happened(1)");
                        statementCell.blocked = true;
                        actualSchedule.add(statementCell);
                        break out;
                    }

                }
                if ((statementCell.type == StatementType.COMMIT // 处理阻塞事务
                        || statementCell.type == StatementType.ROLLBACK) && !exceptionMessage.contains("Deadlock")
                        && !exceptionMessage.contains("lock=true") // 如果是提交或回滚操作，且未发生死锁，则解除另一个事务的阻塞。
                        && !(txnBlock.get(1) && txnBlock.get(2))) {
                    txnBlock.put(otherTxn, false); // 解除另一个事务的阻塞
                    if (blockedStmts.get(otherTxn) != null) {
                        for (int j = 1; j < blockedStmts.get(otherTxn).size(); j++) {
                            StatementCell blockedStmtCell = blockedStmts.get(otherTxn).get(j);
                            try {
                                queues.get(otherTxn).put(blockedStmtCell); // 将被阻塞的语句放入队列
                            } catch (InterruptedException e) {
                                log.info(" -- MainThread blocked exception");
                                log.info("Query: " + statementCell.statement);
                                log.info("Interrupted Exception: " + e.getMessage());
                            }
                            StatementCell blockedReturn = communicationID.poll();
                            startTs = System.currentTimeMillis();
                            while (blockedReturn == null) {
                                if (System.currentTimeMillis() - startTs > 10000) {
                                    log.info(" -- " + txn + "." + statementCell.statementId + ": still time out");
                                    timeout = true;
                                    break;
                                }
                                blockedReturn = communicationID.poll();
                            }
                            if (blockedReturn != null) {
                                blockedStmtCell.result = blockedReturn.result;
                                actualSchedule.add(blockedStmtCell);

                            }
                        }
                    }
                }
                if (exceptionMessage.length() > 0 || (txnBlock.get(1) && txnBlock.get(2)) || timeout) { // 处理异常
                    if (exceptionMessage.contains("Deadlock") || exceptionMessage.contains("lock=true") // 如果发生死锁，则记录死锁并退出循环。
                            || (txnBlock.get(1) && txnBlock.get(2))) { // deadlock
                        log.info(" -- DeadLock happened(2)");
                        isDeadLock = true;
                        break;
                    }
                    if (exceptionMessage.contains("restart") || exceptionMessage.contains("aborted") // 如果事务需要重启或中止，则记录事务中止。
                            || exceptionMessage.contains("TransactionRetry")) {
                        txnAbort.put(txn, true);
                        statementCell.aborted = true;
                    }
                }
            }
            if (isDeadLock) {
                try {
                    tx1.conn.createStatement().executeUpdate("ROLLBACK"); // stop transaction
                    tx2.conn.createStatement().executeUpdate("ROLLBACK");
                } catch (SQLException e) {
                    log.info(" -- Deadlock Commit Failed");
                }
                log.info(" -- schedule execute failed");
            }
            StatementCell stopThread1 = new StatementCell(tx1, schedule.size());
            StatementCell stopThread2 = new StatementCell(tx2, schedule.size());
            try {
                while (communicationID.poll() != null)
                    ;
            } catch (Exception ignored) {
            }
            try {
                queue1.put(stopThread1);
                queue2.put(stopThread2);
            } catch (InterruptedException e) {
                log.info(" -- MainThread stop child thread Interrupted exception: " + e.getMessage());
            }
        }
    }

    class Consumer implements Runnable { // 从队列中取出语句并执行，将执行结果反馈给主线程。
        private final BlockingQueue<StatementCell> queue;
        private final BlockingQueue<StatementCell> communicationID; // represent the execution feedback
        private final int scheduleCount;
        private final int consumerId;

        public Consumer(int consumerId, BlockingQueue<StatementCell> queue,
                BlockingQueue<StatementCell> communicationID, int scheduleCount) {
            this.consumerId = consumerId;
            this.queue = queue;
            this.communicationID = communicationID;
            this.scheduleCount = scheduleCount;
        }

        public void run() {
            try {
                while (true) {
                    StatementCell stmt = queue.take(); // communicate with main thread
                    if (stmt.statementId >= scheduleCount)
                        break; // stop condition: schedule.size()
                    // execute a query
                    String query = stmt.statement;
                    try {
                        if (stmt.type == StatementType.SELECT || stmt.type == StatementType.SELECT_SHARE
                                || stmt.type == StatementType.SELECT_UPDATE) {
                            stmt.result = TableTool.getQueryResultAsListWithException(stmt.tx.conn, query);
                        } else {
                            stmt.tx.conn.createStatement().executeUpdate(query);
                        }
                        if (!((stmt.type == StatementType.COMMIT || stmt.type == StatementType.ROLLBACK)
                                && exceptionMessage.contains("Deadlock"))) { // -加了一个判断，如果死锁，则异常不更新。
                            exceptionMessage = "";
                        }
                    } catch (SQLException e) {
                        log.info(" -- TXNThread threadExec exception");
                        log.info("Query {}: {}", stmt, query);
                        // if (stmt.type == StatementType.COMMIT || stmt.type == StatementType.ROLLBACK
                        // || !exceptionMessage.contains("Deadlock")){
                        exceptionMessage = e.getMessage();
                        log.info("SQL Exception: " + exceptionMessage);
                        exceptionMessage = exceptionMessage + "; [Query] " + query;
                        // exceptionMessage = e.getMessage();
                        // log.info("SQL Exception: " + exceptionMessage);
                        // exceptionMessage = exceptionMessage + "; [Query] " + query;
                    } finally {
                        try {
                            communicationID.put(stmt); // communicate to main thread
                        } catch (InterruptedException e) { // communicationID.put()
                            log.info(" -- TXNThread threadExec exception");
                            log.info("Query {}: {}", stmt, query);
                            log.info("Interrupted Exception: " + e.getMessage());
                        }
                    }
                }
            } catch (InterruptedException e) {
                // thread stop
                log.info(" -- TXNThread run Interrupted exception: " + e.getMessage());
            }
        }
    }
}
