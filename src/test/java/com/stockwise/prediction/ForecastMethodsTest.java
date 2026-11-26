package com.stockwise.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForecastMethodsTest {

    private static final double EPS = 1e-9;

    @Test
    void promedioMovilUsaUltimasNObservaciones() {
        double[] f = new MovingAverage(3).pronosticar(new double[]{10, 20, 30, 40, 50}, 3);
        assertArrayEquals(new double[]{40, 40, 40}, f, EPS);
    }

    @Test
    void promedioPonderadoDaMasPesoAlUltimoDato() {
        // (30*1 + 40*2 + 50*3) / 6 = 43.333
        double[] f = new WeightedMovingAverage(3).pronosticar(new double[]{10, 20, 30, 40, 50}, 1);
        assertEquals(260.0 / 6, f[0], EPS);
    }

    @Test
    void suavizamientoExponencialCalculaNivel() {
        // L0=10, L1=0.5*20+0.5*10=15, L2=0.5*30+0.5*15=22.5
        double[] f = new ExponentialSmoothing(0.5).pronosticar(new double[]{10, 20, 30}, 2);
        assertArrayEquals(new double[]{22.5, 22.5}, f, EPS);
    }

    @Test
    void regresionLinealExtrapolaTendencia() {
        double[] f = new LinearRegressionForecast().pronosticar(new double[]{10, 12, 14, 16}, 3);
        assertArrayEquals(new double[]{18, 20, 22}, f, 1e-6);
    }

    @Test
    void regresionNoDevuelveValoresNegativos() {
        double[] f = new LinearRegressionForecast().pronosticar(new double[]{30, 20, 10, 2}, 4);
        for (double v : f) {
            assertTrue(v >= 0);
        }
    }

    @Test
    void metodoRechazaHistoriaInsuficiente() {
        assertThrows(IllegalArgumentException.class, () -> new MovingAverage(4).pronosticar(new double[]{1, 2}, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExponentialSmoothing(1.5));
    }

    @Test
    void maeYMapeSeCalculanCorrectamente() {
        double[] reales = {100, 200, 0};
        double[] pred = {110, 180, 5};
        assertEquals((10 + 20 + 5) / 3.0, ForecastEvaluator.mae(reales, pred), EPS);
        // MAPE ignora el real cero: (0.10 + 0.10) / 2
        assertEquals(0.10, ForecastEvaluator.mape(reales, pred), EPS);
        assertNull(ForecastEvaluator.mape(new double[]{0, 0}, new double[]{1, 2}));
    }

    @Test
    void evaluacionWalkForwardSoloUsaDatosPasados() {
        double[] serie = {10, 10, 10, 20};
        ForecastEvaluator.Evaluation ev = ForecastEvaluator.evaluar(new MovingAverage(3), serie, 3);
        assertEquals(1, ev.puntos());
        assertEquals(10, ev.ajustes()[3], EPS); // predijo 10 con [10,10,10]
        assertEquals(10, ev.mae(), EPS);
        assertEquals(0.5, ev.mape(), EPS);
        assertTrue(Double.isNaN(ev.ajustes()[0]));
    }
}
