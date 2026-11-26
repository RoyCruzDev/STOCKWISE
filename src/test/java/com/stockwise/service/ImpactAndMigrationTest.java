package com.stockwise.service;

import com.stockwise.model.Compra;
import com.stockwise.model.Producto;
import com.stockwise.model.TipoMovimiento;
import com.stockwise.recommendation.DecisionEstrategica;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Evaluación del impacto real de decisiones y migración de bases de datos de versiones anteriores. */
class ImpactAndMigrationTest {

    @TempDir
    Path dir;

    @Test
    void comparaLaDecisionGuardadaConLasVentasPosteriores() {
        AppContext ctx = new AppContext(dir.resolve("impacto.db"));
        long suc = ctx.sucursales().listar().get(0).id();
        long id = ctx.productos().guardar(new Producto(0, "A1", "Prod A1", null, null, null, null, 10, 6, 0, 0, 15, true),
                "Cat", "Prov");
        ctx.inventario().fijarStock(id, suc, 50, TipoMovimiento.AJUSTE, "inicial");
        LocalDate corte = LocalDate.of(2025, 6, 30);
        for (int i = 0; i < 12; i++) {
            ctx.ventas().registrar(corte.minusMonths(i).withDayOfMonth(15), id, suc, 20, 10, "V" + i, false);
        }
        ctx.ventas().registrar(corte, id, suc, 1, 10, "CIERRE", false);
        PlanningService.Analisis a = ctx.planificacion().generarYGuardar();
        assertEquals(corte, a.snapshot().fechaCorte());
        // Sin compras ni entradas registradas la disponibilidad no es medible: se informa como pendiente.
        assertTrue(a.notas().contains(StockHistoryService.SIN_ENTRADAS));
        assertTrue(a.decisiones().stream().allMatch(d -> d.disponibilidad() == null));
        assertTrue(ctx.scorecard().calcular().componentes().stream()
                .filter(c -> c.nombre().startsWith("Disponibilidad")).allMatch(c -> c.puntaje() == null));

        // Sin datos nuevos: pendiente.
        assertTrue(ctx.planificacion().evaluarImpacto().mensaje().startsWith("Pendiente"));

        // Llegan 41 días de ventas nuevas.
        ctx.ventas().registrar(LocalDate.of(2025, 7, 15), id, suc, 10, 10, "N1", false);
        ctx.ventas().registrar(LocalDate.of(2025, 8, 10), id, suc, 5, 10, "N2", false);
        PlanningService.EvaluacionImpacto ev = ctx.planificacion().evaluarImpacto();
        assertEquals(corte, ev.fechaCorteDecisiones());
        assertEquals(1, ev.impactos().size());
        PlanningService.Impacto imp = ev.impactos().get(0);
        assertEquals(41, imp.diasEvaluados());
        assertEquals(15, imp.unidadesReales(), 1e-9);
        assertEquals(15 * 10 - 15 * 6, imp.margenReal(), 1e-9);
        assertTrue(imp.demandaEsperada() > 0);
        assertNull(imp.roiReal(), "Sin compras en el periodo no hay ROI real (no se inventa)");
        assertTrue(imp.decision().decision() != DecisionEstrategica.DATOS_INSUFICIENTES);
        assertTrue(!imp.resultado().isBlank());
    }

    @Test
    void migraUnaBaseDeVersionAnteriorConservandoLosDatos() throws Exception {
        Path db = dir.resolve("antigua.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE sucursales (id INTEGER PRIMARY KEY AUTOINCREMENT, codigo TEXT NOT NULL UNIQUE "
                    + "COLLATE NOCASE, nombre TEXT NOT NULL, ciudad TEXT, direccion TEXT, activa INTEGER NOT NULL DEFAULT 1)");
            st.execute("CREATE TABLE proveedores (id INTEGER PRIMARY KEY AUTOINCREMENT, nombre TEXT NOT NULL UNIQUE "
                    + "COLLATE NOCASE, contacto TEXT, telefono TEXT, email TEXT, lead_time_dias INTEGER)");
            st.execute("CREATE TABLE productos (id INTEGER PRIMARY KEY AUTOINCREMENT, codigo TEXT NOT NULL UNIQUE "
                    + "COLLATE NOCASE, nombre TEXT NOT NULL, categoria_id INTEGER, proveedor_id INTEGER, "
                    + "precio_venta REAL NOT NULL DEFAULT 0, costo REAL NOT NULL DEFAULT 0, stock_minimo INTEGER NOT NULL "
                    + "DEFAULT 0, stock_maximo INTEGER NOT NULL DEFAULT 0, lead_time_dias INTEGER, activo INTEGER NOT NULL "
                    + "DEFAULT 1)");
            st.execute("CREATE TABLE compras (id INTEGER PRIMARY KEY AUTOINCREMENT, fecha TEXT NOT NULL, producto_id "
                    + "INTEGER NOT NULL, sucursal_id INTEGER NOT NULL, proveedor_id INTEGER, cantidad INTEGER NOT NULL, "
                    + "costo_unitario REAL NOT NULL, total REAL NOT NULL)");
            st.execute("INSERT INTO sucursales(codigo, nombre) VALUES ('PRINCIPAL', 'Principal')");
            st.execute("INSERT INTO proveedores(nombre, lead_time_dias) VALUES ('Prov Antiguo', 7)");
            st.execute("INSERT INTO productos(codigo, nombre, proveedor_id, precio_venta, costo) VALUES "
                    + "('OLD-1', 'Producto antiguo', 1, 10, 6)");
            st.execute("INSERT INTO compras(fecha, producto_id, sucursal_id, proveedor_id, cantidad, costo_unitario, "
                    + "total) VALUES ('2025-01-10', 1, 1, 1, 5, 6, 30)");
        }

        AppContext ctx = new AppContext(db);
        assertEquals(1, ctx.productos().listar().size());
        Compra c = ctx.compras().recientes(10).get(0);
        assertEquals(Compra.RECIBIDA, c.estado());
        assertEquals(LocalDate.of(2025, 1, 10), c.fechaRecepcion());
        assertEquals(Compra.ORIGEN_SISTEMA, c.origen());
        assertEquals(5, c.cantidad());
        assertEquals(7, ctx.compras().proveedores().get(0).leadTimeDias());
        assertNull(ctx.compras().proveedores().get(0).cicloCompraDias());
        try (Connection cn = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = cn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('decisiones', "
                     + "'stock_historico', 'ux_compras_huella')")) {
            assertEquals(3, rs.getInt(1));
        }
        // Reabrir es idempotente.
        AppContext otra = new AppContext(db);
        assertEquals(1, otra.compras().recientes(10).size());
    }
}
