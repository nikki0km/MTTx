package test;

import java.util.*;

/**
 * 终极 Conflict Graph Builder (自由探索 + 边界护航版)
 * 【核心理念】：最大化释放大模型的重排自由度。
 * 仅保留绝对物理因果（事务内部顺序、锁等待、COMMIT可见性边界），
 * 其余语义依赖交由大模型结合 EXPLAIN 进行判断。
 */
public class ConflictGraph {

    public static List<String> buildConflictEdges(ArrayList<StatementCell> order) {
        List<String> edges = new ArrayList<>();
        Set<String> addedEdges = new HashSet<>();

        java.util.function.BiConsumer<StatementCell, StatementCell> addEdge = (from, to) -> {
            String edgeStr = String.format("%d-%d 必须在 %d-%d 之前执行",
                    from.tx.txId, from.statementId, to.tx.txId, to.statementId);
            if (!addedEdges.contains(edgeStr)) {
                edges.add(edgeStr);
                addedEdges.add(edgeStr);
            }
        };

        /*
         * ========================================
         * 绝对物理底线 1：事务内部顺序 (TX_ORDER)
         * ========================================
         */
        Map<Integer, List<StatementCell>> txStmts = new HashMap<>();
        for (StatementCell stmt : order) {
            if (!txStmts.computeIfAbsent(stmt.tx.txId, k -> new ArrayList<>()).contains(stmt)) {
                txStmts.get(stmt.tx.txId).add(stmt);
            }
        }
        for (List<StatementCell> stmts : txStmts.values()) {
            for (int i = 0; i < stmts.size() - 1; i++) {
                addEdge.accept(stmts.get(i), stmts.get(i + 1));
            }
        }

        /*
         * ========================================
         * 绝对物理底线 2：锁等待因果律 (LOCK_WAIT)
         * ========================================
         */
        for (int i = 0; i < order.size(); i++) {
            StatementCell blockedStmt = order.get(i);
            if (blockedStmt.blocked) {
                int otherTxId = (blockedStmt.tx.txId == 1) ? 2 : 1;
                for (int j = 0; j < i; j++) {
                    StatementCell previousStmt = order.get(j);
                    if (!previousStmt.blocked && previousStmt.tx.txId == otherTxId) {
                        addEdge.accept(previousStmt, blockedStmt);
                    }
                }
            }
        }

        /*
         * ========================================
         * 提取实际执行序列（过滤掉没跑成功的阻塞语句残影）
         * ========================================
         */
        List<StatementCell> actualExecOrder = new ArrayList<>();
        for (StatementCell stmt : order) {
            if (!stmt.blocked) {
                actualExecOrder.add(stmt);
            }
        }

        /*
         * ========================================
         * 绝对物理底线 3：事务终结双向墙 (COMMIT/ROLLBACK)
         * MVCC 的绝对次元壁：语句绝不能跨越对方事务的 COMMIT 边界，否则快照（ReadView）会立刻错乱！
         * ========================================
         */
        for (int i = 0; i < actualExecOrder.size(); i++) {
            StatementCell s1 = actualExecOrder.get(i);

            for (int j = i + 1; j < actualExecOrder.size(); j++) {
                StatementCell s2 = actualExecOrder.get(j);

                if (s1.tx.txId != s2.tx.txId) {
                    // 规则 1：如果 s1 是 COMMIT/ROLLBACK，原本在它后面的 s2 绝不能跨越到它前面
                    if (isTransactionEnd(s1)) {
                        addEdge.accept(s1, s2);
                    }

                    // 规则 2：如果 s2 是 COMMIT/ROLLBACK，原本在它前面的 s1 绝不能跨越到它后面
                    if (isTransactionEnd(s2)) {
                        addEdge.accept(s1, s2);
                    }
                }
            }
        }

        /*
         * ========================================
         * 绝对物理底线 4：隔离级别精细化辅助防线 (仅针对 RU 脏读)
         * ========================================
         */
        for (int i = 0; i < actualExecOrder.size(); i++) {
            StatementCell s1 = actualExecOrder.get(i);
            boolean isS1Write = isWriteOperation(s1);
            boolean isS1Read = isReadOperation(s1);

            for (int j = i + 1; j < actualExecOrder.size(); j++) {
                StatementCell s2 = actualExecOrder.get(j);
                if (s1.tx.txId == s2.tx.txId)
                    continue;

                boolean isS2Write = isWriteOperation(s2);
                boolean isS2Read = isReadOperation(s2);

                // 判断是否处于 READ_UNCOMMITTED (读未提交) 级别
                boolean isRU = (s1.tx.isolationlevel == IsolationLevel.READ_UNCOMMITTED) ||
                        (s2.tx.isolationlevel == IsolationLevel.READ_UNCOMMITTED);

                if (isRU) {
                    // RU 级别下，脏读极易引发难以预测的结果突变。保守冻结时序。
                    if ((isS1Read && isS2Write) || (isS1Write && isS2Read) || (isS1Write && isS2Write)) {
                        addEdge.accept(s1, s2);
                    }
                }
            }
        }

        return edges;
    }

    private static boolean isTransactionEnd(StatementCell stmt) {
        if (stmt.statement == null)
            return false;
        String sql = stmt.statement.toUpperCase().trim();
        return sql.equals("COMMIT") || sql.equals("ROLLBACK") ||
                sql.startsWith("COMMIT;") || sql.startsWith("ROLLBACK;");
    }

    private static boolean isWriteOperation(StatementCell stmt) {
        if (stmt == null || stmt.type == null)
            return false;
        return stmt.type == StatementType.INSERT ||
                stmt.type == StatementType.UPDATE ||
                stmt.type == StatementType.DELETE;
    }

    private static boolean isReadOperation(StatementCell stmt) {
        if (stmt == null || stmt.type == null)
            return false;
        return stmt.type == StatementType.SELECT ||
                stmt.type == StatementType.SELECT_SHARE ||
                stmt.type == StatementType.SELECT_UPDATE;
    }

    /**
     * 【终极防线校验器】：双向同步验证，拒绝一切幻觉
     */
    public static boolean isValidDerivedOrder(ArrayList<StatementCell> origOrder,
            ArrayList<StatementCell> derivedOrder) {

        Map<String, Integer> derivedPositions = new HashMap<>();
        for (int i = 0; i < derivedOrder.size(); i++) {
            StatementCell stmt = derivedOrder.get(i);
            int txId = stmt.tx.txId;
            if (txId == 3)
                txId = 1;
            if (txId == 4)
                txId = 2;
            String key = txId + "-" + stmt.statementId;
            derivedPositions.put(key, i);
        }

        List<StatementCell> actualOrig = new ArrayList<>();
        for (StatementCell s : origOrder) {
            if (!s.blocked)
                actualOrig.add(s);
        }

        // 1. 校验：内部事务顺序不被破坏 (TX_ORDER)
        for (int txId = 1; txId <= 2; txId++) {
            int lastPos = -1;
            for (StatementCell s : actualOrig) {
                if (s.tx.txId == txId) {
                    Integer pos = derivedPositions.get(txId + "-" + s.statementId);
                    if (pos != null) {
                        if (pos < lastPos)
                            return false;
                        lastPos = pos;
                    }
                }
            }
        }

        // 2. 校验：锁等待顺序约束 (LOCK_WAIT)
        for (int i = 0; i < origOrder.size(); i++) {
            StatementCell blockedStmt = origOrder.get(i);
            if (blockedStmt.blocked) {
                int otherTxId = (blockedStmt.tx.txId == 1) ? 2 : 1;
                for (int j = 0; j < i; j++) {
                    StatementCell prev = origOrder.get(j);
                    if (!prev.blocked && prev.tx.txId == otherTxId) {
                        String keyPrev = prev.tx.txId + "-" + prev.statementId;
                        String keyBlocked = blockedStmt.tx.txId + "-" + blockedStmt.statementId;

                        Integer posPrev = derivedPositions.get(keyPrev);
                        Integer posBlocked = derivedPositions.get(keyBlocked);

                        if (posPrev != null && posBlocked != null && posPrev > posBlocked)
                            return false;
                    }
                }
            }
        }

        // 3. 校验：事务终结可见性屏障 (COMMIT/ROLLBACK 双向墙)
        for (int i = 0; i < actualOrig.size(); i++) {
            StatementCell s1 = actualOrig.get(i);

            for (int j = i + 1; j < actualOrig.size(); j++) {
                StatementCell s2 = actualOrig.get(j);

                if (s1.tx.txId != s2.tx.txId) {
                    // 只要 s1 或 s2 任意一个是事务终结符，它们的相对顺序就绝对不能颠倒！
                    if (isTransactionEnd(s1) || isTransactionEnd(s2)) {
                        String key1 = s1.tx.txId + "-" + s1.statementId;
                        String key2 = s2.tx.txId + "-" + s2.statementId;
                        Integer pos1 = derivedPositions.get(key1);
                        Integer pos2 = derivedPositions.get(key2);

                        // 如果大模型把顺序颠倒了（越过了 COMMIT），直接拦截打回！
                        if (pos1 != null && pos2 != null && pos1 > pos2) {
                            return false;
                        }
                    }
                }
            }
        }

        // 4. 校验：隔离级别特化防护 (仅拦截 RU 下的乱序)
        for (int i = 0; i < actualOrig.size(); i++) {
            StatementCell s1 = actualOrig.get(i);
            boolean isS1Write = isWriteOperation(s1);
            boolean isS1Read = isReadOperation(s1);

            for (int j = i + 1; j < actualOrig.size(); j++) {
                StatementCell s2 = actualOrig.get(j);
                if (s1.tx.txId == s2.tx.txId)
                    continue;

                boolean isS2Write = isWriteOperation(s2);
                boolean isS2Read = isReadOperation(s2);
                boolean isRU = (s1.tx.isolationlevel == IsolationLevel.READ_UNCOMMITTED) ||
                        (s2.tx.isolationlevel == IsolationLevel.READ_UNCOMMITTED);

                if (isRU && ((isS1Read && isS2Write) || (isS1Write && isS2Read) || (isS1Write && isS2Write))) {
                    String key1 = s1.tx.txId + "-" + s1.statementId;
                    String key2 = s2.tx.txId + "-" + s2.statementId;
                    Integer pos1 = derivedPositions.get(key1);
                    Integer pos2 = derivedPositions.get(key2);

                    // 如果违反了 RU 保护底线，拦截！
                    if (pos1 != null && pos2 != null && pos1 > pos2)
                        return false;
                }
            }
        }

        return true;
    }
}