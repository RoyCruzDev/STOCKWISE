package com.stockwise.service;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Compra;
import com.stockwise.model.Producto;
import com.stockwise.model.Proveedor;
import com.stockwise.model.TipoMovimiento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Órdenes en tránsito, recepción, cancelación, presupuesto y ciclos/tiempos de entrega de proveedores. */
class PurchaseOrderFlowTest {

    @TempDir
    Path dir;
    AppContext ctx;
    long principal;
    long a1;

    @BeforeEach
    void setUp() {
        ctx = new AppContext(dir.resolve("oc.db"));
        principal = ctx.sucursales().listar().get(0).id();
        a1 = ctx.productos().guardar(new Producto(0, "A1", "Prod A1", null, null, null, null, 10, 5, 0, 0, 10, true),
                "Cat", "Prov Y");
        ctx.inventario().fijarStock(a1, principal, 10, TipoMovimiento.AJUSTE, "inicial");
        ctx.presupuestos().guardar(YearMonth.now().toString(), null, 1000, "test");
    }

    private Compra unica() {
        return ctx.compras().recientes(10).get(0);
    }

    @Test
    void pedidoPendienteNoModificaStockPeroCuentaComoTransitoYPresupuesto() {
        ctx.compras().registrarPedido(List.of(new CompraService.LineaCompra(a1, principal, 20, 5)), true, null, 15);
        assertEquals(10, ctx.inventario().stock(a1, principal));
        assertEquals(20, ctx.compras().enTransito().get(a1));
        assertEquals(1, ctx.compras().pendientes().size());
        Compra c = unica();
        assertTrue(c.pendiente());
        assertEquals(LocalDate.now().plusDays(10), c.fechaEsperada(), "Fecha esperada = hoy + tiempo de reposición");
        assertEquals(900, ctx.presupuestos().estadoActual().disponible(), 1e-9);
        AnalysisSnapshot s = ctx.analysis().snapshot();
        assertEquals(20, s.suministro(a1).enTransito());
        assertTrue(s.notas().stream().anyMatch(n -> n.contains("tránsito")));
    }

    @Test
    void recibirIngresaLaMercaderiaUnaSolaVez() {
        ctx.compras().registrarPedido(List.of(new CompraService.LineaCompra(a1, principal, 20, 5)), true, null, 15);
        long id = unica().id();
        ctx.compras().recibir(id, 18);
        assertEquals(28, ctx.inventario().stock(a1, principal));
        Compra c = unica();
        assertEquals(Compra.RECIBIDA, c.estado());
        assertEquals(18, c.cantidad());
        assertEquals(LocalDate.now(), c.fechaRecepcion());
        assertTrue(ctx.compras().enTransito().isEmpty());
        assertEquals(TipoMovimiento.COMPRA, ctx.inventario().movimientos(a1, null, 1).get(0).tipo());
        assertThrows(ValidationException.class, () -> ctx.compras().recibir(id, null));
        assertThrows(ValidationException.class, () -> ctx.compras().cancelar(id));
    }

    @Test
    void cancelarLiberaTransitoYPresupuesto() {
        ctx.compras().registrarPedido(List.of(new CompraService.LineaCompra(a1, principal, 20, 5)), true, null, 15);
        ctx.compras().cancelar(unica().id());
        assertTrue(ctx.compras().enTransito().isEmpty());
        assertEquals(1000, ctx.presupuestos().estadoActual().disponible(), 1e-9);
        assertEquals(10, ctx.inventario().stock(a1, principal));
    }

    @Test
    void validaPedidos() {
        assertThrows(ValidationException.class, () -> ctx.compras().registrarPedido(
                List.of(new CompraService.LineaCompra(a1, principal, 500, 5)), true, null, 15), "Supera presupuesto");
        assertThrows(ValidationException.class, () -> ctx.compras().registrarPedido(
                List.of(new CompraService.LineaCompra(a1, principal, 1, 5)), true, LocalDate.now().minusDays(1), 15));
        // La compra inmediata conserva su comportamiento original.
        ctx.compras().registrar(List.of(new CompraService.LineaCompra(a1, principal, 4, 5)), true);
        assertEquals(14, ctx.inventario().stock(a1, principal));
        assertEquals(Compra.RECIBIDA, unica().estado());
    }

    @Test
    void cicloYTiempoDeEntregaDelProveedor() {
        long b = ctx.productos().guardar(new Producto(0, "B1", "Prod B1", null, null, null, null, 10, 5, 0, 0, null, true),
                "Cat", "Prov Y");
        Proveedor y = ctx.compras().proveedores().get(0);
        assertThrows(ValidationException.class, () -> ctx.compras().guardarProveedor(new Proveedor(y.id(), y.nombre(),
                null, null, null, 10, 0)));
        ctx.compras().guardarProveedor(new Proveedor(y.id(), y.nombre(), null, null, null, 25, 15));
        AnalysisSnapshot s = ctx.analysis().snapshot();
        ProductMetrics mb = s.metrics(b).orElseThrow();
        assertEquals(25, mb.leadTimeDias(), "Sin tiempo propio se usa el del proveedor");
        assertEquals("Proveedor (configurado)", s.suministro(b).origenLeadTime());
        assertEquals(15, s.suministro(b).cicloCompraDias());
        assertEquals(10, s.metrics(a1).orElseThrow().leadTimeDias(), "El tiempo propio del producto tiene prioridad");
    }

    @Test
    void tiempoDeEntregaObservadoConAlMenosTresPedidos() {
        long b = ctx.productos().guardar(new Producto(0, "B1", "Prod B1", null, null, null, null, 10, 5, 0, 0, null, true),
                "Cat", "Prov Z");
        long prov = ctx.compras().proveedores().stream().filter(p -> p.nombre().equals("Prov Z")).findFirst()
                .orElseThrow().id();
        int[] dias = {18, 22, 20};
        for (int i = 0; i < dias.length; i++) {
            LocalDate f = LocalDate.of(2025, 1 + i, 1);
            ctx.database().update("INSERT INTO compras(fecha, producto_id, sucursal_id, proveedor_id, cantidad, "
                    + "costo_unitario, total, estado, fecha_recepcion, origen) VALUES (?,?,?,?,1,5,5,'RECIBIDA',?,"
                    + "'IMPORTACION')", f, b, principal, prov, f.plusDays(dias[i]));
        }
        var obs = ctx.compras().leadTimesObservados().get(prov);
        assertEquals(3, obs.observaciones());
        assertEquals(20, obs.promedioDias(), 1e-9);
        assertEquals(2, obs.desviacionDias(), 1e-9);
        AnalysisSnapshot s = ctx.analysis().snapshot();
        assertEquals(20, s.metrics(b).orElseThrow().leadTimeDias());
        assertEquals(2, s.suministro(b).desviacionLeadTime(), 1e-9);
        assertTrue(s.suministro(b).origenLeadTime().startsWith("Proveedor (observado"));
    }
}
