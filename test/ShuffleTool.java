package test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

public class ShuffleTool {
    public static ArrayList<ArrayList<StatementCell>> genAllSubmittedTrace(Transaction tx1, Transaction tx2) {  //生成两个事务的所有可能的调度顺序。
        int n1 = tx1.statements.size(), n2 = tx2.statements.size();
        ArrayList<ArrayList<StatementCell>> res = new ArrayList<>();
        shuffle(res, new ArrayList<>(), tx1.statements, n1, 0, tx2.statements, n2, 0);   //调用 shuffle 方法，递归生成所有可能的调度顺序。
        return res;
    }
    //递归地选择事务 1 或事务 2 的下一个语句，构建所有可能的调度顺序。
    //使用回溯法确保所有组合都被遍历。
    public static void shuffle(ArrayList<ArrayList<StatementCell>> res, ArrayList<StatementCell> cur,            //递归生成两个事务的所有可能的调度顺序。
                         ArrayList<StatementCell> txn1, int txn1Len, int txn1Idx, ArrayList<StatementCell> txn2,       //res：结果列表，存储所有调度顺序。
                         int txn2Len, int txn2Idx) {
        if (txn1Idx == txn1Len && txn2Idx == txn2Len) {    //txn1Idx：事务 1 当前处理的语句索引，txn1Len：事务 1 的语句数量。
            res.add(new ArrayList<>(cur));         //当前正在构建的调度顺序。
            return;
        }
        if (txn1Idx < txn1Len) {
            cur.add(txn1.get(txn1Idx));
            shuffle(res, cur, txn1, txn1Len, txn1Idx + 1, txn2, txn2Len, txn2Idx);         
            cur.remove(cur.size() - 1); 
        }
        if (txn2Idx < txn2Len) {
            cur.add(txn2.get(txn2Idx));
            shuffle(res, cur, txn1, txn1Len, txn1Idx, txn2, txn2Len, txn2Idx + 1);
            cur.remove(cur.size() - 1);
        }
    }

    public static ArrayList<ArrayList<StatementCell>> sampleSubmittedTrace(Transaction tx1, Transaction tx2, int count) {   //从所有可能的调度顺序中随机采样指定数量的调度顺序。
        ArrayList<ArrayList<StatementCell>> allSubmittedTrace = genAllSubmittedTrace(tx1, tx2);   // 生成所有可能的调度顺序
        int n = allSubmittedTrace.size();
        if (n <= count) {         // 如果总数小于等于需要的数量，则直接返回所有调度顺序
            return allSubmittedTrace;
        }
        ArrayList<ArrayList<StatementCell>> res = new ArrayList<>(count);    //随机采样指定数量的调度顺序
        for (int i = 0; i < count; i++) {
            res.add(allSubmittedTrace.get(i));
        }
        for(int i = count; i < n; i++) {
            int d = new Random().nextInt(i+1);
            if (d < count) {
                res.set(d, allSubmittedTrace.get(i));
            }
        }
        return res;
    }

    public static ArrayList<ArrayList<StatementCell>> genRandomSubmittedTrace(Transaction tx1, Transaction tx2, int count) {       //--好像没用到--随机生成指定数量的调度顺序。
        int tx1Len = tx1.statements.size(), tx2Len = tx2.statements.size();
        if (C(tx1Len+tx2Len, tx1Len) <= count * 1.3) {    //如果组合数较少，则直接生成所有调度顺序
            return genAllSubmittedTrace(tx1, tx2);
        }
        ArrayList<ArrayList<StatementCell>> res = new ArrayList<>(count);   // 随机生成指定数量的调度顺序
        HashSet<String> generated = new HashSet<>(); 
        for (int i = 0; i < count; i++) {
            ArrayList<StatementCell> temp = new ArrayList<>();
            StringBuilder order = new StringBuilder();
            int tx1Idx = 0, tx2Idx = 0;
            while (true) {
                if (tx1Idx == tx1Len && tx2Idx == tx2Len) {     // 如果两个事务的所有语句都已处理，则结束
                    String orderStr = order.toString();
                    if (!generated.contains(orderStr)) {
                        res.add(temp);
                        generated.add(orderStr);
                    }
                    break;
                }
                if (tx1Idx == tx1Len) {    //随机选择事务 1 或事务 2 的下一个语句
                    order.append("2");
                    temp.add(tx2.statements.get(tx2Idx++));
                } else if (tx2Idx == tx2Len) {
                    order.append("1");
                    temp.add(tx1.statements.get(tx1Idx++));
                } else {
                    boolean pickOne = Randomly.getBoolean();
                    if (pickOne) {
                        order.append("1");
                        temp.add(tx1.statements.get(tx1Idx++));
                    } else {
                        order.append("2");
                        temp.add(tx2.statements.get(tx2Idx++));
                    }
                }
            }
        }
        return res;
    }

    private static int A(int n, int m) {       //计算排列数（A）
        int res = 1;
        for (int i = m; i > 0; i--) {
            res *= n;
            n--;
        }
        return res;
    }
 
    private static int C(int n, int m) {    //计算 组合数（C）
        if (m > n / 2) {
            m = n - m;
        }
        return A(n, m) / A(m, m);
    }
}
