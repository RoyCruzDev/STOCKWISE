package com.stockwise.service;

import com.stockwise.database.Database;
import com.stockwise.model.Compra;
import com.stockwise.model.Producto;
import com.stockwise.model.Proveedor;
import com.stockwise.model.TipoMovimiento;
import com.stockwise.repository.CompraRepository;
import com.stockwise.repository.ProductoRepository;
import com.stockwise.repository.ProveedorRepository;
import com.stockwise.util.Formatters;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Registro de compras y órdenes de compra.
 * <ul>
 *   <li>{@link #registrar}: compra recibida en el momento (incrementa el inventario).</li>
 *   <li>{@link #registrarPedido}: orden pendiente (stock en tránsito; no modifica el inventario hasta
 *       {@link #recibir}).</li>
 * </ul>
 * Ambas consumen el presupuesto del periodo; las órdenes canceladas lo liberan.
 */
public class CompraService {

    /** Línea de una orden de compra. */
    public record LineaCompra(long productoId, long sucursalId, int cantidad, double costoUnitario) {
        public double total() {
            return cantidad * costoUnitario;
        }
    }

    private final Database db;
    private final CompraRepository compras;
    private final ProductoRepository productos;
    private final InventarioService inventario;
    private final PresupuestoService presupuesto;
    private final ProveedorRepository proveedores;

    public CompraService(Database db, CompraRepository compras, ProductoRepository productos,
                         InventarioService inventario, PresupuestoService presupuesto) {
        this(db, compras, productos, inventario, presupuesto, new ProveedorRepository(db));
    }

    public CompraService(Database db, CompraRepository compras, ProductoRepository productos,
                         InventarioService inventario, PresupuestoService presupuesto,
                         ProveedorRepository proveedores) {
        this.db = db;
        this.compras = compras;
        this.productos = productos;
        this.inventario = inventario;
        this.presupuesto = presupuesto;
        this.proveedores = proveedores;
    }

    public List<Compra> recientes(int limit) {
        return compras.findRecientes(limit);
    }

    public List<Compra> pendientes() {
        return compras.findPendientes();
    }

    public Map<Long, Integer> enTransito() {
        return compras.enTransitoPorProducto();
    }

    private double validar(List<LineaCompra> lineas, boolean validarPresupuesto) {
        if (lineas.isEmpty()) {
            throw new ValidationException("La orden de compra no tiene productos.");
        }
        double total = 0;
        for (LineaCompra l : lineas) {
            if (l.cantidad() <= 0) {
                throw new ValidationException("Las cantidades de compra deben ser mayores que cero.");
            }
            if (l.costoUnitario() < 0) {
                throw new ValidationException("El costo unitario no puede ser negativo.");
            }
            total += l.total();
        }
        if (validarPresupuesto) {
            double disponible = presupuesto.estadoActual().disponible();
            if (total > disponible + 0.005) {
                throw new ValidationException("La compra (" + Formatters.money(total) + ") supera el presupuesto "
                        + "disponible (" + Formatters.money(disponible) + ").");
            }
        }
        return total;
    }

    /**
     * Registra una compra recibida en el momento, en una sola transacción.
     *
     * @param validarPresupuesto si es verdadero rechaza la orden cuando supera el presupuesto disponible
     */
    public void registrar(List<LineaCompra> lineas, boolean validarPresupuesto) {
        validar(lineas, validarPresupuesto);
        LocalDate hoy = LocalDate.now();
        db.inTransaction(() -> {
            for (LineaCompra l : lineas) {
                Producto p = producto(l.productoId());
                long id = compras.insert(hoy, l.productoId(), l.sucursalId(), p.proveedorId(), l.cantidad(),
                        l.costoUnitario());
                inventario.mover(l.productoId(), l.sucursalId(), l.cantidad(), TipoMovimiento.COMPRA,
                        "Compra #" + id);
            }
        });
    }

    /**
     * Registra una orden de compra pendiente de recepción (stock en tránsito). La fecha esperada por defecto es
     * hoy + tiempo de reposición del producto (o el indicado).
     *
     * @return identificadores de las líneas creadas
     */
    public List<Long> registrarPedido(List<LineaCompra> lineas, boolean validarPresupuesto, LocalDate fechaEsperada,
                                      int leadTimeDefectoDias) {
        validar(lineas, validarPresupuesto);
        LocalDate hoy = LocalDate.now();
        if (fechaEsperada != null && fechaEsperada.isBefore(hoy)) {
            throw new ValidationException("La fecha esperada de llegada no puede ser anterior a hoy.");
        }
        return db.inTransaction(() -> lineas.stream().map(l -> {
            Producto p = producto(l.productoId());
            int lt = p.leadTimeDias() != null && p.leadTimeDias() > 0 ? p.leadTimeDias() : leadTimeDefectoDias;
            LocalDate esperada = fechaEsperada != null ? fechaEsperada : hoy.plusDays(lt);
            return compras.insert(hoy, l.productoId(), l.sucursalId(), p.proveedorId(), l.cantidad(),
                    l.costoUnitario(), Compra.PENDIENTE, esperada, null, null, Compra.ORIGEN_SISTEMA, null);
        }).toList());
    }

    /**
     * Recibe una orden pendiente: ingresa la mercadería al inventario y registra la fecha real de recepción
     * (alimenta el tiempo de entrega observado del proveedor).
     *
     * @param cantidadRecibida unidades efectivamente recibidas (nulo = la cantidad pedida)
     */
    public void recibir(long compraId, Integer cantidadRecibida) {
        db.inTransaction(() -> {
            Compra c = compras.findById(compraId)
                    .orElseThrow(() -> new ValidationException("La orden de compra no existe."));
            if (!c.pendiente()) {
                throw new ValidationException("La orden #" + compraId + " no está pendiente (estado " + c.estado() + ").");
            }
            int cant = cantidadRecibida == null ? c.cantidad() : cantidadRecibida;
            if (cant <= 0) {
                throw new ValidationException("La cantidad recibida debe ser mayor que cero.");
            }
            LocalDate hoy = LocalDate.now();
            compras.marcarRecibida(compraId, hoy.isBefore(c.fecha()) ? c.fecha() : hoy, cant);
            inventario.mover(c.productoId(), c.sucursalId(), cant, TipoMovimiento.COMPRA,
                    "Compra #" + compraId + " (recepción de orden pendiente)");
        });
    }

    /** Cancela una orden pendiente: deja de contarse como tránsito y libera el presupuesto. */
    public void cancelar(long compraId) {
        Compra c = compras.findById(compraId)
                .orElseThrow(() -> new ValidationException("La orden de compra no existe."));
        if (!c.pendiente()) {
            throw new ValidationException("Solo se pueden cancelar órdenes pendientes.");
        }
        compras.marcarCancelada(compraId);
    }

    private Producto producto(long id) {
        return productos.findById(id).orElseThrow(() -> new ValidationException("Producto inexistente: " + id));
    }

    // ------------------------------------------------------------------ proveedores y ciclos de compra

    public List<Proveedor> proveedores() {
        return proveedores.findAll();
    }

    public Map<Long, CompraRepository.LeadTimeObservado> leadTimesObservados() {
        return compras.leadTimesObservados();
    }

    public Map<Long, LocalDate> ultimoPedidoPorProveedor() {
        return compras.ultimoPedidoPorProveedor();
    }

    /** Valida y guarda el tiempo de reposición y el ciclo de compra de un proveedor (nulos = por defecto). */
    public long guardarProveedor(Proveedor p) {
        if (p.nombre() == null || p.nombre().isBlank()) {
            throw new ValidationException("El nombre del proveedor es obligatorio.");
        }
        if (p.leadTimeDias() != null && (p.leadTimeDias() < 1 || p.leadTimeDias() > 365)) {
            throw new ValidationException("El tiempo de reposición debe estar entre 1 y 365 días.");
        }
        if (p.cicloCompraDias() != null && (p.cicloCompraDias() < 1 || p.cicloCompraDias() > 365)) {
            throw new ValidationException("El ciclo de compra debe estar entre 1 y 365 días.");
        }
        proveedores.findByNombre(p.nombre()).filter(o -> o.id() != p.id()).ifPresent(o -> {
            throw new ValidationException("Ya existe un proveedor con el nombre '" + p.nombre().trim() + "'.");
        });
        if (p.id() > 0) {
            proveedores.update(p);
            return p.id();
        }
        return proveedores.insert(p);
    }
}
