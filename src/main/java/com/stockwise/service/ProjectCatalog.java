package com.stockwise.service;

import com.stockwise.database.Database;
import com.stockwise.importer.ColumnMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Catálogo de proyectos (espacios de análisis independientes). Cada proyecto tiene su propia base SQLite con sus
 * productos, ventas, compras, inventario, configuración, importaciones y análisis: los datos de dos proyectos no
 * pueden mezclarse porque nunca comparten archivo. El catálogo solo guarda el nombre y la ubicación de cada base.
 */
public class ProjectCatalog {

    private static final Logger LOG = Logger.getLogger(ProjectCatalog.class.getName());
    public static final String NOMBRE_INICIAL = "Proyecto principal";

    /** Proyecto registrado. {@code ruta} es relativa a la carpeta de datos. */
    public record Proyecto(long id, String nombre, String ruta, String creado, String ultimoUso) {
        @Override
        public String toString() {
            return nombre;
        }
    }

    private final Path dataDir;
    private final Database db;

    public ProjectCatalog(Path dataDir) {
        this.dataDir = dataDir.toAbsolutePath();
        this.db = new Database(this.dataDir.resolve("proyectos.db"));
        db.update("CREATE TABLE IF NOT EXISTS proyectos (id INTEGER PRIMARY KEY AUTOINCREMENT, nombre TEXT NOT NULL "
                + "UNIQUE COLLATE NOCASE, ruta TEXT NOT NULL UNIQUE, creado TEXT NOT NULL, ultimo_uso TEXT)");
        // La base de versiones anteriores (data/stockwise.db) se registra tal cual como primer proyecto.
        if (db.queryLong("SELECT COUNT(*) FROM proyectos") == 0 && Files.isRegularFile(this.dataDir.resolve("stockwise.db"))) {
            db.insert("INSERT INTO proyectos(nombre, ruta, creado) VALUES (?,?,?)", NOMBRE_INICIAL, "stockwise.db",
                    ahora());
        }
    }

    private static String ahora() {
        return LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path rutaBase(Proyecto p) {
        return dataDir.resolve(p.ruta()).normalize();
    }

    public List<Proyecto> listar() {
        return db.query("SELECT * FROM proyectos ORDER BY COALESCE(ultimo_uso, creado) DESC, id DESC",
                rs -> new Proyecto(rs.getLong("id"), rs.getString("nombre"), rs.getString("ruta"),
                        rs.getString("creado"), rs.getString("ultimo_uso")));
    }

    public Optional<Proyecto> buscar(long id) {
        return listar().stream().filter(p -> p.id() == id).findFirst();
    }

    private static String validarNombre(String nombre) {
        String n = nombre == null ? "" : nombre.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) {
            throw new ValidationException("Indique un nombre para el proyecto.");
        }
        if (n.length() > 60) {
            throw new ValidationException("El nombre del proyecto no puede superar 60 caracteres.");
        }
        return n;
    }

    private void verificarUnico(String nombre, long excluirId) {
        if (db.queryLong("SELECT COUNT(*) FROM proyectos WHERE nombre = ? AND id <> ?", nombre, excluirId) > 0) {
            throw new ValidationException("Ya existe un proyecto llamado '" + nombre + "'.");
        }
    }

    /** Crea un proyecto vacío con su propia base de datos. */
    public Proyecto crear(String nombre) {
        String n = validarNombre(nombre);
        verificarUnico(n, -1);
        String slug = ColumnMapper.normalizar(n).replace(' ', '-');
        slug = slug.isEmpty() ? "proyecto" : slug.substring(0, Math.min(30, slug.length()));
        long id = db.insert("INSERT INTO proyectos(nombre, ruta, creado) VALUES (?,?,?)", n,
                "pendiente-" + System.nanoTime(), ahora());
        String ruta = "proyectos/" + id + "-" + slug + "/stockwise.db";
        db.update("UPDATE proyectos SET ruta = ? WHERE id = ?", ruta, id);
        Proyecto p = buscar(id).orElseThrow();
        new AppContext(rutaBase(p)); // crea la base y su esquema
        LOG.info("Proyecto creado: " + n + " (" + ruta + ")");
        return p;
    }

    public void renombrar(long id, String nuevoNombre) {
        buscar(id).orElseThrow(() -> new ValidationException("El proyecto no existe."));
        String n = validarNombre(nuevoNombre);
        verificarUnico(n, id);
        db.update("UPDATE proyectos SET nombre = ? WHERE id = ?", n, id);
    }

    /** Abre el proyecto (crea o migra su esquema si hace falta) y registra el último uso. */
    public AppContext abrir(long id) {
        Proyecto p = buscar(id).orElseThrow(() -> new ValidationException("El proyecto no existe."));
        AppContext ctx = new AppContext(rutaBase(p));
        db.update("UPDATE proyectos SET ultimo_uso = ? WHERE id = ?", ahora(), id);
        return ctx;
    }

    /**
     * Elimina un proyecto y todos sus datos. Exige escribir el nombre exacto como confirmación y antes guarda una
     * copia de su base en backups/proyectos-eliminados.
     *
     * @return ruta de la copia de seguridad
     */
    public Path eliminar(long id, String confirmacion) {
        Proyecto p = buscar(id).orElseThrow(() -> new ValidationException("El proyecto no existe."));
        if (confirmacion == null || !confirmacion.trim().equalsIgnoreCase(p.nombre())) {
            throw new ValidationException("Para eliminar el proyecto escriba exactamente su nombre: " + p.nombre());
        }
        Path base = rutaBase(p);
        Path copia = null;
        try {
            if (Files.isRegularFile(base)) {
                Path dir = dataDir.resolve("backups").resolve("proyectos-eliminados");
                Files.createDirectories(dir);
                String slug = ColumnMapper.normalizar(p.nombre()).replace(' ', '-');
                copia = dir.resolve((slug.isEmpty() ? "proyecto" : slug) + "-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".db");
                Files.copy(base, copia, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(base);
                Files.deleteIfExists(base.resolveSibling(base.getFileName() + "-journal"));
            }
            // Solo se borra la carpeta propia del proyecto (proyectos/<id>-<nombre>), nunca la carpeta de datos.
            Path carpeta = base.getParent();
            if (carpeta != null && carpeta.getParent() != null
                    && carpeta.getParent().equals(dataDir.resolve("proyectos")) && Files.isDirectory(carpeta)) {
                try (Stream<Path> s = Files.walk(carpeta)) {
                    for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(x);
                    }
                }
            }
        } catch (IOException e) {
            throw new ValidationException("No se pudo eliminar el proyecto: " + e.getMessage());
        }
        db.update("DELETE FROM proyectos WHERE id = ?", id);
        LOG.info("Proyecto eliminado: " + p.nombre() + ". Copia de seguridad: " + copia);
        return copia;
    }
}
