package io.github.g1335333249.dbwebview;

/** One table filter. Conditions are combined with AND. */
public class FilterCondition {
    public String column;
    public String operator;
    public String value;
    public String value2;

    public FilterCondition() { }
    public FilterCondition(String column, String operator, String value, String value2) {
        this.column = column;
        this.operator = operator;
        this.value = value;
        this.value2 = value2;
    }
}
