package com.stockwise.exporter;

import com.stockwise.exporter.ReportTable.ColumnType;
import com.stockwise.util.Formatters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Exporta tablas de reporte a PDF (A4 horizontal) con PDFBox: encabezado corporativo, tabla paginada con
 * encabezados repetidos y pie con número de página. Usa fuentes estándar (no requiere recursos externos).
 */
public final class PdfExporter {

    private static final Color AZUL = new Color(15, 35, 75);
    private static final Color GRIS = new Color(242, 244, 248);
    private static final Color TEXTO = new Color(40, 44, 52);
    private static final float MARGEN = 36;
    private static final float ALTO_FILA = 16;
    private static final float TAM = 8;

    private PdfExporter() {
    }

    public static void exportar(List<ReportTable> tablas, Path destino, String empresa) throws IOException {
        PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont negrita = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try (PDDocument doc = new PDDocument()) {
            for (ReportTable t : tablas) {
                new Writer(doc, t, empresa, regular, negrita).escribir();
            }
            int total = doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage page = doc.getPage(i);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true)) {
                    texto(cs, regular, 7, Color.GRAY, MARGEN, 20, "STOCKWISE · Generado el "
                            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
                    String pg = "Página " + (i + 1) + " de " + total;
                    float w = regular.getStringWidth(pg) / 1000 * 7;
                    texto(cs, regular, 7, Color.GRAY, page.getMediaBox().getWidth() - MARGEN - w, 20, pg);
                }
            }
            Path parent = destino.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            doc.save(destino.toFile());
        }
    }

    private static final class Writer {
        final PDDocument doc;
        final ReportTable t;
        final String empresa;
        final PDFont regular;
        final PDFont negrita;
        final PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        float[] anchos;
        PDPageContentStream cs;
        float y;

        Writer(PDDocument doc, ReportTable t, String empresa, PDFont regular, PDFont negrita) {
            this.doc = doc;
            this.t = t;
            this.empresa = empresa;
            this.regular = regular;
            this.negrita = negrita;
        }

        void escribir() throws IOException {
            calcularAnchos();
            nuevaPagina(true);
            int i = 0;
            for (List<Object> fila : t.filas()) {
                if (y - ALTO_FILA < MARGEN + 20) {
                    cs.close();
                    nuevaPagina(false);
                }
                if (i++ % 2 == 1) {
                    cs.setNonStrokingColor(GRIS);
                    cs.addRect(MARGEN, y - ALTO_FILA + 4, size.getWidth() - 2 * MARGEN, ALTO_FILA);
                    cs.fill();
                }
                float x = MARGEN;
                for (int c = 0; c < fila.size(); c++) {
                    String s = formatear(fila.get(c), t.tipos().get(c));
                    s = recortar(s, regular, anchos[c] - 6);
                    boolean derecha = t.tipos().get(c) != ColumnType.TEXTO;
                    float tx = derecha ? x + anchos[c] - 3 - ancho(regular, s) : x + 3;
                    texto(cs, regular, TAM, TEXTO, tx, y - ALTO_FILA + 8, s);
                    x += anchos[c];
                }
                y -= ALTO_FILA;
            }
            if (t.filas().isEmpty()) {
                texto(cs, regular, 9, TEXTO, MARGEN, y - 14, "Sin datos para el periodo o filtros seleccionados.");
            }
            cs.close();
        }

        void nuevaPagina(boolean primera) throws IOException {
            PDPage page = new PDPage(size);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = size.getHeight() - MARGEN;
            // Banda superior
            cs.setNonStrokingColor(AZUL);
            cs.addRect(0, size.getHeight() - 8, size.getWidth(), 8);
            cs.fill();
            texto(cs, negrita, primera ? 15 : 11, AZUL, MARGEN, y - 6, t.titulo() + (primera ? "" : " (cont.)"));
            y -= primera ? 22 : 18;
            if (primera) {
                texto(cs, regular, 9, Color.DARK_GRAY, MARGEN, y, limpiar(empresa + " · " + t.subtitulo()));
                y -= 14;
                for (String l : t.resumen()) {
                    texto(cs, regular, 8.5f, TEXTO, MARGEN, y, recortar(limpiar(l), regular,
                            size.getWidth() - 2 * MARGEN, 8.5f));
                    y -= 12;
                }
                y -= 6;
            }
            // Encabezado de la tabla
            cs.setNonStrokingColor(AZUL);
            cs.addRect(MARGEN, y - ALTO_FILA + 4, size.getWidth() - 2 * MARGEN, ALTO_FILA);
            cs.fill();
            float x = MARGEN;
            for (int c = 0; c < t.columnas().size(); c++) {
                String s = recortar(limpiar(t.columnas().get(c)), negrita, anchos[c] - 6);
                texto(cs, negrita, TAM, Color.WHITE, x + 3, y - ALTO_FILA + 8, s);
                x += anchos[c];
            }
            y -= ALTO_FILA;
        }

        void calcularAnchos() throws IOException {
            int n = t.columnas().size();
            float[] pesos = new float[n];
            for (int c = 0; c < n; c++) {
                float max = ancho(negrita, limpiar(t.columnas().get(c)));
                int muestras = 0;
                for (List<Object> f : t.filas()) {
                    if (muestras++ > 300) {
                        break;
                    }
                    max = Math.max(max, ancho(regular, formatear(f.get(c), t.tipos().get(c))));
                }
                pesos[c] = Math.min(max, 260) + 8;
            }
            float total = 0;
            for (float p : pesos) {
                total += p;
            }
            float disponible = size.getWidth() - 2 * MARGEN;
            anchos = new float[n];
            for (int c = 0; c < n; c++) {
                anchos[c] = pesos[c] / total * disponible;
            }
        }

        float ancho(PDFont f, String s) throws IOException {
            return f.getStringWidth(limpiar(s)) / 1000 * TAM;
        }

        String recortar(String s, PDFont f, float max) throws IOException {
            return recortar(s, f, max, TAM);
        }

        String recortar(String s, PDFont f, float max, float tam) throws IOException {
            s = limpiar(s);
            if (f.getStringWidth(s) / 1000 * tam <= max) {
                return s;
            }
            while (s.length() > 1 && f.getStringWidth(s + "...") / 1000 * tam > max) {
                s = s.substring(0, s.length() - 1);
            }
            return s + "...";
        }
    }

    static String formatear(Object v, ColumnType tipo) {
        if (v == null) {
            return "";
        }
        if (v instanceof LocalDate d) {
            return Formatters.date(d);
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return switch (tipo) {
                case ENTERO -> Formatters.integer(d);
                case MONEDA -> Formatters.money(d);
                case PORCENTAJE -> Formatters.percent(d);
                default -> Formatters.number(d);
            };
        }
        return v.toString();
    }

    /** Las fuentes estándar solo admiten WinAnsi: se reemplazan los caracteres no representables. */
    static String limpiar(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '≤' -> sb.append("<=");
                case '≥' -> sb.append(">=");
                case '→' -> sb.append("->");
                case '∞' -> sb.append("inf");
                case 'α' -> sb.append("alfa");
                case '×' -> sb.append('x');
                case '·', '•' -> sb.append('-');
                case '\n', '\r', '\t' -> sb.append(' ');
                default -> sb.append(ch < 256 || ch == '€' || ch == '–' || ch == '—' ? ch : '?');
            }
        }
        return sb.toString();
    }

    private static void texto(PDPageContentStream cs, PDFont f, float tam, Color color, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(f, tam);
        cs.setNonStrokingColor(color);
        cs.newLineAtOffset(x, y);
        cs.showText(limpiar(s));
        cs.endText();
    }
}
