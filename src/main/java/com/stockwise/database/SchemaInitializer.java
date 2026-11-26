package com.stockwise.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/** Crea (o actualiza) automáticamente la estructura de la base de datos. */
public final class SchemaInitializer {

    private static final Logger LOG = Logger.getLogger(SchemaInitializer.class.getName());

    public static final String SUCURSAL_PRINCIPAL = "PRINCIPAL";
    /**
     * Versión del esquema: 1 = original; 2 = planificación avanzada; 3 = historial de importaciones, devoluciones y
     * corridas de análisis; 4 = rangos de predicción y precisión por horizonte; 5 = motivos de errores de importación
     * y estados de las acciones recomendadas.
     */
    public static final int VERSION = 5;

    private SchemaInitializer() {
    }

    public static void initialize(Database db) {
        String script;
        try (InputStream in = SchemaInitializer.class.getResourceAsStream("/db/schema.sql")) {
            if (in == null) {
                throw new DataAccessException("No se encontró el recurso db/schema.sql");
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DataAccessException("No se pudo leer el esquema", e);
        }
        respaldarAntesDeMigrar(db);
        db.inTransaction(() -> {
            db.executeScript(script);
            migrar(db);
            // Sucursal por defecto: permite importar ventas/inventarios sin columna de sucursal.
            if (db.queryLong("SELECT COUNT(*) FROM sucursales") == 0) {
                db.insert("INSERT INTO sucursales(codigo, nombre, ciudad, activa) VALUES (?,?,?,1)",
                        SUCURSAL_PRINCIPAL, "Sucursal principal", "");
            }
            db.update("INSERT OR IGNORE INTO configuracion(clave, valor) VALUES ('db.creada', ?)",
                    LocalDate.now().toString());
            db.update("INSERT INTO configuracion(clave, valor) VALUES ('db.version', ?) "
                    + "ON CONFLICT(clave) DO UPDATE SET valor = excluded.valor", String.valueOf(VERSION));
        });
    }

    /** Versión registrada en la base (0 si no tiene datos de STOCKWISE o es anterior al control de versiones). */
    public static int version(Database db) {
        if (db.queryLong("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='configuracion'") == 0) {
            return 0;
        }
        return db.queryOne("SELECT valor FROM configuracion WHERE clave = 'db.version'", rs -> rs.getString(1))
                .map(v -> {
                    try {
                        return Integer.parseInt(v.trim());
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }).orElse(0);
    }

    /**
     * Copia de seguridad automática del archivo antes de migrar una base con datos de una versión anterior. Se hace
     * con el archivo cerrado (cada operación abre su propia conexión), en la carpeta backups junto a la base.
     */
    static Path respaldarAntesDeMigrar(Database db) {
        Path file = db.getFile();
        try {
            if (!Files.isRegularFile(file) || Files.size(file) == 0) {
                return null;
            }
            boolean conDatos = db.queryLong("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND "
                    + "name='productos'") > 0;
            if (!conDatos || version(db) >= VERSION) {
                return null;
            }
            Path dir = file.getParent().resolve("backups");
            Files.createDirectories(dir);
            Path destino = dir.resolve("pre-migracion-v" + VERSION + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".db");
            Files.copy(file, destino, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Copia de seguridad previa a la migración del esquema: " + destino);
            return destino;
        } catch (IOException e) {
            throw new DataAccessException("No se pudo crear la copia de seguridad previa a la migración: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Migraciones aditivas (idempotentes): agregan columnas nuevas a tablas existentes sin perder datos.
     * Las compras ya registradas se consideran recibidas en su fecha (comportamiento de versiones anteriores).
     */
    static void migrar(Database db) {
        agregarColumna(db, "compras", "estado", "TEXT NOT NULL DEFAULT 'RECIBIDA'");
        agregarColumna(db, "compras", "fecha_esperada", "TEXT");
        agregarColumna(db, "compras", "fecha_recepcion", "TEXT");
        agregarColumna(db, "compras", "documento", "TEXT");
        agregarColumna(db, "compras", "origen", "TEXT NOT NULL DEFAULT 'SISTEMA'");
        agregarColumna(db, "compras", "huella", "TEXT");
        agregarColumna(db, "proveedores", "ciclo_compra_dias", "INTEGER");
        db.update("UPDATE compras SET fecha_recepcion = fecha WHERE estado = 'RECIBIDA' AND fecha_recepcion IS NULL");
        db.update("CREATE UNIQUE INDEX IF NOT EXISTS ux_compras_huella ON compras(huella)");
        db.update("CREATE INDEX IF NOT EXISTS idx_compras_estado ON compras(estado, producto_id)");
        // v3: resultados por producto de cada corrida de análisis (pronósticos, métricas y serie usada).
        String[][] resultados = {
                {"analisis_id", "INTEGER REFERENCES analisis(id) ON DELETE CASCADE"},
                {"unidades30", "REAL"}, {"unidades90", "REAL"}, {"unidades365", "REAL"},
                {"tendencia", "TEXT"}, {"pendiente", "REAL"}, {"perfil", "TEXT"},
                {"p7", "REAL"}, {"p15", "REAL"}, {"p30", "REAL"}, {"p60", "REAL"}, {"p90", "REAL"},
                {"sigma_mensual", "REAL"}, {"confianza", "TEXT"},
                {"mae", "REAL"}, {"rmse", "REAL"}, {"wape", "REAL"}, {"mape", "REAL"}, {"sesgo", "REAL"},
                {"mae_ingenuo", "REAL"}, {"puntos_validacion", "INTEGER"}, {"modelos", "TEXT"},
                {"serie_periodos", "TEXT"}, {"serie_valores", "TEXT"}, {"ajustes", "TEXT"},
                {"pronostico_mensual", "TEXT"}, {"periodos_futuros", "TEXT"},
                {"cobertura_dias", "REAL"}, {"dias_sin_venta", "INTEGER"}, {"estado_inventario", "TEXT"},
                {"lead_time", "INTEGER"}, {"categoria", "TEXT"}, {"advertencias", "TEXT"},
                {"rangos", "TEXT"}, {"metricas_horizonte", "TEXT"}};
        for (String[] c : resultados) {
            agregarColumna(db, "decisiones", c[0], c[1]);
        }
        db.update("CREATE INDEX IF NOT EXISTS idx_decisiones_analisis ON decisiones(analisis_id)");
        // v5: motivos agrupados de los errores de cada importación.
        agregarColumna(db, "importaciones", "detalle", "TEXT");
    }

    static boolean tieneColumna(Database db, String tabla, String columna) {
        return db.query("PRAGMA table_info(" + tabla + ")", rs -> rs.getString("name")).stream()
                .anyMatch(columna::equalsIgnoreCase);
    }

    private static void agregarColumna(Database db, String tabla, String columna, String definicion) {
        if (!tieneColumna(db, tabla, columna)) {
            db.update("ALTER TABLE " + tabla + " ADD COLUMN " + columna + " " + definicion);
        }
    }
}
