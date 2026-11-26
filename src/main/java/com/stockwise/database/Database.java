package com.stockwise.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Punto único de acceso a la base de datos SQLite local.
 * <p>
 * Cada operación abre su propia conexión salvo que se ejecute dentro de
 * {@link #inTransaction(SqlWork)}, en cuyo caso todas las operaciones del mismo hilo
 * comparten la conexión transaccional (commit/rollback automático).
 */
public class Database {

    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    private final Path file;
    private final ThreadLocal<Connection> txConnection = new ThreadLocal<>();
    /** Sentencias preparadas reutilizables de la transacción en curso (por hilo). */
    private final ThreadLocal<Map<String, Deque<PreparedStatement>>> sentencias = new ThreadLocal<>();
    /** Caché de páginas por conexión (KiB, valor negativo según SQLite): mantiene los índices en memoria al importar. */
    static final int CACHE_PAGINAS_KIB = 32_768;

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public Database(Path file) {
        this.file = file.toAbsolutePath();
        try {
            if (this.file.getParent() != null) {
                Files.createDirectories(this.file.getParent());
            }
            Class.forName("org.sqlite.JDBC");
        } catch (IOException | ClassNotFoundException e) {
            throw new DataAccessException("No se pudo preparar la base de datos en " + this.file, e);
        }
    }

    public Path getFile() {
        return file;
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA cache_size = -" + CACHE_PAGINAS_KIB);
        }
        return c;
    }

    public <T> T withConnection(SqlFunction<T> fn) {
        Connection tx = txConnection.get();
        try {
            if (tx != null) {
                return fn.apply(tx);
            }
            try (Connection c = open()) {
                return fn.apply(c);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Error de base de datos: " + e.getMessage(), e);
        }
    }

    /** Ejecuta un bloque de trabajo en una transacción. Las transacciones anidadas se unen a la externa. */
    public <T> T inTransaction(SqlWork<T> work) {
        if (txConnection.get() != null) {
            try {
                return work.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new DataAccessException(e.getMessage(), e);
            }
        }
        Connection c = null;
        try {
            c = open();
            c.setAutoCommit(false);
            txConnection.set(c);
            sentencias.set(new HashMap<>());
            T result = work.run();
            c.commit();
            return result;
        } catch (Exception e) {
            if (c != null) {
                try {
                    c.rollback();
                } catch (SQLException ex) {
                    LOG.log(Level.WARNING, "Fallo al revertir la transacción", ex);
                }
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new DataAccessException(e.getMessage(), e);
        } finally {
            Map<String, Deque<PreparedStatement>> cache = sentencias.get();
            sentencias.remove();
            if (cache != null) {
                cerrarSentencias(cache);
            }
            txConnection.remove();
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException ex) {
                    LOG.log(Level.WARNING, "Fallo al cerrar la conexión", ex);
                }
            }
        }
    }

    public void inTransaction(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }

    // ------------------------------------------------------------------ helpers JDBC

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof Boolean b) {
                ps.setInt(i + 1, b ? 1 : 0);
            } else if (p instanceof java.time.LocalDate d) {
                ps.setString(i + 1, d.toString());
            } else if (p instanceof Enum<?> en) {
                ps.setString(i + 1, en.name());
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    /**
     * Obtiene una sentencia preparada. Dentro de una transacción se reutilizan las sentencias ya compiladas (las
     * importaciones ejecutan las mismas consultas miles de veces); fuera de ella se prepara una nueva.
     */
    private PreparedStatement preparar(Connection c, String sql, boolean claves) throws SQLException {
        Map<String, Deque<PreparedStatement>> cache = sentencias.get();
        if (cache != null && txConnection.get() == c) {
            Deque<PreparedStatement> libres = cache.get(clave(sql, claves));
            PreparedStatement ps = libres == null ? null : libres.poll();
            if (ps != null) {
                return ps;
            }
        }
        return claves ? c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS) : c.prepareStatement(sql);
    }

    /** Devuelve la sentencia al conjunto reutilizable de la transacción o la cierra si no hay transacción. */
    private void liberar(Connection c, PreparedStatement ps, String sql, boolean claves) throws SQLException {
        Map<String, Deque<PreparedStatement>> cache = sentencias.get();
        if (cache != null && txConnection.get() == c) {
            ps.clearParameters();
            cache.computeIfAbsent(clave(sql, claves), k -> new ArrayDeque<>()).push(ps);
        } else {
            ps.close();
        }
    }

    private static String clave(String sql, boolean claves) {
        return (claves ? "K|" : "Q|") + sql;
    }

    private static void cerrarSentencias(Map<String, Deque<PreparedStatement>> cache) {
        for (Deque<PreparedStatement> d : cache.values()) {
            for (PreparedStatement ps : d) {
                try {
                    ps.close();
                } catch (SQLException ex) {
                    LOG.log(Level.FINE, "Fallo al cerrar una sentencia", ex);
                }
            }
        }
        cache.clear();
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(c -> {
            PreparedStatement ps = preparar(c, sql, false);
            try {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                    return out;
                }
            } finally {
                liberar(c, ps, sql, false);
            }
        });
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(0));
    }

    public double queryDouble(String sql, Object... params) {
        return queryOne(sql, rs -> rs.getDouble(1), params).orElse(0.0);
    }

    public long queryLong(String sql, Object... params) {
        return queryOne(sql, rs -> rs.getLong(1), params).orElse(0L);
    }

    public int update(String sql, Object... params) {
        return withConnection(c -> {
            PreparedStatement ps = preparar(c, sql, false);
            try {
                bind(ps, params);
                return ps.executeUpdate();
            } finally {
                liberar(c, ps, sql, false);
            }
        });
    }

    /** Ejecuta un INSERT y devuelve la clave generada. */
    public long insert(String sql, Object... params) {
        return withConnection(c -> {
            PreparedStatement ps = preparar(c, sql, true);
            try {
                bind(ps, params);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            } finally {
                liberar(c, ps, sql, true);
            }
        });
    }

    public void executeScript(String script) {
        withConnection(c -> {
            try (Statement st = c.createStatement()) {
                for (String stmt : script.split(";\\s*(\\r?\\n|$)")) {
                    String s = stripComments(stmt).trim();
                    if (!s.isEmpty()) {
                        st.execute(s);
                    }
                }
            }
            return null;
        });
    }

    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public static Long getNullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
