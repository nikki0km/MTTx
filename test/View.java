package test;

import java.util.Arrays;
import java.util.HashMap;

//视图（View） 是数据库中的一个重要概念，它是一个虚拟表，其内容由查询定义。视图并不实际存储数据，而是基于一个或多个表的查询结果动态生成。视图可以简化复杂的查询、提高数据安全性，并提供逻辑上的数据抽象。
public class View {
    HashMap<Integer, Object[]> data;
    HashMap<Integer, Boolean> deleted; // may be null

    View() {
        data = new HashMap<>();
    }

    View(boolean withDel) {
        data = new HashMap<>();
        if (withDel) {
            deleted = new HashMap<>();
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("View{\n");
        for (int rowId : data.keySet()) {
            sb.append("\t");
            sb.append(rowId).append(":");
            sb.append(Arrays.toString(data.get(rowId)));
            if (deleted != null) {
                sb.append(" deleted: ").append(deleted.get(rowId));
            }
            sb.append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
