package com.stockwise.service;

import com.stockwise.model.Recomendacion;
import com.stockwise.model.TipoRecomendacion;
import com.stockwise.recommendation.DecisionEstrategica;
import com.stockwise.repository.AnalisisRepository.Analisis;
import com.stockwise.repository.DecisionRepository;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import com.stockwise.repository.DecisionRepository.DetalleAnalisis;
import com.stockwise.repository.RecomendacionRepository;
import com.stockwise.util.Formatters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Acciones recomendadas para el dueño, en lenguaje sencillo: qué pasa, qué considerar hacer, por qué, cuánto confiar
 * en la predicción, qué riesgo tiene y qué se espera lograr. Son ayudas, no órdenes: aceptar, rechazar o posponer solo
 * registra la decisión del dueño; nunca se modifican precios, inventario ni compras.
 */
public class AccionesService {

    /** Tipo de acción, con el color que la identifica (verde = oportunidad, rojo = alerta, neutro = información). */
    public enum TipoAccion {
        COMPRAR_MAS("Comprar más", "rojo"),
        TRASLADAR("Trasladar a otra sucursal", "naranja"),
        NEGOCIAR("Negociar con el proveedor", "naranja"),
        COMPRAR_MENOS("Comprar menos", "naranja"),
        NO_COMPRAR("No comprar por ahora", "naranja"),
        LIQUIDAR("Liquidar exceso de inventario", "naranja"),
        REDUCIR_PRECIO("Reducir el precio", "naranja"),
        PROMOCIONAR("Promocionar", "verde"),
        EVALUAR_PRECIO("Evaluar un nuevo precio", "verde"),
        MANTENER("Mantener sin cambios", "neutro");

        private final String etiqueta;
        private final String color;

        TipoAccion(String etiqueta, String color) {
            this.etiqueta = etiqueta;
            this.color = color;
        }

        public String etiqueta() {
            return etiqueta;
        }

        public String color() {
            return color;
        }

        /** Acciones en las que el simulador de precios ayuda a decidir. */
        public boolean usaPrecio() {
            return this == LIQUIDAR || this == REDUCIR_PRECIO || this == EVALUAR_PRECIO || this == PROMOCIONAR;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    /** De dónde viene la acción. */
    public enum Origen {
        DECISION, TRASLADO
    }

    /** Estado de la acción según lo que decidió el dueño. */
    public enum Estado {
        PENDIENTE("Pendiente"), ACEPTADA("Aceptada"), RECHAZADA("Rechazada"), POSPUESTA("Pospuesta");

        private final String etiqueta;

        Estado(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        @Override
        public String toString() {
            return etiqueta;
        }

        static Estado de(String guardado) {
            if (guardado == null) {
                return PENDIENTE;
            }
            return switch (guardado) {
                case DecisionRepository.APLICADA -> ACEPTADA;
                case DecisionRepository.DESCARTADA -> RECHAZADA;
                case DecisionRepository.POSPUESTA -> POSPUESTA;
                default -> PENDIENTE;
            };
        }

        String guardado() {
            return switch (this) {
                case ACEPTADA -> DecisionRepository.APLICADA;
                case RECHAZADA -> DecisionRepository.DESCARTADA;
                case POSPUESTA -> DecisionRepository.POSPUESTA;
                case PENDIENTE -> DecisionRepository.PENDIENTE;
            };
        }
    }

    /**
     * Acción recomendada.
     *
     * @param errorPrediccion texto con el error de predicción y el periodo de validación
     * @param errorAlto       la predicción es poco confiable: se advierte junto a la recomendación
     */
    public record Accion(Origen origen, long id, long productoId, String codigo, String producto, TipoAccion tipo,
                         int prioridad, String situacion, String recomendacion, String motivo, String errorPrediccion,
                         boolean errorAlto, String riesgo, String resultadoEsperado, Estado estado, double precioVenta,
                         double costo, double demanda30, int cantidad, String detalleTecnico) {
    }

    /** Error de predicción (WAPE) a partir del cual se advierte que la recomendación es incierta. */
    public static final double ERROR_ALTO = 0.5;

    private final ResultsService resultados;
    private final DecisionRepository decisiones;
    private final RecomendacionRepository recomendaciones;
    private final ConfigService config;

    public AccionesService(ResultsService resultados, DecisionRepository decisiones,
                           RecomendacionRepository recomendaciones, ConfigService config) {
        this.resultados = resultados;
        this.decisiones = decisiones;
        this.recomendaciones = recomendaciones;
        this.config = config;
    }

    /** Acciones del último análisis (vacío si aún no se analizó), de la más a la menos urgente. */
    public List<Accion> acciones() {
        Analisis a = resultados.ultimo().orElse(null);
        if (a == null) {
            return List.of();
        }
        double margenMinimo = config.get().margenMinimo();
        boolean inventario = resultados.hayInventario();
        List<Accion> out = new ArrayList<>();
        for (DecisionGuardada d : resultados.resultados(a.id())) {
            Accion x = desdeDecision(d, margenMinimo, inventario);
            if (x != null) {
                out.add(x);
            }
        }
        out.addAll(traslados(recomendaciones == null ? List.of() : recomendaciones.findAll()));
        out.sort(Comparator.comparingInt(Accion::prioridad).thenComparing(x -> x.tipo().ordinal())
                .thenComparing(Accion::producto));
        return out;
    }

    /** Registra la decisión del dueño. No ejecuta compras, traslados ni cambios de precio. */
    public void marcar(Accion a, Estado estado) {
        if (a.origen() == Origen.DECISION) {
            decisiones.updateEstado(a.id(), estado.guardado());
        } else {
            recomendaciones.updateEstado(a.id(), estado.guardado());
        }
    }

    /** Verdadero si el proyecto tiene inventario (sin él no se proponen compras, excesos ni liquidaciones). */
    public boolean inventarioRegistrado() {
        return resultados.hayInventario();
    }

    public double margenMinimo() {
        return config.get().margenMinimo();
    }

    // ------------------------------------------------------------------ construcción de cada acción

    private static int prioridad(String p) {
        if (p == null) {
            return 3;
        }
        return switch (p) {
            case "CRITICA" -> 0;
            case "ALTA" -> 1;
            case "MEDIA" -> 2;
            default -> 3;
        };
    }

    private static String u(double v) {
        return Formatters.integer(Math.round(v)) + " u";
    }

    /** Error de predicción y periodo de validación, en palabras. */
    static String error(DetalleAnalisis d) {
        if (d == null || d.confianza() == null) {
            return "Sin predicción: no hay historial suficiente para estimar las ventas.";
        }
        if (ResultadosBuilder.PROVISIONAL.equals(d.confianza())) {
            return "Producto nuevo: estimación provisional con pocas semanas de ventas, sin precisión comprobada.";
        }
        if (d.wape() == null) {
            return "Error de predicción no medible: no hubo meses suficientes para comprobarlo.";
        }
        int meses = d.puntosValidacion() == null ? 0 : d.puntosValidacion();
        return String.format("Error de predicción: %.0f %% (comprobado pronosticando %d mes(es) pasados y comparando con "
                + "lo que realmente se vendió).", d.wape() * 100, meses);
    }

    static boolean errorAlto(DetalleAnalisis d) {
        if (d == null || d.confianza() == null) {
            return true;
        }
        return ResultadosBuilder.PROVISIONAL.equals(d.confianza()) || "BAJA".equals(d.confianza())
                || "INSUFICIENTE".equals(d.confianza()) || d.wape() == null || d.wape() > ERROR_ALTO;
    }

    static Accion desdeDecision(DecisionGuardada r, double margenMinimo) {
        return desdeDecision(r, margenMinimo, true);
    }

    /**
     * @param hayInventario sin inventario registrado el stock es desconocido: solo se proponen las acciones que no
     *                      dependen de él (dejar de comprar lo que no se vende y negociar el costo)
     */
    static Accion desdeDecision(DecisionGuardada r, double margenMinimo, boolean hayInventario) {
        if (!hayInventario && r.decision() != DecisionEstrategica.DESCONTINUAR
                && r.decision() != DecisionEstrategica.NEGOCIAR) {
            return null;
        }
        DetalleAnalisis d = r.detalle();
        if (r.decision() == DecisionEstrategica.DATOS_INSUFICIENTES) {
            return null;
        }
        double p30 = d != null && d.p30() != null ? d.p30() : r.demandaMensual();
        double margen = r.precioVenta() > 0 ? (r.precioVenta() - r.costoUnitario()) / r.precioVenta() : 0;
        boolean bajando = d != null && "DECRECIENTE".equals(d.tendencia());
        boolean creciendo = d != null && "CRECIENTE".equals(d.tendencia());
        double meses = r.demandaMensual() > 0 ? r.stock() / r.demandaMensual() : Double.POSITIVE_INFINITY;
        String mesesTxt = Double.isInfinite(meses) ? "mucho tiempo" : String.format("unos %.0f mes(es)", Math.max(1, meses));
        double valorStock = r.stock() * r.costoUnitario();
        String dias = d != null && d.coberturaDias() != null && r.demandaMensual() > 0
                ? " El stock alcanza para unos " + Formatters.integer(Math.round(d.coberturaDias())) + " días"
                + (d.leadTime() != null ? " y el proveedor tarda " + d.leadTime() + " días en entregar." : ".") : "";
        TipoAccion tipo;
        String situacion;
        String recomendacion;
        String motivo;
        String riesgo;
        String resultado;
        switch (r.decision()) {
            case RECOMPRAR -> {
                tipo = TipoAccion.COMPRAR_MAS;
                situacion = "Te quedan " + u(r.stock()) + (r.enTransito() > 0 ? " (+" + u(r.enTransito()) + " en camino)"
                        : "") + " y podrías vender unas " + u(p30) + " en los próximos 30 días." + dias;
                recomendacion = r.cantidadSugerida() > 0 ? "Considera comprar " + u(r.cantidadSugerida())
                        + " (inversión aproximada " + Formatters.money(r.cantidadSugerida() * r.costoUnitario()) + ")."
                        : "Considera reponer en el próximo pedido.";
                motivo = "Si no repones, podrías quedarte sin producto antes de que llegue el siguiente pedido.";
                riesgo = "Si se vende menos de lo previsto, quedarían unidades guardadas y dinero inmovilizado.";
                resultado = "No perder ventas por falta de stock: unas " + u(p30) + " en 30 días (≈ "
                        + Formatters.money(p30 * r.precioVenta()) + " en ventas).";
            }
            case NEGOCIAR -> {
                tipo = TipoAccion.NEGOCIAR;
                situacion = String.format("Cada unidad deja solo %.0f %% de ganancia; tu mínimo es %.0f %%.", margen * 100,
                        margenMinimo * 100);
                recomendacion = "Antes de volver a comprar, intenta conseguir un mejor costo con el proveedor"
                        + (hayInventario && r.cantidadSugerida() > 0 ? " (el próximo pedido sería de " + u(r.cantidadSugerida()) + ")."
                        : ".");
                motivo = "La ganancia por unidad está por debajo del mínimo que configuraste.";
                riesgo = "Si el costo no baja, seguirás vendiendo con poca ganancia o tendrás que subir el precio.";
                resultado = "Cada S/ 0.50 menos de costo sumaría unos " + Formatters.money(0.5 * p30)
                        + " de ganancia al mes con las ventas previstas.";
            }
            case REDUCIR -> {
                boolean precio = bajando && margen > margenMinimo + 0.05;
                tipo = precio ? TipoAccion.REDUCIR_PRECIO : TipoAccion.COMPRAR_MENOS;
                situacion = "Tienes " + u(r.stock()) + ", suficiente para " + mesesTxt + " de ventas"
                        + (bajando ? ", y las ventas vienen bajando." : ".");
                recomendacion = precio ? "Evalúa una rebaja pequeña para vender más rápido y pausa las compras. Usa el "
                        + "simulador de precio antes de decidir." : "Compra menos o pausa los próximos pedidos hasta que "
                        + "el stock baje.";
                motivo = "Tienes más productos de los que probablemente venderás pronto.";
                riesgo = precio ? "Una rebaja reduce la ganancia por unidad; si las ventas no suben lo suficiente, "
                        + "ganarías menos." : "Si la demanda sube de pronto podrías quedarte corto: revisa el stock "
                        + "cada semana.";
                resultado = "No invertir más en un producto que ya tienes de sobra (stock actual ≈ "
                        + Formatters.money(valorStock) + " al costo).";
            }
            case DESCONTINUAR -> {
                tipo = TipoAccion.NO_COMPRAR;
                Long sin = d == null ? null : d.diasSinVenta();
                situacion = (sin == null ? "No registra ventas" : "Lleva " + sin + " días sin venderse")
                        + (r.stock() > 0 ? " y tienes " + u(r.stock()) + " guardadas." : ".");
                recomendacion = "No compres más por ahora.";
                motivo = "No hay ventas recientes que justifiquen reponerlo.";
                riesgo = "Si es un producto de temporada podría volver a venderse: revísalo antes de retirarlo.";
                resultado = "No inmovilizar más dinero en un producto que no se vende"
                        + (valorStock > 0 ? " (hoy hay " + Formatters.money(valorStock) + " en stock)." : ".");
            }
            case LIQUIDAR -> {
                tipo = TipoAccion.LIQUIDAR;
                situacion = "Tienes " + u(r.stock()) + " (≈ " + Formatters.money(valorStock)
                        + " al costo) y se venden pocas: unas " + u(p30) + " en 30 días.";
                recomendacion = "Considera venderlo con descuento para recuperar dinero. Usa el simulador para ver "
                        + "hasta dónde puedes bajar sin perder.";
                motivo = "Tienes muchos más productos de los que probablemente venderás.";
                riesgo = "El descuento reduce la ganancia por unidad. No bajes del costo ("
                        + Formatters.money(r.costoUnitario()) + ").";
                resultado = "Recuperar parte de los " + Formatters.money(valorStock) + " inmovilizados y liberar espacio.";
            }
            case PUBLICITAR -> {
                tipo = TipoAccion.PROMOCIONAR;
                situacion = String.format("Deja buena ganancia (%.0f %% por unidad) pero se vende poco%s.", margen * 100,
                        bajando ? " y viene bajando" : "");
                recomendacion = "Dale más visibilidad: mejor ubicación, combo o una promoción corta.";
                motivo = "Es rentable: vender más unidades sumaría ganancia.";
                riesgo = "La promoción tiene un costo y no garantiza más ventas.";
                resultado = "Más ventas de un producto que deja buen margen.";
            }
            default -> {
                boolean evaluar = creciendo && margen >= margenMinimo && meses > 1;
                tipo = evaluar ? TipoAccion.EVALUAR_PRECIO : TipoAccion.MANTENER;
                situacion = evaluar ? "Las ventas vienen creciendo y el stock acompaña." : "El stock acompaña a las ventas.";
                recomendacion = evaluar ? "Puedes evaluar un precio un poco mayor. Simúlalo antes de decidir."
                        : "No hace falta hacer cambios.";
                motivo = evaluar ? "Cuando la demanda crece, a veces el cliente acepta un pequeño aumento."
                        : "Stock y ventas están equilibrados.";
                riesgo = evaluar ? "Un precio mayor puede reducir las ventas; no hay datos para saber cuánto."
                        : "Ninguno inmediato: revisa en el próximo análisis.";
                resultado = evaluar ? "Más ganancia por unidad si las ventas se mantienen." : "Seguir vendiendo con normalidad.";
            }
        }
        String tecnico = "Motivo técnico: " + r.motivo() + "\nAcción sugerida por el motor: " + r.accion()
                + (d == null ? "" : String.format("%nReserva para no quedarte sin stock: %s u · punto de pedido: %s u · "
                        + "nivel objetivo: %s u · modelo: %s", Formatters.number(r.stockSeguridad()),
                Formatters.number(r.puntoReorden()), Formatters.number(r.nivelObjetivo()), r.modelo()));
        return new Accion(Origen.DECISION, r.id(), r.productoId(), r.codigo(), r.producto(), tipo,
                prioridad(r.prioridad()), situacion, recomendacion, motivo, error(d), errorAlto(d), riesgo, resultado,
                Estado.de(r.estado()), r.precioVenta(), r.costoUnitario(), p30, r.cantidadSugerida(), tecnico);
    }

    /**
     * Traslados entre sucursales propuestos por el asesor (ventas reales de los últimos 90 días por sucursal). Si hay
     * varios para el mismo producto y sucursales se muestra uno solo, el más reciente.
     */
    static List<Accion> traslados(List<Recomendacion> recs) {
        List<Accion> out = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        List<Recomendacion> orden = new ArrayList<>(recs);
        orden.sort(Comparator.comparingLong(Recomendacion::id).reversed());
        for (Recomendacion r : orden) {
            if (r.tipo() != TipoRecomendacion.TRASLADO || r.productoId() == null
                    || !vistos.add(r.productoId() + "|" + r.sucursalId() + "|" + r.sucursalDestinoId())) {
                continue;
            }
            Map<String, Estado> estados = Map.of(RecomendacionRepository.APLICADA, Estado.ACEPTADA,
                    RecomendacionRepository.DESCARTADA, Estado.RECHAZADA, DecisionRepository.POSPUESTA, Estado.POSPUESTA);
            // El asesor guarda el producto como "CÓDIGO - Nombre".
            String codigo = "";
            String nombre = r.producto() == null ? "" : r.producto();
            int sep = nombre.indexOf(" - ");
            if (sep > 0) {
                codigo = nombre.substring(0, sep).trim();
                nombre = nombre.substring(sep + 3).trim();
            }
            out.add(new Accion(Origen.TRASLADO, r.id(), r.productoId(), codigo, nombre, TipoAccion.TRASLADAR,
                    prioridad(r.prioridad() == null ? null : r.prioridad().name()), r.problema(),
                    r.accion() + " Cuando lo hagas, regístralo en Inventario.", r.justificacion(),
                    "No depende de la predicción: se calcula con las ventas reales de los últimos 90 días de cada sucursal.",
                    false, "El traslado tiene costo de transporte; confirma el stock físico antes de mover productos.",
                    "Cubrir la demanda de " + r.sucursalDestino() + " sin comprar más"
                            + (r.beneficio() == null ? "." : " (evita invertir ≈ " + Formatters.money(r.beneficio()) + ")."),
                    estados.getOrDefault(r.estado(), Estado.PENDIENTE), 0, 0, 0, 0, ""));
        }
        return out;
    }
}
