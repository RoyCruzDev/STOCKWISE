package com.stockwise.prediction;

import java.util.Arrays;

/**
 * Promedio móvil ponderado con pesos lineales 1..N: la observación más reciente pesa N y la más antigua 1.
 */
public class WeightedMovingAverage implements ForecastMethod {
    private final int ventana;

    public WeightedMovingAverage(int ventana) {
        if (ventana < 1) {
            throw new IllegalArgumentException("Ventana inválida");
        }
        this.ventana = ventana;
    }

    @Override
    public String nombre() {
        return "Promedio móvil ponderado (" + ventana + ")";
    }

    @Override
    public int minimoDatos() {
        return ventana;
    }

    @Override
    public double[] pronosticar(double[] historia, int horizonte) {
        validar(historia, horizonte);
        double suma = 0;
        double pesos = 0;
        int inicio = historia.length - ventana;
        for (int i = 0; i < ventana; i++) {
            int peso = i + 1;
            suma += historia[inicio + i] * peso;
            pesos += peso;
        }
        double[] out = new double[horizonte];
        Arrays.fill(out, Math.max(0, suma / pesos));
        return out;
    }
}
