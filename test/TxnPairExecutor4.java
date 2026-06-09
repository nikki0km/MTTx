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
public class TxnPairExecutor4 {

    private final ArrayList<StatementCell> submittedOrder;
    private final Transaction tx1;
    private final Transaction tx2;

    private TxnPairResult result;
    private ArrayList<StatementCell> actualSchedule;
    private ArrayList<Object> finalState;

    private boolean isDeadLock = false;
    private boolean timeout = false;
    private String exceptionMessage = "";
    private final Map<Integer, Boolean> txnAbort = new HashMap<>();

    public TxnPairExecutor4(ArrayList<StatementCell> schedule, Transaction tx1, Transaction tx2) {
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

    private void execute() {
        TableTool.setIsolationLevel(tx1);
        TableTool.setIsolationLevel(tx2);
        actualSchedule = new ArrayList<>();
        txnAbort.put(1, false);
        txnAbort.put(2, false);

        BlockingQueue<StatementCell> queue1 = new ArrayBlockingQueue<>(10);
        BlockingQueue<StatementCell> queue2 = new ArrayBlockingQueue<>(10);
        // 无界队列
        BlockingQueue<StatementCell> communicationID = new java.util.concurrent.LinkedBlockingQueue<>();

        Thread producer = new Thread(new Producer(queue1, queue2, submittedOrder, communicationID));
        Thread consumer1 = new Thread(new Consumer(1, queue1, communicationID, submittedOrder.size()));
        Thread consumer2 = new Thread(new Consumer(2, queue2, communicationID, submittedOrder.size()));

        producer.start();
        consumer1.start();
        consumer2.start();

        try {
            // 1. 等待指挥官结束
            producer.join();

            // 2. 【关键修复】如果发生死锁，Consumer 可能卡在数据库 IO 上
            // 必须在主线程这里发送一个 SQL 级的 ROLLBACK 来打断 Consumer 的等待
            if (isDeadLock || timeout) {
                try {
                    // 使用原生 SQL 执行回滚，绕过驱动的状态检查，确保能唤醒 Consumer
                    if (tx1.conn != null && !tx1.conn.isClosed())
                        tx1.conn.createStatement().execute("ROLLBACK");
                } catch (Exception ignored) {
                }

                try {
                    if (tx2.conn != null && !tx2.conn.isClosed())
                        tx2.conn.createStatement().execute("ROLLBACK");
                } catch (Exception ignored) {
                }
            }

            // 3. 现在 Consumer 应该被唤醒并退出了，可以安全 Join
            consumer1.join();
            consumer2.join();

        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // ==================== 【终极方案：强制状态切换复位】 ====================
        // 解决 "Can't call rollback when autocommit=true" 问题
        // 只有这样才能确保 MySQL 服务端的 MDL 锁被彻底释放。

        // --- 清理 Tx1 ---
        cleanupConnection(tx1.conn, "Tx1");

        // --- 清理 Tx2 ---
        cleanupConnection(tx2.conn, "Tx2");

        try {
            if (tx1.conn != null && !tx1.conn.isClosed()) {
                tx1.conn.close();
                log.info("Closed Tx1 connection successfully.");
            }
        } catch (SQLException e) {
            log.warn("Failed to close Tx1 connection: {}", e.getMessage());
        }

        try {
            if (tx2.conn != null && !tx2.conn.isClosed()) {
                tx2.conn.close();
                log.info("Closed Tx2 connection successfully.");
            }
        } catch (SQLException e) {
            log.warn("Failed to close Tx2 connection: {}", e.getMessage());
        }

        // 安全获取结果
        try {
            finalState = TableTool.getQueryResultAsList("SELECT * FROM " + TableTool.TableName);
            log.info("-- finalState--{}", finalState);
        } catch (Exception e) {
            log.error("Failed to query final state", e);
        }
    }

    /**
     * 专门用于处理顽固连接的清理方法
     * 强制执行：AutoCommit(false) -> Rollback -> AutoCommit(true) 序列
     */
    private void cleanupConnection(SQLConnection conn, String txName) {
        if (conn == null)
            return;
        try {
            if (conn.isClosed())
                return;

            // 1. 强制设为非自动提交 (开启一个新事务上下文)
            try {
                conn.setAutoCommit(false);
            } catch (SQLException e) {
                // 如果这一步失败，连接可能已经断开，后续也做不了了
                return;
            }

            // 2. 强制回滚 (清理服务端锁)
            try {
                conn.rollback();
            } catch (SQLException e) {
                log.debug("{} rollback failed (expected if conn issue): {}", txName, e.getMessage());
            }

            // 3. 强制设回自动提交 (释放 MDL 锁)
            try {
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                log.warn("{} setAutoCommit(true) failed: {}", txName, e.getMessage());
            }

        } catch (SQLException e) {
            log.error("Critical error during {} cleanup", txName, e);
        }
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
            isDeadLock = false;
            out: for (queryID = 0; queryID < schedule.size(); queryID++) {
                int txn = schedule.get(queryID).tx.txId;
                StatementCell statementCell = schedule.get(queryID).copy();
                int otherTxn = (txn == 1) ? 2 : 1;
                if (txnBlock.get(txn)) {
                    ArrayList<StatementCell> stmts = blockedStmts.get(txn);
                    stmts.add(statementCell);
                    blockedStmts.put(txn, stmts);
                    continue;
                }
                try {
                    if (!queues.get(txn).offer(statementCell, 1, TimeUnit.SECONDS)) {
                        log.warn("Failed to offer statementCell to queue{}, possible thread stall.", txn);
                    }
                } catch (InterruptedException e) {
                    log.info(" -- MainThread run exception");
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
                    if ((statementCell.type == StatementType.COMMIT || statementCell.type == StatementType.ROLLBACK)
                            && txnBlock.get(otherTxn)) {
                        StatementCell nextReturn = communicationID.poll();
                        while (nextReturn == null) {
                            if (System.currentTimeMillis() - startTs > 15000) {
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
                    } else {
                        isDeadLock = true;
                        log.info(" -- DeadLock happened(1)");
                        statementCell.blocked = true;
                        actualSchedule.add(statementCell);
                        break out;
                    }

                }
                if ((statementCell.type == StatementType.COMMIT
                        || statementCell.type == StatementType.ROLLBACK) && !exceptionMessage.contains("Deadlock")
                        && !exceptionMessage.contains("lock=true")
                        && !(txnBlock.get(1) && txnBlock.get(2))) {
                    txnBlock.put(otherTxn, false);
                    if (blockedStmts.get(otherTxn) != null) {
                        for (int j = 1; j < blockedStmts.get(otherTxn).size(); j++) {
                            StatementCell blockedStmtCell = blockedStmts.get(otherTxn).get(j);
                            try {
                                queues.get(otherTxn).put(blockedStmtCell);
                            } catch (InterruptedException e) {
                                log.info(" -- MainThread blocked exception");
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
                    if (exceptionMessage.contains("Deadlock") || exceptionMessage.contains("lock=true")
                            || (txnBlock.get(1) && txnBlock.get(2))) {
                        log.info(" -- DeadLock happened(2)");
                        isDeadLock = true;
                        break;
                    }
                    if (exceptionMessage.contains("restart") || exceptionMessage.contains("aborted")
                            || exceptionMessage.contains("TransactionRetry")) {
                        txnAbort.put(txn, true);
                        statementCell.aborted = true;
                    }
                }
            }
            if (isDeadLock) {
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
                    java.sql.Statement sqlStmt = null;

                    try {
                        sqlStmt = stmt.tx.conn.createStatement();

                        if (stmt.type == StatementType.SELECT || stmt.type == StatementType.SELECT_SHARE
                                || stmt.type == StatementType.SELECT_UPDATE) {
                            stmt.result = TableTool.getQueryResultAsListWithException(stmt.tx.conn, query);
                        } else {
                            sqlStmt.executeUpdate(query);
                        }

                        if (!((stmt.type == StatementType.COMMIT || stmt.type == StatementType.ROLLBACK)
                                && exceptionMessage.contains("Deadlock"))) {
                            exceptionMessage = "";
                        }
                    } catch (SQLException e) {
                        log.info(" -- TXNThread threadExec exception");
                        exceptionMessage = e.getMessage();
                        log.info("SQL Exception: " + exceptionMessage);
                        exceptionMessage = exceptionMessage + "; [Query] " + query;
                    } finally {
                        // 务必关闭 Statement
                        if (sqlStmt != null) {
                            try {
                                sqlStmt.close();
                            } catch (SQLException ignore) {
                            }
                        }
                        try {
                            communicationID.put(stmt);
                        } catch (InterruptedException e) {
                            log.info(" -- TXNThread Interrupted: " + e.getMessage());
                        }
                    }
                }
            } catch (InterruptedException e) {
                log.info(" -- TXNThread run Interrupted exception: " + e.getMessage());
            }
        }
    }
}