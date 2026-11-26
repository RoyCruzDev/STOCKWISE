package com.stockwise.service;

import com.stockwise.model.InventarioItem;
import com.stockwise.model.Sucursal;
import com.stockwise.repository.InventarioRepository;
import com.stockwise.repository.SucursalRepository;
import com.stockwise.repository.VentaRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SucursalService {

    /** Indicadores comparativos de una sucursal. */
    public record RendimientoSucursal(Sucursal sucursal, double ventas, double unidades, double utilidad,
                                      double margen, double valorInventario, long unidadesInventario,
                                      double participacion, Double rotacion) {
    }

    private final SucursalRepository sucursales;
    private final VentaRepository ventas;
    private final InventarioRepository inventario;

    public SucursalService(SucursalRepository sucursales, VentaRepository ventas, InventarioRepository inventario) {
        this.sucursales = sucursales;
        this.ventas = ventas;
        this.inventario = inventario;
    }

    public List<Sucursal> listar() {
        return sucursales.findAll();
    }

    public List<Sucursal> activas() {
        return sucursales.findActivas();
    }

    public long guardar(Sucursal s) {
        if (s.codigo() == null || s.codigo().isBlank()) {
            throw new ValidationException("El código de la sucursal es obligatorio.");
        }
        if (s.nombre() == null || s.nombre().isBlank()) {
            throw new ValidationException("El nombre de la sucursal es obligatorio.");
        }
        Optional<Sucursal> existente = sucursales.findByCodigoOrNombre(s.codigo());
        if (existente.isPresent() && existente.get().codigo().equalsIgnoreCase(s.codigo().trim())
                && existente.get().id() != s.id()) {
            throw new ValidationException("Ya existe una sucursal con el código " + s.codigo().trim() + ".");
        }
        if (s.id() <= 0) {
            return sucursales.insert(s);
        }
        sucursales.update(s);
        return s.id();
    }

    public void eliminar(long id) {
        if (sucursales.findAll().size() <= 1) {
            throw new ValidationException("Debe existir al menos una sucursal.");
        }
        sucursales.delete(id);
    }

    /** Compara el rendimiento de las sucursales en el rango indicado. */
    public List<RendimientoSucursal> comparar(LocalDate desde, LocalDate hasta) {
        List<VentaRepository.Aggregate> agg = ventas.porSucursal(desde, hasta);
        double total = agg.stream().mapToDouble(VentaRepository.Aggregate::importe).sum();
        List<InventarioItem> inv = inventario.findAll();
        long dias = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(desde, hasta) + 1);
        List<RendimientoSucursal> out = new ArrayList<>();
        for (VentaRepository.Aggregate a : agg) {
            Sucursal s = sucursales.findById(a.id()).orElseThrow();
            double valorInv = inv.stream().filter(i -> i.sucursalId() == a.id())
                    .mapToDouble(InventarioItem::valorCosto).sum();
            long uniInv = inv.stream().filter(i -> i.sucursalId() == a.id()).mapToLong(InventarioItem::stock).sum();
            double utilidad = a.utilidad();
            double margen = a.importe() > 0 ? utilidad / a.importe() : 0;
            // Rotación anualizada del inventario a costo: costo de lo vendido (anualizado) / inventario a costo.
            Double rotacion = valorInv > 0 ? (a.costo() * 365.0 / dias) / valorInv : null;
            out.add(new RendimientoSucursal(s, a.importe(), a.unidades(), utilidad, margen, valorInv, uniInv,
                    total > 0 ? a.importe() / total : 0, rotacion));
        }
        return out;
    }
}
