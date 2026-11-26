package com.stockwise.service;

import com.stockwise.database.Database;
import com.stockwise.model.InventarioItem;
import com.stockwise.model.Movimiento;
import com.stockwise.model.TipoMovimiento;
import com.stockwise.repository.InventarioRepository;
import com.stockwise.repository.MovimientoRepository;

import java.util.List;

/** Operaciones de inventario. Toda variación de stock queda registrada como movimiento. */
public class InventarioService {

    private final Database db;
    private final InventarioRepository inventario;
    private final MovimientoRepository movimientos;

    public InventarioService(Database db, InventarioRepository inventario, MovimientoRepository movimientos) {
        this.db = db;
        this.inventario = inventario;
        this.movimientos = movimientos;
    }

    public List<InventarioItem> listar(Long sucursalId) {
        return sucursalId == null ? inventario.findAll() : inventario.findBySucursal(sucursalId);
    }

    public List<InventarioItem> porProducto(long productoId) {
        return inventario.findByProducto(productoId);
    }

    public List<Movimiento> movimientos(Long productoId, Long sucursalId, int limit) {
        return movimientos.find(productoId, sucursalId, limit);
    }

    public int stock(long productoId, long sucursalId) {
        return inventario.getStock(productoId, sucursalId);
    }

    public boolean existe(long productoId, long sucursalId) {
        return inventario.exists(productoId, sucursalId);
    }

    /** Fija el stock a un valor (ajuste por conteo físico o importación). */
    public void fijarStock(long productoId, long sucursalId, int nuevoStock, TipoMovimiento tipo, String nota) {
        if (nuevoStock < 0) {
            throw new ValidationException("El stock no puede ser negativo.");
        }
        db.inTransaction(() -> {
            int actual = inventario.getStock(productoId, sucursalId);
            inventario.upsertStock(productoId, sucursalId, nuevoStock);
            movimientos.insert(productoId, sucursalId, tipo, nuevoStock - actual, nuevoStock, nota);
        });
    }

    /**
     * Suma (cantidad positiva) o resta (negativa) unidades al stock.
     *
     * @return stock resultante
     */
    public int mover(long productoId, long sucursalId, int cantidad, TipoMovimiento tipo, String nota) {
        if (cantidad == 0) {
            throw new ValidationException("La cantidad debe ser distinta de cero.");
        }
        return db.inTransaction(() -> {
            int actual = inventario.getStock(productoId, sucursalId);
            int nuevo = actual + cantidad;
            if (nuevo < 0) {
                throw new ValidationException("Stock insuficiente: disponible " + actual + ", solicitado "
                        + Math.abs(cantidad) + ".");
            }
            inventario.upsertStock(productoId, sucursalId, nuevo);
            movimientos.insert(productoId, sucursalId, tipo, cantidad, nuevo, nota);
            return nuevo;
        });
    }

    /** Traslada unidades entre sucursales de forma atómica. */
    public void trasladar(long productoId, long origenId, long destinoId, int cantidad, String nota) {
        if (origenId == destinoId) {
            throw new ValidationException("La sucursal de origen y destino deben ser distintas.");
        }
        if (cantidad <= 0) {
            throw new ValidationException("La cantidad a trasladar debe ser mayor que cero.");
        }
        db.inTransaction(() -> {
            String detalle = nota == null || nota.isBlank() ? "" : " - " + nota.trim();
            mover(productoId, origenId, -cantidad, TipoMovimiento.TRASLADO_SALIDA, "Traslado" + detalle);
            mover(productoId, destinoId, cantidad, TipoMovimiento.TRASLADO_ENTRADA, "Traslado" + detalle);
        });
    }
}
