package com.stockwise.controller;

import com.stockwise.util.Icons;

import java.util.Arrays;
import java.util.List;

/**
 * Pantallas de la aplicación. El menú lateral muestra los módulos principales agrupados en secciones (Proyecto,
 * Análisis, Gestión, Datos, Informes); las herramientas secundarias son pantallas hijas a las que se llega desde la
 * barra superior de su módulo (pestañas o "Más herramientas"). Cada pantalla tiene un único lugar.
 */
public enum View {
    INICIO("Inicio", "inicio", Icons.HOME),
    /** Panel con todos los indicadores: hijo de Inicio. */
    DASHBOARD("Panel completo", "dashboard", Icons.DASHBOARD),
    /** Módulo de demanda: abre los resultados del análisis. */
    DEMANDA("Demanda", null, Icons.TREND),
    RESULTADOS("Resultados del análisis", "resultados", Icons.ANALYTICS),
    COMPRAS("Compras y presupuesto", "compras", Icons.PURCHASE),
    PREDICCION("Pronóstico detallado por producto", "prediccion", Icons.TREND),
    PLANIFICACION("Planificación avanzada de inventario", "planificacion", Icons.ANALYTICS),
    /** Pantalla de procesamiento del análisis: se abre con los botones "Analizar". */
    PROCESAMIENTO("Analizando", "procesamiento", Icons.REFRESH),
    ACCIONES("Acciones recomendadas", "acciones", Icons.ADVISOR),
    ASESOR("Todas las alertas del asesor", "asesor", Icons.ADVISOR),
    PRODUCTOS("Productos", "productos", Icons.PRODUCTS),
    INVENTARIO("Inventario", "inventario", Icons.INVENTORY),
    SUCURSALES("Sucursales", "sucursales", Icons.STORE),
    VENTAS("Ventas", "ventas", Icons.SALES),
    IMPORTACION("Importar Excel / CSV", "importacion", Icons.IMPORT),
    HISTORIAL("Historial", "historial", Icons.HISTORY),
    REPORTES("Reportes y exportación", "reportes", Icons.REPORT),
    CONFIGURACION("Configuración", "configuracion", Icons.SETTINGS);

    /** Cómo se accede a una pantalla. */
    public enum Acceso {
        /** Elemento del menú lateral. */
        MENU,
        /** Pestaña visible en la barra del módulo. */
        PASO,
        /** Dentro del desplegable "Más herramientas". */
        HERRAMIENTA,
        /** Solo desde botones de otras pantallas (p. ej. "Analizar"). */
        OCULTO
    }

    private final String titulo;
    private final String fxml;
    private final Icons icono;

    View(String titulo, String fxml, Icons icono) {
        this.titulo = titulo;
        this.fxml = fxml;
        this.icono = icono;
    }

    /** Módulo del menú al que pertenece la pantalla (nulo si es un módulo del menú). */
    public View padre() {
        return switch (this) {
            case DASHBOARD -> INICIO;
            case RESULTADOS, COMPRAS, PREDICCION, PLANIFICACION, PROCESAMIENTO -> DEMANDA;
            case ASESOR -> ACCIONES;
            case SUCURSALES -> INVENTARIO;
            default -> null;
        };
    }

    public Acceso acceso() {
        return switch (this) {
            case PREDICCION, PLANIFICACION, ASESOR -> Acceso.HERRAMIENTA;
            case PROCESAMIENTO -> Acceso.OCULTO;
            default -> padre() == null ? Acceso.MENU : Acceso.PASO;
        };
    }

    /** Verdadero para los módulos del menú lateral. */
    public boolean enMenu() {
        return padre() == null;
    }

    /** Título de la sección del menú que empieza en este módulo (o nulo). */
    public String seccion() {
        return switch (this) {
            case INICIO -> "Proyecto";
            case DEMANDA -> "Análisis";
            case PRODUCTOS -> "Gestión";
            case IMPORTACION -> "Datos";
            case REPORTES -> "Informes";
            default -> null;
        };
    }

    /** Módulo del menú que se resalta cuando esta pantalla está abierta. */
    public View modulo() {
        return padre() == null ? this : padre();
    }

    /** Pantalla que se abre al elegir el módulo (Demanda abre Resultados). */
    public View destino() {
        return this == DEMANDA ? RESULTADOS : this;
    }

    /** Pantallas hijas del módulo con el acceso indicado, en orden. */
    public List<View> hijos(Acceso acceso) {
        return Arrays.stream(values()).filter(v -> v.padre() == this && v.acceso() == acceso).toList();
    }

    /** Módulos del menú lateral, en orden. */
    public static List<View> menu() {
        return Arrays.stream(values()).filter(View::enMenu).toList();
    }

    public String titulo() {
        return titulo;
    }

    /** Título corto para la pestaña de la barra del módulo. */
    public String tituloCorto() {
        return switch (this) {
            case INICIO -> "Resumen";
            case INVENTARIO -> "Existencias";
            case RESULTADOS -> "Resultados";
            case ACCIONES -> "Acciones";
            default -> titulo;
        };
    }

    public String recurso() {
        return "/views/" + destino().fxml + ".fxml";
    }

    public Icons icono() {
        return icono;
    }
}
