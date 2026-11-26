package com.stockwise.service;

import com.stockwise.database.Database;
import com.stockwise.database.SchemaInitializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/** Copias de seguridad y restauración de la base de datos SQLite. */
public class BackupService {

    private static final Logger LOG = Logger.getLogger(BackupService.class.getName());
    private final Database db;

    public BackupService(Database db) {
        this.db = db;
    }

    public Path defaultDirectory() {
        return db.getFile().getParent().resolve("backups");
    }

    /** Crea una copia consistente usando VACUUM INTO. */
    public Path crearCopia(Path directorio) {
        try {
            Path dir = directorio == null ? defaultDirectory() : directorio;
            Files.createDirectories(dir);
            String nombre = "stockwise-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + ".db";
            Path destino = dir.resolve(nombre).toAbsolutePath();
            String ruta = destino.toString().replace("'", "''");
            db.withConnection(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("VACUUM INTO '" + ruta + "'");
                }
                return null;
            });
            LOG.info("Copia de seguridad creada: " + destino);
            return destino;
        } catch (IOException e) {
            throw new ValidationException("No se pudo crear la carpeta de copias: " + e.getMessage());
        }
    }

    /** Restaura la base de datos desde una copia. Verifica que el archivo sea una base STOCKWISE válida. */
    public void restaurar(Path copia) {
        if (copia == null || !Files.isRegularFile(copia)) {
            throw new ValidationException("Seleccione un archivo de copia válido.");
        }
        validarCopia(copia);
        // Copia de seguridad automática del estado actual antes de sobrescribir.
        crearCopia(defaultDirectory().resolve("antes-de-restaurar"));
        try {
            Files.copy(copia, db.getFile(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ValidationException("No se pudo restaurar la copia: " + e.getMessage());
        }
        SchemaInitializer.initialize(db);
        LOG.info("Base de datos restaurada desde " + copia);
    }

    private static void validarCopia(Path copia) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copia.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN "
                     + "('productos','ventas','inventario','sucursales')")) {
            if (!rs.next() || rs.getInt(1) < 4) {
                throw new ValidationException("El archivo no es una copia de seguridad de STOCKWISE.");
            }
        } catch (SQLException e) {
            throw new ValidationException("El archivo no es una base de datos válida: " + e.getMessage());
        }
    }
}
