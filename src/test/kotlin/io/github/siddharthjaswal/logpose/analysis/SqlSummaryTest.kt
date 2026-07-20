package io.github.siddharthjaswal.logpose.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The row label for a database event comes from here, so the bar is: never wrong. An
 * unrecognised statement must degrade to "other" with no table rather than guess — a
 * mislabelled row costs more trust than an unlabelled one.
 */
class SqlSummaryTest {

    private fun op(sql: String) = SqlSummary.of(sql).operation
    private fun table(sql: String) = SqlSummary.of(sql).table

    @Test fun `reads the four common statements`() {
        assertEquals(SqlSummary.SELECT, op("SELECT id, name FROM users WHERE id = ?"))
        assertEquals("users", table("SELECT id, name FROM users WHERE id = ?"))

        assertEquals(SqlSummary.INSERT, op("INSERT INTO orders (id) VALUES (?)"))
        assertEquals("orders", table("INSERT INTO orders (id) VALUES (?)"))

        assertEquals(SqlSummary.UPDATE, op("UPDATE riders SET status = ? WHERE id = ?"))
        assertEquals("riders", table("UPDATE riders SET status = ? WHERE id = ?"))

        assertEquals(SqlSummary.DELETE, op("DELETE FROM sessions WHERE expired = 1"))
        assertEquals("sessions", table("DELETE FROM sessions WHERE expired = 1"))
    }

    @Test fun `is case and whitespace insensitive`() {
        assertEquals(SqlSummary.SELECT, op("  select  *\n  from   `users`  "))
        assertEquals("users", table("  select  *\n  from   `users`  "))
    }

    @Test fun `strips quoting and schema qualifiers`() {
        assertEquals("users", table("SELECT * FROM \"users\""))
        assertEquals("users", table("SELECT * FROM [users]"))
        assertEquals("users", table("SELECT * FROM main.users"))
        assertEquals("orders", table("INSERT INTO `main`.`orders` (id) VALUES (?)"))
    }

    @Test fun `handles a table immediately followed by punctuation`() {
        assertEquals("users", table("SELECT * FROM users;"))
        assertEquals("users", table("SELECT * FROM users WHERE id=1"))
        assertEquals("orders", table("INSERT INTO orders(id) VALUES (?)"))
        assertEquals("a", table("SELECT * FROM a, b"))
    }

    @Test fun `transaction and schema statements are labelled, not mislabelled`() {
        assertEquals(SqlSummary.TRANSACTION, op("BEGIN DEFERRED TRANSACTION"))
        assertEquals(SqlSummary.TRANSACTION, op("COMMIT"))
        assertEquals(SqlSummary.TRANSACTION, op("ROLLBACK TO SAVEPOINT x"))
        assertNull(table("COMMIT"))

        assertEquals(SqlSummary.SCHEMA, op("CREATE TABLE users (id INTEGER)"))
        assertEquals("users", table("CREATE TABLE users (id INTEGER)"))
        assertEquals(SqlSummary.SCHEMA, op("PRAGMA journal_mode = WAL"))
    }

    @Test fun `a subquery source yields no single table rather than a wrong one`() {
        assertEquals(SqlSummary.SELECT, op("SELECT * FROM (SELECT id FROM users) t"))
        assertNull(table("SELECT * FROM (SELECT id FROM users) t"))
    }

    @Test fun `unreadable input degrades to other`() {
        assertEquals(SqlSummary.OTHER, op("EXPLAIN QUERY PLAN SELECT 1"))
        assertEquals(SqlSummary.OTHER, op(""))
        assertEquals(SqlSummary.OTHER, op("not sql at all"))
        assertNull(table("not sql at all"))
    }

    @Test fun `a CTE is a select even though its table is unclear`() {
        assertEquals(SqlSummary.SELECT, op("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent"))
        assertNull(table("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent"))
    }
}
