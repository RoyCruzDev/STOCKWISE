package com.stockwise.prediction;

import java.util.Arrays;

/** Promedio móvil simple: el pronóstico es la media de las últimas N observaciones. */
public class MovingAverage implements ForecastMethod {
    private final int ventana;

    public MovingAverage(int ventana) {
        if (ventana < 1) {
            throw new IllegalArgumentException("Ventana inválida");
        }
        this.ventana = ventana;
    }

    @Override
    public String nombre() {
        return "Promedio móvil (" + ventana + ")";
    }

    @Override
    public int minimoDatos() {
        return ventana;
    }

    @Override
    public double[] pronosticar(double[] historia, int horizonte) {
        validar(historia, horizonte);
        double suma = 0;
        for (int i = historia.length - ventana; i < historia.length; i++) {
            suma += historia[i];
        }
        double[] out = new double[horizonte];
        Arrays.fill(out, Math.max(0, suma / ventana));
        return out;
    }
}
