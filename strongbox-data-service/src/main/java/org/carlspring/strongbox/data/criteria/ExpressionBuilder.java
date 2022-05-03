package org.carlspring.strongbox.data.criteria;

import org.carlspring.strongbox.data.domain.DomainObject;

/**
 * @author sbespalov
 *
 */
public abstract class ExpressionBuilder<E extends ExpressionBuilder<E, T>, T extends DomainObject>
{
    private Class<T> targetClass;

    private ExpressionDialect dialect;

    private Expression expression = new Expression();

    public ExpressionBuilder(Class<T> targetClass,
                             ExpressionDialect dialect)
    {
        super();
        this.targetClass = targetClass;
        this.dialect = dialect;
    }

    public ExpressionBuilder(Class<T> targetClass)
    {
        this(targetClass, new DefaultExpressionDialect());
    }

    public E of(String attribute)
    {
        expression.setProperty(dialect.parseProperty(attribute));
        return (E) this;
    }

    public E using(String operator)
    {
        expression.setOperator(dialect.parseOperator(operator));
        return (E) this;
    }

    public E with(String value)
    {
        expression.setValue(dialect.parseValue(value));
        return (E) this;
    }

    public Expression build()
    {
        return expression;
    }

}