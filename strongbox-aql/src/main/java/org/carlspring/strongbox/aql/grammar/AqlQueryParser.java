package org.carlspring.strongbox.aql.grammar;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.carlspring.strongbox.aql.grammar.AQLParser.QueryContext;
import org.carlspring.strongbox.data.criteria.QueryParser;
import org.carlspring.strongbox.domain.ArtifactEntity;

/**
 * @author sbespalov
 *
 */
public class AqlQueryParser extends QueryParser<QueryContext, ArtifactEntity, AqlStatementVisitor>
{

    public AqlQueryParser(String query)
    {
        super(createParser(CharStreams.fromString(query)));
    }

    public static Parser createParser(CharStream is)
    {
        AQLLexer lexer = new AQLLexer(is);
        CommonTokenStream commonTokenStream = new CommonTokenStream(lexer);
        return new AQLParser(commonTokenStream);
    }

    @Override
    protected AqlStatementVisitor createTreeVisitor()
    {
        return new AqlStatementVisitor();
    }

    @Override
    protected QueryContext parseQueryTree(Parser parser)
    {
        return ((AQLParser) parser).query();
    }

}
