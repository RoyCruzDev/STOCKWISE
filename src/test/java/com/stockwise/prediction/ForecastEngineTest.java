package com.stockwise.prediction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForecastEngineTest {

    private final ForecastEngine engine = new ForecastEngine(new ForecastEngine.Params(3, 3, 0.3, 6));

    private static List<String> periodos(int n) {
        List<String> p = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            p.add(java.time.YearMonth.of(2024, 1).plusMonths(i).toString());
        }
        return p;
    }

    @Test
    void conMenosDeTresMesesNoSeInventaPronostico() {
        ForecastResult r = engine.pronosticar(periodos(2), new double[]{10, 12});
        assertFalse(r.suficiente());
        assertNull(r.mejor());
        assertEquals(ForecastResult.Confianza.INSUFICIENTE, r.confianza());
        assertFalse(r.advertencias().isEmpty());
        assertEquals(0, r.demandaProximos(3));
    }

    @Test
    void sinVentasNoHayPronostico() {
        ForecastResult r = engine.pronosticar(periodos(6), new double[6]);
        assertFalse(r.suficiente());
        assertTrue(r.advertencias().get(0).contains("No hay ventas"));
    }

    @Test
    void serieLinealSeleccionaRegresionYDetectaTendenciaCreciente() {
        double[] s = new double[12];
        for (int i = 0; i < s.length; i++) {
            s[i] = 50 + 5 * i;
        }
        ForecastResult r = engine.pronosticar(periodos(12), s);
        assertTrue(r.suficiente());
        assertEquals("Regresión lineal", r.mejor().metodo());
        assertEquals(ForecastResult.Tendencia.CRECIENTE, r.tendencia());
        assertEquals(110, r.mejor().pronostico()[0], 1e-6);
        assertEquals(110 + 115 + 120, r.demandaProximos(3), 1e-6);
        assertEquals(6, r.periodosFuturos().size());
        assertEquals("2025-01", r.periodosFuturos().get(0));
        // 4 clásicos + ingenuo (referencia) + Holt amortiguado (la serie tiene tendencia) + combinación de los 3 mejores
        assertEquals(List.of("Promedio móvil (3)", "Promedio móvil ponderado (3)", "Suavizamiento exponencial (α=0.3)",
                "Regresión lineal", "Ingenuo (referencia)", "Holt (tendencia amortiguada)", "Combinación de los 3 mejores"),
                r.metodos().stream().map(ForecastResult.MethodResult::metodo).toList());
        assertNotNull(r.mejor().mae());
    }

    @Test
    void serieDecrecienteDetectaTendencia() {
        double[] s = {100, 95, 88, 82, 77, 70, 64, 60, 52, 47};
        ForecastResult r = engine.pronosticar(periodos(s.length), s);
        assertEquals(ForecastResult.Tendencia.DECRECIENTE, r.tendencia());
        assertTrue(r.pendiente() < 0);
    }

    @Test
    void serieEstableTieneTendenciaEstableYConfianzaAlta() {
        double[] s = {40, 41, 39, 40, 42, 40, 39, 41, 40, 40, 41, 39};
        ForecastResult r = engine.pronosticar(periodos(s.length), s);
        assertEquals(ForecastResult.Tendencia.ESTABLE, r.tendencia());
        assertEquals(ForecastResult.Confianza.ALTA, r.confianza());
        assertEquals(40, r.mejor().pronostico()[0], 2.0);
    }

    @Test
    void historialCortoAdvierteYNoEsConfianzaAlta() {
        ForecastResult r = engine.pronosticar(periodos(4), new double[]{10, 30, 5, 40});
        assertTrue(r.suficiente());
        assertTrue(r.confianza() != ForecastResult.Confianza.ALTA);
        assertTrue(r.advertencias().stream().anyMatch(a -> a.contains("Historial corto")));
    }

    @Test
    void demandaIntermitenteGeneraAdvertencia() {
        double[] s = {0, 10, 0, 0, 12, 0, 0, 0, 9, 0};
        ForecastResult r = engine.pronosticar(periodos(s.length), s);
        assertTrue(r.advertencias().stream().anyMatch(a -> a.contains("intermitente")));
    }
}
