package com.stockwise.importer;

import com.stockwise.database.Database;
import com.stockwise.database.SchemaInitializer;
import com.stockwise.importer.FieldDef.FieldType;
import com.stockwise.importer.ImportRow.Estado;
import com.stockwise.model.Producto;
import com.stockwise.model.Sucursal;
import com.stockwise.model.TipoMovimiento;
import com.stockwise.repository.CategoriaRepository;
import com.stockwise.repository.CompraRepository;
import com.stockwise.repository.DevolucionRepository;
import com.stockwise.repository.ImportacionRepository;
import com.stockwise.repository.ConfigRepository;
import com.stockwise.model.Compra;
import com.stockwise.model.Proveedor;
import com.stockwise.repository.PresupuestoRepository;
import com.stockwise.repository.ProductoRepository;
import com.stockwise.repository.ProveedorRepository;
import com.stockwise.repository.SucursalRepository;
import com.stockwise.repository.VentaRepository;
import com.stockwise.service.InventarioService;
import com.stockwise.service.Progreso;
import com.stockwise.service.ValidationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Importación adaptable de Excel: convierte y valida cada fila según el tipo elegido, detecta duplicados
 * (en el archivo y en la base de datos) y guarda las filas válidas en una única transacción.
 */
public class ImportService {

    private static final Logger LOG = Logger.getLogger(ImportService.class.getName());

    private final Database db;
    private final ProductoRepository productos;
    private final SucursalRepository sucursales;
    private final CategoriaRepository categorias;
    private final ProveedorRepository proveedores;
    private final VentaRepository ventas;
    private final PresupuestoRepository presupuestos;
    private final InventarioService inventario;
    private final CompraRepository compras;
    private final ConfigRepository config;
    private final PlantillasImportacion plantillas;
    private final ImportacionRepository importaciones;
    private final DevolucionRepository devoluciones;

    public ImportService(Database db, ProductoRepository productos, SucursalRepository sucursales,
                         CategoriaRepository categorias, ProveedorRepository proveedores, VentaRepository ventas,
                         PresupuestoRepository presupuestos, InventarioService inventario) {
        this(db, productos, sucursales, categorias, proveedores, ventas, presupuestos, inventario,
                new CompraRepository(db), new ConfigRepository(db));
    }

    public ImportService(Database db, ProductoRepository productos, SucursalRepository sucursales,
                         CategoriaRepository categorias, ProveedorRepository proveedores, VentaRepository ventas,
                         PresupuestoRepository presupuestos, InventarioService inventario, CompraRepository compras,
                         ConfigRepository config) {
        this.compras = compras;
        this.importaciones = new ImportacionRepository(db);
        this.devoluciones = new DevolucionRepository(db);
        this.config = config;
        this.plantillas = new PlantillasImportacion(config);
        this.db = db;
        this.productos = productos;
        this.sucursales = sucursales;
        this.categorias = categorias;
        this.proveedores = proveedores;
        this.ventas = ventas;
        this.presupuestos = presupuestos;
        this.inventario = inventario;
    }

    // ------------------------------------------------------------------ vista previa / validación

    public ImportPreview previsualizar(SheetData hoja, ImportType tipo, Map<String, Integer> mapeo) {
        return previsualizar(hoja, tipo, mapeo, false);
    }

    /**
     * Opciones elegidas por el usuario para una importación.
     *
     * @param crearFaltantes         en ventas, crear los productos y sucursales que no existan
     * @param lineasIgualesSeparadas registrar como ventas distintas las líneas idénticas de un mismo archivo (dos
     *                               ítems iguales en una boleta). Por defecto se omiten como repetidas.
     */
    public record Opciones(boolean crearFaltantes, boolean lineasIgualesSeparadas) {
        public static final Opciones NINGUNA = new Opciones(false, false);

        /**
         * Sin columna de documento (boleta, factura, orden) no hay forma de distinguir un registro repetido de dos
         * ventas iguales el mismo día, que es lo más probable: en ese caso las líneas iguales se registran por
         * separado. Con documento, una línea idéntica se considera repetida salvo que el usuario elija lo contrario.
         */
        Opciones efectivas(ImportType tipo, Map<String, Integer> mapeo) {
            boolean sinDocumento = (tipo == ImportType.VENTAS && !mapeo.containsKey("referencia"))
                    || (tipo == ImportType.COMPRAS && !mapeo.containsKey("documento"));
            return sinDocumento && !lineasIgualesSeparadas ? new Opciones(crearFaltantes, true) : this;
        }
    }

    /**
     * @param crearFaltantes en ventas, crear los productos y sucursales que no existan (la vista previa solo lo indica)
     */
    public ImportPreview previsualizar(SheetData hoja, ImportType tipo, Map<String, Integer> mapeo,
                                       boolean crearFaltantes) {
        return previsualizar(hoja, tipo, mapeo, new Opciones(crearFaltantes, false));
    }

    public ImportPreview previsualizar(SheetData hoja, ImportType tipo, Map<String, Integer> mapeo, Opciones op) {
        boolean crearFaltantes = op.crearFaltantes();
        List<String> faltantes = tipo.campos().stream()
                .filter(f -> f.obligatorio() && mapeo.get(f.clave()) == null)
                .map(FieldDef::etiqueta).toList();
        if (!faltantes.isEmpty()) {
            throw new ValidationException("Debe relacionar las columnas obligatorias: " + String.join(", ", faltantes));
        }
        // Se usa una única conexión para todas las consultas de validación (no se escribe nada).
        List<ImportRow> filas = db.inTransaction(() -> {
            Lookup lookup = new Lookup(op.efectivas(tipo, mapeo), false);
            lookup.formato = FormatoArchivo.deducir(hoja.filas(), tipo, mapeo);
            Set<String> clavesArchivo = new HashSet<>();
            List<ImportRow> out = new ArrayList<>();
            for (int i = 0; i < hoja.filas().size(); i++) {
                out.add(procesar(hoja.filas().get(i), hoja.numerosFila().get(i), tipo, mapeo, lookup, clavesArchivo));
            }
            return out;
        });
        return new ImportPreview(tipo, hoja.nombre(), Map.copyOf(mapeo), filas);
    }

    private void verificarObligatorios(ImportType tipo, Map<String, Integer> mapeo) {
        List<String> faltantes = tipo.campos().stream()
                .filter(f -> f.obligatorio() && mapeo.get(f.clave()) == null)
                .map(FieldDef::etiqueta).toList();
        if (!faltantes.isEmpty()) {
            throw new ValidationException("Debe relacionar las columnas obligatorias: " + String.join(", ", faltantes));
        }
    }

    /** Convierte y valida una fila (misma lógica para la vista previa y la importación por bloques). */
    private ImportRow procesar(List<Object> celdas, int numeroFila, ImportType tipo, Map<String, Integer> mapeo,
                               Lookup lookup, Set<String> clavesArchivo) {
        ImportRow row = esFilaDeTotales(celdas) ? totales(numeroFila)
                : convertir(celdas, numeroFila, tipo, mapeo, lookup.formato);
        if (row.estado() == Estado.NUEVO) {
            switch (tipo) {
                case SUCURSALES -> validarSucursal(row, clavesArchivo);
                case PRODUCTOS -> validarProducto(row, clavesArchivo);
                case INVENTARIO -> validarInventario(row, clavesArchivo, lookup);
                case VENTAS -> validarVenta(row, clavesArchivo, lookup);
                case PRESUPUESTOS -> validarPresupuesto(row, clavesArchivo, lookup);
                case PROVEEDORES -> validarProveedor(row, clavesArchivo);
                case COMPRAS -> validarCompra(row, clavesArchivo, lookup);
            }
        }
        return row;
    }

    /** Convierte las celdas al tipo de cada campo y verifica los obligatorios. */
    private ImportRow convertir(List<Object> celdas, int numeroFila, ImportType tipo, Map<String, Integer> mapeo,
                                FormatoArchivo fmt) {
        ImportRow row = new ImportRow(numeroFila);
        for (FieldDef f : tipo.campos()) {
            Integer col = mapeo.get(f.clave());
            Object raw = col == null || col >= celdas.size() ? null : celdas.get(col);
            try {
                Object v = switch (f.tipo()) {
                    case TEXTO -> ValueParser.texto(raw);
                    case NUMERO -> ValueParser.numero(raw, fmt.comaDecimal());
                    case ENTERO -> {
                        Double d = ValueParser.numero(raw, fmt.comaDecimal());
                        if (d == null) {
                            yield null;
                        }
                        long r = Math.round(d);
                        if (Math.abs(d - r) > 1e-9) {
                            // Productos a granel o fracciones: se guardan unidades enteras y se avisa.
                            row.nota(f.etiqueta() + ": " + ValueParser.texto(raw) + " se redondeó a " + r
                                    + " (se guardan unidades enteras)");
                        }
                        yield (int) r;
                    }
                    case FECHA -> ValueParser.fecha(raw, fmt.mesPrimero());
                    case PERIODO -> ValueParser.periodo(raw);
                };
                if (v == null && f.obligatorio()) {
                    row.error("Falta el dato obligatorio '" + f.etiqueta() + "'");
                }
                row.put(f.clave(), v);
            } catch (IllegalArgumentException e) {
                row.error(f.etiqueta() + ": " + e.getMessage());
            }
            if (f.tipo() == FieldType.NUMERO || f.tipo() == FieldType.ENTERO) {
                Object v = row.get(f.clave());
                if (v instanceof Number n && n.doubleValue() < 0) {
                    if (tipo == ImportType.VENTAS && (f.clave().equals("cantidad") || f.clave().equals("total"))) {
                        // Devolución / nota de crédito de un reporte ERP: se registra como devolución (en positivo)
                        // y se descuenta de la demanda; no es una venta ni un error de datos.
                        row.put(f.clave(), -n.doubleValue());
                        if (row.get("_devolucion") == null) {
                            row.put("_devolucion", Boolean.TRUE);
                            row.nota("Devolución o nota de crédito: se registra como devolución y se descuenta de "
                                    + "la demanda");
                        }
                    } else {
                        row.error(f.etiqueta() + " no puede ser negativo");
                    }
                }
            }
        }
        return row;
    }

    private static final java.util.Set<String> TOTALES = java.util.Set.of("total", "totales", "subtotal",
            "sub total", "total general", "gran total", "suma", "total final");

    /** Fila de totales/subtotales típica de los reportes ERP: alguna celda de texto es solo una etiqueta de total. */
    static boolean esFilaDeTotales(List<Object> celdas) {
        for (Object c : celdas) {
            if (c instanceof String s) {
                String n = ColumnMapper.normalizar(s);
                if (TOTALES.contains(n) || n.startsWith("total general") || n.startsWith("subtotal ")
                        || n.startsWith("total de ") || n.startsWith("totales ")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ImportRow totales(int numeroFila) {
        ImportRow r = new ImportRow(numeroFila);
        r.omitir("Fila de totales/subtotales del reporte: se omite");
        return r;
    }

    /** Caché de búsquedas para no consultar la base de datos por cada fila. */
    private final class Lookup {
        final Map<String, Optional<Producto>> prod = new HashMap<>();
        final Map<String, Optional<Producto>> porNombre = new HashMap<>();
        final Map<String, Optional<Sucursal>> suc = new HashMap<>();
        /** Crear los productos y sucursales de las ventas que no existan (opción elegida por el usuario). */
        final boolean crear;
        /** Falso en la vista previa: no se escribe nada en la base. */
        final boolean escribir;
        /** Orden de fechas y separador decimal deducidos del archivo. */
        FormatoArchivo formato = FormatoArchivo.POR_DEFECTO;
        /** Apariciones de cada huella en el archivo (clave: hash de 64 bits, para no guardar los textos). */
        final Map<Long, Integer> ocurrencias = new HashMap<>();
        long lineasIguales;

        /** Registrar las líneas idénticas del archivo como ventas distintas (opción del usuario). */
        final boolean lineasSeparadas;

        /**
         * Con la opción de líneas separadas, las líneas idénticas dentro del mismo archivo (mismo día, producto,
         * cantidad, precio y documento) se consideran ventas reales distintas (dos ítems iguales en una boleta): la
         * primera conserva la huella habitual y las siguientes reciben "#2", "#3"... Así el total coincide con el
         * original y volver a importar el archivo no duplica nada. Sin la opción, la huella no cambia y la repetición
         * se omite como registro repetido.
         */
        String conOcurrencia(String base, ImportRow row) {
            if (!lineasSeparadas) {
                return base;
            }
            long h = 1125899906842597L;
            for (int i = 0; i < base.length(); i++) {
                h = 31 * h + base.charAt(i);
            }
            int n = ocurrencias.merge(h, 1, Integer::sum);
            if (n == 1) {
                return base;
            }
            lineasIguales++;
            row.nota("Línea igual a otra del mismo documento: se registra como una venta más (aparición " + n + ")");
            return base + "#" + n;
        }
        int productosCreados;
        int sucursalesCreadas;

        /** Huellas ya usadas en esta importación (filas completadas al guardar). */
        final Set<String> usadas = new HashSet<>();

        Lookup(Opciones op, boolean escribir) {
            this.crear = op.crearFaltantes();
            this.lineasSeparadas = op.lineasIgualesSeparadas();
            this.escribir = escribir;
        }

        Optional<Producto> producto(String codigo) {
            return prod.computeIfAbsent(codigo.toUpperCase(), k -> productos.findByCodigo(codigo));
        }

        /** Por código y, si no está en el catálogo, por nombre exacto (solo si un único producto lo tiene). */
        Optional<Producto> producto(String codigo, String nombre) {
            return producto(codigo, null, nombre);
        }

        /**
         * Busca el producto en este orden: código, código sin ceros a la izquierda, código de barras y nombre exacto
         * (solo si un único producto del catálogo lo tiene). Los resultados se guardan para no consultar por fila.
         */
        Optional<Producto> producto(String codigo, String barras, String nombre) {
            Optional<Producto> p = codigo == null ? Optional.empty() : prod.computeIfAbsent(codigo.toUpperCase(),
                    k -> productos.findByCodigo(codigo).or(() -> productos.findByCodigoSinCeros(codigo)));
            if (p.isEmpty() && barras != null && !barras.isBlank()) {
                p = prod.computeIfAbsent(barras.toUpperCase(),
                        k -> productos.findByCodigo(barras).or(() -> productos.findByCodigoSinCeros(barras)));
            }
            if (p.isEmpty() && nombre != null && !nombre.isBlank()) {
                p = porNombre.computeIfAbsent(nombre.trim().toUpperCase(), k -> productos.findByNombreUnico(nombre));
            }
            if (p.isEmpty() && codigo != null && !codigo.isBlank()) {
                // Relación de columnas antigua en la que "Código de producto" apunta a la columna del nombre.
                p = porNombre.computeIfAbsent(codigo.trim().toUpperCase(), k -> productos.findByNombreUnico(codigo));
            }
            return p;
        }

        Optional<Sucursal> sucursal(String codigo) {
            String c = codigo == null || codigo.isBlank() ? SchemaInitializer.SUCURSAL_PRINCIPAL : codigo;
            return suc.computeIfAbsent(c.toUpperCase(), k -> sucursales.findByCodigoOrNombre(c));
        }

        /** Crea el producto con los datos de la fila (código, nombre, categoría, precio y costo del archivo). */
        Producto crearProducto(ImportRow r, double precio) {
            String codigo = ((String) r.get("producto")).trim();
            String nombre = (String) r.get("nombre_producto");
            Double costo = (Double) r.get("costo");
            Long cat = categorias.getOrCreate((String) r.get("categoria"));
            Producto p = new Producto(0, codigo, nombre == null || nombre.isBlank() ? codigo : nombre.trim(), cat, null,
                    null, null, precio, costo == null || costo < 0 ? 0 : costo, 0, 0, null, true);
            long id = productos.insert(p);
            Producto creado = productos.findById(id).orElseThrow();
            prod.put(codigo.toUpperCase(), Optional.of(creado));
            if (nombre != null && !nombre.isBlank()) {
                porNombre.put(nombre.trim().toUpperCase(), Optional.of(creado));
            }
            productosCreados++;
            return creado;
        }

        Sucursal crearSucursal(String nombre) {
            String n = nombre.trim();
            long id = sucursales.insert(new Sucursal(0, n.length() > 30 ? n.substring(0, 30) : n, n, "", "", true));
            Sucursal s = sucursales.findById(id).orElseThrow();
            suc.put(n.toUpperCase(), Optional.of(s));
            sucursalesCreadas++;
            return s;
        }
    }

    private static void marcarDuplicadoArchivo(ImportRow row, Set<String> claves, String clave) {
        if (!claves.add(clave.toUpperCase())) {
            row.setEstado(Estado.DUPLICADO);
            row.nota("Registro repetido en el archivo");
        }
    }

    private void validarSucursal(ImportRow row, Set<String> claves) {
        String codigo = (String) row.get("codigo");
        marcarDuplicadoArchivo(row, claves, codigo);
        if (row.estado() == Estado.NUEVO && sucursales.findByCodigoOrNombre(codigo)
                .filter(s -> s.codigo().equalsIgnoreCase(codigo)).isPresent()) {
            row.setEstado(Estado.ACTUALIZA);
            row.nota("La sucursal existe: se actualizarán sus datos");
        }
    }

    private void validarProducto(ImportRow row, Set<String> claves) {
        String codigo = (String) row.get("codigo");
        if (codigo.length() > 50) {
            row.error("El código supera 50 caracteres");
            return;
        }
        Integer min = (Integer) row.get("stock_minimo");
        Integer max = (Integer) row.get("stock_maximo");
        if (min != null && max != null && max > 0 && max < min) {
            row.error("El stock máximo es menor que el mínimo");
            return;
        }
        double precio = (Double) row.get("precio");
        double costo = (Double) row.get("costo");
        if (precio < costo) {
            row.nota("Advertencia: el precio es menor que el costo");
        }
        marcarDuplicadoArchivo(row, claves, codigo);
        if (row.estado() == Estado.NUEVO && productos.findByCodigo(codigo).isPresent()) {
            row.setEstado(Estado.ACTUALIZA);
            row.nota("El producto existe: se actualizarán sus datos");
        }
    }

    /**
     * Precio unitario neto: el descuento puede ser un porcentaje ("10 %", "10%") o el importe de toda la línea.
     *
     * @return nulo si el descuento no se puede interpretar
     */
    static Double precioConDescuento(double precio, double cantidad, String descuento) {
        String d = descuento.trim();
        try {
            if (d.endsWith("%")) {
                double pct = ValueParser.numero(d.substring(0, d.length() - 1));
                return Math.round(precio * (1 - pct / 100) * 10000) / 10000.0;
            }
            double importe = Math.abs(ValueParser.numero(d));
            return Math.round(Math.max(0, precio - importe / cantidad) * 10000) / 10000.0;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern CIENTIFICA =
            java.util.regex.Pattern.compile("(?i)^\\d+([.,]\\d+)?e\\+?\\d+$");

    /**
     * Código en notación científica ("7,75E+12"): Excel recortó los dígitos al guardar el archivo y ya no se puede
     * saber el código original. Se rechaza la fila con una explicación concreta en lugar de "producto no existe".
     */
    static boolean codigoIlegible(ImportRow row) {
        for (String campo : List.of("producto", "codigo_barras")) {
            Object v = row.get(campo);
            if (v instanceof String s && CIENTIFICA.matcher(s.trim()).matches()) {
                row.error("El código '" + s + "' está en notación científica: Excel recortó sus dígitos al guardar el "
                        + "archivo. Exporte el reporte como XLSX o dé formato de Texto a la columna de códigos");
                return true;
            }
        }
        return false;
    }

    private void validarInventario(ImportRow row, Set<String> claves, Lookup lookup) {
        if (codigoIlegible(row)) {
            return;
        }
        Optional<Producto> p = lookup.producto((String) row.get("producto"), (String) row.get("codigo_barras"),
                (String) row.get("nombre_producto"));
        Optional<Sucursal> s = lookup.sucursal((String) row.get("sucursal"));
        if (p.isEmpty()) {
            row.error("El producto '" + row.get("producto") + "' no existe (importe primero los productos)");
        }
        if (s.isEmpty()) {
            row.error("La sucursal '" + row.get("sucursal") + "' no existe (importe primero las sucursales)");
        }
        if (row.estado() == Estado.ERROR) {
            return;
        }
        row.put("_producto_id", p.get().id());
        row.put("_sucursal_id", s.get().id());
        marcarDuplicadoArchivo(row, claves, p.get().id() + ":" + s.get().id());
        if (row.estado() == Estado.NUEVO && inventario.existe(p.get().id(), s.get().id())) {
            row.setEstado(Estado.ACTUALIZA);
            row.nota("Stock actual " + inventario.stock(p.get().id(), s.get().id()) + " → " + row.get("stock"));
        }
    }

    private void validarVenta(ImportRow row, Set<String> claves, Lookup lookup) {
        LocalDate fecha = (LocalDate) row.get("fecha");
        if (fecha.isAfter(LocalDate.now())) {
            row.error("La fecha " + fecha + " es futura");
        }
        if (fecha.getYear() < 1990) {
            row.error("La fecha " + fecha + " no es válida");
        }
        if (codigoIlegible(row)) {
            return;
        }
        Optional<Producto> p = lookup.producto((String) row.get("producto"), (String) row.get("codigo_barras"),
                (String) row.get("nombre_producto"));
        Optional<Sucursal> s = lookup.sucursal((String) row.get("sucursal"));
        boolean productoNuevo = p.isEmpty() && lookup.crear;
        boolean sucursalNueva = s.isEmpty() && lookup.crear && row.get("sucursal") != null;
        if (p.isEmpty() && !productoNuevo) {
            row.error("El producto '" + row.get("producto") + "' no existe (importe primero los productos)");
        }
        if (s.isEmpty() && !sucursalNueva) {
            row.error("La sucursal '" + row.get("sucursal") + "' no existe (importe primero las sucursales)");
        }
        double cantidad = (Double) row.get("cantidad");
        if (cantidad <= 0) {
            row.error("La cantidad debe ser distinta de cero");
        }
        if (row.estado() == Estado.ERROR) {
            return;
        }
        Double precio = (Double) row.get("precio");
        Double total = (Double) row.get("total");
        if (precio == null) {
            if (total != null) {
                precio = Math.round(total / cantidad * 10000) / 10000.0;
                row.nota("Precio calculado desde el total");
            } else if (p.isPresent()) {
                precio = p.get().precioVenta();
                row.nota("Sin precio: se usó el precio del producto");
            } else {
                row.error("Producto nuevo sin precio ni total: no se puede registrar la venta");
                return;
            }
            row.put("precio", precio);
        } else if (total != null && Math.abs(precio * cantidad - total) > Math.max(0.05, total * 0.01)) {
            // El total es lo realmente cobrado (incluye descuentos y redondeos del comprobante).
            precio = Math.round(total / cantidad * 10000) / 10000.0;
            row.put("precio", precio);
            row.nota("Total distinto de cantidad × precio (descuento): se usó el total cobrado");
        } else if (total == null && row.get("descuento") != null) {
            Double neto = precioConDescuento(precio, cantidad, (String) row.get("descuento"));
            if (neto == null) {
                row.error("Descuento: '" + row.get("descuento") + "' no es un importe ni un porcentaje");
                return;
            }
            precio = neto;
            row.put("precio", precio);
        }
        if (productoNuevo || sucursalNueva) {
            if (!lookup.escribir) {
                // Vista previa: se informa sin escribir; al importar se crean una sola vez.
                row.put("_lineasSeparadas", lookup.lineasSeparadas);
                row.nota((productoNuevo ? "Producto nuevo '" + row.get("producto") + "': se creará" : "")
                        + (productoNuevo && sucursalNueva ? " · " : "")
                        + (sucursalNueva ? "Sucursal nueva '" + row.get("sucursal") + "': se creará" : ""));
                return;
            }
            if (productoNuevo) {
                p = Optional.of(lookup.crearProducto(row, precio));
            }
            if (sucursalNueva) {
                s = Optional.of(lookup.crearSucursal((String) row.get("sucursal")));
            }
        }
        long pid = p.get().id();
        long sid = s.get().id();
        row.put("_producto_id", pid);
        row.put("_sucursal_id", sid);
        boolean devolucion = Boolean.TRUE.equals(row.get("_devolucion"));
        String huella = lookup.conOcurrencia(devolucion
                ? DevolucionRepository.huella(fecha, pid, sid, cantidad, precio, (String) row.get("referencia"))
                : VentaRepository.huella(fecha, pid, sid, cantidad, precio, (String) row.get("referencia")), row);
        row.put("_huella", huella);
        if (!lookup.lineasSeparadas) {
            marcarDuplicadoArchivo(row, claves, huella);
        }
        if (row.estado() == Estado.NUEVO && (devolucion ? devoluciones.idPorHuella(huella).isPresent()
                : ventas.existsHuella(huella))) {
            row.setEstado(Estado.DUPLICADO);
            row.nota(devolucion ? "La devolución ya está registrada en el sistema"
                    : "La venta ya está registrada en el sistema");
        }
    }

    private void validarPresupuesto(ImportRow row, Set<String> claves, Lookup lookup) {
        String suc = (String) row.get("sucursal");
        Long sid = null;
        if (suc != null && !suc.isBlank()) {
            Optional<Sucursal> s = lookup.sucursal(suc);
            if (s.isEmpty()) {
                row.error("La sucursal '" + suc + "' no existe");
                return;
            }
            sid = s.get().id();
        }
        row.put("_sucursal_id", sid);
        String periodo = row.get("periodo").toString();
        marcarDuplicadoArchivo(row, claves, periodo + ":" + (sid == null ? 0 : sid));
        if (row.estado() == Estado.NUEVO && presupuestos.exists(periodo, sid)) {
            row.setEstado(Estado.ACTUALIZA);
            row.nota("El presupuesto del periodo existe: se actualizará el monto");
        }
    }

    private void validarProveedor(ImportRow row, Set<String> claves) {
        String nombre = (String) row.get("nombre");
        Integer lt = (Integer) row.get("lead_time");
        Integer ciclo = (Integer) row.get("ciclo");
        if (lt != null && (lt < 1 || lt > 365)) {
            row.error("El tiempo de entrega debe estar entre 1 y 365 días");
        }
        if (ciclo != null && (ciclo < 1 || ciclo > 365)) {
            row.error("El ciclo de compra debe estar entre 1 y 365 días");
        }
        if (row.estado() == Estado.ERROR) {
            return;
        }
        marcarDuplicadoArchivo(row, claves, nombre);
        if (row.estado() == Estado.NUEVO && proveedores.findByNombre(nombre).isPresent()) {
            row.setEstado(Estado.ACTUALIZA);
            row.nota("El proveedor existe: se actualizarán los datos informados");
        }
    }

    private void validarCompra(ImportRow row, Set<String> claves, Lookup lookup) {
        LocalDate fecha = (LocalDate) row.get("fecha");
        LocalDate hoy = LocalDate.now();
        if (fecha.isAfter(hoy)) {
            row.error("La fecha de pedido " + fecha + " es futura");
        }
        if (codigoIlegible(row)) {
            return;
        }
        Optional<Producto> p = lookup.producto((String) row.get("producto"), (String) row.get("codigo_barras"),
                (String) row.get("nombre_producto"));
        Optional<Sucursal> s = lookup.sucursal((String) row.get("sucursal"));
        if (p.isEmpty()) {
            row.error("El producto '" + row.get("producto") + "' no existe (importe primero los productos)");
        }
        if (s.isEmpty()) {
            row.error("La sucursal '" + row.get("sucursal") + "' no existe (importe primero las sucursales)");
        }
        int cantidad = (Integer) row.get("cantidad");
        if (cantidad <= 0) {
            row.error("La cantidad debe ser mayor que cero");
        }
        String estado;
        try {
            estado = ValueParser.estadoCompra(row.get("estado"));
        } catch (IllegalArgumentException e) {
            row.error("Estado: " + e.getMessage());
            return;
        }
        LocalDate esperada = (LocalDate) row.get("fecha_esperada");
        LocalDate recepcion = (LocalDate) row.get("fecha_recepcion");
        if (estado == null) {
            if (recepcion != null) {
                estado = Compra.RECIBIDA;
            } else if (esperada != null) {
                estado = Compra.PENDIENTE;
                row.nota("Sin estado ni recepción y con fecha esperada: se considera pendiente (en tránsito)");
            } else {
                estado = Compra.RECIBIDA;
                row.nota("Sin estado ni fecha de recepción: se asume recibida en la fecha del pedido");
            }
        }
        if (recepcion != null && recepcion.isBefore(fecha)) {
            row.error("La fecha de recepción es anterior a la fecha de pedido");
        }
        if (Compra.RECIBIDA.equals(estado) && recepcion != null && recepcion.isAfter(hoy)) {
            row.error("La fecha de recepción " + recepcion + " es futura");
        }
        if (Compra.PENDIENTE.equals(estado) && recepcion != null) {
            row.nota("Orden pendiente con fecha de recepción: se ignora la fecha de recepción");
            recepcion = null;
        }
        if (row.estado() == Estado.ERROR) {
            return;
        }
        Double costo = (Double) row.get("costo");
        Double total = (Double) row.get("total");
        if (costo == null) {
            if (total != null) {
                costo = Math.round(total / cantidad * 10000) / 10000.0;
                row.nota("Costo calculado desde el total");
            } else {
                costo = p.get().costo();
                row.nota("Sin costo: se usó el costo del producto");
            }
        }
        row.put("_costo", costo);
        row.put("_estado", estado);
        row.put("_recepcion", Compra.RECIBIDA.equals(estado) ? (recepcion != null ? recepcion : fecha) : null);
        long pid = p.get().id();
        long sid = s.get().id();
        row.put("_producto_id", pid);
        row.put("_sucursal_id", sid);
        String huella = lookup.conOcurrencia(
                CompraRepository.huella(fecha, pid, sid, cantidad, costo, (String) row.get("documento")), row);
        row.put("_huella", huella);
        if (!lookup.lineasSeparadas) {
            marcarDuplicadoArchivo(row, claves, huella);
        }
        if (row.estado() == Estado.NUEVO && compras.existsHuella(huella)) {
            row.setEstado(Estado.DUPLICADO);
            row.nota("La orden de compra ya está registrada en el sistema");
        }
        if (Compra.PENDIENTE.equals(estado) && row.importable()) {
            row.nota("Pendiente: se descontará como stock en tránsito hasta su recepción");
        }
    }

    // ------------------------------------------------------------------ perfiles de mapeo

    private static String clavePerfil(ImportType tipo, List<String> encabezados) {
        String firma = String.join("|", encabezados.stream().map(ColumnMapper::normalizar).toList());
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(firma.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "importacion.perfil." + tipo.name() + "." + java.util.HexFormat.of().formatHex(h, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "importacion.perfil." + tipo.name() + "." + Integer.toHexString(firma.hashCode());
        }
    }

    /**
     * Relación de columnas usada la última vez para un reporte con exactamente los mismos encabezados (p. ej. el
     * mismo reporte del ERP exportado otro mes).
     */
    public Optional<Map<String, Integer>> perfilGuardado(ImportType tipo, List<String> encabezados) {
        return config.get(clavePerfil(tipo, encabezados)).map(v -> {
            Map<String, Integer> m = new java.util.LinkedHashMap<>();
            for (String par : v.split(";")) {
                String[] kv = par.split("=");
                if (kv.length == 2 && tipo.campos().stream().anyMatch(f -> f.clave().equals(kv[0]))) {
                    try {
                        int idx = Integer.parseInt(kv[1]);
                        if (idx >= 0 && idx < encabezados.size()) {
                            m.put(kv[0], idx);
                        }
                    } catch (NumberFormatException ignored) {
                        // perfil dañado: se ignora el par
                    }
                }
            }
            return m;
        }).filter(m -> !m.isEmpty());
    }

    /** Recuerda la relación de columnas para la próxima importación del mismo formato de reporte. */
    public void guardarPerfil(ImportType tipo, List<String> encabezados, Map<String, Integer> mapeo) {
        StringBuilder sb = new StringBuilder();
        mapeo.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ";").append(k).append('=').append(v));
        config.set(clavePerfil(tipo, encabezados), sb.toString());
        plantillas.guardar(tipo, null, encabezados, mapeo);
    }

    /** Perfil guardado o, si no existe, la sugerencia automática por sinónimos. */
    public Map<String, Integer> mapeoSugerido(ImportType tipo, List<String> encabezados) {
        Optional<Map<String, Integer>> exacto = perfilGuardado(tipo, encabezados);
        if (exacto.isPresent()) {
            return exacto.get();
        }
        Map<String, Integer> auto = ColumnMapper.sugerir(tipo, encabezados);
        return plantillas.masParecida(tipo, encabezados).map(c -> combinar(c.mapeo(), auto)).orElse(auto);
    }

    /** La plantilla manda; la detección automática completa los campos que la plantilla no tiene. */
    static Map<String, Integer> combinar(Map<String, Integer> plantilla, Map<String, Integer> auto) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>(plantilla);
        auto.forEach((campo, col) -> {
            if (!m.containsKey(campo) && !m.containsValue(col)) {
                m.put(campo, col);
            }
        });
        return m;
    }

    /** De dónde sale la relación sugerida, para informarlo al usuario (vacío si es la detección automática). */
    public Optional<String> origenSugerencia(ImportType tipo, List<String> encabezados) {
        if (perfilGuardado(tipo, encabezados).isPresent()) {
            return Optional.of("Formato reconocido: se reutilizó la relación de columnas de una importación anterior "
                    + "con los mismos encabezados.");
        }
        return plantillas.masParecida(tipo, encabezados).map(c -> String.format("Se aplicó la plantilla \"%s\" "
                + "(el archivo tiene el %.0f %% de sus columnas). Revise las columnas marcadas antes de importar.",
                c.plantilla().nombre(), c.cobertura() * 100));
    }

    /** Plantillas de importación guardadas. */
    public PlantillasImportacion plantillas() {
        return plantillas;
    }

    // ------------------------------------------------------------------ guardado

    /** Guarda todas las filas importables en una sola transacción (todo o nada). */
    public ImportResult importar(ImportPreview preview) {
        return importar(preview, null, null);
    }

    /**
     * Guarda las filas importables en una sola transacción y registra la importación en el historial. Cada venta,
     * devolución u orden de compra del archivo (nueva o ya existente) queda vinculada a esta importación, para poder
     * eliminarla después sin afectar registros que también pertenecen a otras importaciones.
     *
     * @param archivo     nombre del archivo de origen (para el historial)
     * @param encabezados encabezados de la hoja (estructura del reporte); puede ser nulo
     */
    public ImportResult importar(ImportPreview preview, String archivo, List<String> encabezados) {
        if (preview.importables() == 0) {
            throw new ValidationException("No hay filas válidas para importar. " + preview.resumen());
        }
        int[] contadores = new int[2]; // insertados, actualizados
        LocalDate[] periodo = periodo(preview);
        db.inTransaction(() -> {
            long impId = importaciones.insertar(LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    archivo == null || archivo.isBlank() ? "(hoja " + preview.hoja() + ")" : archivo, preview.hoja(),
                    preview.tipo().name(), periodo[0], periodo[1], preview.filas().size(),
                    encabezados == null ? null : estructura(encabezados));
            Lookup creador = new Lookup(new Opciones(true, true), true);
            for (ImportRow r : preview.filas()) {
                if (preview.tipo() == ImportType.VENTAS && r.importable() && r.get("_producto_id") == null) {
                    completarVentaNueva(r, creador);
                }
                int res = guardarFila(impId, preview.tipo(), preview.hoja(), r);
                if (res >= 0) {
                    contadores[res]++;
                }
            }
            importaciones.actualizarConteos(impId, contadores[0], contadores[1],
                    (int) preview.count(Estado.DUPLICADO), (int) preview.count(Estado.ERROR),
                    (int) preview.count(Estado.OMITIDA));
        });
        int omitidos = preview.filas().size() - contadores[0] - contadores[1];
        ImportResult res = new ImportResult(preview.tipo(), contadores[0], contadores[1], omitidos);
        LOG.info("Importación completada desde hoja '" + preview.hoja() + "': " + res.resumen());
        return res;
    }

    /**
     * Venta validada en la vista previa con producto o sucursal por crear: los crea una sola vez (o los encuentra si
     * otra fila ya los creó) y completa los identificadores y la huella.
     */
    private void completarVentaNueva(ImportRow r, Lookup lookup) {
        double precio = (Double) r.get("precio");
        Producto p = lookup.producto((String) r.get("producto"), (String) r.get("codigo_barras"),
                (String) r.get("nombre_producto")).orElseGet(() -> lookup.crearProducto(r, precio));
        Sucursal s = lookup.sucursal((String) r.get("sucursal"))
                .orElseGet(() -> lookup.crearSucursal((String) r.get("sucursal")));
        r.put("_producto_id", p.id());
        r.put("_sucursal_id", s.id());
        LocalDate fecha = (LocalDate) r.get("fecha");
        double cantidad = (Double) r.get("cantidad");
        boolean devolucion = Boolean.TRUE.equals(r.get("_devolucion"));
        String base = devolucion
                ? DevolucionRepository.huella(fecha, p.id(), s.id(), cantidad, precio, (String) r.get("referencia"))
                : VentaRepository.huella(fecha, p.id(), s.id(), cantidad, precio, (String) r.get("referencia"));
        if (Boolean.TRUE.equals(r.get("_lineasSeparadas"))) {
            r.put("_huella", lookup.conOcurrencia(base, r));
        } else if (!lookup.usadas.add(base)) {
            // Misma línea dos veces en el archivo: se omite como repetida (comportamiento por defecto).
            r.setEstado(Estado.DUPLICADO);
            r.nota("Registro repetido en el archivo");
        } else {
            r.put("_huella", base);
        }
    }

    /**
     * Guarda una fila validada y la vincula a la importación.
     *
     * @return 0 si se insertó, 1 si actualizó un registro existente, −1 si no se guardó (error, omitida o repetida)
     */
    private int guardarFila(long impId, ImportType tipo, String hoja, ImportRow r) {
        if (!r.importable()) {
            if (r.estado() == Estado.DUPLICADO) {
                vincularExistente(impId, tipo, r);
            }
            return -1;
        }
        switch (tipo) {
            case SUCURSALES -> guardarSucursal(r);
            case PRODUCTOS -> guardarProducto(r);
            case INVENTARIO -> inventario.fijarStock((Long) r.get("_producto_id"), (Long) r.get("_sucursal_id"),
                    (Integer) r.get("stock"), TipoMovimiento.IMPORTACION,
                    "Importación Excel (hoja " + hoja + ", fila " + r.numeroFila() + ")");
            case VENTAS -> {
                if (Boolean.TRUE.equals(r.get("_devolucion"))) {
                    double cant = (Double) r.get("cantidad");
                    importaciones.vincular(impId, "devoluciones", devoluciones.insert((LocalDate) r.get("fecha"),
                            (Long) r.get("_producto_id"), (Long) r.get("_sucursal_id"), cant,
                            cant * (Double) r.get("precio"), (String) r.get("referencia"),
                            (String) r.get("_huella")), true);
                } else {
                    importaciones.vincular(impId, "ventas", ventas.insert((LocalDate) r.get("fecha"),
                            (Long) r.get("_producto_id"), (Long) r.get("_sucursal_id"),
                            (Double) r.get("cantidad"), (Double) r.get("precio"),
                            (String) r.get("referencia"), (String) r.get("_huella")), true);
                }
            }
            case PRESUPUESTOS -> presupuestos.upsert(((YearMonth) r.get("periodo")).toString(),
                    (Long) r.get("_sucursal_id"), (Double) r.get("monto"), (String) r.get("descripcion"));
            case PROVEEDORES -> guardarProveedor(r);
            case COMPRAS -> importaciones.vincular(impId, "compras", guardarCompra(r), true);
        }
        return r.estado() == Estado.NUEVO ? 0 : 1;
    }

    // ------------------------------------------------------------------ importación por bloques (archivos grandes)

    /** Filas que se validan y guardan en cada transacción. */
    public static final int TAMANO_BLOQUE = 10_000;
    /** Filas con observaciones que se conservan para revisarlas o exportarlas. */
    public static final int MAX_OBSERVACIONES = 5_000;

    /**
     * Resultado de una importación por bloques.
     *
     * @param observaciones filas con error u observaciones (hasta {@link #MAX_OBSERVACIONES})
     * @param completa      falso si se canceló o falló a mitad: los bloques ya guardados se conservan y la
     *                      importación queda en estado PARCIAL (puede eliminarse desde el historial o completarse
     *                      importando otra vez el archivo: lo ya guardado se detecta como repetido)
     */
    public record ResultadoBloques(ImportType tipo, String hoja, long filas, long insertados, long actualizados,
                                   long duplicados, long errores, long omitidos, List<ImportRow> observaciones,
                                   boolean completa, long importacionId, String mensaje, ResumenErrores causas,
                                   int productosCreados, int sucursalesCreadas, long lineasIguales,
                                   Path filasRechazadas) {

        public ResultadoBloques(ImportType tipo, String hoja, long filas, long insertados, long actualizados,
                                long duplicados, long errores, long omitidos, List<ImportRow> observaciones,
                                boolean completa, long importacionId, String mensaje) {
            this(tipo, hoja, filas, insertados, actualizados, duplicados, errores, omitidos, observaciones, completa,
                    importacionId, mensaje, new ResumenErrores(), 0, 0, 0, null);
        }

        /** Verdadero si no se guardó ni reconoció ningún registro: todas las filas fallaron. */
        public boolean fallida() {
            return insertados + actualizados + duplicados == 0 && errores > 0;
        }

        /** Explicación completa para el usuario: resumen, causas de los errores y qué hacer. */
        public String explicacion() {
            StringBuilder sb = new StringBuilder(resumen());
            if (productosCreados > 0) {
                sb.append(String.format("%nSe crearon %,d producto(s) nuevos con los datos del archivo. Revise su costo "
                        + "en Productos para calcular bien la utilidad.", productosCreados));
            }
            if (sucursalesCreadas > 0) {
                sb.append(String.format("%nSe registraron %,d sucursal(es) nuevas.", sucursalesCreadas));
            }
            if (lineasIguales > 0) {
                sb.append(String.format("%n%,d línea(s) iguales a otra del archivo se registraron como ventas distintas "
                        + "(no hay número de documento que las diferencie). Reimportar el archivo no las duplica.",
                        lineasIguales));
            }
            if (duplicados > 0) {
                sb.append(String.format("%n%,d fila(s) repetidas no se volvieron a guardar (ya estaban registradas o "
                        + "aparecen dos veces con el mismo documento).", duplicados));
            }
            if (fallida()) {
                sb.append("\n\nNO SE GUARDÓ NINGUNA VENTA: todas las filas tienen errores.");
            }
            if (!causas.vacio()) {
                sb.append("\n\nMotivos de los errores:\n").append(causas.texto());
            }
            if (mensaje != null) {
                sb.append("\n\n").append(mensaje);
            }
            return sb.toString();
        }

        public String resumen() {
            return String.format("%s: %,d filas leídas · %,d nuevas · %,d actualizadas · %,d repetidas · %,d con error · "
                            + "%,d omitidas.", tipo.etiqueta(), filas, insertados, actualizados, duplicados, errores,
                    omitidos);
        }

        /** Observaciones en el formato de la vista previa (para mostrarlas o exportarlas). */
        public ImportPreview comoPreview(Map<String, Integer> mapeo) {
            return new ImportPreview(tipo, hoja, Map.copyOf(mapeo), observaciones);
        }
    }

    /**
     * Importa una hoja leyéndola por flujo y guardándola en bloques de {@value #TAMANO_BLOQUE} filas, cada uno en su
     * propia transacción, con la misma conversión, validación, detección de repetidos e historial que la importación
     * normal. La memoria usada no depende del tamaño del archivo. El avance informado corresponde a los bytes
     * realmente leídos del archivo. Si se cancela, se detiene entre bloques sin dejar un bloque a medias.
     */
    public ResultadoBloques importarPorBloques(Path archivo, LectorStreaming.Hoja hoja, ImportType tipo,
                                               Map<String, Integer> mapeo, Progreso p) {
        return importarPorBloques(archivo, hoja, tipo, mapeo, false, p);
    }

    /** @param crearFaltantes en ventas, crear los productos y sucursales que no existan */
    public ResultadoBloques importarPorBloques(Path archivo, LectorStreaming.Hoja hoja, ImportType tipo,
                                               Map<String, Integer> mapeo, boolean crearFaltantes, Progreso p) {
        return importarPorBloques(archivo, hoja, tipo, mapeo, new Opciones(crearFaltantes, false), p);
    }

    public ResultadoBloques importarPorBloques(Path archivo, LectorStreaming.Hoja hoja, ImportType tipo,
                                               Map<String, Integer> mapeo, Opciones op, Progreso p) {
        verificarObligatorios(tipo, mapeo);
        boolean transaccional = tipo == ImportType.VENTAS || tipo == ImportType.COMPRAS;
        long impId = importaciones.insertar(LocalDateTime.now().format(java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss")), archivo.getFileName().toString(), hoja.nombre(), tipo.name(),
                null, null, (int) Math.min(Integer.MAX_VALUE, hoja.totalFilas()), estructura(hoja.encabezados()));
        importaciones.marcarEstado(impId, ImportacionRepository.PARCIAL);
        Lookup lookup = new Lookup(op.efectivas(tipo, mapeo), true);
        lookup.formato = FormatoArchivo.deducir(hoja.muestra().filas(), tipo, mapeo);
        ResumenErrores causas = new ResumenErrores();
        FilasRechazadas rechazadas = new FilasRechazadas(FilasRechazadas.archivo(db.getFile(), impId),
                hoja.encabezados());
        Set<String> clavesMaestras = new HashSet<>();
        long[] c = new long[6]; // filas, insertados, actualizados, duplicados, errores, omitidos
        LocalDate[] periodo = new LocalDate[2];
        List<ImportRow> observaciones = new ArrayList<>();
        List<Object[]> bloque = new ArrayList<>(TAMANO_BLOQUE);
        Runnable guardarBloque = () -> {
            if (bloque.isEmpty()) {
                return;
            }
            p.verificar();
            db.inTransaction(() -> {
                // Repetidos dentro del archivo: en ventas/compras los bloques anteriores ya están guardados y se
                // detectan en la base; dentro del bloque, con este conjunto.
                Set<String> claves = transaccional ? new HashSet<>() : clavesMaestras;
                for (Object[] f : bloque) {
                    @SuppressWarnings("unchecked")
                    ImportRow r = procesar((List<Object>) f[1], (Integer) f[0], tipo, mapeo, lookup, claves);
                    int res = guardarFila(impId, tipo, hoja.nombre(), r);
                    c[0]++;
                    if (res >= 0) {
                        c[1 + res]++;
                    } else if (r.estado() == Estado.DUPLICADO) {
                        c[3]++;
                    } else if (r.estado() == Estado.ERROR) {
                        c[4]++;
                        causas.agregar(r);
                        @SuppressWarnings("unchecked")
                        List<Object> celdas = (List<Object>) f[1];
                        rechazadas.agregar(r.numeroFila(), r.mensajesTexto(), celdas);
                    } else {
                        c[5]++;
                    }
                    if ((r.importable() || r.estado() == Estado.DUPLICADO) && r.get("fecha") instanceof LocalDate fe
                            && transaccional) {
                        periodo[0] = periodo[0] == null || fe.isBefore(periodo[0]) ? fe : periodo[0];
                        periodo[1] = periodo[1] == null || fe.isAfter(periodo[1]) ? fe : periodo[1];
                    }
                    if (!r.mensajes().isEmpty() && observaciones.size() < MAX_OBSERVACIONES) {
                        observaciones.add(r);
                    }
                }
            });
            bloque.clear();
        };
        long inicio = System.nanoTime();
        Progreso lectura = new Progreso() {
            @Override
            public void avance(double porcentaje, String mensaje) {
                p.avance(Math.min(99, porcentaje), String.format("%s · %,d de %,d filas guardadas%s", mensaje, c[0],
                        hoja.totalFilas(), velocidad(c[0], System.nanoTime() - inicio, porcentaje)));
            }

            @Override
            public boolean cancelado() {
                return p.cancelado();
            }
        };
        String mensaje = null;
        boolean completa = true;
        try {
            LectorStreaming.recorrer(archivo, hoja, (num, valores) -> {
                bloque.add(new Object[]{num, valores});
                if (bloque.size() >= TAMANO_BLOQUE) {
                    guardarBloque.run();
                }
            }, lectura);
            guardarBloque.run();
        } catch (java.util.concurrent.CancellationException e) {
            completa = false;
            mensaje = String.format("Importación cancelada: se guardaron %,d de %,d filas. La importación quedó como "
                    + "PARCIAL: puede eliminarla desde el Historial o volver a importar el archivo para completarla "
                    + "(lo ya guardado se detecta como repetido).", c[0], hoja.totalFilas());
        } catch (RuntimeException e) {
            if (c[0] == 0) {
                // Falló antes de guardar nada (p. ej. archivo que no cabe con seguridad en memoria): no queda rastro
                // en el historial y se informa el motivo original.
                importaciones.borrarSinRegistros(impId);
                if (e instanceof ValidationException) {
                    throw e;
                }
                throw new ValidationException("No se pudo importar el archivo: " + e.getMessage());
            }
            completa = false;
            LOG.log(java.util.logging.Level.SEVERE, "Error en la importación por bloques", e);
            mensaje = String.format("La importación se detuvo por un error después de guardar %,d filas: %s. Los bloques "
                    + "guardados se conservan (importación PARCIAL); puede eliminarla desde el Historial o corregir el "
                    + "archivo y volver a importarlo.", c[0], e.getMessage());
        } finally {
            rechazadas.close();
        }
        importaciones.actualizarConteos(impId, (int) c[1], (int) c[2], (int) c[3], (int) c[4], (int) c[5]);
        importaciones.actualizarPeriodo(impId, periodo[0], periodo[1]);
        ResultadoBloques res = new ResultadoBloques(tipo, hoja.nombre(), c[0], c[1], c[2], c[3], c[4], c[5],
                observaciones, completa, impId, mensaje, causas, lookup.productosCreados, lookup.sucursalesCreadas,
                lookup.lineasIguales, rechazadas.generado());
        importaciones.marcarEstado(impId, res.fallida() ? ImportacionRepository.FALLIDA
                : completa ? ImportacionRepository.COMPLETADA : ImportacionRepository.PARCIAL);
        importaciones.guardarDetalle(impId, causas.vacio() ? null : causas.texto());
        p.avance(100, completa ? "Importación completada" : "Importación detenida");
        LOG.info("Importación por bloques de '" + hoja.nombre() + "': " + res.resumen());
        if (!causas.vacio()) {
            LOG.warning("Motivos de los errores de '" + hoja.nombre() + "':\n" + causas.texto());
        }
        return res;
    }

    /**
     * Velocidad y tiempo restante estimado a partir del avance real (bytes leídos); vacío mientras no hay datos
     * suficientes para estimar.
     */
    static String velocidad(long filas, long nanos, double porcentaje) {
        double seg = nanos / 1e9;
        if (filas == 0 || seg < 1 || porcentaje <= 1) {
            return "";
        }
        long restante = Math.round(seg * (100 - porcentaje) / porcentaje);
        return String.format(" · %,.0f filas/s · quedan ≈ %s", filas / seg,
                restante >= 60 ? (restante / 60) + " min " + (restante % 60) + " s" : restante + " s");
    }

    /** Un registro repetido que ya existía también pertenece a esta importación (sin ser su dueña). */
    private void vincularExistente(long impId, ImportType tipo, ImportRow r) {
        String huella = (String) r.get("_huella");
        if (huella == null) {
            return;
        }
        if (tipo == ImportType.VENTAS) {
            boolean dev = Boolean.TRUE.equals(r.get("_devolucion"));
            (dev ? devoluciones.idPorHuella(huella) : ventas.idPorHuella(huella))
                    .ifPresent(id -> importaciones.vincular(impId, dev ? "devoluciones" : "ventas", id, false));
        } else if (tipo == ImportType.COMPRAS) {
            compras.idPorHuella(huella).ifPresent(id -> importaciones.vincular(impId, "compras", id, false));
        }
    }

    /** Periodo cubierto por las filas válidas o repetidas (ventas y compras); {null, null} si no aplica. */
    public static LocalDate[] periodo(ImportPreview preview) {
        LocalDate desde = null;
        LocalDate hasta = null;
        if (preview.tipo() == ImportType.VENTAS || preview.tipo() == ImportType.COMPRAS) {
            for (ImportRow r : preview.filas()) {
                if ((r.importable() || r.estado() == Estado.DUPLICADO) && r.get("fecha") instanceof LocalDate f) {
                    desde = desde == null || f.isBefore(desde) ? f : desde;
                    hasta = hasta == null || f.isAfter(hasta) ? f : hasta;
                }
            }
        }
        return new LocalDate[]{desde, hasta};
    }

    /** Firma de la estructura de un reporte: encabezados normalizados en orden. */
    public static String estructura(List<String> encabezados) {
        return String.join("|", encabezados.stream().map(ColumnMapper::normalizar).toList());
    }

    /**
     * Compara los encabezados con los de la última importación del mismo tipo. Devuelve una descripción del cambio
     * (columnas nuevas y faltantes) o vacío si la estructura coincide o no hay importaciones anteriores.
     */
    public Optional<String> cambioEstructura(ImportType tipo, List<String> encabezados) {
        Optional<String> anterior = importaciones.ultimaEstructura(tipo.name());
        String actual = estructura(encabezados);
        if (anterior.isEmpty() || anterior.get().equals(actual)) {
            return Optional.empty();
        }
        List<String> previas = List.of(anterior.get().split("\\|", -1));
        List<String> nuevas = List.of(actual.split("\\|", -1));
        List<String> agregadas = nuevas.stream().filter(c -> !c.isBlank() && !previas.contains(c)).toList();
        List<String> faltantes = previas.stream().filter(c -> !c.isBlank() && !nuevas.contains(c)).toList();
        StringBuilder sb = new StringBuilder("La estructura del archivo cambió respecto de la última importación de "
                + tipo.etiqueta() + ".");
        if (!agregadas.isEmpty()) {
            sb.append(" Columnas nuevas: ").append(String.join(", ", agregadas)).append('.');
        }
        if (!faltantes.isEmpty()) {
            sb.append(" Columnas que ya no están: ").append(String.join(", ", faltantes)).append('.');
        }
        if (agregadas.isEmpty() && faltantes.isEmpty()) {
            sb.append(" Las columnas cambiaron de orden.");
        }
        sb.append(" Revise la relación de columnas antes de validar.");
        return Optional.of(sb.toString());
    }

    /** Importaciones anteriores del mismo tipo cuyo periodo se superpone con el del archivo. */
    public List<ImportacionRepository.Importacion> importacionesSolapadas(ImportPreview preview) {
        LocalDate[] p = periodo(preview);
        if (p[0] == null) {
            return List.of();
        }
        return importaciones.solapadas(preview.tipo().name(), p[0], p[1]);
    }

    private void guardarSucursal(ImportRow r) {
        String codigo = (String) r.get("codigo");
        Optional<Sucursal> existente = sucursales.findByCodigoOrNombre(codigo)
                .filter(s -> s.codigo().equalsIgnoreCase(codigo));
        Sucursal s = new Sucursal(existente.map(Sucursal::id).orElse(0L), codigo, (String) r.get("nombre"),
                (String) r.get("ciudad"), (String) r.get("direccion"), true);
        if (existente.isPresent()) {
            sucursales.update(s);
        } else {
            sucursales.insert(s);
        }
    }

    private void guardarProveedor(ImportRow r) {
        String nombre = (String) r.get("nombre");
        Optional<Proveedor> e = proveedores.findByNombre(nombre);
        Proveedor p = new Proveedor(e.map(Proveedor::id).orElse(0L), nombre,
                r.get("contacto") != null ? (String) r.get("contacto") : e.map(Proveedor::contacto).orElse(null),
                r.get("telefono") != null ? (String) r.get("telefono") : e.map(Proveedor::telefono).orElse(null),
                r.get("email") != null ? (String) r.get("email") : e.map(Proveedor::email).orElse(null),
                r.get("lead_time") != null ? (Integer) r.get("lead_time") : e.map(Proveedor::leadTimeDias).orElse(null),
                r.get("ciclo") != null ? (Integer) r.get("ciclo") : e.map(Proveedor::cicloCompraDias).orElse(null));
        if (e.isPresent()) {
            proveedores.update(p);
        } else {
            proveedores.insert(p);
        }
    }

    /** Las órdenes importadas no modifican el stock (el inventario importado ya refleja lo recibido). */
    private long guardarCompra(ImportRow r) {
        long pid = (Long) r.get("_producto_id");
        Long prov = proveedores.getOrCreate((String) r.get("proveedor"));
        if (prov == null) {
            prov = productos.findById(pid).map(Producto::proveedorId).orElse(null);
        }
        return compras.insert((LocalDate) r.get("fecha"), pid, (Long) r.get("_sucursal_id"), prov, (Integer) r.get("cantidad"),
                (Double) r.get("_costo"), (String) r.get("_estado"), (LocalDate) r.get("fecha_esperada"),
                (LocalDate) r.get("_recepcion"), (String) r.get("documento"), Compra.ORIGEN_IMPORTACION,
                (String) r.get("_huella"));
    }

    private void guardarProducto(ImportRow r) {
        String codigo = (String) r.get("codigo");
        Optional<Producto> existente = productos.findByCodigo(codigo);
        Long cat = categorias.getOrCreate((String) r.get("categoria"));
        Long prov = proveedores.getOrCreate((String) r.get("proveedor"));
        Integer min = (Integer) r.get("stock_minimo");
        Integer max = (Integer) r.get("stock_maximo");
        Producto p = new Producto(existente.map(Producto::id).orElse(0L), codigo, (String) r.get("nombre"),
                cat != null ? cat : existente.map(Producto::categoriaId).orElse(null), null,
                prov != null ? prov : existente.map(Producto::proveedorId).orElse(null), null,
                (Double) r.get("precio"), (Double) r.get("costo"),
                min != null ? min : existente.map(Producto::stockMinimo).orElse(0),
                max != null ? max : existente.map(Producto::stockMaximo).orElse(0),
                r.get("lead_time") != null ? (Integer) r.get("lead_time")
                        : existente.map(Producto::leadTimeDias).orElse(null),
                true);
        if (existente.isPresent()) {
            productos.update(p);
        } else {
            productos.insert(p);
        }
    }
}
