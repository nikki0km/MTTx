package test.common;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;

@Getter
@Setter
public abstract class Column {
    protected Table table;
    protected String columnName;
    protected DataType dataType;
    protected boolean primaryKey;
    protected boolean unique;
    protected boolean notNull;
    protected boolean defaultNull;
    protected boolean autoIncrement;
    protected int size;
    protected ArrayList<String> appearedValues = new ArrayList<>();
    // 新增字段标记是否已被选择过
    private boolean selected = false;
    
    // 新增方法标记为已选,仅在蜕变关系6中会用到，以防X列的where语句被修改
    public void markAsSelected() {
        this.selected = true;
    }

    protected Column() {}

    protected Column(Table table, String columnName, DataType dataType, boolean primaryKey, boolean unique, boolean notNull , boolean defaultNull, boolean autoIncrement, int size) {
        this.table = table;
        this.columnName = columnName;
        this.dataType = dataType;
        this.primaryKey = primaryKey;
        this.unique = unique;
        this.notNull = notNull;
        this.defaultNull = defaultNull;
        this.autoIncrement = autoIncrement;
        this.size = size;
    }

    public abstract String getRandomVal();
}
