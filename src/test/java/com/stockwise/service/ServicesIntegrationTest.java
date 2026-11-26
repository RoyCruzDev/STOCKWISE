package com.stockwise.service;

import com.stockwise.model.Producto;
import com.stockwise.model.Sucursal;
import com.stockwise.model.TipoMovimiento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicesIntegrationTest {

    @TempDir
    Path dir;
    AppContext ctx;
    long principal;

    @BeforeEach
    void setUp() {
        ctx = new AppContext(dir.resolve("test.db"));
        principal = ctx.sucursales().listar().get(0).id();
    }

    private long nuevoProducto(String codigo, double precio, double costo) {
        return ctx.productos().guardar(new Producto(0, codigo, "Prod " + codigo, null, null, null, null, precio, costo,
                5, 100, 10, true), "Categoría X", "Proveedor Y");
    }

    @Test
    void creaLaBaseDeDatosConSucursalPrincipal() {
        assertTrue(Files.exists(dir.resolve("test.db")));
        assertEquals(1, ctx.sucursales().listar().size());
        assertEquals("PRINCIPAL", ctx.sucursales().listar().get(0).codigo());
    }

    @Test
    void guardaYActualizaProductosConCategoriaYProveedor() {
        long id = nuevoProducto("A1", 10, 6);
        Producto p = ctx.productos().buscar(id).orElseThrow();
        assertEquals("Categoría X", p.categoria());
        assertEquals("Proveedor Y", p.proveedor());
        ctx.productos().guardar(new Producto(id, "A1", "Nuevo nombre", null, null, null, null, 12, 6, 5, 100, 10, true),
                "Otra", "");
        Producto act = ctx.productos().buscar(id).orElseThrow();
        assertEquals("Nuevo nombre", act.nombre());
        assertEquals("Otra", act.categoria());
        assertEquals(null, act.proveedor());
        assertEquals(2, ctx.productos().categorias().size());
    }

    @Test
    void rechazaCodigoDuplicadoYDatosInvalidos() {
        nuevoProducto("A1", 10, 6);
        assertThrows(ValidationException.class, () -> nuevoProducto("a1", 10, 6));
        assertThrows(ValidationException.class, () -> ctx.productos().guardar(
                new Producto(0, "B", "B", null, null, null, null, -1, 6, 0, 0, null, true), null, null));
        assertThrows(ValidationException.class, () -> ctx.productos().guardar(
                new Producto(0, "C", "C", null, null, null, null, 1, 1, 10, 5, null, true), null, null));
    }

    @Test
    void ventaDescuentaStockYRegistraMovimiento() {
        long id = nuevoProducto("A1", 10, 6);
        ctx.inventario().fijarStock(id, principal, 20, TipoMovimiento.AJUSTE, "inicial");
        ctx.ventas().registrar(LocalDate.now(), id, principal, 5, 10, "F001", true);
        assertEquals(15, ctx.inventario().stock(id, principal));
        assertEquals(2, ctx.inventario().movimientos(id, null, 10).size());
        assertEquals(TipoMovimiento.VENTA, ctx.inventario().movimientos(id, null, 10).get(0).tipo());
        // Duplicado exacto rechazado
        assertThrows(ValidationException.class,
                () -> ctx.ventas().registrar(LocalDate.now(), id, principal, 5, 10, "F001", true));
    }

    @Test
    void ventaSinStockSuficienteSeRevierteCompleta() {
        long id = nuevoProducto("A1", 10, 6);
        ctx.inventario().fijarStock(id, principal, 3, TipoMovimiento.AJUSTE, null);
        assertThrows(ValidationException.class,
                () -> ctx.ventas().registrar(LocalDate.now(), id, principal, 5, 10, null, true));
        // La transacción revirtió también el registro de la venta.
        assertTrue(ctx.ventas().buscar(null, null, id, null, 10).isEmpty());
        assertEquals(3, ctx.inventario().stock(id, principal));
    }

    @Test
    void trasladoEntreSucursalesEsAtomico() {
        long id = nuevoProducto("A1", 10, 6);
        long otra = ctx.sucursales().guardar(new Sucursal(0, "S2", "Segunda", "", "", true));
        ctx.inventario().fijarStock(id, principal, 10, TipoMovimiento.AJUSTE, null);
        ctx.inventario().trasladar(id, principal, otra, 4, "prueba");
        assertEquals(6, ctx.inventario().stock(id, principal));
        assertEquals(4, ctx.inventario().stock(id, otra));
        assertThrows(ValidationException.class, () -> ctx.inventario().trasladar(id, principal, otra, 50, null));
        assertEquals(6, ctx.inventario().stock(id, principal));
        assertEquals(4, ctx.inventario().stock(id, otra));
        assertThrows(ValidationException.class, () -> ctx.inventario().trasladar(id, otra, otra, 1, null));
    }

    @Test
    void compraRespetaPresupuestoYAumentaStock() {
        long id = nuevoProducto("A1", 10, 6);
        String periodo = YearMonth.now().toString();
        ctx.presupuestos().guardar(periodo, null, 100, "test");
        assertEquals(100, ctx.presupuestos().estadoActual().disponible(), 1e-9);
        ctx.compras().registrar(List.of(new CompraService.LineaCompra(id, principal, 10, 6)), true);
        assertEquals(10, ctx.inventario().stock(id, principal));
        assertEquals(40, ctx.presupuestos().estadoActual().disponible(), 1e-9);
        assertThrows(ValidationException.class, () -> ctx.compras().registrar(
                List.of(new CompraService.LineaCompra(id, principal, 10, 6)), true));
        assertEquals(10, ctx.inventario().stock(id, principal));
    }

    @Test
    void presupuestoGeneralSeUsaSiNoHayPresupuestoDelPeriodo() {
        AppSettings s = ctx.config().get();
        ctx.config().save(new AppSettings(s.empresa(), s.moneda(), s.simboloMoneda(), 5000, s.ventanaPromedioMovil(),
                s.ventanaPonderado(), s.alpha(), s.horizonteMeses(), s.mesesMinimosConfiables(), s.stockMinimoDefecto(),
                s.leadTimeDefectoDias(), s.nivelServicio(), s.mesesSobreinventario(), s.diasSinMovimiento(),
                s.margenMinimo(), s.tema(), s.directorioBackup()));
        assertEquals(5000, ctx.presupuestos().estadoActual().asignado(), 1e-9);
        assertThrows(ValidationException.class, () -> ctx.presupuestos().guardar("2025/13", null, 1, null));
    }

    @Test
    void configuracionInvalidaEsRechazada() {
        AppSettings s = ctx.config().get();
        AppSettings mala = new AppSettings(s.empresa(), s.moneda(), s.simboloMoneda(), s.presupuestoGeneral(), 1,
                s.ventanaPonderado(), 1.5, s.horizonteMeses(), s.mesesMinimosConfiables(), s.stockMinimoDefecto(),
                s.leadTimeDefectoDias(), s.nivelServicio(), s.mesesSobreinventario(), s.diasSinMovimiento(),
                s.margenMinimo(), s.tema(), s.directorioBackup());
        assertThrows(ValidationException.class, () -> ctx.config().save(mala));
    }

    @Test
    void copiaDeSeguridadYRestauracion() throws Exception {
        long id = nuevoProducto("A1", 10, 6);
        Path copia = ctx.backup().crearCopia(dir.resolve("bk"));
        assertTrue(Files.size(copia) > 0);
        ctx.productos().eliminar(id);
        assertTrue(ctx.productos().listar().isEmpty());
        ctx.backup().restaurar(copia);
        assertFalse(ctx.productos().listar().isEmpty());
        assertThrows(ValidationException.class, () -> ctx.backup().restaurar(dir.resolve("no-existe.db")));
    }

    @Test
    void eliminarProductoEliminaEnCascadaSuInventario() {
        long id = nuevoProducto("A1", 10, 6);
        ctx.inventario().fijarStock(id, principal, 10, TipoMovimiento.AJUSTE, null);
        ctx.productos().eliminar(id);
        assertTrue(ctx.inventario().listar(null).isEmpty());
    }
}
