package test;

import lombok.extern.slf4j.Slf4j;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TxnPairExecutor3 {

    private final ArrayList<StatementCell> submittedOrder;
    private final Transaction tx1;
    private final Transaction tx2;

    private TxnPairResult result;
    private ArrayList<StatementCell> actualSchedule;
    private ArrayList<Object> finalState;

    private volatile boolean isDeadLock = false; // 使用 volatile 保证线程可见性
    private boolean timeout = false;
    private String exceptionMessage = "";
    private final Map<Integer, Boolean> txnAbort = new HashMap<>();

    public TxnPairExecutor3(ArrayList<StatementCell> schedule, Transaction tx1, Transaction tx2) {
        this.submittedOrder = schedule;
        this.tx1 = tx1;
        this.tx2 = tx2;
    }

    TxnPairResult getResult() {
        if (result == null) {
            execute();
            result = new TxnPairResult(actualSchedule, finalState, isDeadLock);
        }
        return result;
    }

    public Map<Integer, Boolean> getTxnAbort() {
        return new HashMap<>(this.txnAbort);
    }

    private void execute() {
        TableTool.setIsolationLevel(tx1);
        TableTool.setIsolationLevel(tx2);
        actualSchedule = new ArrayList<>();
        txnAbort.put(1, false);
        txnAbort.put(2, false);

        // 队列大小保持现状
        BlockingQueue<StatementCell> queue1 = new ArrayBlockingQueue<>(10);
        BlockingQueue<StatementCell> queue2 = new ArrayBlockingQueue<>(10);
        BlockingQueue<StatementCell> communicationID = new ArrayBlockingQueue<>(20);

        Thread producer = new Thread(new Producer(queue1, queue2, submittedOrder, communicationID));
        Thread consumer1 = new Thread(new Consumer(1, queue1, communicationID, submittedOrder.size()));
        Thread consumer2 = new Thread(new Consumer(2, queue2, communicationID, submittedOrder.size()));

        producer.start();
        consumer1.start();
        consumer2.start();

        try {
            producer.join(); // 等待调度结束

            // 【关键修复 1】必须等待消费者线程彻底退出
            // 否则它们持有的数据库锁会导致后续 DROP TABLE 卡死
            consumer1.join();
            consumer2.join();

        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 只有所有线程都退出了，再去查最终状态才是安全的
        finalState = TableTool.getQueryResultAsList("SELECT * FROM " + TableTool.TableName);
        log.info("-- finalState--{}", finalState);
    }

    class Producer implements Runnable {
        private final BlockingQueue<StatementCell> queue1;
        private final BlockingQueue<StatementCell> queue2;
        private final ArrayList<StatementCell> schedule;
        private final BlockingQueue<StatementCell> communicationID;

        public Producer(BlockingQueue<StatementCell> queue1, BlockingQueue<StatementCell> queue2,
                ArrayList<StatementCell> schedule, BlockingQueue<StatementCell> communicationID) {
            this.queue1 = queue1;
            this.queue2 = queue2;
            this.schedule = schedule;
            this.communicationID = communicationID;
        }

        public void run() {
            Map<Integer, Boolean> txnBlock = new HashMap<>();
            txnBlock.put(1, false);
            txnBlock.put(2, false);
            Map<Integer, ArrayList<StatementCell>> blockedStmts = new HashMap<>();
            blockedStmts.put(1, null);
            blockedStmts.put(2, null);
            Map<Integer, BlockingQueue<StatementCell>> queues = new HashMap<>();
            queues.put(1, queue1);
            queues.put(2, queue2);

            int queryID;
            long startTs;
            timeout = false;

            out: for (queryID = 0; queryID < schedule.size(); queryID++) {
                int txn = schedule.get(queryID).tx.txId;
                StatementCell statementCell = schedule.get(queryID).copy();
                int otherTxn = (txn == 1) ? 2 : 1;

                if (txnBlock.get(txn)) {
                    ArrayList<StatementCell> stmts = blockedStmts.get(txn);
                    if (stmts != null)
                        stmts.add(statementCell);
                    blockedStmts.put(txn, stmts);
                    continue;
                }

                try {
                    // 使用 offer 避免在这里就卡死
                    if (!queues.get(txn).offer(statementCell, 1, TimeUnit.SECONDS)) {
                        log.warn("Failed to offer statementCell to queue{}, possible thread stall.", txn);
                    }
                } catch (InterruptedException e) {
                    log.info(" -- MainThread run exception: " + e.getMessage());
                }

                StatementCell queryReturn = communicationID.poll();
                startTs = System.currentTimeMillis();

                while (queryReturn == null) {
                    if (System.currentTimeMillis() - startTs > 2000) {
                        log.info(txn + "-" + statementCell.statementId + ": time out");
                        txnBlock.put(txn, true);
                        StatementCell blockPoint = statementCell.copy();
                        blockPoint.blocked = true;
                        actualSchedule.add(blockPoint);
                        ArrayList<StatementCell> stmts = new ArrayList<>();
                        stmts.add(statementCell);
                        blockedStmts.put(txn, stmts);
                        break;
                    }
                    queryReturn = communicationID.poll();
                }

                if (queryReturn != null) {
                    // ... (原有逻辑保持不变) ...
                    if ((statementCell.type == StatementType.COMMIT || statementCell.type == StatementType.ROLLBACK)
                            && txnBlock.get(otherTxn)) {
                        StatementCell nextReturn = communicationID.poll();
                        while (nextReturn == null) {
                            if (System.currentTimeMillis() - startTs > 15000) {
                                log.info(" -- " + txn + "." + statementCell.statementId
                                        + ": time out waiting blocked txn");
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
                            log.info(" -- next return failed");
                            break;
                        }
                    } else if (queryReturn.statement.equals(statementCell.statement)) {
                        statementCell.result = queryReturn.result;
                        actualSchedule.add(statementCell);
                    } else {
                        isDeadLock = true;
                        log.info(" -- DeadLock happened(1) - mismatch");
                        statementCell.blocked = true;
                        actualSchedule.add(statementCell);
                        break out;
                    }
                }

                // 解锁逻辑
                if ((statementCell.type == StatementType.COMMIT || statementCell.type == StatementType.ROLLBACK)
                        && !exceptionMessage.contains("Deadlock")
                        && !exceptionMessage.contains("lock=true")
                        && !(txnBlock.get(1) && txnBlock.get(2))) {
                    txnBlock.put(otherTxn, false);
                    if (blockedStmts.get(otherTxn) != null) {
                        for (int j = 1; j < blockedStmts.get(otherTxn).size(); j++) {
                            StatementCell blockedStmtCell = blockedStmts.get(otherTxn).get(j);
                            try {
                                queues.get(otherTxn).put(blockedStmtCell);
                            } catch (InterruptedException e) {
                                log.error("Interrupt during unblocking", e);
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

                if (exceptionMessage.length() > 0 || (txnBlock.get(1) && txnBlock.get(2)) || timeout) {
                    if (exceptionMessage.toLowerCase().contains("deadlock") ||
                            exceptionMessage.contains("lock=true") ||
                            (txnBlock.get(1) && txnBlock.get(2))) {
                        log.info(" -- DeadLock happened(2)");
                        isDeadLock = true;
                        break;
                    }
                }
            } // end for loop

            // =======================================================
            // 【关键修复 2】清理和停止逻辑，防止死锁
            // =======================================================
            if (isDeadLock) {
                try {
                    // 尝试回滚以释放数据库锁，让 Consumer 能够动起来
                    if (!tx1.conn.isClosed())
                        tx1.conn.createStatement().executeUpdate("ROLLBACK");
                    if (!tx2.conn.isClosed())
                        tx2.conn.createStatement().executeUpdate("ROLLBACK");
                } catch (SQLException e) {
                    log.info(" -- Deadlock Commit Failed / Rollback error: " + e.getMessage());
                }
                log.info(" -- schedule execute failed");
            }

            // 发送停止信号给 Consumer
            StatementCell stopThread1 = new StatementCell(tx1, schedule.size());
            StatementCell stopThread2 = new StatementCell(tx2, schedule.size());

            try {
                // 1. 先清空 communicationID，防止 Consumer 卡在 put()
                while (communicationID.poll() != null)
                    ;

                // 2. 【核心】清空任务队列。
                // 如果 Consumer 卡在数据库里，它不会取队列。如果队列满了，Main 线程就在这里卡死。
                // 清空队列能保证下面的 put() 能够成功，从而让 Main 线程正常退出 run()。
                queue1.clear();
                queue2.clear();

                queue1.put(stopThread1);
                queue2.put(stopThread2);
            } catch (InterruptedException e) {
                log.info(" -- MainThread stop child thread Interrupted exception: " + e.getMessage());
            }
        }
    }

    class Consumer implements Runnable {
        private final BlockingQueue<StatementCell> queue;
        private final BlockingQueue<StatementCell> communicationID;
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
                    StatementCell stmt = queue.take();
                    if (stmt.statementId >= scheduleCount)
                        break;

                    String query = stmt.statement;
                    try {
                        if (stmt.type == StatementType.SELECT || stmt.type == StatementType.SELECT_SHARE
                                || stmt.type == StatementType.SELECT_UPDATE) {
                            stmt.result = TableTool.getQueryResultAsListWithException(stmt.tx.conn, query);
                        } else {
                            stmt.tx.conn.createStatement().executeUpdate(query);
                        }
                    } catch (SQLException e) {
                        log.info(" -- TXNThread threadExec exception: " + e.getMessage());
                        exceptionMessage = e.getMessage(); // 简单记录，详细解析在下面

                        if (exceptionMessage != null) {
                            String lowerMsg = exceptionMessage.toLowerCase();
                            // 检测数据库死锁
                            if (lowerMsg.contains("deadlock") || lowerMsg.contains("lock wait timeout exceeded")) {
                                isDeadLock = true;
                                log.info("13==Deadlock detected in Consumer: {}", exceptionMessage);
                            } else if (lowerMsg.contains("current transaction is aborted") ||
                                    lowerMsg.contains("restart") ||
                                    lowerMsg.contains("transaction aborted")) {
                                txnAbort.put(stmt.tx.txId, true);
                                stmt.aborted = true;
                            }
                        }
                    } finally {
                        try {
                            // 使用 offer 带超时，防止 Producer 已经挂了导致 Consumer 卡死在这里
                            if (!communicationID.offer(stmt, 2, TimeUnit.SECONDS)) {
                                log.info("Consumer {} failed to put result, queue full or main thread gone.",
                                        consumerId);
                            }
                        } catch (InterruptedException e) {
                            log.info(" -- TXNThread interrupted during put");
                        }
                    }
                }
            } catch (InterruptedException e) {
                log.info(" -- TXNThread run Interrupted");
            }
        }
    }
}