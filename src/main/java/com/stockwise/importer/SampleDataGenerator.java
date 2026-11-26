package com.stockwise.importer;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Random;

/**
 * Genera un archivo Excel de DEMOSTRACIÓN con el formato de la plantilla oficial, para conocer el sistema
 * sin datos reales. Los datos son sintéticos (semilla fija) y así se indica en la hoja de instrucciones.
 */
public final class SampleDataGenerator {

    private record Prod(String codigo, String nombre, String categoria, String proveedor, double precio,
                        double costo, int min, int max, int leadTime, double base, double tendencia,
                        double estacionalidad) {
    }

    private static final String[][] SUCURSALES = {
            {"LIM01", "Lima Centro", "Lima", "Av. Abancay 123"},
            {"ARE01", "Arequipa Mall", "Arequipa", "Av. Ejército 456"},
            {"TRU01", "Trujillo Plaza", "Trujillo", "Jr. Pizarro 789"}};

    private static final List<Prod> PRODUCTOS = List.of(
            new Prod("LAP-001", "Laptop 14\" Core i5", "Computadoras", "TecnoImport", 2899, 2150, 5, 40, 20, 18, 0.25, 0.3),
            new Prod("LAP-002", "Laptop 15\" Ryzen 7", "Computadoras", "TecnoImport", 3599, 2780, 3, 25, 20, 9, 0.10, 0.3),
            new Prod("MON-001", "Monitor 24\" FHD", "Monitores", "VisionPro", 649, 455, 8, 60, 15, 25, 0.0, 0.2),
            new Prod("MON-002", "Monitor 27\" 2K", "Monitores", "VisionPro", 1099, 820, 4, 30, 15, 10, 0.35, 0.2),
            new Prod("TEC-001", "Teclado mecánico RGB", "Accesorios", "GearMax", 249, 150, 10, 80, 10, 40, 0.5, 0.1),
            new Prod("MOU-001", "Mouse inalámbrico", "Accesorios", "GearMax", 89, 48, 20, 150, 10, 75, 0.3, 0.1),
            new Prod("AUD-001", "Audífonos Bluetooth", "Audio", "SoundWave", 199, 118, 10, 90, 12, 45, -0.8, 0.4),
            new Prod("AUD-002", "Parlante portátil", "Audio", "SoundWave", 299, 255, 5, 40, 12, 20, -0.3, 0.5),
            new Prod("IMP-001", "Impresora multifuncional", "Impresión", "PrintCorp", 799, 610, 3, 20, 25, 7, -0.1, 0.1),
            new Prod("TIN-001", "Cartucho de tinta negro", "Impresión", "PrintCorp", 79, 52, 30, 200, 25, 60, -0.4, 0.0),
            new Prod("CAB-001", "Cable HDMI 2m", "Accesorios", "GearMax", 35, 12, 30, 250, 10, 110, 0.6, 0.0),
            new Prod("SSD-001", "SSD 1TB NVMe", "Almacenamiento", "DataStore", 399, 290, 8, 70, 15, 30, 0.9, 0.1),
            new Prod("USB-001", "Memoria USB 64GB", "Almacenamiento", "DataStore", 45, 39, 25, 200, 15, 90, -0.5, 0.2),
            new Prod("CAM-001", "Cámara web HD", "Accesorios", "VisionPro", 189, 112, 6, 50, 15, 22, -1.2, 0.0),
            new Prod("SIL-001", "Silla ergonómica", "Mobiliario", "OfiConfort", 899, 640, 2, 15, 30, 0, 0.0, 0.0),
            new Prod("TAB-001", "Tablet 10\"", "Computadoras", "TecnoImport", 1299, 1180, 3, 25, 20, 8, 0.0, 0.3));

    /** Factor estacional por mes (campaña escolar en marzo, fiestas en diciembre). */
    private static final double[] ESTACIONAL = {0.9, 0.95, 1.25, 1.0, 0.95, 1.0, 1.1, 0.95, 0.9, 1.0, 1.1, 1.45};

    private SampleDataGenerator() {
    }

    public static void generar(Path destino) throws IOException {
        generar(destino, 24, LocalDate.now().withDayOfMonth(1).minusDays(1));
    }

    /**
     * @param meses meses de historial
     * @param hasta última fecha de ventas
     */
    public static void generar(Path destino, int meses, LocalDate hasta) throws IOException {
        Random rnd = new Random(42);
        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle header = TemplateGenerator.estiloEncabezado(wb, IndexedColors.DARK_BLUE);
            CellStyle fecha = wb.createCellStyle();
            fecha.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));

            Sheet info = wb.createSheet("Instrucciones");
            info.createRow(0).createCell(0).setCellValue("ARCHIVO DE DEMOSTRACIÓN - datos sintéticos generados por STOCKWISE.");
            info.createRow(1).createCell(0).setCellValue("Importe las hojas en este orden: Sucursales, Proveedores, Productos, "
                    + "Inventario, Ventas históricas, Órdenes de compra, Presupuestos.");
            info.setColumnWidth(0, 110 * 256);

            Sheet suc = hoja(wb, ImportType.SUCURSALES, header);
            int r = 1;
            for (String[] s : SUCURSALES) {
                Row row = suc.createRow(r++);
                for (int i = 0; i < s.length; i++) {
                    row.createCell(i).setCellValue(s[i]);
                }
            }

            Sheet prod = hoja(wb, ImportType.PRODUCTOS, header);
            r = 1;
            for (Prod p : PRODUCTOS) {
                Row row = prod.createRow(r++);
                row.createCell(0).setCellValue(p.codigo());
                row.createCell(1).setCellValue(p.nombre());
                row.createCell(2).setCellValue(p.categoria());
                row.createCell(3).setCellValue(p.proveedor());
                row.createCell(4).setCellValue(p.precio());
                row.createCell(5).setCellValue(p.costo());
                row.createCell(6).setCellValue(p.min());
                row.createCell(7).setCellValue(p.max());
                row.createCell(8).setCellValue(p.leadTime());
            }

            // Ventas: un registro por producto, sucursal y día con venta.
            Sheet ven = hoja(wb, ImportType.VENTAS, header);
            r = 1;
            double[] pesoSucursal = {0.5, 0.3, 0.2};
            YearMonth inicio = YearMonth.from(hasta).minusMonths(meses - 1);
            int doc = 1000;
            int[][][] vendidas = new int[meses][PRODUCTOS.size()][SUCURSALES.length];
            for (int m = 0; m < meses; m++) {
                YearMonth ym = inicio.plusMonths(m);
                for (int pi = 0; pi < PRODUCTOS.size(); pi++) {
                    Prod p = PRODUCTOS.get(pi);
                    if (p.base() <= 0) {
                        continue; // producto sin ventas (para demostrar "sin movimiento")
                    }
                    double estacion = 1 + (ESTACIONAL[ym.getMonthValue() - 1] - 1) * (p.estacionalidad() / 0.3);
                    double mensual = Math.max(0, (p.base() + p.tendencia() * m) * estacion);
                    for (int si = 0; si < SUCURSALES.length; si++) {
                        // Ventas de AUD-002 se concentran en Lima (exceso en Trujillo para sugerir traslados).
                        double peso = p.codigo().equals("AUD-002") ? (si == 0 ? 0.9 : si == 1 ? 0.1 : 0.0)
                                : pesoSucursal[si];
                        double unidades = mensual * peso * (0.85 + rnd.nextDouble() * 0.3);
                        int total = (int) Math.round(unidades);
                        vendidas[m][pi][si] = total;
                        int transacciones = Math.max(1, Math.min(total, 4 + rnd.nextInt(5)));
                        int restante = total;
                        for (int t = 0; t < transacciones && restante > 0; t++) {
                            int cant = t == transacciones - 1 ? restante
                                    : Math.max(1, (int) Math.round(restante / (double) (transacciones - t)));
                            restante -= cant;
                            int dia = 1 + rnd.nextInt(ym.lengthOfMonth());
                            LocalDate f = ym.atDay(dia);
                            if (f.isAfter(hasta)) {
                                f = hasta;
                            }
                            Row row = ven.createRow(r++);
                            row.createCell(0).setCellValue(f);
                            row.getCell(0).setCellStyle(fecha);
                            row.createCell(1).setCellValue(p.codigo());
                            row.createCell(2).setCellValue(SUCURSALES[si][0]);
                            row.createCell(3).setCellValue(cant);
                            row.createCell(4).setCellValue(p.precio());
                            row.createCell(5).setCellValue(cant * p.precio());
                            row.createCell(6).setCellValue("B" + (doc++));
                        }
                    }
                }
            }

            // Inventario actual con situaciones variadas (agotados, riesgo, exceso).
            Sheet inv = hoja(wb, ImportType.INVENTARIO, header);
            r = 1;
            for (int pi = 0; pi < PRODUCTOS.size(); pi++) {
                Prod p = PRODUCTOS.get(pi);
                for (int si = 0; si < SUCURSALES.length; si++) {
                    double mensual = Math.max(1, (p.base() + p.tendencia() * meses) * pesoSucursal[si]);
                    int stock = switch (pi % 5) {
                        case 0 -> (int) Math.round(mensual * 0.3); // riesgo
                        case 1 -> (int) Math.round(mensual * 1.5); // normal
                        case 2 -> (int) Math.round(mensual * 6);   // sobreinventario
                        case 3 -> si == 0 ? 0 : (int) Math.round(mensual * 2);
                        default -> (int) Math.round(mensual * 1.2);
                    };
                    if (p.codigo().equals("AUD-002")) {
                        stock = si == 0 ? 1 : si == 2 ? 45 : 6;
                    }
                    if (p.base() <= 0) {
                        stock = 12;
                    }
                    Row row = inv.createRow(r++);
                    row.createCell(0).setCellValue(p.codigo());
                    row.createCell(1).setCellValue(SUCURSALES[si][0]);
                    row.createCell(2).setCellValue(stock);
                }
            }

            escribirProveedores(wb, header);
            escribirCompras(wb, header, fecha, rnd, vendidas, inicio, hasta, pesoSucursal);

            Sheet pre = hoja(wb, ImportType.PRESUPUESTOS, header);
            YearMonth actual = YearMonth.now();
            for (int i = 0; i < 3; i++) {
                Row row = pre.createRow(i + 1);
                row.createCell(0).setCellValue(actual.plusMonths(i).toString());
                row.createCell(1).setCellValue("");
                row.createCell(2).setCellValue(25000);
                row.createCell(3).setCellValue("Presupuesto de compras (demostración)");
            }

            Files.createDirectories(destino.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(destino)) {
                wb.write(out);
            }
        }
    }

    /** Proveedor de demostración: tiempo de entrega y ciclo de compra en días. */
    private static final Object[][] PROVEEDORES = {
            {"TecnoImport", "Ana Torres", 20, 30}, {"VisionPro", "Luis Rojas", 15, 30},
            {"GearMax", "Carla Díaz", 10, 15}, {"SoundWave", "Mario Paz", 12, 30},
            {"PrintCorp", "Rosa Vega", 25, 30}, {"DataStore", "Iván Soto", 15, 15},
            {"OfiConfort", "Elena Ruiz", 30, 60}};

    private static void escribirProveedores(Workbook wb, CellStyle header) {
        Sheet sh = hoja(wb, ImportType.PROVEEDORES, header);
        int r = 1;
        for (Object[] p : PROVEEDORES) {
            Row row = sh.createRow(r++);
            row.createCell(0).setCellValue((String) p[0]);
            row.createCell(1).setCellValue((String) p[1]);
            row.createCell(2).setCellValue("");
            row.createCell(3).setCellValue("");
            row.createCell(4).setCellValue((Integer) p[2]);
            row.createCell(5).setCellValue((Integer) p[3]);
        }
    }

    /**
     * Órdenes de compra: cada mes se repone lo vendido (recepción el día 1, pedido un tiempo de entrega antes), de modo
     * que el stock reconstruido sea coherente con el inventario actual. Los productos en riesgo tienen además una
     * orden pendiente (en tránsito).
     */
    private static void escribirCompras(Workbook wb, CellStyle header, CellStyle fecha, Random rnd, int[][][] vendidas,
                                        YearMonth inicio, LocalDate hasta, double[] pesoSucursal) {
        Sheet sh = hoja(wb, ImportType.COMPRAS, header);
        int r = 1;
        int oc = 5000;
        for (int m = 0; m < vendidas.length; m++) {
            LocalDate recepcion = inicio.plusMonths(m).atDay(1);
            for (int pi = 0; pi < PRODUCTOS.size(); pi++) {
                Prod p = PRODUCTOS.get(pi);
                LocalDate pedido = recepcion.minusDays(p.leadTime() + rnd.nextInt(5) - 2L);
                for (int si = 0; si < SUCURSALES.length; si++) {
                    int cant = vendidas[m][pi][si];
                    if (cant <= 0) {
                        continue;
                    }
                    r = filaCompra(sh, r, fecha, pedido, p, SUCURSALES[si][0], cant, "Recibida", null, recepcion,
                            "OC-" + oc);
                }
                oc++;
            }
        }
        for (int pi = 0; pi < PRODUCTOS.size(); pi += 5) {
            Prod p = PRODUCTOS.get(pi);
            if (p.base() <= 0) {
                continue;
            }
            int cant = (int) Math.max(1, Math.round((p.base() + p.tendencia() * vendidas.length) * pesoSucursal[1]));
            r = filaCompra(sh, r, fecha, hasta.minusDays(3), p, SUCURSALES[1][0], cant, "Pendiente",
                    hasta.plusDays(p.leadTime()), null, "OC-" + (oc++));
        }
    }

    private static int filaCompra(Sheet sh, int r, CellStyle fecha, LocalDate pedido, Prod p, String sucursal, int cant,
                                  String estado, LocalDate esperada, LocalDate recepcion, String doc) {
        Row row = sh.createRow(r);
        row.createCell(0).setCellValue(pedido);
        row.getCell(0).setCellStyle(fecha);
        row.createCell(1).setCellValue(p.codigo());
        row.createCell(2).setCellValue(sucursal);
        row.createCell(3).setCellValue(cant);
        row.createCell(4).setCellValue(p.costo());
        row.createCell(5).setCellValue(cant * p.costo());
        row.createCell(6).setCellValue(p.proveedor());
        row.createCell(7).setCellValue(estado);
        if (esperada != null) {
            row.createCell(8).setCellValue(esperada);
            row.getCell(8).setCellStyle(fecha);
        }
        if (recepcion != null) {
            row.createCell(9).setCellValue(recepcion);
            row.getCell(9).setCellStyle(fecha);
        }
        row.createCell(10).setCellValue(doc);
        return r + 1;
    }

    private static Sheet hoja(Workbook wb, ImportType tipo, CellStyle header) {
        Sheet sh = wb.createSheet(tipo.etiqueta());
        Row h = sh.createRow(0);
        for (int i = 0; i < tipo.campos().size(); i++) {
            h.createCell(i).setCellValue(tipo.campos().get(i).etiqueta());
            h.getCell(i).setCellStyle(header);
            sh.setColumnWidth(i, 18 * 256);
        }
        return sh;
    }
}
