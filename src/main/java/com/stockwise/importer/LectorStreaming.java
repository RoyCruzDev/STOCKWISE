package com.stockwise.importer;

import com.stockwise.service.Progreso;
import com.stockwise.service.ValidationException;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lectura <b>por flujo</b> de archivos de importación, sin cargar el libro completo en memoria.
 * <ul>
 *   <li>XLSX: el ZIP se abre desde el archivo (acceso aleatorio, no desde un InputStream) y cada hoja se recorre con
 *       SAX ({@link XSSFReader}); nunca se crea el modelo {@code XSSFWorkbook} ni se copia una parte completa a memoria.
 *       Esto evita el límite de 100 MB de Apache POI por parte del ZIP y el consumo de varios GB del modelo.</li>
 *   <li>CSV/TXT: se decodifica y recorre por flujo (codificación y separador automáticos).</li>
 *   <li>XLS (formato antiguo, máx. 65.536 filas por hoja): modelo de solo lectura abierto desde el archivo, tras
 *       verificar que su tamaño sea seguro para la memoria disponible.</li>
 * </ul>
 * El progreso se informa por bytes realmente procesados.
 */
public final class LectorStreaming {

    /** Filas que se conservan en memoria por hoja para la vista previa al inspeccionar. */
    public static final int MUESTRA = 20_000;
    static final int FILAS_BUSQUEDA_ENCABEZADO = 30;
    /** Fracción de la memoria libre que puede ocupar la tabla de textos compartidos de un XLSX. */
    static final double FRACCION_MEMORIA_SEGURA = 0.5;

    /** Receptor de filas de datos (puede lanzar {@link CancellationException} para detener la lectura). */
    @FunctionalInterface
    public interface Filas {
        void fila(int numeroFila, List<Object> valores);
    }

    /**
     * Hoja inspeccionada.
     *
     * @param muestra     encabezados y las primeras filas de datos (todas si {@link #completa()})
     * @param totalFilas  filas de datos no vacías de la hoja
     */
    public record Hoja(String nombre, int filaEncabezado, List<String> encabezados, SheetData muestra, long totalFilas) {
        public boolean completa() {
            return muestra.totalFilas() == totalFilas;
        }
    }

    private LectorStreaming() {
    }

    // =================================================================== API

    /** Lee encabezados, cuenta todas las filas y conserva hasta {@code limiteMuestra} filas por hoja. */
    public static List<Hoja> inspeccionar(Path archivo, int limiteMuestra, Progreso p) {
        Formato f = formato(archivo);
        List<Procesador> procs = new ArrayList<>();
        recorrerFormato(f, archivo, p, nombre -> {
            Procesador pr = new Procesador(nombre, limiteMuestra, null, -1, -1);
            procs.add(pr);
            return pr;
        }, null);
        List<Hoja> out = new ArrayList<>();
        procs.forEach(Procesador::fin);
        for (Procesador pr : procs) {
            if (pr.filaEncabezado > 0) {
                out.add(new Hoja(pr.nombre, pr.filaEncabezado, pr.encabezados, new SheetData(pr.nombre, pr.filaEncabezado,
                        pr.encabezados, pr.muestra, pr.numeros), pr.total));
            }
        }
        if (out.isEmpty()) {
            throw new ValidationException(f == Formato.CSV
                    ? "El archivo CSV no contiene datos tabulares (se requieren encabezados y al menos dos columnas)."
                    : "El archivo no contiene hojas con datos tabulares.");
        }
        return out;
    }

    /** Recorre por flujo todas las filas de datos de una hoja ya inspeccionada. */
    public static void recorrer(Path archivo, Hoja hoja, Filas destino, Progreso p) {
        Formato f = formato(archivo);
        recorrerFormato(f, archivo, p, nombre -> nombre.equals(hoja.nombre())
                ? new Procesador(nombre, 0, destino, hoja.filaEncabezado(), hoja.encabezados().size()) : null,
                hoja.nombre());
    }

    // =================================================================== común

    enum Formato { XLSX, XLS, CSV }

    static Formato formato(Path archivo) {
        if (archivo == null || !Files.isRegularFile(archivo)) {
            throw new ValidationException("El archivo no existe.");
        }
        String n = archivo.getFileName().toString().toLowerCase();
        if (n.endsWith(".csv") || n.endsWith(".txt")) {
            return Formato.CSV;
        }
        if (n.endsWith(".xlsx")) {
            return Formato.XLSX;
        }
        if (n.endsWith(".xls")) {
            return Formato.XLS;
        }
        throw new ValidationException("Formato no admitido: use Excel (.xlsx, .xls) o texto delimitado (.csv, .txt).");
    }

    /** Crea el procesador de una hoja (o nulo para saltarla). */
    @FunctionalInterface
    interface FabricaProcesador {
        Procesador para(String hoja);
    }

    private static void recorrerFormato(Formato f, Path archivo, Progreso p, FabricaProcesador fabrica, String soloHoja) {
        try {
            switch (f) {
                case XLSX -> xlsx(archivo, p, fabrica, soloHoja);
                case XLS -> xls(archivo, p, fabrica);
                case CSV -> csv(archivo, p, fabrica);
            }
        } catch (ValidationException | CancellationException e) {
            throw e;
        } catch (NotOfficeXmlFileException | org.apache.poi.EmptyFileException
                 | org.apache.poi.poifs.filesystem.NotOLE2FileException
                 | org.apache.poi.poifs.filesystem.OfficeXmlFileException
                 | org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new ValidationException("El archivo no es un libro de Excel válido.");
        } catch (OutOfMemoryError e) {
            throw new ValidationException("No hay memoria suficiente para leer este archivo. Guárdelo como CSV "
                    + "(Archivo › Guardar como › CSV UTF-8) e impórtelo: los CSV se leen por flujo sin límite de tamaño.");
        } catch (IOException e) {
            throw new ValidationException("No se pudo leer el archivo (¿no es un Excel válido?): " + e.getMessage());
        } catch (Exception e) {
            throw new ValidationException("El archivo no es un libro de Excel válido o está dañado: " + e.getMessage());
        }
    }

    /** Detecta encabezados y entrega las filas de datos de una hoja. */
    static final class Procesador {
        final String nombre;
        final int limiteMuestra;
        final Filas destino;
        final int encabezadoFijo;
        int primeraFila = -1;
        int filaEncabezado = -1;
        int ncols;
        List<String> encabezados = List.of();
        final List<List<Object>> muestra = new ArrayList<>();
        final List<Integer> numeros = new ArrayList<>();
        long total;

        Procesador(String nombre, int limiteMuestra, Filas destino, int encabezadoFijo, int columnasFijas) {
            this.nombre = nombre;
            this.limiteMuestra = limiteMuestra;
            this.destino = destino;
            this.encabezadoFijo = encabezadoFijo;
            this.ncols = columnasFijas;
        }

        /** Filas iniciales guardadas mientras se busca la fila de encabezados: {número, celdas}. */
        private final List<Object[]> inicio = new ArrayList<>();

        void fila(int num, List<Object> celdas) {
            if (primeraFila < 0) {
                primeraFila = num;
            }
            if (filaEncabezado < 0) {
                if (encabezadoFijo > 0) {
                    if (num == encabezadoFijo) {
                        filaEncabezado = num;
                    }
                    return;
                }
                if (num <= primeraFila + FILAS_BUSQUEDA_ENCABEZADO) {
                    inicio.add(new Object[]{num, celdas});
                    return;
                }
                elegirEncabezado();
                if (filaEncabezado < 0) {
                    return;
                }
            }
            datos(num, celdas);
        }

        /** Termina la búsqueda de encabezados en hojas más cortas que la ventana de búsqueda. */
        void fin() {
            if (filaEncabezado < 0 && encabezadoFijo <= 0 && !inicio.isEmpty()) {
                elegirEncabezado();
            }
        }

        /**
         * Elige como encabezados la fila de las primeras con más celdas que coinciden con nombres de columna conocidos
         * (Fecha, Producto, Cantidad, SKU, Stock...). Así se saltan las filas de título del reporte ("Empresa: ...",
         * "RUC: ...", "Desde: ..."). Si ninguna coincide se usa la primera fila con al menos dos textos.
         */
        private void elegirEncabezado() {
            int mejor = -1;
            int mejorPuntos = 0;
            int primeraConTextos = -1;
            for (int i = 0; i < inicio.size(); i++) {
                @SuppressWarnings("unchecked")
                List<Object> celdas = (List<Object>) inicio.get(i)[1];
                long textos = celdas.stream().filter(v -> v instanceof String s && !s.isBlank() && !esNumero(s)).count();
                if (textos < 2) {
                    continue;
                }
                if (primeraConTextos < 0) {
                    primeraConTextos = i;
                }
                int puntos = Vocabulario.puntos(celdas);
                if (puntos > mejorPuntos) {
                    mejorPuntos = puntos;
                    mejor = i;
                }
            }
            int elegida = mejorPuntos >= 2 ? mejor : primeraConTextos;
            if (elegida < 0) {
                inicio.clear();
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> celdas = (List<Object>) inicio.get(elegida)[1];
            filaEncabezado = (Integer) inicio.get(elegida)[0];
            ncols = celdas.size();
            List<String> enc = new ArrayList<>();
            for (int c = 0; c < ncols; c++) {
                String h = ValueParser.texto(celdas.get(c));
                enc.add(h == null || h.isBlank() ? "Columna " + (c + 1) : h.trim());
            }
            encabezados = enc;
            List<Object[]> resto = new ArrayList<>(inicio.subList(elegida + 1, inicio.size()));
            inicio.clear();
            for (Object[] f : resto) {
                @SuppressWarnings("unchecked")
                List<Object> c = (List<Object>) f[1];
                datos((Integer) f[0], c);
            }
        }

        private void datos(int num, List<Object> celdas) {
            if (num <= filaEncabezado) {
                return;
            }
            List<Object> v = new ArrayList<>(ncols);
            boolean vacia = true;
            for (int c = 0; c < ncols; c++) {
                Object x = c < celdas.size() ? celdas.get(c) : null;
                if (x != null && !(x instanceof String s && s.isBlank())) {
                    vacia = false;
                }
                v.add(x);
            }
            if (vacia) {
                return;
            }
            total++;
            if (destino != null) {
                destino.fila(num, v);
            }
            if (muestra.size() < limiteMuestra) {
                muestra.add(v);
                numeros.add(num);
            }
        }
    }

    static boolean esNumero(String v) {
        return v.trim().matches("[-+]?[0-9.,\\s]+");
    }

    /** Flujo que cuenta los bytes leídos e informa el avance real. */
    static final class Contador extends FilterInputStream {
        private final Progreso p;
        private final double base;
        private final double rango;
        private final long total;
        private final String mensaje;
        private long leidos;
        private long ultimoAviso;

        Contador(InputStream in, Progreso p, double base, double rango, long total, String mensaje) {
            super(in);
            this.p = p;
            this.base = base;
            this.rango = rango;
            this.total = Math.max(1, total);
            this.mensaje = mensaje;
        }

        private void sumar(long n) {
            if (n <= 0) {
                return;
            }
            leidos += n;
            if (leidos - ultimoAviso >= 1 << 20 || leidos >= total) {
                ultimoAviso = leidos;
                p.verificar();
                p.avance(base + rango * Math.min(1.0, leidos / (double) total),
                        String.format("%s (%,d de %,d MB)", mensaje, leidos >> 20, total >> 20));
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                sumar(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            sumar(n);
            return n;
        }
    }

    // =================================================================== XLSX (SAX)

    private static long memoriaLibre() {
        Runtime r = Runtime.getRuntime();
        return r.maxMemory() - (r.totalMemory() - r.freeMemory());
    }

    /** Verifica que la tabla de textos compartidos quepa con seguridad en la memoria disponible. */
    static void verificarMemoriaXlsx(long bytesTextosCompartidos, long memoriaLibre) {
        // Cada byte de XML de textos ocupa ~2,5 bytes en memoria (caracteres UTF-16 + objetos).
        long necesario = (long) (bytesTextosCompartidos * 2.5);
        if (necesario > memoriaLibre * FRACCION_MEMORIA_SEGURA) {
            throw new ValidationException(String.format("El archivo es demasiado grande para leerlo con seguridad en "
                            + "este equipo: su tabla de textos ocupa %,d MB y la memoria disponible es de %,d MB. "
                            + "Guárdelo como CSV (en Excel: Archivo › Guardar como › CSV UTF-8) e impórtelo: los CSV se "
                            + "leen por flujo sin límite de tamaño y se conservan las mismas columnas y datos.",
                    bytesTextosCompartidos >> 20, memoriaLibre >> 20));
        }
    }

    private static void xlsx(Path archivo, Progreso p, FabricaProcesador fabrica, String soloHoja) throws Exception {
        Map<String, Long> tamanos = new HashMap<>();
        try (ZipFile zip = new ZipFile(archivo.toFile())) {
            var e = zip.entries();
            while (e.hasMoreElements()) {
                ZipEntry z = e.nextElement();
                tamanos.put(z.getName(), z.getSize());
            }
        } catch (java.util.zip.ZipException e) {
            throw new ValidationException("El archivo no es un libro de Excel válido.");
        }
        verificarMemoriaXlsx(tamanos.getOrDefault("xl/sharedStrings.xml", 0L), memoriaLibre());
        try (OPCPackage pkg = OPCPackage.open(archivo.toFile(), PackageAccess.READ)) {
            XSSFReader r = new XSSFReader(pkg);
            p.avance(1, "Leyendo textos del libro");
            ReadOnlySharedStringsTable sst = new ReadOnlySharedStringsTable(pkg, false);
            StylesTable estilos = r.getStylesTable();
            Libro libro = leerLibro(r.getWorkbookData());
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) r.getSheetsData();
            List<String[]> partes = new ArrayList<>();
            long totalBytes = 0;
            // Primero se identifican las hojas visibles para medir el avance sobre sus bytes.
            while (it.hasNext()) {
                try (InputStream in = it.next()) {
                    String nombre = it.getSheetName();
                    String parte = it.getSheetPart().getPartName().getName().replaceFirst("^/", "");
                    if (libro.ocultas.contains(nombre) || (soloHoja != null && !soloHoja.equals(nombre))) {
                        continue;
                    }
                    partes.add(new String[]{nombre, parte});
                    totalBytes += Math.max(0, tamanos.getOrDefault(parte, 0L));
                }
            }
            long hechos = 0;
            it = (XSSFReader.SheetIterator) r.getSheetsData();
            while (it.hasNext()) {
                try (InputStream in = it.next()) {
                    String nombre = it.getSheetName();
                    String parte = it.getSheetPart().getPartName().getName().replaceFirst("^/", "");
                    if (partes.stream().noneMatch(x -> x[0].equals(nombre))) {
                        continue;
                    }
                    Procesador pr = fabrica.para(nombre);
                    if (pr == null) {
                        continue;
                    }
                    long tam = Math.max(1, tamanos.getOrDefault(parte, 1L));
                    double base = 2 + 97.0 * hechos / Math.max(1, totalBytes);
                    double rango = 97.0 * tam / Math.max(1, totalBytes);
                    XMLReader xr = XMLHelper.newXMLReader();
                    xr.setContentHandler(new HojaHandler(sst, estilos, libro.fecha1904, pr::fila));
                    try {
                        xr.parse(new InputSource(new Contador(in, p, base, rango, tam, "Leyendo hoja " + nombre)));
                    } catch (SAXException e) {
                        if (e.getException() instanceof RuntimeException re) {
                            throw re;
                        }
                        throw e;
                    }
                    hechos += tam;
                }
            }
        }
    }

    private record Libro(boolean fecha1904, java.util.Set<String> ocultas) {
    }

    private static Libro leerLibro(InputStream in) throws Exception {
        boolean[] f1904 = {false};
        java.util.Set<String> ocultas = new java.util.HashSet<>();
        try (in) {
            XMLReader xr = XMLHelper.newXMLReader();
            xr.setContentHandler(new DefaultHandler() {
                @Override
                public void startElement(String uri, String local, String qName, Attributes a) {
                    String n = local == null || local.isEmpty() ? qName : local;
                    if ("workbookPr".equals(n)) {
                        String v = a.getValue("date1904");
                        f1904[0] = "1".equals(v) || "true".equalsIgnoreCase(v);
                    } else if ("sheet".equals(n)) {
                        String st = a.getValue("state");
                        if ("hidden".equals(st) || "veryHidden".equals(st)) {
                            ocultas.add(a.getValue("name"));
                        }
                    }
                }
            });
            xr.parse(new InputSource(in));
        }
        return new Libro(f1904[0], ocultas);
    }

    /** Manejador SAX de una hoja: convierte cada celda al mismo tipo que el lector anterior. */
    static final class HojaHandler extends DefaultHandler {
        private final ReadOnlySharedStringsTable sst;
        private final StylesTable estilos;
        private final boolean fecha1904;
        private final Filas salida;
        private final Map<Integer, Boolean> esFecha = new HashMap<>();
        private final StringBuilder texto = new StringBuilder();
        private List<Object> fila = new ArrayList<>();
        private int numFila;
        private int siguienteFila = 1;
        private int col;
        private int ultimaCol = -1;
        private String tipo;
        private int estilo;
        private boolean enValor;
        private boolean enTextoInline;

        HojaHandler(ReadOnlySharedStringsTable sst, StylesTable estilos, boolean fecha1904, Filas salida) {
            this.sst = sst;
            this.estilos = estilos;
            this.fecha1904 = fecha1904;
            this.salida = salida;
        }

        private static String nombre(String local, String qName) {
            return local == null || local.isEmpty() ? qName : local;
        }

        static int columna(String ref) {
            int c = 0;
            for (int i = 0; i < ref.length(); i++) {
                char ch = ref.charAt(i);
                if (ch >= 'A' && ch <= 'Z') {
                    c = c * 26 + (ch - 'A' + 1);
                } else {
                    break;
                }
            }
            return c - 1;
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes a) {
            switch (nombre(local, qName)) {
                case "row" -> {
                    String r = a.getValue("r");
                    numFila = r == null ? siguienteFila : Integer.parseInt(r);
                    fila = new ArrayList<>();
                    ultimaCol = -1;
                }
                case "c" -> {
                    String r = a.getValue("r");
                    col = r == null ? ultimaCol + 1 : columna(r);
                    tipo = a.getValue("t");
                    String s = a.getValue("s");
                    estilo = s == null ? -1 : Integer.parseInt(s);
                    texto.setLength(0);
                }
                case "v" -> {
                    enValor = true;
                    texto.setLength(0);
                }
                case "t" -> enTextoInline = "inlineStr".equals(tipo);
                default -> {
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (enValor || enTextoInline) {
                texto.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            switch (nombre(local, qName)) {
                case "v" -> enValor = false;
                case "t" -> enTextoInline = false;
                case "c" -> {
                    Object v = valor();
                    while (fila.size() <= col) {
                        fila.add(null);
                    }
                    fila.set(col, v);
                    ultimaCol = col;
                }
                case "row" -> {
                    salida.fila(numFila, fila);
                    siguienteFila = numFila + 1;
                }
                default -> {
                }
            }
        }

        private Object valor() {
            String v = texto.toString();
            if (tipo == null || "n".equals(tipo)) {
                if (v.isBlank()) {
                    return null;
                }
                double d = Double.parseDouble(v);
                if (fecha(estilo) && DateUtil.isValidExcelDate(d)) {
                    return DateUtil.getLocalDateTime(d, fecha1904).toLocalDate();
                }
                return d;
            }
            return switch (tipo) {
                case "s" -> {
                    if (v.isBlank()) {
                        yield null;
                    }
                    String s = sst.getItemAt(Integer.parseInt(v.trim())).getString();
                    yield s == null || s.trim().isEmpty() ? null : s.trim();
                }
                case "inlineStr", "str" -> v.trim().isEmpty() ? null : v.trim();
                case "b" -> "1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim());
                default -> null; // "e" (error) u otros
            };
        }

        private boolean fecha(int s) {
            if (s < 0 || estilos == null) {
                return false;
            }
            return esFecha.computeIfAbsent(s, k -> {
                XSSFCellStyle st = estilos.getStyleAt(k);
                return st != null && DateUtil.isADateFormat(st.getDataFormat(), st.getDataFormatString());
            });
        }
    }

    // =================================================================== XLS

    private static void xls(Path archivo, Progreso p, FabricaProcesador fabrica) throws Exception {
        long tam = Files.size(archivo);
        // El formato .xls se lee completo (sin API de flujo práctica); se verifica que quepa con margen.
        if (tam * 10 > memoriaLibre() * FRACCION_MEMORIA_SEGURA) {
            throw new ValidationException(String.format("El archivo .xls (%,d MB) es demasiado grande para leerlo con "
                    + "seguridad en este equipo. Guárdelo como .xlsx o CSV UTF-8 e impórtelo de nuevo: esos formatos se "
                    + "leen por flujo.", tam >> 20));
        }
        p.avance(5, "Leyendo libro .xls");
        try (Workbook wb = WorkbookFactory.create(archivo.toFile(), null, true)) {
            int n = wb.getNumberOfSheets();
            for (int i = 0; i < n; i++) {
                p.verificar();
                if (wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)) {
                    continue;
                }
                Sheet sh = wb.getSheetAt(i);
                Procesador pr = fabrica.para(sh.getSheetName());
                if (pr == null) {
                    continue;
                }
                int filas = Math.max(1, sh.getLastRowNum() + 1);
                for (Row row : sh) {
                    List<Object> celdas = new ArrayList<>();
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        Cell cell = row.getCell(c);
                        celdas.add(ExcelReader.valor(cell));
                    }
                    pr.fila(row.getRowNum() + 1, celdas);
                    if (row.getRowNum() % 2000 == 0) {
                        p.verificar();
                        p.avance(5 + 94.0 * (i + row.getRowNum() / (double) filas) / n, "Leyendo hoja " + sh.getSheetName());
                    }
                }
            }
        }
    }

    // =================================================================== CSV

    /** UTF-8 si todo el archivo es UTF-8 válido; si no, Windows-1252. Se verifica por flujo. */
    static Charset detectarCodificacion(Path archivo) throws IOException {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.allocate(1 << 16);
        CharBuffer out = CharBuffer.allocate(1 << 16);
        try (InputStream is = Files.newInputStream(archivo)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf, 0, in.remaining())) != -1) {
                in.put(buf, 0, n);
                in.flip();
                CoderResult r = dec.decode(in, out, false);
                if (r.isError()) {
                    return Charset.forName("windows-1252");
                }
                out.clear();
                in.compact();
            }
            in.flip();
            if (dec.decode(in, out, true).isError() || dec.flush(out).isError()) {
                return Charset.forName("windows-1252");
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void csv(Path archivo, Progreso p, FabricaProcesador fabrica) throws IOException {
        String nombreArchivo = archivo.getFileName().toString();
        int punto = nombreArchivo.lastIndexOf('.');
        String hoja = punto > 0 ? nombreArchivo.substring(0, punto) : nombreArchivo;
        Procesador pr = fabrica.para(hoja);
        if (pr == null) {
            return;
        }
        p.avance(1, "Detectando codificación");
        Charset cs = detectarCodificacion(archivo);
        char sep;
        try (BufferedReader br = Files.newBufferedReader(archivo, cs)) {
            StringBuilder inicio = new StringBuilder();
            String l;
            for (int i = 0; i < 60 && (l = br.readLine()) != null; i++) {
                inicio.append(l).append('\n');
            }
            sep = CsvReader.detectarSeparador(inicio.toString());
        }
        long tam = Files.size(archivo);
        try (Reader rd = new BufferedReader(new InputStreamReader(new Contador(Files.newInputStream(archivo), p, 2, 97,
                tam, "Leyendo " + nombreArchivo), cs), 1 << 16)) {
            int[] numero = {0};
            parsear(rd, sep, campos -> {
                numero[0]++;
                List<Object> celdas = new ArrayList<>(campos.size());
                for (String c : campos) {
                    String v = c.trim();
                    celdas.add(v.isEmpty() ? null : v);
                }
                pr.fila(numero[0], celdas);
            });
        }
        pr.fin();
        if (pr.filaEncabezado < 0) {
            throw new ValidationException("El archivo CSV no contiene datos tabulares (se requieren encabezados y al "
                    + "menos dos columnas).");
        }
    }

    /** Divide el flujo en filas y campos respetando comillas (campos multilínea, comillas dobles escapadas). */
    static void parsear(Reader rd, char sep, java.util.function.Consumer<List<String>> salida) throws IOException {
        List<String> fila = new ArrayList<>();
        StringBuilder campo = new StringBuilder();
        boolean comillas = false;
        boolean primero = true;
        int c;
        int pendiente = -1;
        while ((c = pendiente >= 0 ? pendiente : rd.read()) != -1) {
            pendiente = -1;
            char ch = (char) c;
            if (primero) {
                primero = false;
                if (ch == '﻿') {
                    continue;
                }
            }
            if (comillas) {
                if (ch == '"') {
                    int sig = rd.read();
                    if (sig == '"') {
                        campo.append('"');
                    } else {
                        comillas = false;
                        pendiente = sig;
                    }
                } else {
                    campo.append(ch);
                }
            } else if (ch == '"') {
                comillas = true;
            } else if (ch == sep) {
                fila.add(campo.toString());
                campo.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r') {
                    int sig = rd.read();
                    if (sig != '\n') {
                        pendiente = sig;
                    }
                }
                fila.add(campo.toString());
                campo.setLength(0);
                salida.accept(fila);
                fila = new ArrayList<>();
            } else {
                campo.append(ch);
            }
        }
        if (campo.length() > 0 || !fila.isEmpty()) {
            fila.add(campo.toString());
            salida.accept(fila);
        }
    }
}
