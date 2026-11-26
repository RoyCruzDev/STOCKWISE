package com.stockwise.prediction;

import com.stockwise.analytics.Statistics;
import org.apache.commons.math3.stat.regression.SimpleRegression;

/** Regresión lineal por mínimos cuadrados sobre el tiempo; extrapola la tendencia (sin valores negativos). */
public class LinearRegressionForecast implements ForecastMethod {

    @Override
    public String nombre() {
        return "Regresión lineal";
    }

    @Override
    public int complejidad() {
        return 2;
    }

    @Override
    public int minimoDatos() {
        return 3;
    }

    @Override
    public double[] pronosticar(double[] historia, int horizonte) {
        validar(historia, horizonte);
        SimpleRegression r = Statistics.regression(historia);
        double[] out = new double[horizonte];
        for (int h = 0; h < horizonte; h++) {
            out[h] = Math.max(0, r.predict(historia.length + h));
        }
        return out;
    }
}
