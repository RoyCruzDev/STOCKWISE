package com.stockwise.service;

import com.stockwise.model.Presupuesto;
import com.stockwise.repository.CompraRepository;
import com.stockwise.repository.PresupuestoRepository;

import java.time.YearMonth;
import java.util.List;

/** Gestión del presupuesto de compras y cálculo del saldo disponible. */
public class PresupuestoService {

    /** Estado del presupuesto de un periodo. */
    public record EstadoPresupuesto(String periodo, double asignado, double comprometido, double disponible,
                                    String origen) {
    }

    private final PresupuestoRepository presupuestos;
    private final CompraRepository compras;
    private final ConfigService config;

    public PresupuestoService(PresupuestoRepository presupuestos, CompraRepository compras, ConfigService config) {
        this.presupuestos = presupuestos;
        this.compras = compras;
        this.config = config;
    }

    public List<Presupuesto> listar() {
        return presupuestos.findAll();
    }

    public void guardar(String periodo, Long sucursalId, double monto, String descripcion) {
        try {
            YearMonth.parse(periodo);
        } catch (Exception e) {
            throw new ValidationException("El periodo debe tener el formato AAAA-MM (ej. 2025-03).");
        }
        if (monto < 0) {
            throw new ValidationException("El monto del presupuesto no puede ser negativo.");
        }
        presupuestos.upsert(periodo, sucursalId, monto, descripcion);
    }

    public void eliminar(long id) {
        presupuestos.delete(id);
    }

    /**
     * Presupuesto disponible del periodo: el registrado para ese mes (o, si no existe, el presupuesto general
     * de configuración) menos las compras ya registradas en el mes.
     */
    public EstadoPresupuesto estado(YearMonth periodo) {
        String p = periodo.toString();
        double asignado;
        String origen;
        var registrado = presupuestos.totalPeriodo(p);
        if (registrado.isPresent()) {
            asignado = registrado.get();
            origen = "Presupuesto registrado para " + p;
        } else {
            asignado = config.get().presupuestoGeneral();
            origen = "Presupuesto general de configuración";
        }
        double comprometido = compras.totalEnPeriodo(p);
        return new EstadoPresupuesto(p, asignado, comprometido, Math.max(0, asignado - comprometido), origen);
    }

    public EstadoPresupuesto estadoActual() {
        return estado(YearMonth.now());
    }
}
