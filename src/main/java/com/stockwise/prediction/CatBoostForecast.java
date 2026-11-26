package com.stockwise.prediction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GBDT al estilo CatBoost: árboles <b>simétricos (oblivious)</b> —el mismo corte en todos los nodos de un nivel—,
 * fuerte regularización L2 de las hojas y codificación de la variable categórica (posición estacional) mediante
 * <b>estadísticos de objetivo ordenados</b> (ordered target statistics): cada fila se codifica solo con las filas
 * anteriores en el tiempo, lo que evita la fuga de información del objetivo. Estas características lo hacen más
 * robusto con series ruidosas (demanda errática o irregular).
 * <p>
 * Implementación propia en Java del algoritmo (no usa la biblioteca nativa de Yandex), para funcionar sin
 * dependencias nativas y 100 % offline.
 */
public class CatBoostForecast extends GradientBoostingForecast {

    private final int iteraciones;
    private final double tasa;
    private final int profundidad;
    private final double lambda;
    private final int maxBins;
    /** Peso del valor previo en los estadísticos de objetivo. */
    private final double pesoPrevio;

    public CatBoostForecast() {
        this(80, 0.08, 3, 3.0, 32, 1.0);
    }

    public CatBoostForecast(int iteraciones, double tasa, int profundidad, double lambda, int maxBins,
                            double pesoPrevio) {
        this.iteraciones = iteraciones;
        this.tasa = tasa;
        this.profundidad = profundidad;
        this.lambda = lambda;
        this.maxBins = maxBins;
        this.pesoPrevio = pesoPrevio;
    }

    @Override
    public String nombre() {
        return "CatBoost (árboles simétricos)";
    }

    /** Árbol simétrico: un corte por nivel y 2^profundidad hojas. */
    private record Arbol(int[] variables, double[] umbrales, double[] hojas) {
        double evaluar(double[] x) {
            int idx = 0;
            for (int l = 0; l < variables.length; l++) {
                if (x[variables[l]] > umbrales[l]) {
                    idx |= 1 << l;
                }
            }
            return hojas[idx];
        }
    }

    /** Codificación ordenada: cada fila usa solo las anteriores con la misma categoría. */
    static double[] estadisticosOrdenados(double[] y, int[] cat, double previo, double peso) {
        double[] out = new double[y.length];
        double[] suma = new double[12];
        int[] cuenta = new int[12];
        for (int i = 0; i < y.length; i++) {
            int c = Math.floorMod(cat[i], 12);
            out[i] = (suma[c] + previo * peso) / (cuenta[c] + peso);
            suma[c] += y[i];
            cuenta[c]++;
        }
        return out;
    }

    @Override
    protected Modelo entrenar(double[][] x, double[] y, int[] categorias) {
        int n = x.length;
        double base = Arrays.stream(y).average().orElse(0);
        double[] ts = estadisticosOrdenados(y, categorias, base, pesoPrevio);
        double[][] xa = new double[n][];
        for (int i = 0; i < n; i++) {
            xa[i] = Arrays.copyOf(x[i], x[i].length + 1);
            xa[i][x[i].length] = ts[i];
        }
        // Estadístico para predicción: todas las filas de entrenamiento de la categoría.
        double[] sumaCat = new double[12];
        int[] cuentaCat = new int[12];
        for (int i = 0; i < n; i++) {
            sumaCat[Math.floorMod(categorias[i], 12)] += y[i];
            cuentaCat[Math.floorMod(categorias[i], 12)]++;
        }
        double[][] bordes = bordes(xa, maxBins);
        int[][] bins = binear(xa, bordes);
        double[] f = new double[n];
        Arrays.fill(f, base);
        double[] grad = new double[n];
        List<Arbol> arboles = new ArrayList<>();
        for (int it = 0; it < iteraciones; it++) {
            for (int i = 0; i < n; i++) {
                grad[i] = f[i] - y[i];
            }
            Arbol a = construir(n, bins, bordes, grad);
            for (int i = 0; i < n; i++) {
                f[i] += tasa * a.evaluar(xa[i]);
            }
            arboles.add(a);
        }
        return (xx, c) -> {
            int k = Math.floorMod(c, 12);
            double[] v = Arrays.copyOf(xx, xx.length + 1);
            v[xx.length] = (sumaCat[k] + base * pesoPrevio) / (cuentaCat[k] + pesoPrevio);
            double s = base;
            for (Arbol a : arboles) {
                s += tasa * a.evaluar(v);
            }
            return s;
        };
    }

    private Arbol construir(int n, int[][] bins, double[][] bordes, double[] grad) {
        int[] nodo = new int[n];
        List<Integer> vars = new ArrayList<>();
        List<Double> umbrales = new ArrayList<>();
        for (int nivel = 0; nivel < profundidad; nivel++) {
            int nodos = 1 << nivel;
            double[] gTot = new double[nodos];
            int[] cTot = new int[nodos];
            for (int i = 0; i < n; i++) {
                gTot[nodo[i]] += grad[i];
                cTot[nodo[i]]++;
            }
            double sinCorte = 0;
            for (int k = 0; k < nodos; k++) {
                sinCorte += ganancia(gTot[k], cTot[k], lambda);
            }
            double mejor = sinCorte + 1e-12;
            int mejorVar = -1;
            int mejorBin = -1;
            for (int v = 0; v < bordes.length; v++) {
                int nb = bordes[v].length;
                if (nb < 2) {
                    continue;
                }
                double[][] gb = new double[nodos][nb];
                int[][] cb = new int[nodos][nb];
                for (int i = 0; i < n; i++) {
                    gb[nodo[i]][bins[i][v]] += grad[i];
                    cb[nodo[i]][bins[i][v]]++;
                }
                double[] gl = new double[nodos];
                int[] cl = new int[nodos];
                for (int b = 0; b < nb - 1; b++) {
                    double score = 0;
                    for (int k = 0; k < nodos; k++) {
                        gl[k] += gb[k][b];
                        cl[k] += cb[k][b];
                        score += ganancia(gl[k], cl[k], lambda) + ganancia(gTot[k] - gl[k], cTot[k] - cl[k], lambda);
                    }
                    if (score > mejor) {
                        mejor = score;
                        mejorVar = v;
                        mejorBin = b;
                    }
                }
            }
            if (mejorVar < 0) {
                break;
            }
            vars.add(mejorVar);
            umbrales.add(bordes[mejorVar][mejorBin]);
            for (int i = 0; i < n; i++) {
                if (bins[i][mejorVar] > mejorBin) {
                    nodo[i] |= 1 << nivel;
                }
            }
        }
        int hojas = 1 << vars.size();
        double[] g = new double[hojas];
        int[] c = new int[hojas];
        for (int i = 0; i < n; i++) {
            g[nodo[i]] += grad[i];
            c[nodo[i]]++;
        }
        double[] valores = new double[hojas];
        for (int k = 0; k < hojas; k++) {
            valores[k] = c[k] == 0 ? 0 : -g[k] / (c[k] + lambda);
        }
        return new Arbol(vars.stream().mapToInt(Integer::intValue).toArray(),
                umbrales.stream().mapToDouble(Double::doubleValue).toArray(), valores);
    }
}
