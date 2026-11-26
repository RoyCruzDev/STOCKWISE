package com.stockwise.controller;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Menú por secciones: módulos principales visibles, herramientas secundarias agrupadas y sin pantallas perdidas. */
class ViewNavigationTest {

    @Test
    void menuAgrupadoEnSecciones() {
        assertEquals(List.of(View.INICIO, View.DEMANDA, View.ACCIONES, View.PRODUCTOS, View.INVENTARIO, View.VENTAS,
                View.IMPORTACION, View.HISTORIAL, View.REPORTES, View.CONFIGURACION), View.menu());
        assertEquals("Proyecto", View.INICIO.seccion());
        assertEquals("Análisis", View.DEMANDA.seccion());
        assertEquals("Gestión", View.PRODUCTOS.seccion());
        assertEquals("Datos", View.IMPORTACION.seccion());
        assertEquals("Informes", View.REPORTES.seccion());
    }

    @Test
    void demandaIntegraComprasPronosticoYPlanificacion() {
        assertEquals(List.of(View.RESULTADOS, View.COMPRAS), View.DEMANDA.hijos(View.Acceso.PASO));
        assertEquals(List.of(View.PREDICCION, View.PLANIFICACION), View.DEMANDA.hijos(View.Acceso.HERRAMIENTA));
        assertEquals(View.RESULTADOS, View.DEMANDA.destino());
        assertEquals(View.Acceso.OCULTO, View.PROCESAMIENTO.acceso());
        assertEquals(View.ACCIONES, View.ASESOR.modulo());
        assertEquals(View.INICIO, View.DASHBOARD.modulo());
        assertEquals(View.INVENTARIO, View.SUCURSALES.modulo());
    }

    @Test
    void todasLasPantallasSonAccesiblesYTienenVista() {
        for (View v : View.values()) {
            View modulo = v.modulo();
            assertTrue(modulo.enMenu(), v + " debe pertenecer a un módulo del menú");
            assertTrue(v.enMenu() || modulo.hijos(v.acceso()).contains(v), v + " no es accesible");
            assertNotNull(View.class.getResource(v.recurso()), "vista inexistente: " + v.recurso());
        }
    }

    @Test
    void ningunaVistaSeDuplica() {
        long distintas = Arrays.stream(View.values()).filter(v -> v.destino() == v).map(View::recurso).distinct().count();
        long conVista = Arrays.stream(View.values()).filter(v -> v.destino() == v).count();
        assertEquals(conVista, distintas);
    }
}
