package com.stockwise.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reutilización de sentencias preparadas dentro de transacciones y caché de páginas de SQLite. */
class DatabaseStatementCacheTest {

    @TempDir
    Path dir;

    private Database base() {
        Database db = new Database(dir.resolve("cache.db"));
        db.update("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER NOT NULL)");
        return db;
    }

    @Test
    void consultaAnidadaConLaMismaSentenciaNoSeInterfiere() {
        Database db = base();
        db.inTransaction(() -> {
            for (int i = 1; i <= 5; i++) {
                db.insert("INSERT INTO t(v) VALUES (?)", i * 10);
            }
            // Mientras se recorre el resultado se ejecuta la misma SQL: debe usar otra sentencia.
            List<Long> suma = db.query("SELECT v FROM t WHERE v >= ? ORDER BY v", rs -> {
                long v = rs.getLong(1);
                return v + db.query("SELECT v FROM t WHERE v >= ? ORDER BY v", r2 -> r2.getLong(1), 40).size();
            }, 30);
            assertEquals(List.of(32L, 42L, 52L), suma);
        });
        assertEquals(5, db.queryLong("SELECT COUNT(*) FROM t"));
    }

    @Test
    void sentenciasReutilizadasNoArrastranParametros() {
        Database db = base();
        db.inTransaction(() -> {
            for (int i = 0; i < 1000; i++) {
                db.insert("INSERT INTO t(v) VALUES (?)", i);
            }
            assertEquals(1, db.query("SELECT v FROM t WHERE v = ?", rs -> rs.getLong(1), 7).size());
            assertEquals(1, db.query("SELECT v FROM t WHERE v = ?", rs -> rs.getLong(1), 999).size());
        });
        assertEquals(499500L, db.queryLong("SELECT SUM(v) FROM t"));
    }

    @Test
    void transaccionFallidaRevierteYLaBaseSigueUsable() {
        Database db = base();
        assertThrows(RuntimeException.class, () -> db.inTransaction(() -> {
            db.insert("INSERT INTO t(v) VALUES (?)", 1);
            db.insert("INSERT INTO t(v) VALUES (?)", (Object) null);
        }));
        assertEquals(0, db.queryLong("SELECT COUNT(*) FROM t"));
        db.inTransaction(() -> db.insert("INSERT INTO t(v) VALUES (?)", 2));
        // Fuera de transacción las sentencias se cierran al terminar cada operación.
        db.insert("INSERT INTO t(v) VALUES (?)", 3);
        assertEquals(5, db.queryLong("SELECT SUM(v) FROM t"));
    }

    @Test
    void cacheDePaginasAmpliada() {
        Database db = base();
        assertEquals(-Database.CACHE_PAGINAS_KIB, db.queryLong("PRAGMA cache_size"));
    }
}
