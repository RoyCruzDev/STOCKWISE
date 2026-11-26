package com.stockwise.recommendation;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulación de precios sin inventar elasticidad: con el costo, el precio actual y las unidades que se espera vender
 * calcula, para cada precio alternativo, la ganancia por unidad y cuántas unidades habría que vender para ganar lo
 * mismo que hoy. No predice cuánto cambiarían las ventas con el nuevo precio (no hay datos para estimarlo): eso queda
 * a criterio del dueño. Respeta el costo y el margen mínimo configurado.
 */
public final class PriceSimulator {

    /** Variaciones de precio que se comparan por defecto. */
    public static final double[] VARIACIONES = {-0.10, -0.05, -0.025, 0, 0.025, 0.05};

    /**
     * Escenario de un precio.
     *
     * @param ingresos           ingresos si se vendieran las mismas unidades que hoy (supuesto, no predicción)
     * @param utilidad           ganancia si se vendieran las mismas unidades que hoy
     * @param unidadesNecesarias unidades a vender para igualar la ganancia actual (nulo si el precio no cubre el costo)
     * @param cambioNecesario    variación de unidades necesaria frente a las esperadas (nulo si no aplica)
     */
    public record Escenario(double precio, double variacionPrecio, double margenUnitario, double margenPct,
                            double unidadesBase, double ingresos, double utilidad, Double unidadesNecesarias,
                            Double cambioNecesario, boolean bajoCosto, boolean bajoMargenMinimo) {

        public boolean permitido() {
            return !bajoCosto && !bajoMargenMinimo;
        }
    }

    private PriceSimulator() {
    }

    /** Precio más bajo que respeta el margen mínimo sobre el precio: costo / (1 − margen). */
    public static double precioMinimo(double costo, double margenMinimo) {
        return margenMinimo >= 1 ? Double.POSITIVE_INFINITY : costo / (1 - Math.max(0, margenMinimo));
    }

    /** Redondea a 10 céntimos (precios de góndola). */
    public static double redondear(double precio) {
        return Math.round(precio * 10) / 10.0;
    }

    public static Escenario escenario(double precioActual, double costo, double unidadesBase, double margenMinimo,
                                      double precio) {
        double margenUnit = precio - costo;
        double margenPct = precio > 0 ? margenUnit / precio : 0;
        double utilidadActual = (precioActual - costo) * unidadesBase;
        Double necesarias = margenUnit > 0 && utilidadActual > 0 ? utilidadActual / margenUnit : null;
        Double cambio = necesarias != null && unidadesBase > 0 ? necesarias / unidadesBase - 1 : null;
        return new Escenario(precio, precioActual > 0 ? precio / precioActual - 1 : 0, margenUnit, margenPct,
                unidadesBase, precio * unidadesBase, margenUnit * unidadesBase, necesarias, cambio,
                precio <= costo, margenPct < margenMinimo - 1e-9);
    }

    /** Escenarios para las variaciones estándar más, opcionalmente, un precio elegido por el usuario. */
    public static List<Escenario> escenarios(double precioActual, double costo, double unidadesBase,
                                             double margenMinimo, Double precioElegido) {
        List<Escenario> out = new ArrayList<>();
        for (double v : VARIACIONES) {
            double p = v == 0 ? precioActual : redondear(precioActual * (1 + v));
            out.add(escenario(precioActual, costo, unidadesBase, margenMinimo, p));
        }
        if (precioElegido != null && precioElegido > 0
                && out.stream().noneMatch(e -> Math.abs(e.precio() - precioElegido) < 0.005)) {
            out.add(escenario(precioActual, costo, unidadesBase, margenMinimo, precioElegido));
        }
        out.sort((a, b) -> Double.compare(a.precio(), b.precio()));
        return out;
    }

    /** Explicación en lenguaje sencillo de pasar del precio actual al alternativo. */
    public static String explicar(double precioActual, double costo, double unidadesBase, double margenMinimo,
                                  double precio, String moneda) {
        if (costo <= 0) {
            return "El costo de este producto no está registrado: no se puede calcular cuánto ganarías. Registre el "
                    + "costo en Productos para usar el simulador.";
        }
        Escenario hoy = escenario(precioActual, costo, unidadesBase, margenMinimo, precioActual);
        Escenario alt = escenario(precioActual, costo, unidadesBase, margenMinimo, precio);
        if (alt.bajoCosto()) {
            return String.format("Con %s %.2f venderías por debajo del costo (%s %.2f): perderías dinero en cada unidad.",
                    moneda, precio, moneda, costo);
        }
        StringBuilder sb = new StringBuilder(String.format("Hoy cada unidad deja %s %.2f de ganancia; con %s %.2f "
                + "dejaría %s %.2f.", moneda, hoy.margenUnitario(), moneda, precio, moneda, alt.margenUnitario()));
        if (alt.unidadesNecesarias() != null && unidadesBase > 0) {
            double dif = alt.unidadesNecesarias() - unidadesBase;
            if (Math.abs(dif) < 0.5) {
                sb.append(" La ganancia sería prácticamente la misma.");
            } else if (dif > 0) {
                sb.append(String.format(" Para ganar lo mismo que hoy (%s %.2f en 30 días) tendrías que vender unas %.0f "
                                + "unidades en vez de %.0f (%.0f %% más).", moneda, hoy.utilidad(),
                        Math.ceil(alt.unidadesNecesarias()), unidadesBase, alt.cambioNecesario() * 100));
            } else {
                sb.append(String.format(" Ganarías lo mismo que hoy vendiendo solo %.0f unidades en vez de %.0f; si las "
                                + "ventas bajan más que eso, ganarías menos.", Math.ceil(alt.unidadesNecesarias()),
                        unidadesBase));
            }
        }
        if (alt.bajoMargenMinimo()) {
            sb.append(String.format(" Atención: queda por debajo de tu margen mínimo (%.0f %%). El precio más bajo "
                            + "permitido es %s %.2f.", margenMinimo * 100, moneda,
                    Math.ceil(precioMinimo(costo, margenMinimo) * 100) / 100));
        }
        sb.append(" No hay datos para saber cuánto cambiarán las ventas con el nuevo precio: es una simulación.");
        return sb.toString();
    }
}
