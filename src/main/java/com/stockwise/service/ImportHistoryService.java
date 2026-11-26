package com.stockwise.service;

import com.stockwise.database.Database;
import com.stockwise.importer.ImportType;
import com.stockwise.repository.ImportacionRepository;
import com.stockwise.repository.ImportacionRepository.Importacion;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Historial de importaciones y eliminación segura. Al eliminar una importación solo se borran las ventas,
 * devoluciones y órdenes de compra que pertenecen exclusivamente a ella; los registros que también contiene otra
 * importación activa se conservan (y pasan a pertenecer a esa otra), y nunca se borran registros creados
 * manualmente o importados antes de existir el historial.
 */
public class ImportHistoryService {

    private static final Logger LOG = Logger.getLogger(ImportHistoryService.class.getName());
    private static final List<String> TABLAS = List.of("ventas", "devoluciones", "compras");

    /** Qué ocurrirá (u ocurrió) al eliminar una importación. */
    public record Impacto(Importacion importacion, int ventasEliminar, int ventasConservar, int devolucionesEliminar,
                          int devolucionesConservar, int comprasEliminar, int comprasConservar,
                          int comprasRecibidasEnSistema, long registrosAjenos, List<String> mensajes) {

        public int totalEliminar() {
            return ventasEliminar + devolucionesEliminar + comprasEliminar;
        }
    }

    private final Database db;
    private final ImportacionRepository repo;

    public ImportHistoryService(Database db, ImportacionRepository repo) {
        this.db = db;
        this.repo = repo;
    }

    public List<Importacion> listar() {
        return repo.listar();
    }

    /** Ventas registradas que no pertenecen a ninguna importación (anteriores al historial o manuales). */
    public long ventasSinHistorial() {
        return repo.sinImportacion("ventas");
    }

    /** Compras de esta lista que ya se recibieron en STOCKWISE (tienen movimiento de inventario): se conservan. */
    private List<Long> recibidasEnSistema(List<Long> compras) {
        if (compras.isEmpty()) {
            return List.of();
        }
        String ids = compras.stream().map(String::valueOf).collect(Collectors.joining(","));
        return db.query("SELECT c.id FROM compras c WHERE c.id IN (" + ids + ") AND EXISTS (SELECT 1 FROM "
                        + "movimientos_inventario m WHERE m.tipo = 'COMPRA' AND (m.nota = 'Compra #' || c.id OR m.nota LIKE "
                        + "'Compra #' || c.id || ' %'))", rs -> rs.getLong(1));
    }

    public Impacto impacto(long importacionId) {
        Importacion imp = repo.findById(importacionId)
                .orElseThrow(() -> new ValidationException("La importación no existe."));
        if (ImportacionRepository.ELIMINADA.equals(imp.estado())) {
            throw new ValidationException("La importación ya fue eliminada.");
        }
        List<Long> v = repo.exclusivos(importacionId, "ventas");
        List<Long> dv = repo.exclusivos(importacionId, "devoluciones");
        List<Long> c = new ArrayList<>(repo.exclusivos(importacionId, "compras"));
        List<Long> recibidas = recibidasEnSistema(c);
        c.removeAll(recibidas);
        long ajenos = TABLAS.stream().mapToLong(t -> repo.ajenos(importacionId, t)).sum();
        List<String> msg = new ArrayList<>();
        ImportType tipo = ImportType.valueOf(imp.tipo());
        switch (tipo) {
            case VENTAS, COMPRAS -> {
                msg.add("Se eliminarán " + (v.size() + dv.size() + c.size()) + " registro(s) que solo pertenecen a "
                        + "esta importación.");
                int compartidos = repo.compartidos(importacionId, "ventas").size()
                        + repo.compartidos(importacionId, "devoluciones").size()
                        + repo.compartidos(importacionId, "compras").size();
                if (compartidos > 0) {
                    msg.add(compartidos + " registro(s) también están en otras importaciones y se conservarán.");
                }
                if (ajenos > 0) {
                    msg.add(ajenos + " registro(s) ya existían antes (registro manual o datos anteriores) y se "
                            + "conservarán.");
                }
                if (!recibidas.isEmpty()) {
                    msg.add(recibidas.size() + " orden(es) de compra ya se recibieron en STOCKWISE (movieron el "
                            + "inventario) y se conservarán.");
                }
                msg.add("Los análisis guardados no se modifican; vuelva a analizar para actualizar las predicciones.");
            }
            default -> msg.add("Los datos de " + tipo.etiqueta().toLowerCase() + " son datos maestros o fotografías "
                    + "de stock: se quita la importación del historial pero los datos se conservan (puede editarlos "
                    + "desde su módulo).");
        }
        return new Impacto(imp, v.size(), repo.compartidos(importacionId, "ventas").size(), dv.size(),
                repo.compartidos(importacionId, "devoluciones").size(), c.size(),
                repo.compartidos(importacionId, "compras").size(), recibidas.size(), ajenos, msg);
    }

    /** Elimina la importación según {@link #impacto(long)} en una sola transacción. */
    public Impacto eliminar(long importacionId) {
        Impacto imp = impacto(importacionId);
        db.inTransaction(() -> {
            List<Long> v = repo.exclusivos(importacionId, "ventas");
            List<Long> dv = repo.exclusivos(importacionId, "devoluciones");
            List<Long> c = new ArrayList<>(repo.exclusivos(importacionId, "compras"));
            c.removeAll(recibidasEnSistema(c));
            repo.transferirPropiedad(importacionId);
            borrar("ventas", v);
            borrar("devoluciones", dv);
            borrar("compras", c);
            repo.marcarEliminada(importacionId);
        });
        LOG.info("Importación #" + importacionId + " eliminada: " + imp.totalEliminar() + " registro(s) borrados.");
        return imp;
    }

    private void borrar(String tabla, List<Long> ids) {
        for (int i = 0; i < ids.size(); i += 500) {
            String lista = ids.subList(i, Math.min(ids.size(), i + 500)).stream().map(String::valueOf)
                    .collect(Collectors.joining(","));
            db.update("DELETE FROM " + tabla + " WHERE id IN (" + lista + ")");
        }
    }
}
