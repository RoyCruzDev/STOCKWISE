package com.stockwise.service;

import com.stockwise.database.Database;
import com.stockwise.model.TipoMovimiento;
import com.stockwise.model.Venta;
import com.stockwise.repository.VentaRepository;

import java.time.LocalDate;
import java.util.List;

public class VentaService {

    private final Database db;
    private final VentaRepository ventas;
    private final InventarioService inventario;

    public VentaService(Database db, VentaRepository ventas, InventarioService inventario) {
        this.db = db;
        this.ventas = ventas;
        this.inventario = inventario;
    }

    public List<Venta> buscar(LocalDate desde, LocalDate hasta, Long productoId, Long sucursalId, int limit) {
        return ventas.find(desde, hasta, productoId, sucursalId, limit);
    }

    /**
     * Registra una venta. Si {@code descontarStock} es verdadero, descuenta el inventario de la sucursal
     * (falla si no hay stock suficiente).
     */
    public long registrar(LocalDate fecha, long productoId, long sucursalId, double cantidad, double precio,
                          String referencia, boolean descontarStock) {
        if (fecha == null) {
            throw new ValidationException("La fecha es obligatoria.");
        }
        if (fecha.isAfter(LocalDate.now())) {
            throw new ValidationException("La fecha de venta no puede ser futura.");
        }
        if (cantidad <= 0) {
            throw new ValidationException("La cantidad debe ser mayor que cero.");
        }
        if (precio < 0) {
            throw new ValidationException("El precio no puede ser negativo.");
        }
        if (descontarStock && cantidad != Math.floor(cantidad)) {
            throw new ValidationException("Para descontar inventario la cantidad debe ser un número entero.");
        }
        String h = VentaRepository.huella(fecha, productoId, sucursalId, cantidad, precio, referencia);
        if (ventas.existsHuella(h)) {
            throw new ValidationException("Ya existe una venta idéntica registrada (posible duplicado).");
        }
        return db.inTransaction(() -> {
            long id = ventas.insert(fecha, productoId, sucursalId, cantidad, precio, referencia);
            if (descontarStock) {
                inventario.mover(productoId, sucursalId, -(int) cantidad, TipoMovimiento.VENTA,
                        "Venta #" + id + (referencia == null || referencia.isBlank() ? "" : " (" + referencia + ")"));
            }
            return id;
        });
    }

    /** Elimina el registro de venta (no modifica el inventario). */
    public void eliminar(long id) {
        ventas.delete(id);
    }
}
