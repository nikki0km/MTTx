package test;

import lombok.extern.slf4j.Slf4j;
import test.TxnPairExecutor.Consumer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TxnPairExecutor { // 模拟两个事务（tx1 和 tx2）的并发执行，并记录执行结果。

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

    public TxnPairExecutor(ArrayList<StatementCell> schedule, Transaction tx1, Transaction tx2) {
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
        BlockingQueue<StatementCell> queue1 = new SynchronousQueue<>(); // 创建两个同步队列（queue1 和 queue2）
        BlockingQueue<StatementCell> queue2 = new SynchronousQueue<>();
        BlockingQueue<StatementCell> communicationID = new SynchronousQueue<>(); // 创建一个通信队列（communicationID）
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
        log.info("-- finalState--{}", finalState);
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
                    queues.get(txn).put(statementCell); // 将语句放入队列
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
                if (queryReturn != null) {
                } else {
                }
                if (queryReturn != null) { // 成功收到反馈，则处理反馈结果。
                    if ((statementCell.type == StatementType.COMMIT || statementCell.type == StatementType.ROLLBACK) // 如果是提交或回滚操作，且另一个事务被阻塞，则处理阻塞事务。说明事务1已经完成，等待事务2是否解除死锁，能收回复
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
                    } else { // 如果发生死锁，则记录死锁并退出循环。 ​​子线程（Consumer）返回的语句不是当前事务的语句​​
                             // 时触发。例如：主线程期望事务1返回结果，但收到的是事务2的语句结果，说明两个事务互相等待对方的锁。
                        isDeadLock = true;
                        log.info(" -- DeadLock happened(1)");
                        statementCell.blocked = true;
                        actualSchedule.add(statementCell);
                        break out;
                    }
                }
                // 第一次处理可能遗漏阻塞语句： 因为上述可能只解决了第一个被阻塞的语句
                // 还可能：如果 nextReturn 超时（15 秒未返回），仅标记 timeout = true，但 ​不恢复阻塞事务。
                // 可能导致阻塞事务永远无法继续。
                if ((statementCell.type == StatementType.COMMIT // 处理阻塞事务
                        || statementCell.type == StatementType.ROLLBACK) && !exceptionMessage.contains("Deadlock")
                        && !exceptionMessage.contains("lock=true") // 当系统确认没有死锁风险时，主动 ​强制恢复阻塞事务。
                        && !(txnBlock.get(1) && txnBlock.get(2))) {
                    txnBlock.put(otherTxn, false); // 直接解除另一个事务的阻塞
                    // // 新增：等待COMMIT完成确认
                    // StatementCell commitConfirm = communicationID.poll();
                    // long commitStartTs = System.currentTimeMillis();
                    // while (commitConfirm == null) {
                    // if (System.currentTimeMillis() - commitStartTs > 5000) { // 超时5秒
                    // log.error("COMMIT confirmation timeout!");
                    // break;
                    // }
                    // commitConfirm = communicationID.poll();
                    // }
                    // // 确认COMMIT完成后，再恢复阻塞操作
                    // if (commitConfirm != null && commitConfirm.statement.equals("COMMIT")) {
                    // log.info("COMMIT confirmed, resuming blocked operations...");
                    // 恢复被阻塞的事务操作
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
                    // }
                }
                if (exceptionMessage.length() > 0 || (txnBlock.get(1) && txnBlock.get(2)) || timeout) { // 处理异常
                    if (exceptionMessage.contains("Deadlock") || exceptionMessage.contains("lock=true") // 如果发生死锁，则记录死锁并退出循环。
                            || (txnBlock.get(1) && txnBlock.get(2))) { // deadlock 试试 && !isTransactionFinished(tx1) &&
                                                                       // !isTransactionFinished(tx2)
                        log.info(" -- DeadLock happened(2)");
                        log.info("txnBlock: 1={}, 2={}", txnBlock.get(1), txnBlock.get(2));
                        log.info("exceptionMessage: {}", exceptionMessage);
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
                    ResultSet rs = tx1.conn.createStatement().executeQuery("SELECT @@autocommit");
                    if (rs.next()) {
                        log.info("Transaction 1 autocommit: " + rs.getBoolean(1)); // 可以在 ROLLBACK; 之前，检查 tx1 和 tx2
                                                                                   // 的事务状态：如果 autocommit =
                                                                                   // 1，说明事务已经提交；如果 autocommit =
                                                                                   // 0，说明事务仍然在进行中。
                    }
                    tx1.conn.createStatement().executeUpdate("ROLLBACK;"); // stop transaction
                    tx2.conn.createStatement().executeUpdate("ROLLBACK;");
                } catch (SQLException e) {
                    log.info(" -- Deadlock Commit Failed");
                }
                log.info(" -- schedule execute failed");
            }
            // StatementCell stopThread1 = new StatementCell(tx1, schedule.size());
            // StatementCell stopThread2 = new StatementCell(tx2, schedule.size());
            StatementCell stopSignal = new StatementCell(null, -1); // -1 表示终止信号
            try {
                while (communicationID.poll() != null)
                    ;
            } catch (Exception ignored) {
            }
            // try {
            // queue1.put(stopSignal);
            // queue2.put(stopSignal);
            // } catch (InterruptedException e) {
            // log.info(" -- MainThread stop child thread Interrupted exception: " +
            // e.getMessage());
            // }
            // 修改后的代码：
            try {
                // 设置超时时间为1秒
                boolean offered1 = queue1.offer(stopSignal, 1, TimeUnit.SECONDS);
                if (!offered1) {
                    log.info("Stop signal to queue1 failed, consumer may have exited.");
                }
                boolean offered2 = queue2.offer(stopSignal, 1, TimeUnit.SECONDS);
                if (!offered2) {
                    log.info("Stop signal to queue2 failed, consumer may have exited.");
                }
            } catch (InterruptedException e) {
                log.info("Interrupted while sending stop signals: " + e.getMessage());
                Thread.currentThread().interrupt(); // 重新设置中断状态
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
                    if (stmt.statementId == -1) {
                        log.info("Consumer received stop signal, terminating...");
                        break;
                    }
                    // execute a query
                    String query = stmt.statement;
                    try {
                        if (stmt.type == StatementType.SELECT || stmt.type == StatementType.SELECT_SHARE
                                || stmt.type == StatementType.SELECT_UPDATE) {
                            stmt.result = TableTool.getQueryResultAsListWithException(stmt.tx.conn, query);
                        } else {
                            stmt.tx.conn.createStatement().executeUpdate(query);
                            // // 新增：发送COMMIT完成确认
                            // if (stmt.type == StatementType.COMMIT) {
                            // StatementCell commitConfirm = new StatementCell(null, -2); // -2表示COMMIT完成
                            // commitConfirm.statement = "COMMIT";
                            // communicationID.put(commitConfirm);
                            // }
                        }
                        if (!((stmt.type == StatementType.COMMIT || stmt.type == StatementType.ROLLBACK)
                                && exceptionMessage.contains("Deadlock"))) { // -加了一个判断，如果死锁，则异常不更新。
                            exceptionMessage = "";
                        }
                    } catch (SQLException e) {
                        log.info(" -- TXNThread threadExec exception");
                        log.info("Query {}: {}", stmt, query);
                        exceptionMessage = e.getMessage();
                        log.info("SQL Exception: " + exceptionMessage);
                        exceptionMessage = exceptionMessage + "; [Query] " + query;
                        // // 处理锁超时异常
                        // if (exceptionMessage.contains("Lock wait timeout exceeded")) {
                        // log.info("Lock wait timeout. Rolling back transaction.");
                        // try {
                        // stmt.tx.conn.rollback(); // 回滚事务
                        // } catch (SQLException rollbackException) {
                        // log.error("Rollback failed: " + rollbackException.getMessage());
                        // }
                        // }
                    } finally {
                        try {
                            // if(stmt.type != StatementType.COMMIT){ //将执行过的语句给生产者，除了commit。
                            communicationID.put(stmt); // communicate to main thread
                            // }
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
