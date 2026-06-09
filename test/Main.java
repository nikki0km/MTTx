package test;

import com.beust.jcommander.JCommander;
import test.common.Table;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

@Slf4j
public class Main {
    public static void main(String[] args) {
        Options options = new Options();
        JCommander jCmd = new JCommander();
        jCmd.addObject(options);
        jCmd.parse(args);
        verifyOptions(options);
        log.info(String.format("Run tests for %s in [DB %s]-[Table %s] on [%s:%d]",
                options.getDBMS(), options.getDbName(), options.getTableName(), options.getHost(), options.getPort())); // options传参
        txnTesting(options);
    }

    private static void txnTesting(Options options) {
        TableTool.initialize(options);
        Transaction tx1, tx2, tx3, tx4;

        switch (options.getGenerationMode()) {
            // case PRESET: // 预设用例模式
            // handlePresetMode(options);
            // break;

            case METAMORPHIC1:
                handleMetamorphic1Mode(options);
                break;

            case METAMORPHIC2:
                handleMetamorphic2Mode(options);
                break;

            case METAMORPHIC3:
                handleMetamorphic3Mode(options);
                break;

            case METAMORPHIC4:
                handleMetamorphic4Mode(options);
                break;

            default:
                handleMetamorphic1Mode(options);
                break;
        }
    }

    // // 预设用例
    // private static void handlePresetMode(Options options) {
    // Scanner scanner;
    // if (options.getCaseFile().equals("")) {
    // log.info("Read database and transactions from command line");
    // scanner = new Scanner(System.in);
    // } else {
    // try {
    // File caseFile = new File(options.getCaseFile());
    // scanner = new Scanner(caseFile);
    // log.info("Read database and transactions from file: {}",
    // options.getCaseFile());
    // } catch (FileNotFoundException e) {
    // throw new RuntimeException("Read case from file failed: ", e);
    // }
    // }

    // TableTool.prepareTableFromScanner(scanner);
    // TableTool.preProcessTable();
    // TableTool.bugReport.setCreateTableSQL("<read from preset case>");
    // TableTool.bugReport.setInitializeStatements(new
    // ArrayList<>(TableTool.executedStatements));
    // TableTool.bugReport.setInitialTable(TableTool.tableToView().toString());
    // log.info("Initial table:\n{}", TableTool.tableToView());

    // Transaction tx1 = TableTool.readTransactionFromScanner(scanner, 1);
    // Transaction tx2 = TableTool.readTransactionFromScanner(scanner, 2);
    // Transaction tx3 = TableTool.readTransactionFromScanner(scanner, 1);
    // Transaction tx4 = TableTool.readTransactionFromScanner(scanner, 2);
    // String scheduleStr = TableTool.readScheduleFromScanner(scanner);
    // TableTool.bugReport.setTx1(tx1);
    // TableTool.bugReport.setTx2(tx2);
    // TableTool.bugReport.setTx3(tx3);
    // TableTool.bugReport.setTx4(tx4);
    // scanner.close();

    // log.info("Read transactions from file:\n{}{}", tx1, tx2, tx3, tx4);
    // TableTool.txPair++;
    // MtChecker3 checker = new MtChecker3(tx1, tx2, tx3, tx4);
    // if (scheduleStr == null || scheduleStr.isEmpty()) {
    // log.info("No schedule provided, running fixed order check.");
    // checker.checkAllInsertPositions();
    // } else {
    // checker.checkFixedOrder(scheduleStr);
    // }
    // }

    // 蜕变测试1，增加rollback在语句中间
    private static void handleMetamorphic1Mode(Options options) {
        // 实现蜕变关系1的测试逻辑
        log.info("Using Metamorphic Relation 1 for test case generation");
        while (true) {
            log.info("Create new table.");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }

            Table table = TableTool.dbms.buildTable(options.getTableName());
            table.initialize();
            log.info("createsql: {}", table.getCreateTableSql());

            TableTool.preProcessTable();
            TableTool.bugReport.setCreateTableSQL(table.getCreateTableSql());
            TableTool.bugReport.setInitializeStatements(table.getInitializeStatements());
            TableTool.bugReport.setInitialTable(TableTool.tableToView().toString());
            log.info("Initial table:\n{}", TableTool.tableToView());

            for (int _i = 0; _i < 5; _i++) {
                log.info("Generate transaction pair 1");
                TableTool.txPair++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }

                TableTool.recoverOriginalTable();
                Transaction tx1 = table.genTransaction(1);
                Transaction tx2 = table.genNullTransaction(2);
                // TableTool.makeConflict(tx1, tx2);
                TableTool.bugReport.setTx1(tx1);
                TableTool.bugReport.setTx2(tx2);
                log.info("Transaction 1:\n{}", tx1);
                log.info("Transaction 2:\n{}", tx2);

                log.info("Generate transaction pair 2");
                TableTool.txPair++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }

                Transaction tx3 = table.copyTransaction(1, tx1);
                Transaction tx4 = table.genRollbackTransaction(2);
                TableTool.bugReport.setTx3(tx3);
                TableTool.bugReport.setTx4(tx4);
                log.info("Transaction 3:\n{}", tx3);
                log.info("Transaction 4:\n{}", tx4);

                MtChecker1 checker = new MtChecker1(tx1, tx2, tx3, tx4);
                checker.checkAllInsertPositions();
            }
        }
    }

    // 从可重复读和串行化，不会出现幻读。
    private static void handleMetamorphic2Mode(Options options) {
        log.info("Using Metamorphic Relation 2 for test case generation");
        while (true) {
            log.info("Create new table.");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }

            Table table = TableTool.dbms.buildTable(options.getTableName());
            table.initialize();
            log.info("createsql: {}", table.getCreateTableSql());

            TableTool.preProcessTable();
            TableTool.bugReport.setCreateTableSQL(table.getCreateTableSql());
            TableTool.bugReport.setInitializeStatements(table.getInitializeStatements());
            TableTool.bugReport.setInitialTable(TableTool.tableToView().toString());
            log.info("Initial table:\n{}", TableTool.tableToView());

            for (int _i = 0; _i < 5; _i++) {
                log.info("Generate transaction pair 1");
                TableTool.txPair++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }

                TableTool.recoverOriginalTable();
                Transaction tx1 = table.genTransaction2(1);
                Transaction tx2 = table.genTransaction3(2);
                // TableTool.makeConflict(tx1, tx2);
                TableTool.bugReport.setTx1(tx1);
                TableTool.bugReport.setTx2(tx2);
                log.info("Transaction 1:\n{}", tx1);
                log.info("Transaction 2:\n{}", tx2);

                log.info("Generate transaction pair 2");
                TableTool.txPair++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }

                Transaction tx3 = table.copyTransaction(1, tx1);
                Transaction tx4 = table.genNullTransaction(2);
                TableTool.bugReport.setTx3(tx3);
                TableTool.bugReport.setTx4(tx4);
                log.info("Transaction 3:\n{}", tx3);
                log.info("Transaction 4:\n{}", tx4);

                MtChecker2 checker = new MtChecker2(tx1, tx2, tx3, tx4);
                checker.checkFix();
            }
        }
    }

    private static void handleMetamorphic3Mode(Options options) {
        // 实现蜕变关系3的测试逻辑,改变执行顺序，
        log.info("Using Metamorphic Relation 3 for test case generation");
        while (true) {
            log.info("Create new table.");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }

            Table table = TableTool.dbms.buildTable(options.getTableName());
            table.initialize();
            log.info("createsql: {}", table.getCreateTableSql());

            TableTool.preProcessTable();
            TableTool.bugReport.setCreateTableSQL(table.getCreateTableSql());
            TableTool.bugReport.setInitializeStatements(table.getInitializeStatements());
            TableTool.bugReport.setInitialTable(TableTool.tableToView().toString());
            log.info("Initial table:\n{}", TableTool.tableToView());

            for (int _i = 0; _i < 5; _i++) {
                log.info("Generate transaction pair 1");
                TableTool.txPair++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }

                TableTool.recoverOriginalTable();
                Transaction tx1 = table.genTransaction(1);
                Transaction tx2 = table.genTransaction(2);
                tx2.isolationlevel = tx1.isolationlevel;
                // TableTool.makeConflict(tx1, tx2);
                TableTool.bugReport.setTx1(tx1);
                TableTool.bugReport.setTx2(tx2);
                log.info("Transaction 1:\n{}", tx1);
                log.info("Transaction 2:\n{}", tx2);

                log.info("Generate transaction pair 2");
                TableTool.txPair++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }

                Transaction tx3 = table.copyTransaction(1, tx1);
                Transaction tx4 = table.copyTransaction(2, tx2);
                TableTool.bugReport.setTx3(tx3);
                TableTool.bugReport.setTx4(tx4);
                log.info("Transaction 3:\n{}", tx3);
                log.info("Transaction 4:\n{}", tx4);

                MtChecker3 checker = new MtChecker3(tx1, tx2, tx3, tx4);
                checker.checkRandom();
            }
        }
    }

    // 表不同
    private static void handleMetamorphic4Mode(Options options) {
        // 实现蜕变关系4的测试逻辑
        log.info("Using Metamorphic Relation 4 for test case generation");
        while (true) {
            log.info("Create new table.");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }

            Table table = TableTool.dbms.buildTable(options.getTableName());
            table.initialize();
            log.info("createsql: {}", table.getCreateTableSql());

            TableTool.preProcessTable();
            TableTool.bugReport2.setCreateTableSQL(table.getCreateTableSql());
            TableTool.bugReport2.setInitializeStatements(table.getInitializeStatements());
            TableTool.bugReport2.setInitialTable(TableTool.tableToView().toString());

            log.info(table.getCreateTableSql());
            String joinedSql = String.join("\n", table.getInitializeStatements()); // 用换行符分隔
            log.info("\n{}", joinedSql);
            // List<String> statements = table.getInitializeStatements();
            // for (String sql : statements) {
            // log.info("{}", sql); // 每条 SQL 前加 "-" 前缀
            // }

            log.info("Initial table:\n{}", TableTool.tableToView());

            for (int _i = 0; _i < 5; _i++) {
                log.info("Generate transaction pair 1");
                TableTool.txPair++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }

                TableTool.recoverOriginalTable();
                Transaction tx1 = table.genTransaction11(1);
                Transaction tx2 = table.genTransaction11(2);
                // TableTool.makeConflict(tx1, tx2);
                TableTool.bugReport2.setTx1(tx1);
                TableTool.bugReport2.setTx2(tx2);
                log.info("Transaction 1:\n{}", tx1);
                log.info("Transaction 2:\n{}", tx2);

                log.info("Generate transaction pair 2");
                TableTool.txPair++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }

                Transaction tx3 = table.copyTransaction(1, tx1);
                Transaction tx4 = table.copyTransaction(2, tx2);
                // Transaction tx4 = table.copyTransaction(2, tx2);
                TableTool.bugReport2.setTx3(tx3);
                TableTool.bugReport2.setTx4(tx4);
                log.info("Transaction 3:\n{}", tx3);
                log.info("Transaction 4:\n{}", tx4);

                MtChecker4 checker = new MtChecker4(tx1, tx2, tx3, tx4);
                checker.checkRandom();
            }
        }
    }

    private static void verifyOptions(Options options) {
        System.out.println("DEBUG DBMS: " + options.getDBMS());
        if (Arrays.stream(DBMS.values()).map(DBMS::name).noneMatch(options.getDBMS()::equals)) {
            throw new RuntimeException("Unknown DBMS: " + options.getDBMS());
        }
    }
}