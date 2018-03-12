package com.github.lit.jdbc.statement.select;

import com.github.lit.commons.page.Page;
import com.github.lit.commons.page.PageList;
import com.github.lit.jdbc.enums.JoinType;
import com.github.lit.jdbc.enums.Logic;
import com.github.lit.jdbc.model.StatementContext;
import com.github.lit.jdbc.model.TableInfo;
import com.github.lit.jdbc.statement.where.AbstractCondition;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;

import java.util.*;

/**
 * User : liulu
 * Date : 2017/6/4 9:33
 * version $Id: SelectImpl.java, v 0.1 Exp $
 */
@SuppressWarnings("unchecked")
public class SelectImpl<T> extends AbstractCondition<Select<T>, SelectExpression<T>> implements Select<T> {

    /**
     * 整体 Select 语句
     */
    private PlainSelect select;

    /**
     * Select 语句的列，包括函数
     */
    private List<SelectItem> selectItems;

    /**
     * join 语句
     */
    private List<Join> joins;

    /**
     * joinTables 的所有 table 对象
     */
    private Map<Class<?>, Table> joinTables;

    /**
     * join 表的 表信息
     */
    private Map<Class<?>, TableInfo> joinTableInfos;

    /**
     * order by语句
     */
    private List<OrderByElement> orderBy;

    /**
     * 白名单字段
     */
//    private List<SelectExpressionItem> includes;

    /**
     * 黑名单字段
     */
    private List<String> excludes;

    /**
     * groupBy 语句
     */
    private List<Expression> groupBy;

    /**
     * having 语句
     */
    private StringBuilder having;

    /**
     * 多表查询额外的字段
     */
//    private Map<Class<?>, List<Column>> additionalColumns;
//
//    private List<Function> functions;
//
    private SelectExpression<T> selectExpression;


//    private int addFieldLength = 0;

    private boolean addDefaultColumn = true;

    private Class<T> entityClass;

    private Integer pageNum;

    private Integer pageSize;

    private boolean queryCount;

    private static List<SelectItem> COUNT_FUNC_ITEM;

    static {
        Function countFunc = new Function();
        countFunc.setName("count");
        countFunc.setAllColumns(true);

        SelectItem item = new SelectExpressionItem(countFunc);
        COUNT_FUNC_ITEM = Collections.singletonList(item);
    }

    public SelectImpl(Class<T> clazz) {
        super(clazz);
        entityClass = clazz;
        initSelect();
    }

    private void initSelect() {
        selectItems = new ArrayList<>(tableInfo.getFieldColumnMap().size());

        select = new PlainSelect();
        select.setSelectItems(selectItems);
        select.setFromItem(table);
//        functions = new ArrayList<>();
//        selectExpression = new SelectExpression<>(this);
    }

    @Override
    public Select<T> include(String... fieldNames) {
        // 自定义白名单, 不添加默认字段
        addDefaultColumn = false;
        for (String fieldName : fieldNames) {
            selectItems.add(new SelectExpressionItem(getColumnExpression(fieldName)));
        }
        return this;
    }

    @Override
    public Select<T> exclude(String... fieldNames) {
        if (excludes == null) {
            excludes = new ArrayList<>(fieldNames.length);
        }
        excludes.addAll(Arrays.asList(fieldNames));
        return this;
    }

    @Override
    public Select<T> addField(Class<?> tableClass, String... fieldNames) {
        initJoin();
        if (joinTableInfos.get(tableClass) == null) {
            TableInfo tableInfo = new TableInfo(tableClass);
            joinTableInfos.put(tableClass, tableInfo);
            joinTables.put(tableClass, new Table(tableInfo.getTableName()));
        }

        for (String fieldName : fieldNames) {
            selectItems.add(new SelectExpressionItem(getColumnExpression(tableClass, fieldName)));
        }
        return this;
    }

    @Override
    public Select<T> function(String funcName) {
        function(funcName, false);
        return this;
    }

    @Override
    public Select<T> function(String funcName, String... fieldNames) {
        function(funcName, false, fieldNames);
        return this;
    }

    @Override
    public Select<T> function(String funcName, boolean distinct, String... fieldNames) {
        // 自定义函数时, 不添加默认字段
        addDefaultColumn = false;

        Function function = new Function();

        boolean noFuncColumns = fieldNames == null || fieldNames.length == 0 || fieldNames[0] == null;
        if (noFuncColumns) {
            function.setAllColumns(true);
        } else {
            List<Expression> funcColumns = new ArrayList<>(fieldNames.length);
            for (String fieldName : fieldNames) {
                funcColumns.add(getColumnExpression(fieldName));
            }
            function.setParameters(new ExpressionList(funcColumns));
        }

        function.setName(funcName);
        function.setDistinct(distinct);

        selectItems.add(new SelectExpressionItem(function));
        return this;
    }


    @Override
    public Select<T> alias(String... alias) {
        int size = selectItems.size();
        int start = size - alias.length;
        for (int i = start; i < size; i++) {
            ((SelectExpressionItem) selectItems.get(i)).setAlias(new Alias(alias[i - start]));
        }
        return this;
    }

    @Override
    public Select<T> tableAlias(String alias) {
        if (joins == null || joins.size() == 0) {
            table.setAlias(new Alias(alias));
        } else {
            Join join = getLastJoin();
            join.getRightItem().setAlias(new Alias(alias));
        }
        return this;
    }

    private JoinExpression<T> joinExpression;

    @Override
    public JoinExpression<T> join(Class<?> tableClass) {

        join(tableClass, false);
        return joinExpression;
    }

    protected void on(Class<?> tableClass, String fieldName) {
        getLastJoin().setOnExpression(getColumnExpression(tableClass, fieldName));
    }

    @Override
    public JoinExpression<T> join(JoinType joinType, Class<?> tableClass) {
        join(tableClass, false);

        Join join = getLastJoin();
        switch (joinType) {
            case RIGHT:
                join.setRight(true);
                break;
            case LEFT:
                join.setLeft(true);
                break;
            case FULL:
                join.setFull(true);
                break;
            case NATURAL:
                join.setNatural(true);
                break;
            case CROSS:
                join.setCross(true);
                break;
            case INNER:
                join.setInner(true);
                break;
            case OUTER:
                join.setOuter(true);
                break;
            case SEMI:
                join.setSemi(true);
                break;
        }

        return joinExpression;
    }

    @Override
    public SelectExpression<T> and(Class<?> tableClass, String fieldName) {
        where.append(" and ").append(getColumnExpression(tableClass, fieldName));
        return selectExpression;
    }

    @Override
    public SelectExpression<T> or(Class<?> tableClass, String fieldName) {
        where.append(" or ").append(getColumnExpression(tableClass, fieldName));
        return selectExpression;
    }

    @Override
    public Select<T> simpleJoin(Class<?> tableClass) {
        join(tableClass, true);
        return this;
    }

    private void join(Class<?> tableClass, boolean simple) {
        initJoin();

        if (joinTableInfos.get(tableClass) == null) {
            TableInfo tableInfo = new TableInfo(tableClass);
            joinTableInfos.put(tableClass, tableInfo);
            joinTables.put(tableClass, new Table(tableInfo.getTableName()));
        }

        Join join = new Join();
        join.setRightItem(joinTables.get(tableClass));
        join.setSimple(simple);
        joins.add(join);
    }

    private void initJoin() {
        if (joins == null) {
            joins = new ArrayList<>();
            joinTableInfos = new HashMap<>();
            joinTables = new HashMap<>();
        }
    }

//    @Override
//    public Select<T> on(Class<?> table1, String field1, Logic logic, Class<?> table2, String field2) {
//        Join join = getLastJoin();
//        String condition = getColumnExpression(table1, field1) + logic.getCode() + getColumnExpression(table2, field2);
//        join.setOnExpression(new HexValue(condition));
//        return this;
//    }


    private String getColumnName(Class<?> clazz, String fieldName) {
        if (clazz == null) {
            return fieldName;
        }
        if (Objects.equals(clazz, entityClass)) {
            return tableInfo.getTableNameOrAlias() + "." + getColumnName(fieldName);
        }
        TableInfo joinTableInfo = joinTableInfos.get(clazz);
        if (joinTableInfo == null) {
            return fieldName;
        }
        String column = joinTableInfo.getFieldColumnMap().get(fieldName);
        return column == null || column.isEmpty() ? fieldName : joinTableInfo.getTableNameOrAlias() + "." + column;
    }

    private Column getColumnExpression(Class<?> clazz, String fieldName) {
        if (clazz == null) {
            return new Column(fieldName);
        }
        if (Objects.equals(clazz, entityClass)) {
            return getColumnExpression(fieldName);
        }
        Table table = joinTables.get(clazz);
        if (table == null) {
            return new Column(fieldName);
        }
        String column = joinTableInfos.get(clazz).getFieldColumnMap().get(fieldName);
        return column == null || column.isEmpty() ? new Column(fieldName) : new Column(table, column);
    }

//    private Column getColumnExpression(Class<?> clazz, String field) {
//        if (Objects.equals(clazz, entityClass)) {
//            return getColumnExpression(field);
//        }
//        String column = joinTableInfos.get(clazz).getFieldColumnMap().get(field);
//        return column == null || column.isEmpty() ? new Column(field) : new Column( column);
//    }

    private Join getLastJoin() {
        return joins.get(joins.size() - 1);
    }

    private Operation lastOperation;

    @Override
    protected SelectExpression<T> getExpression() {
        if (selectExpression == null) {
            selectExpression = new SelectExpression<>(this);
        }
        return selectExpression;
    }

    @Override
    public void addParamValue(Logic logic, Object... values) {

        if (lastOperation == Operation.JOIN) {
            throw new UnsupportedOperationException("join on expression do not support this method!");
        }

        super.addParamValue(logic, values);
    }


    public void setExpression(Logic logic, Class<?> clazz, String fieldName) {

        if (lastOperation == Operation.JOIN) {
            setJoinOnExpression(logic, clazz, fieldName);
            return;
        }

        Column columnExpression = getColumnExpression(clazz, fieldName);
        where.append(logic.getCode()).append(columnExpression.toString());
    }

    private void setJoinOnExpression(Logic logic, Class<?> clazz, String fieldName) {
        Join join = getLastJoin();
        Expression left = join.getOnExpression();

        BinaryExpression binaryExpression = null;
        switch (logic) {
            case EQ:
                binaryExpression = new EqualsTo();
                break;
            case NOT_EQ:
                binaryExpression = new EqualsTo();
                binaryExpression.setNot();
                break;
            case GT:
                binaryExpression = new GreaterThan();
                break;
        }
        if (binaryExpression == null) {
            throw new UnsupportedOperationException("join on expression do not support this logic" + logic.getCode());
        }
        binaryExpression.setLeftExpression(left);
        binaryExpression.setRightExpression(getColumnExpression(clazz, fieldName));
        join.setOnExpression(binaryExpression);
    }

    //    @Override
//    public Select<T> joinCondition(Class<?> table1, String field1, Logic logic, Class<?> table2, String field2) {
//        String condition = getColumnExpression(table1, field1) + logic.getCode() + getColumnExpression(table2, field2);
//        where.append(condition);
//        return this;
//    }

//    @Override
//    public Select<T> and(Class<?> table, String field, Logic logic, Object... values) {
//        String expression = getExpression(getColumnExpression(table, field), logic, values);
//        and();
//        where.append(expression);
//        return this;
//    }

//    @Override
//    public Select<T> or(Class<?> table, String field, Logic logic, Object... values) {
//        String expression = getExpression(getColumnExpression(table, field), logic, values);
//        or();
//        where.append(expression);
//        return this;
//    }

    @Override
    public Select<T> groupBy(String... fields) {
        if (groupBy == null) {
            groupBy = new ArrayList<>(fields.length);
            having = new StringBuilder();
        }
        for (String field : fields) {
            groupBy.add(getColumnExpression(field));
        }
        return this;
    }

    @Override
    public SelectExpression<T> having(String fieldName) {
        return selectExpression;
    }

//    @Override
//    public com.github.lit.jdbc.statement.select.Select<T> having(String fieldName, Object value) {
//        return this.having(fieldName, Logic.EQ, value);
//    }
//
//    @Override
//    public com.github.lit.jdbc.statement.select.Select<T> having(String fieldName, Logic logic, Object... values) {
//        String expression = getExpression(getColumnExpression(fieldName), logic, values);
//        having.append(expression);
//        return this;
//    }

    @Override
    public Select<T> asc(String... fieldNames) {
        return order(true, fieldNames);
    }

    @Override
    public Select<T> desc(String... fieldNames) {
        return order(false, fieldNames);
    }

    private Select<T> order(boolean asc, String... fieldNames) {
        if (orderBy == null) {
            orderBy = new ArrayList<>();
        }

        for (String fieldName : fieldNames) {
            OrderByElement element = new OrderByElement();
            element.setExpression(getColumnExpression(fieldName));
            element.setAsc(asc);
            orderBy.add(element);
        }
        return this;
    }

    @Override
    public int count() {
        processSelect();
        select.setOrderByElements(null);
        select.setSelectItems(COUNT_FUNC_ITEM);
        int count = (int) executor.execute(new StatementContext(select.toString(), params, StatementContext.Type.SELECT_SINGLE, int.class));
        select.setOrderByElements(orderBy);
        select.setSelectItems(selectItems);
        return count;
    }

    @Override
    public T single() {
        return single(entityClass);
    }

    @Override
    public <E> E single(Class<E> clazz) {
        processSelect();
        String sql;
        if (pageNum != null && pageSize != null) {
            sql = pageHandler.getPageSql(dbName, select.toString(), pageSize, pageNum);
        } else {
            sql = select.toString();
        }
        return (E) executor.execute(new StatementContext(entityClass, sql, params, StatementContext.Type.SELECT_SINGLE, clazz));
    }

    @Override
    public List<T> list() {
        return list(entityClass);
    }

    @Override
    public <E> List<E> list(Class<E> clazz) {

        if (pageNum != null && pageSize != null) {
            PageList<E> result;
            if (queryCount) {
                int totalRecord = count();
                result = new PageList<>(pageSize, pageNum, totalRecord);
                if (Objects.equals(totalRecord, 0)) {
                    return result;
                }
            } else {
                processSelect();
                result = new PageList<>(pageSize);
            }

            String pageSql = pageHandler.getPageSql(dbName, select.toString(), pageSize, pageNum);
            List execute = (List) executor.execute(new StatementContext(entityClass, pageSql, params, StatementContext.Type.SELECT_LIST, clazz));

            result.addAll(execute);

            return result;
        }

        processSelect();
        return (List<E>) executor.execute(new StatementContext(entityClass, select.toString(), params, StatementContext.Type.SELECT_LIST, clazz));
    }

    @Override
    public Select<T> page(Page pager) {
        return page(pager.getPageNum(), pager.getPageSize(), pager.isCount());
    }

    @Override
    public Select<T> page(int pageNum, int pageSize) {
        return page(pageNum, pageSize, true);
    }

    @Override
    public Select<T> page(int pageNum, int pageSize, boolean queryCont) {
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.queryCount = queryCont;
        return this;
    }

    private boolean processed;

    private void processSelect() {

        if (processed) {
            return;
        }

        if (addDefaultColumn) {

            Map<String, String> fieldColumnMap = tableInfo.getFieldColumnMap();
            if (excludes != null) {
                for (String field : excludes) {
                    fieldColumnMap.remove(field);
                }
            }
            for (String column : fieldColumnMap.values()) {
                selectItems.add(new SelectExpressionItem(new Column(table, column)));
            }
        }

        if (joins != null && joins.size() > 0) {
            select.setJoins(joins);
        }
        if (where != null && where.length() > 0) {
            select.setWhere(new HexValue(where.toString()));
        }
//        if (groupBy != null && groupBy.size() > 0) {
//            select.setGroupByColumnReferences(groupBy);
//        }
//        if (having != null && having.length() > 0) {
//            select.setHaving(new HexValue(having.toString()));
//        }
        if (orderBy != null && orderBy.size() > 0) {
            select.setOrderByElements(orderBy);
        }
        processed = true;
    }


    enum Operation {

        INCLUDE,

        EXCLUDE,

        ADDITIONAL_FIELD,

        FUNCTION,

        JOIN,

        WHERE,;


    }

}