package com.stockwise.prediction;

import java.util.ArrayList;
import java.util.List;

/**
 * GBDT al estilo LightGBM: árboles construidos <b>por hojas</b> (leaf-wise: siempre se divide la hoja con mayor
 * ganancia), búsqueda de cortes sobre <b>histogramas</b> de variables discretizadas, regularización L2 de las hojas
 * y mínimo de observaciones por hoja. Pérdida cuadrática.
 * <p>
 * Implementación propia en Java del algoritmo (no usa la biblioteca nativa de Microsoft), para funcionar sin
 * dependencias nativas y 100 % offline.
 */
public class LightGbmForecast extends GradientBoostingForecast {

    private final int iteraciones;
    private final double tasa;
    private final int maxHojas;
    private final int minDatosHoja;
    private final double lambda;
    private final int maxBins;

    public LightGbmForecast() {
        this(60, 0.1, 6, 3, 1.0, 32);
    }

    public LightGbmForecast(int iteraciones, double tasa, int maxHojas, int minDatosHoja, double lambda, int maxBins) {
        this.iteraciones = iteraciones;
        this.tasa = tasa;
        this.maxHojas = maxHojas;
        this.minDatosHoja = minDatosHoja;
        this.lambda = lambda;
        this.maxBins = maxBins;
    }

    @Override
    public String nombre() {
        return "LightGBM (GBDT por hojas)";
    }

    private static final class Nodo {
        int variable = -1;
        double umbral;
        Nodo izq;
        Nodo der;
        double valor;

        double evaluar(double[] x) {
            Nodo n = this;
            while (n.variable >= 0) {
                n = x[n.variable] <= n.umbral ? n.izq : n.der;
            }
            return n.valor;
        }
    }

    private static final class Hoja {
        final int[] filas;
        final Nodo nodo = new Nodo();
        double g;
        double h;
        int variable = -1;
        int binCorte;
        double ganancia;

        Hoja(int[] filas) {
            this.filas = filas;
        }
    }

    @Override
    protected Modelo entrenar(double[][] x, double[] y, int[] categorias) {
        int n = x.length;
        double[][] bordes = bordes(x, maxBins);
        int[][] bins = binear(x, bordes);
        double base = 0;
        for (double v : y) {
            base += v;
        }
        base /= n;
        double[] f = new double[n];
        java.util.Arrays.fill(f, base);
        double[] grad = new double[n];
        List<Nodo> arboles = new ArrayList<>();
        for (int it = 0; it < iteraciones; it++) {
            for (int i = 0; i < n; i++) {
                grad[i] = f[i] - y[i];
            }
            Nodo raiz = construirArbol(n, bins, bordes, grad);
            for (int i = 0; i < n; i++) {
                f[i] += tasa * raiz.evaluar(x[i]);
            }
            arboles.add(raiz);
        }
        final double b = base;
        return (xx, c) -> {
            double s = b;
            for (Nodo a : arboles) {
                s += tasa * a.evaluar(xx);
            }
            return s;
        };
    }

    private Nodo construirArbol(int n, int[][] bins, double[][] bordes, double[] grad) {
        int[] todas = new int[n];
        for (int i = 0; i < n; i++) {
            todas[i] = i;
        }
        List<Hoja> hojas = new ArrayList<>();
        Hoja raiz = new Hoja(todas);
        buscarCorte(raiz, bins, bordes, grad);
        hojas.add(raiz);
        while (hojas.size() < maxHojas) {
            Hoja mejor = null;
            for (Hoja hj : hojas) {
                if (hj.variable >= 0 && hj.ganancia > 1e-12 && (mejor == null || hj.ganancia > mejor.ganancia)) {
                    mejor = hj;
                }
            }
            if (mejor == null) {
                break;
            }
            int v = mejor.variable;
            int corte = mejor.binCorte;
            int nIzq = 0;
            for (int i : mejor.filas) {
                if (bins[i][v] <= corte) {
                    nIzq++;
                }
            }
            int[] izq = new int[nIzq];
            int[] der = new int[mejor.filas.length - nIzq];
            int a = 0;
            int d = 0;
            for (int i : mejor.filas) {
                if (bins[i][v] <= corte) {
                    izq[a++] = i;
                } else {
                    der[d++] = i;
                }
            }
            Hoja hi = new Hoja(izq);
            Hoja hd = new Hoja(der);
            mejor.nodo.variable = v;
            mejor.nodo.umbral = bordes[v][corte];
            mejor.nodo.izq = hi.nodo;
            mejor.nodo.der = hd.nodo;
            hojas.remove(mejor);
            buscarCorte(hi, bins, bordes, grad);
            buscarCorte(hd, bins, bordes, grad);
            hojas.add(hi);
            hojas.add(hd);
        }
        for (Hoja hj : hojas) {
            if (hj.g == 0 && hj.h == 0) {
                sumar(hj, grad);
            }
            hj.nodo.valor = -hj.g / (hj.h + lambda);
        }
        return raiz.nodo;
    }

    private static void sumar(Hoja hj, double[] grad) {
        double g = 0;
        for (int i : hj.filas) {
            g += grad[i];
        }
        hj.g = g;
        hj.h = hj.filas.length;
    }

    /** Busca el mejor corte de la hoja recorriendo el histograma de cada variable. */
    private void buscarCorte(Hoja hj, int[][] bins, double[][] bordes, double[] grad) {
        sumar(hj, grad);
        hj.variable = -1;
        hj.ganancia = 0;
        if (hj.filas.length < 2 * minDatosHoja) {
            return;
        }
        double padre = ganancia(hj.g, hj.h, lambda);
        for (int v = 0; v < bordes.length; v++) {
            int nb = bordes[v].length;
            if (nb < 2) {
                continue;
            }
            double[] gb = new double[nb];
            int[] cb = new int[nb];
            for (int i : hj.filas) {
                gb[bins[i][v]] += grad[i];
                cb[bins[i][v]]++;
            }
            double gl = 0;
            int cl = 0;
            for (int b = 0; b < nb - 1; b++) {
                gl += gb[b];
                cl += cb[b];
                int cr = hj.filas.length - cl;
                if (cl < minDatosHoja || cr < minDatosHoja || cb[b] == 0) {
                    continue;
                }
                double gan = ganancia(gl, cl, lambda) + ganancia(hj.g - gl, cr, lambda) - padre;
                if (gan > hj.ganancia + 1e-12) {
                    hj.ganancia = gan;
                    hj.variable = v;
                    hj.binCorte = b;
                }
            }
        }
    }
}
