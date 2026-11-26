package com.stockwise.recommendation;

import com.stockwise.recommendation.PriceSimulator.Escenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Simulador de precios: cuentas exactas, respeto del costo y del margen mínimo, sin inventar elasticidad. */
class PriceSimulatorTest {

    @Test
    void ejemploDelDueno() {
        // Precio S/ 20.00, costo S/ 12.00, se esperan vender 120 u: ganancia actual 8 × 120 = 960.
        Escenario e = PriceSimulator.escenario(20, 12, 120, 0.15, 19.50);
        assertEquals(7.5, e.margenUnitario(), 1e-9);
        assertEquals(128, e.unidadesNecesarias(), 1e-9);
        assertEquals(128.0 / 120 - 1, e.cambioNecesario(), 1e-9);
        assertEquals(19.5 * 120, e.ingresos(), 1e-9);
        assertEquals(7.5 * 120, e.utilidad(), 1e-9);
        assertTrue(e.permitido());
        String t = PriceSimulator.explicar(20, 12, 120, 0.15, 19.50, "S/");
        assertTrue(t.contains("unas 128 unidades en vez de 120"), t);
        assertTrue(t.contains("es una simulación"), t);
    }

    @Test
    void respetaCostoYMargenMinimo() {
        assertEquals(12 / 0.75, PriceSimulator.precioMinimo(12, 0.25), 1e-9);
        Escenario bajoMargen = PriceSimulator.escenario(20, 12, 100, 0.25, 15.5);
        assertTrue(bajoMargen.bajoMargenMinimo());
        assertFalse(bajoMargen.permitido());
        Escenario bajoCosto = PriceSimulator.escenario(20, 12, 100, 0.10, 11.9);
        assertTrue(bajoCosto.bajoCosto());
        assertNull(bajoCosto.unidadesNecesarias());
        assertTrue(PriceSimulator.explicar(20, 12, 100, 0.1, 11.9, "S/").contains("por debajo del costo"));
        assertTrue(PriceSimulator.explicar(20, 12, 100, 0.25, 15.5, "S/").contains("margen mínimo"));
        assertTrue(PriceSimulator.explicar(20, 0, 100, 0.25, 19, "S/").contains("costo de este producto no está"));
    }

    @Test
    void escenariosIncluyenActualYElegidoOrdenados() {
        List<Escenario> l = PriceSimulator.escenarios(20, 12, 120, 0.15, 19.35);
        assertEquals(PriceSimulator.VARIACIONES.length + 1, l.size());
        assertTrue(l.stream().anyMatch(e -> e.precio() == 20 && e.variacionPrecio() == 0));
        assertTrue(l.stream().anyMatch(e -> Math.abs(e.precio() - 19.35) < 1e-9));
        for (int i = 1; i < l.size(); i++) {
            assertTrue(l.get(i - 1).precio() <= l.get(i).precio());
        }
        Escenario sube = l.get(l.size() - 1);
        assertTrue(sube.unidadesNecesarias() < 120, "con precio mayor basta vender menos para ganar lo mismo");
    }
}
