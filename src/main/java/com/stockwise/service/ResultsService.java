package com.stockwise.service;

import com.stockwise.database.Database;
import com.stockwise.model.Prioridad;
import com.stockwise.prediction.HorizonForecast;
import com.stockwise.recommendation.DecisionEstrategica;
import com.stockwise.repository.AnalisisRepository;
import com.stockwise.repository.AnalisisRepository.Analisis;
import com.stockwise.repository.DecisionRepository;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import com.stockwise.repository.DecisionRepository.DetalleAnalisis;
import com.stockwise.repository.VentaRepository;
import com.stockwise.util.Formatters;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Consulta de corridas de análisis guardadas: indicadores para el gerente, rankings, respuestas de compra,
 * comparación entre corridas y eliminación de resultados (sin tocar los datos históricos).
 */
public class ResultsService {

    /** Rankings de productos. */
    public enum Ranking {
        MAS_VENDIDOS("Más vendidos", "Los que más unidades vendieron en los últimos 12 meses."),
        MAYOR_CRECIMIENTO("Creciendo", "Cuyas ventas vienen subiendo: unidades más por mes."),
        MAYOR_DEMANDA("Más demanda prevista", "Cuánto podrías vender en los próximos 30 días."),
        PERDIENDO_VENTAS("Perdiendo ventas", "Cuyas ventas vienen bajando: unidades menos por mes."),
        EXCESO("Exceso de stock", "Tienes más de lo que probablemente venderás en 3 meses: unidades de más."),
        PARA_RECOMPRAR("Necesitan reposición", "Ordenados por urgencia: cuántas unidades conviene pedir (según lo que podrías vender, tu stock, "
                + "lo ya pedido y el tiempo de entrega)."),
        MENOS_VENDIDOS("Menos vendidos", "Con stock y la menor rotación (unidades vendidas en 12 meses / stock)."),
        OLVIDADOS("Sin venderse", "Llevan tiempo sin venderse (días sin ventas) o nunca se vendieron y tienen stock."),
        NO_RECOMPRAR("No recomprar", "Exceso de inventario o demanda insuficiente: liquidar, reducir o descontinuar.");

        private final String etiqueta;
        private final String descripcion;

        Ranking(String etiqueta, String descripcion) {
            this.etiqueta = etiqueta;
            this.descripcion = descripcion;
        }

        public String etiqueta() {
            return etiqueta;
        }

        public String descripcion() {
            return descripcion;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    /** Preguntas del gerente que responde la pantalla de resultados, en orden de importancia. */
    public enum Pregunta {
        COMPRAR("¿Qué comprar y cuántas unidades?", "Necesitan reposición: la demanda pronosticada durante el tiempo "
                + "de entrega supera el stock más lo ya pedido."),
        PODRIAN_AGOTARSE("¿Qué está por agotarse?", "Agotados o con stock para menos días que el tiempo de "
                + "reposición (con margen de 50 %)."),
        MAYOR_DEMANDA("¿Qué tendrá más demanda?", "Los 20 productos con mayor demanda pronosticada para los próximos 30 días."),
        PERDIENDO_DEMANDA("¿Qué está perdiendo demanda?", "Ventas con tendencia a la baja comprobada en su historial."),
        EXCESO("¿Qué tiene exceso de stock?", "Inventario muy superior a lo que se venderá en los próximos meses: "
                + "capital inmovilizado."),
        NO_RECOMPRAR("¿Qué dejar de comprar?", "Liquidar, reducir o descontinuar: no conviene reponerlos por ahora."),
        CRECIMIENTO("¿Qué tiene oportunidad de crecer?", "Demanda en aumento comprobado o buen margen con poca "
                + "visibilidad: candidatos a impulsar."),
        MANTENER("¿Qué mantener igual?", "Stock y demanda equilibrados: seguir con la política actual.");

        private final String etiqueta;
        private final String descripcion;

        Pregunta(String etiqueta, String descripcion) {
            this.etiqueta = etiqueta;
            this.descripcion = descripcion;
        }

        public String descripcion() {
            return descripcion;
        }

        /** Preguntas que no se pueden responder sin conocer el stock (sin inventario quedan pendientes). */
        public boolean dependeDelStock() {
            return this == COMPRAR || this == PODRIAN_AGOTARSE || this == EXCESO || this == MANTENER;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    /** Aviso para lo que depende del stock cuando el proyecto no tiene inventario registrado. */
    public static final String SIN_INVENTARIO = "Importe su inventario para calcularlo";

    /** Verdadero si el proyecto tiene inventario registrado (sin base de datos se asume que sí). */
    public boolean hayInventario() {
        return db == null || db.queryLong("SELECT COUNT(*) FROM inventario") > 0;
    }

    /**
     * Explicación cuando no hay inventario registrado: solo lo que se sabe por las ventas (cuánto se venderá,
     * tendencia y confianza), sin cantidades a comprar que supondrían un stock inventado.
     */
    public static String explicacionSinInventario(DecisionGuardada r) {
        DetalleAnalisis d = d(r);
        List<String> l = new ArrayList<>();
        if (d != null && d.p30() != null) {
            l.add("Se espera vender unas " + Formatters.integer(Math.round(d.p30())) + " unidades en los próximos 30 "
                    + "días.");
        }
        if (d != null && "CRECIENTE".equals(d.tendencia())) {
            l.add("Sus ventas vienen creciendo.");
        } else if (d != null && "DECRECIENTE".equals(d.tendencia())) {
            l.add("Sus ventas vienen bajando.");
        }
        if (r.decision() == DecisionEstrategica.DESCONTINUAR && d != null && d.diasSinVenta() != null) {
            l.add("Lleva " + d.diasSinVenta() + " días sin venderse.");
        }
        l.add(confianzaSencilla(d));
        l.add("Para saber cuánto comprar, importe su inventario.");
        return String.join(" ", l);
    }

    /**
     * Productos que responden la pregunta. Sin inventario registrado el stock es desconocido (no cero): las preguntas
     * que dependen del stock quedan vacías y "qué dejar de comprar" solo incluye los productos que ya no se venden.
     */
    public static List<DecisionGuardada> pregunta(List<DecisionGuardada> r, Pregunta q, boolean hayInventario) {
        if (hayInventario) {
            return pregunta(r, q);
        }
        if (q.dependeDelStock()) {
            return List.of();
        }
        if (q == Pregunta.NO_RECOMPRAR) {
            return pregunta(r, q).stream().filter(x -> x.decision() == DecisionEstrategica.DESCONTINUAR).toList();
        }
        return pregunta(r, q);
    }

    public static String resumen(Pregunta q, List<DecisionGuardada> l, boolean hayInventario) {
        return !hayInventario && q.dependeDelStock() ? SIN_INVENTARIO : resumen(q, l);
    }

    /** Rankings visibles: sin inventario se ocultan los que dependen del stock (exceso y reposición). */
    public static List<Ranking> visibles(boolean hayInventario) {
        return hayInventario ? VISIBLES : VISIBLES.stream()
                .filter(k -> k != Ranking.EXCESO && k != Ranking.PARA_RECOMPRAR).toList();
    }

    /** Máximo de productos listados en "¿Qué tendrá más demanda?". */
    public static final int TOP_DEMANDA = 20;

    /**
     * Fuente de datos usada en el análisis.
     *
     * @param uso        para qué se usa (p. ej. compras: pedidos en tránsito, nunca demanda)
     * @param estado     qué hay disponible
     * @param disponible falso si falta y limita los resultados
     */
    public record FuenteDatos(String nombre, String uso, String estado, boolean disponible) {
    }

    /** Indicadores principales de una corrida. */
    public record Kpis(int productos, int conPronostico, String mayorDemanda, Double mayorDemanda30, long crecientes,
                       long riesgo, long exceso, long sinMovimiento, double compraSugerida, double compraAprobada,
                       Double presupuesto, long aComprar) {
    }

    /** Cambio de un producto entre dos corridas. */
    public record CambioProducto(long productoId, String codigo, String producto, Double p30Anterior, Double p30Nuevo,
                                 Double variacion, String patronAnterior, String patronNuevo,
                                 DecisionEstrategica decisionAnterior, DecisionEstrategica decisionNueva,
                                 String tendenciaAnterior, String tendenciaNueva, Double wapeAnterior,
                                 Double wapeNuevo, Double pronosticadoAnterior, Double real) {

        public boolean cambioPatron() {
            return !Objects.equals(patronAnterior, patronNuevo);
        }

        public boolean cambioDecision() {
            return decisionAnterior != decisionNueva;
        }

        /** Error absoluto del pronóstico anterior frente a la venta real (nulo si no hay datos nuevos). */
        public Double errorReal() {
            return pronosticadoAnterior == null || real == null ? null : Math.abs(pronosticadoAnterior - real);
        }
    }

    /**
     * Comparación entre dos corridas.
     *
     * @param diasEvaluados días con ventas nuevas usados para medir el pronóstico anterior (0 = pendiente)
     * @param wapeRealAnterior WAPE del pronóstico anterior frente a las ventas reales posteriores (nulo si pendiente)
     */
    public record Comparacion(Analisis anterior, Analisis nuevo, int diasEvaluados, Double wapeRealAnterior,
                              Double sesgoRealAnterior, List<CambioProducto> cambios, long cambiosPatron,
                              long cambiosDecision, List<String> resumen) {
    }

    public static final int DIAS_EVALUACION = 30;

    private final AnalisisRepository analisis;
    private final DecisionRepository resultados;
    private final VentaRepository ventas;
    private final ConfigService config;
    /** Para consultar las fuentes de datos (nula en pruebas sin base). */
    private final Database db;

    public ResultsService(AnalisisRepository analisis, DecisionRepository resultados, VentaRepository ventas,
                          ConfigService config) {
        this(analisis, resultados, ventas, config, null);
    }

    public ResultsService(AnalisisRepository analisis, DecisionRepository resultados, VentaRepository ventas,
                          ConfigService config, Database db) {
        this.db = db;
        this.analisis = analisis;
        this.resultados = resultados;
        this.ventas = ventas;
        this.config = config;
    }

    public List<Analisis> analisis() {
        return analisis.listar();
    }

    public Optional<Analisis> ultimo() {
        return analisis.ultimo();
    }

    public Optional<Analisis> buscar(long id) {
        return analisis.findById(id);
    }

    public List<DecisionGuardada> resultados(long analisisId) {
        return resultados.porAnalisis(analisisId);
    }

    /** Elimina solo los resultados de la corrida; ventas, compras e inventario se conservan. */
    public void eliminar(long analisisId) {
        analisis.findById(analisisId).orElseThrow(() -> new ValidationException("El análisis no existe."));
        analisis.eliminar(analisisId);
    }

    // ------------------------------------------------------------------ indicadores, rankings y preguntas

    private static DetalleAnalisis d(DecisionGuardada r) {
        return r.detalle();
    }

    private static double u365(DecisionGuardada r) {
        return d(r) == null ? 0 : d(r).unidades365();
    }

    private static boolean tendencia(DecisionGuardada r, String t) {
        return d(r) != null && t.equals(d(r).tendencia());
    }

    private static boolean estado(DecisionGuardada r, String... estados) {
        if (d(r) == null || d(r).estadoInventario() == null) {
            return false;
        }
        for (String e : estados) {
            if (e.equals(d(r).estadoInventario())) {
                return true;
            }
        }
        return false;
    }

    public static Kpis kpis(Analisis a, List<DecisionGuardada> r) {
        DecisionGuardada top = r.stream().filter(x -> d(x) != null && d(x).p30() != null)
                .max(Comparator.comparingDouble(x -> d(x).p30())).orElse(null);
        double sugerida = r.stream().filter(x -> x.cantidadSugerida() > 0)
                .mapToDouble(x -> x.cantidadSugerida() * x.costoUnitario()).sum();
        return new Kpis(r.size(), a.conPronostico(), top == null ? null : top.producto(),
                top == null ? null : d(top).p30(), r.stream().filter(x -> tendencia(x, "CRECIENTE")).count(),
                r.stream().filter(x -> estado(x, "AGOTADO", "RIESGO_AGOTAMIENTO")).count(),
                r.stream().filter(x -> estado(x, "SOBREINVENTARIO")).count(),
                r.stream().filter(x -> estado(x, "SIN_MOVIMIENTO")).count(), sugerida,
                a.compraAprobada() == null ? 0 : a.compraAprobada(), a.presupuesto(),
                pregunta(r, Pregunta.COMPRAR).size());
    }

    private static final Set<DecisionEstrategica> NO_COMPRAR = EnumSet.of(DecisionEstrategica.LIQUIDAR,
            DecisionEstrategica.REDUCIR, DecisionEstrategica.DESCONTINUAR);

    private static boolean comprar(DecisionGuardada r) {
        return r.cantidadSugerida() > 0 && (r.decision() == DecisionEstrategica.RECOMPRAR
                || r.decision() == DecisionEstrategica.NEGOCIAR);
    }

    private static int peso(DecisionGuardada r) {
        try {
            return Prioridad.valueOf(r.prioridad()).peso();
        } catch (IllegalArgumentException | NullPointerException e) {
            return 0;
        }
    }

    private static double cobertura(DecisionGuardada r) {
        return d(r) == null || d(r).coberturaDias() == null ? Double.MAX_VALUE : d(r).coberturaDias();
    }

    /** Crecimiento mensual relativo a la venta mensual promedio de los últimos 12 meses. */
    public static double crecimiento(DecisionGuardada r) {
        double prom = u365(r) / 12.0;
        return d(r) == null || prom <= 0 ? 0 : d(r).pendiente() / prom;
    }

    /** Rotación anual: unidades vendidas en 12 meses / stock (infinito si no hay stock). */
    public static double rotacion(DecisionGuardada r) {
        return r.stock() <= 0 ? Double.POSITIVE_INFINITY : u365(r) / r.stock();
    }

    /** Rankings que se muestran en pantalla, en orden (los demás se conservan para exportar). */
    public static final List<Ranking> VISIBLES = List.of(Ranking.MAS_VENDIDOS, Ranking.MAYOR_CRECIMIENTO,
            Ranking.MAYOR_DEMANDA, Ranking.PERDIENDO_VENTAS, Ranking.EXCESO, Ranking.PARA_RECOMPRAR, Ranking.OLVIDADOS);

    /** Unidades de más frente a lo que se venderá en 3 meses (0 si no hay exceso). */
    public static double unidadesDeMas(DecisionGuardada r) {
        double tres = d(r) != null && d(r).p90() != null ? d(r).p90() : r.demandaMensual() * 3;
        return Math.max(0, r.stock() - tres);
    }

    /** Cifra que ordena y se grafica en cada ranking (siempre en unidades o días, no porcentajes). */
    public static double valorRanking(DecisionGuardada r, Ranking k) {
        return switch (k) {
            case MAS_VENDIDOS -> u365(r);
            case MAYOR_CRECIMIENTO, PERDIENDO_VENTAS -> d(r) == null ? 0 : Math.abs(d(r).pendiente());
            case MAYOR_DEMANDA -> p30(r);
            case EXCESO -> unidadesDeMas(r);
            case PARA_RECOMPRAR -> r.cantidadSugerida();
            case MENOS_VENDIDOS -> rotacion(r);
            case OLVIDADOS -> d(r) == null || d(r).diasSinVenta() == null ? 0 : d(r).diasSinVenta();
            case NO_RECOMPRAR -> r.impactoEstimado() == null ? 0 : r.impactoEstimado();
        };
    }

    /** Unidad de la cifra de cada ranking. */
    public static String unidadRanking(Ranking k) {
        return switch (k) {
            case MAS_VENDIDOS -> "unidades vendidas en 12 meses";
            case MAYOR_CRECIMIENTO -> "unidades más por mes";
            case PERDIENDO_VENTAS -> "unidades menos por mes";
            case MAYOR_DEMANDA -> "unidades que podrías vender en 30 días";
            case EXCESO -> "unidades de más";
            case PARA_RECOMPRAR -> "unidades a pedir";
            case MENOS_VENDIDOS -> "veces que se vende el stock al año";
            case OLVIDADOS -> "días sin venderse";
            case NO_RECOMPRAR -> "impacto estimado";
        };
    }

    public static List<DecisionGuardada> ranking(List<DecisionGuardada> r, Ranking k, int diasSinMovimiento,
                                                 int limite) {
        List<DecisionGuardada> l = switch (k) {
            case MAYOR_DEMANDA -> r.stream().filter(x -> p30(x) > 0)
                    .sorted(Comparator.comparingDouble((DecisionGuardada x) -> -p30(x))).toList();
            case PERDIENDO_VENTAS -> r.stream().filter(x -> tendencia(x, "DECRECIENTE") && u365(x) > 0)
                    .sorted(Comparator.comparingDouble((DecisionGuardada x) -> d(x).pendiente())).toList();
            case EXCESO -> r.stream().filter(x -> unidadesDeMas(x) >= 1)
                    .sorted(Comparator.comparingDouble((DecisionGuardada x) -> -unidadesDeMas(x))).toList();
            case MAS_VENDIDOS -> r.stream().filter(x -> u365(x) > 0)
                    .sorted(Comparator.comparingDouble(ResultsService::u365).reversed()).toList();
            case MAYOR_CRECIMIENTO -> r.stream().filter(x -> tendencia(x, "CRECIENTE") && u365(x) > 0)
                    .sorted(Comparator.comparingDouble((DecisionGuardada x) -> -d(x).pendiente())).toList();
            case PARA_RECOMPRAR -> r.stream().filter(ResultsService::comprar)
                    .sorted(Comparator.comparingInt((DecisionGuardada x) -> -peso(x))
                            .thenComparingDouble(ResultsService::cobertura)).toList();
            case MENOS_VENDIDOS -> r.stream().filter(x -> x.stock() > 0 && u365(x) > 0)
                    .sorted(Comparator.comparingDouble(ResultsService::rotacion)).toList();
            case OLVIDADOS -> r.stream().filter(x -> d(x) != null && ((d(x).diasSinVenta() != null
                            && d(x).diasSinVenta() >= diasSinMovimiento) || (d(x).diasSinVenta() == null && x.stock() > 0)))
                    .sorted(Comparator.comparingLong((DecisionGuardada x) -> d(x).diasSinVenta() == null
                            ? Long.MAX_VALUE : d(x).diasSinVenta()).reversed()).toList();
            case NO_RECOMPRAR -> r.stream().filter(x -> NO_COMPRAR.contains(x.decision()))
                    .sorted(Comparator.comparingDouble((DecisionGuardada x) -> x.impactoEstimado() == null ? 0
                            : x.impactoEstimado()).reversed()).toList();
        };
        return limite > 0 && l.size() > limite ? l.subList(0, limite) : l;
    }

    private static double p30(DecisionGuardada r) {
        return d(r) == null || d(r).p30() == null ? 0 : d(r).p30();
    }

    /** Meses de stock frente a la demanda pronosticada (infinito si no hay demanda). */
    private static double mesesDeStock(DecisionGuardada r) {
        return r.demandaMensual() <= 0 ? Double.POSITIVE_INFINITY : r.stock() / r.demandaMensual();
    }

    /** Stock para más de 6 meses de demanda pronosticada, o sobreinventario detectado. */
    static final double MESES_EXCESO = 6;

    public static List<DecisionGuardada> pregunta(List<DecisionGuardada> r, Pregunta q) {
        Predicate<DecisionGuardada> f = switch (q) {
            case COMPRAR -> ResultsService::comprar;
            case MANTENER -> x -> x.decision() == DecisionEstrategica.MANTENER;
            case NO_RECOMPRAR -> x -> NO_COMPRAR.contains(x.decision());
            case PERDIENDO_DEMANDA -> x -> tendencia(x, "DECRECIENTE");
            case PODRIAN_AGOTARSE -> x -> x.demandaMensual() > 0 && (estado(x, "AGOTADO", "RIESGO_AGOTAMIENTO")
                    || (d(x) != null && d(x).leadTime() != null && cobertura(x) < d(x).leadTime() * 1.5));
            case MAYOR_DEMANDA -> x -> p30(x) > 0;
            case EXCESO -> x -> x.stock() > 0 && (estado(x, "SOBREINVENTARIO")
                    || (x.demandaMensual() > 0 && mesesDeStock(x) > MESES_EXCESO));
            case CRECIMIENTO -> x -> (tendencia(x, "CRECIENTE") && u365(x) > 0)
                    || x.decision() == DecisionEstrategica.PUBLICITAR;
        };
        Comparator<DecisionGuardada> orden = switch (q) {
            case COMPRAR, PODRIAN_AGOTARSE -> Comparator.comparingInt((DecisionGuardada x) -> -peso(x))
                    .thenComparingDouble(ResultsService::cobertura);
            case PERDIENDO_DEMANDA -> Comparator.comparingDouble(ResultsService::crecimiento);
            case MAYOR_DEMANDA -> Comparator.comparingDouble((DecisionGuardada x) -> -p30(x));
            case EXCESO -> Comparator.comparingDouble((DecisionGuardada x) -> -(x.stock() * x.costoUnitario()));
            case CRECIMIENTO -> Comparator.comparingDouble((DecisionGuardada x) -> -crecimiento(x));
            default -> Comparator.comparingDouble((DecisionGuardada x) -> -u365(x));
        };
        List<DecisionGuardada> l = r.stream().filter(f).sorted(orden).toList();
        return q == Pregunta.MAYOR_DEMANDA && l.size() > TOP_DEMANDA ? l.subList(0, TOP_DEMANDA) : l;
    }

    /** Resumen de una línea para la tarjeta de la pregunta (cifras clave de la lista). */
    public static String resumen(Pregunta q, List<DecisionGuardada> l) {
        if (l.isEmpty()) {
            return switch (q) {
                case COMPRAR -> "Nada que comprar por ahora.";
                case PODRIAN_AGOTARSE -> "Ningún producto en riesgo.";
                case MAYOR_DEMANDA -> "Sin pronósticos disponibles.";
                default -> "Ningún producto.";
            };
        }
        DecisionGuardada top = l.get(0);
        return switch (q) {
            case COMPRAR -> {
                long u = l.stream().mapToLong(DecisionGuardada::cantidadSugerida).sum();
                double inv = l.stream().mapToDouble(x -> x.cantidadSugerida() * x.costoUnitario()).sum();
                yield Formatters.integer(u) + " u · inversión " + Formatters.money(inv);
            }
            case PODRIAN_AGOTARSE -> l.stream().filter(x -> estado(x, "AGOTADO")).count() + " ya agotado(s) · más "
                    + "urgente: " + top.producto();
            case MAYOR_DEMANDA -> top.producto() + ": " + Formatters.number(p30(top)) + " u en 30 días";
            case PERDIENDO_DEMANDA -> "Mayor caída: " + top.producto();
            case EXCESO -> Formatters.money(l.stream().mapToDouble(x -> x.stock() * x.costoUnitario()).sum())
                    + " inmovilizados";
            case NO_RECOMPRAR -> l.stream().filter(x -> x.decision() == DecisionEstrategica.DESCONTINUAR).count()
                    + " para descontinuar · " + l.stream().filter(x -> x.decision() == DecisionEstrategica.LIQUIDAR)
                    .count() + " para liquidar";
            case CRECIMIENTO -> "Mayor crecimiento: " + top.producto();
            case MANTENER -> l.size() + " producto(s) equilibrados";
        };
    }

    /**
     * Explicación en lenguaje sencillo para el gerente: qué hacer, por qué y cuánta confianza merece la predicción.
     * Los detalles estadísticos se muestran aparte.
     */
    public static String explicacionSencilla(DecisionGuardada r) {
        DetalleAnalisis d = d(r);
        List<String> l = new ArrayList<>();
        double posicion = r.stock() + r.enTransito();
        switch (r.decision()) {
            case RECOMPRAR, NEGOCIAR -> {
                if (r.cantidadSugerida() > 0) {
                    l.add("Compre " + Formatters.integer(r.cantidadSugerida()) + " unidades"
                            + (r.decision() == DecisionEstrategica.NEGOCIAR ? " después de negociar un mejor costo"
                            : "") + ".");
                }
                if (d != null && d.p30() != null) {
                    l.add("Se espera vender unas " + Formatters.integer(Math.round(d.p30())) + " unidades en los "
                            + "próximos 30 días y hoy tiene " + Formatters.integer(posicion) + " (contando lo ya "
                            + "pedido)" + (d.leadTime() != null ? "; el proveedor tarda " + d.leadTime()
                            + " días en entregar." : "."));
                }
            }
            case DESCONTINUAR -> l.add("No vuelva a comprarlo"
                    + (d != null && d.diasSinVenta() != null ? ": lleva " + d.diasSinVenta() + " días sin venderse."
                    : "."));
            case LIQUIDAR -> l.add("No lo reponga y considere venderlo con descuento: tiene "
                    + Formatters.integer(r.stock()) + " unidades, mucho más de lo que se venderá pronto.");
            case REDUCIR -> l.add("Reduzca o pause las próximas compras: el stock alcanza para bastante tiempo o la "
                    + "demanda está bajando.");
            case PUBLICITAR -> l.add("Buen margen pero se vende poco: dele más visibilidad o una promoción.");
            case MANTENER -> l.add("Todo en orden: el stock acompaña a la demanda. No hace falta actuar.");
            case DATOS_INSUFICIENTES -> l.add("Aún no hay suficientes ventas para recomendar algo con fundamento.");
        }
        if (d != null && d.tendencia() != null) {
            switch (d.tendencia()) {
                case "CRECIENTE" -> l.add("Sus ventas vienen creciendo.");
                case "DECRECIENTE" -> l.add("Sus ventas vienen bajando.");
                default -> {
                }
            }
        }
        if (d != null && d.coberturaDias() != null && r.demandaMensual() > 0) {
            l.add(d.coberturaDias() < 1 ? "Ya está agotado." : "El stock actual alcanza para unos "
                    + Formatters.integer(Math.round(d.coberturaDias())) + " días.");
        }
        l.add(confianzaSencilla(d));
        return String.join(" ", l);
    }

    /** Cuánto confiar en la predicción, en palabras. */
    public static String confianzaSencilla(DetalleAnalisis d) {
        if (d == null || d.confianza() == null) {
            return "Sin predicción: no hay historial suficiente.";
        }
        String error = d.wape() == null ? "" : " (en meses pasados se equivocó en promedio un "
                + Math.round(d.wape() * 100) + " %)";
        return switch (d.confianza()) {
            case "ALTA" -> "Predicción confiable" + error + ".";
            case "MEDIA" -> "Predicción razonable" + error + "; revise el rango mínimo-máximo.";
            case ResultadosBuilder.PROVISIONAL -> "Producto nuevo: estimación provisional basada en pocas semanas de "
                    + "ventas, sin precisión comprobada.";
            default -> "Predicción poco confiable" + error + ": úsela solo como referencia.";
        };
    }

    /**
     * Qué datos sustentan el análisis y qué limitaciones tienen. Las ventas son la demanda; las compras y el
     * inventario nunca se usan como demanda; los días sin stock pueden ocultar demanda no registrada.
     */
    public List<FuenteDatos> fuentes(Analisis a, List<DecisionGuardada> r) {
        List<FuenteDatos> out = new ArrayList<>();
        long meses = a.ventasDesde() == null || a.ventasHasta() == null ? 0
                : ChronoUnit.MONTHS.between(a.ventasDesde().withDayOfMonth(1), a.ventasHasta().withDayOfMonth(1)) + 1;
        out.add(new FuenteDatos("Ventas", "Son la demanda: lo único que se usa para predecir.",
                a.registrosVentas() == 0 ? "Sin ventas" : Formatters.integer(a.registrosVentas()) + " registros"
                        + (a.ventasDesde() == null ? "" : " del " + Formatters.date(a.ventasDesde()) + " al "
                        + Formatters.date(a.ventasHasta()) + " (" + meses + " meses)"), a.registrosVentas() > 0));
        if (db != null) {
            long dev = db.queryLong("SELECT COUNT(*) FROM devoluciones");
            out.add(new FuenteDatos("Devoluciones", "Se restan de las ventas para obtener la demanda neta.",
                    dev == 0 ? "No registradas" : Formatters.integer(dev) + " registros", true));
            long conStock = db.queryLong("SELECT COUNT(DISTINCT producto_id) FROM inventario WHERE stock > 0");
            out.add(new FuenteDatos("Inventario", "Stock actual: días de cobertura, riesgo de agotarse y exceso. "
                    + "No es demanda.", conStock == 0 ? "No importado: el stock se toma como 0 y las alertas de "
                    + "agotamiento no son fiables" : Formatters.integer(conStock) + " productos con stock",
                    conStock > 0));
            long compras = db.queryLong("SELECT COUNT(*) FROM compras");
            long pendientes = db.queryLong("SELECT COUNT(*) FROM compras WHERE estado <> 'RECIBIDA'");
            out.add(new FuenteDatos("Compras", "Pedidos en tránsito y reposiciones pasadas. Nunca se cuentan como "
                    + "demanda.", compras == 0 ? "No importadas: no se descuentan pedidos en camino"
                    : Formatters.integer(compras) + " registros (" + pendientes + " pendientes de recibir)",
                    compras > 0));
            boolean medible = new com.stockwise.repository.StockHistoricoRepository(db).hayEntradas();
            String faltantes;
            if (!medible) {
                faltantes = "No medible: sin compras recibidas no se sabe si hubo días sin stock. Si los hubo, la "
                        + "demanda real pudo ser mayor que la venta registrada.";
            } else {
                long sinStock = db.queryLong("SELECT COUNT(DISTINCT producto_id) FROM stock_historico "
                        + "WHERE stock <= 0 AND fecha > ? AND fecha <= ?", a.fechaCorte().minusDays(90), a.fechaCorte());
                faltantes = sinStock == 0 ? "Sin días sin stock en los 90 días previos al corte."
                        : sinStock + " producto(s) estuvieron sin stock en los últimos 90 días: su demanda real "
                        + "puede ser mayor que lo vendido.";
            }
            out.add(new FuenteDatos("Faltantes de stock", "Días sin stock ocultan demanda (no se pudo vender).",
                    faltantes, medible));
        }
        long pocos = r.stream().filter(x -> d(x) != null && d(x).confianza() != null
                && (d(x).puntosValidacion() == null || d(x).puntosValidacion() < 2)).count();
        long provisionales = r.stream().filter(x -> d(x) != null
                && ResultadosBuilder.PROVISIONAL.equals(d(x).confianza())).count();
        long sinPronostico = r.stream().filter(x -> d(x) == null || d(x).p30() == null).count();
        out.add(new FuenteDatos("Historial por producto", "Para comprobar la precisión se pronostican meses pasados "
                + "y se comparan con lo vendido (backtesting).", sinPronostico + " sin pronóstico (historial "
                + "insuficiente) · " + provisionales + " nuevos con estimación provisional · " + pocos
                + " con precisión no demostrada", sinPronostico + pocos == 0));
        return out;
    }

    public int diasSinMovimiento() {
        return config.get().diasSinMovimiento();
    }

    // ------------------------------------------------------------------ comparación

    /** Compara dos corridas; el orden de los argumentos no importa (se ordenan por fecha de corte y id). */
    public Comparacion comparar(long idA, long idB) {
        Analisis a = analisis.findById(idA).orElseThrow(() -> new ValidationException("El análisis no existe."));
        Analisis b = analisis.findById(idB).orElseThrow(() -> new ValidationException("El análisis no existe."));
        if (a.fechaCorte().isAfter(b.fechaCorte()) || (a.fechaCorte().equals(b.fechaCorte()) && a.id() > b.id())) {
            Analisis t = a;
            a = b;
            b = t;
        }
        Map<Long, DecisionGuardada> ra = new HashMap<>();
        resultados.porAnalisis(a.id()).forEach(x -> ra.put(x.productoId(), x));
        List<DecisionGuardada> rb = resultados.porAnalisis(b.id());
        int dias = (int) Math.min(DIAS_EVALUACION, Math.max(0, ChronoUnit.DAYS.between(a.fechaCorte(), b.fechaCorte())));
        Map<Long, Double> reales = dias > 0 ? ventas.netoPorProductoEntre(a.fechaCorte(), a.fechaCorte().plusDays(dias))
                : Map.of();
        List<CambioProducto> cambios = new ArrayList<>();
        double sumAbs = 0;
        double sumErr = 0;
        double sumReal = 0;
        for (DecisionGuardada nb : rb) {
            DecisionGuardada na = ra.get(nb.productoId());
            Double pronAnt = null;
            Double real = null;
            if (na != null && dias > 0 && d(na) != null && d(na).pronosticoMensual() != null) {
                pronAnt = HorizonForecast.acumulado(ResultadosBuilder.leerTextos(d(na).periodosFuturos()),
                        ResultadosBuilder.leerNumeros(d(na).pronosticoMensual()), a.fechaCorte(), dias);
                real = reales.getOrDefault(nb.productoId(), 0.0);
                sumAbs += Math.abs(pronAnt - real);
                sumErr += pronAnt - real;
                sumReal += real;
            }
            Double p30a = na == null || d(na) == null ? null : d(na).p30();
            Double p30b = d(nb) == null ? null : d(nb).p30();
            Double variacion = p30a == null || p30b == null || p30a <= 0 ? null : (p30b - p30a) / p30a;
            cambios.add(new CambioProducto(nb.productoId(), nb.codigo(), nb.producto(), p30a, p30b, variacion,
                    na == null ? null : na.patron(), nb.patron(), na == null ? null : na.decision(), nb.decision(),
                    na == null || d(na) == null ? null : d(na).tendencia(), d(nb) == null ? null : d(nb).tendencia(),
                    na == null || d(na) == null ? null : d(na).wape(), d(nb) == null ? null : d(nb).wape(), pronAnt,
                    real));
        }
        long cambiosPatron = cambios.stream().filter(c -> c.patronAnterior() != null && c.cambioPatron()).count();
        long cambiosDecision = cambios.stream().filter(c -> c.decisionAnterior() != null && c.cambioDecision()).count();
        Double wapeReal = dias > 0 && sumReal > 0 ? sumAbs / sumReal : null;
        Double sesgoReal = dias > 0 && sumReal > 0 ? sumErr / sumReal : null;
        List<String> resumen = new ArrayList<>();
        if (dias == 0) {
            resumen.add("Ambos análisis tienen la misma fecha de corte: la precisión real del análisis anterior queda "
                    + "pendiente hasta importar ventas posteriores al " + a.fechaCorte() + ".");
        } else if (wapeReal == null) {
            resumen.add("No hubo ventas en los " + dias + " días posteriores al análisis anterior: no se puede medir "
                    + "su precisión real.");
        } else {
            resumen.add(String.format("Precisión real del análisis anterior en los %d días siguientes a su corte: "
                    + "WAPE %.1f%% (%s %.1f%%).", dias, wapeReal * 100, sesgoReal >= 0 ? "sobreestimó" : "subestimó",
                    Math.abs(sesgoReal) * 100));
        }
        if (a.wape() != null && b.wape() != null) {
            double dif = (b.wape() - a.wape()) * 100;
            resumen.add(Math.abs(dif) < 0.05 ? String.format("Error de backtesting (WAPE): %.1f%% en ambos análisis (sin cambios).",
                    b.wape() * 100) : String.format("Error de backtesting (WAPE): %.1f%% → %.1f%% (%s %.1f puntos).",
                    a.wape() * 100, b.wape() * 100, dif < 0 ? "mejoró" : "empeoró", Math.abs(dif)));
        }
        resumen.add(cambiosPatron + " producto(s) cambiaron su patrón de demanda y " + cambiosDecision
                + " su recomendación.");
        return new Comparacion(a, b, dias, wapeReal, sesgoReal, cambios, cambiosPatron, cambiosDecision, resumen);
    }

    /** Pronóstico de una corrida para los próximos días desde su corte (para el detalle de producto). */
    public static double pronosticoDias(DecisionGuardada r, LocalDate corte, int dias) {
        if (d(r) == null || d(r).pronosticoMensual() == null) {
            return 0;
        }
        return HorizonForecast.acumulado(ResultadosBuilder.leerTextos(d(r).periodosFuturos()),
                ResultadosBuilder.leerNumeros(d(r).pronosticoMensual()), corte, dias);
    }
}
