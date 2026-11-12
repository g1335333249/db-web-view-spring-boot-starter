package io.github.g1335333249.dbwebview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SingleStatementSqlTest {
    @Test
    void permitsSemicolonsInsideQuotesCommentsAndDollarStrings() {
        assertEquals("SELECT ';' AS value", SingleStatementSql.normalize("SELECT ';' AS value;"));
        assertEquals("SELECT 1 /* ; */", SingleStatementSql.normalize("SELECT 1 /* ; */;"));
        assertEquals("DO $$ BEGIN PERFORM 1; END $$", SingleStatementSql.normalize("DO $$ BEGIN PERFORM 1; END $$;"));
    }

    @Test
    void rejectsAdditionalStatements() {
        assertThrows(IllegalArgumentException.class, () -> SingleStatementSql.normalize("SELECT 1; SELECT 2"));
        assertThrows(IllegalArgumentException.class, () -> SingleStatementSql.normalize("SELECT 1; 'other'"));
        assertThrows(IllegalArgumentException.class, () -> SingleStatementSql.normalize("SELECT 1;;"));
        assertThrows(IllegalArgumentException.class, () -> SingleStatementSql.normalize("SELECT 'unterminated"));
    }
}
