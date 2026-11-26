package com.stockwise.importer;

import com.stockwise.importer.FieldDef.FieldType;

import java.util.List;

/**
 * Tipos de información importables y sus campos. El orden del enum es el orden recomendado de importación.
 * Los sinónimos incluyen encabezados habituales de reportes de sistemas ERP (se comparan sin tildes ni signos).
 */
public enum ImportType {

    SUCURSALES("Sucursales", List.of(
            new FieldDef("codigo", "Código", true, FieldType.TEXTO,
                    List.of("codigo", "cod", "codigo sucursal", "id sucursal", "sucursal", "cod sucursal",
                            "cod almacen", "codigo almacen"), "Código único de la sucursal"),
            new FieldDef("nombre", "Nombre", true, FieldType.TEXTO,
                    List.of("nombre", "nombre sucursal", "descripcion", "tienda", "nombre almacen"),
                    "Nombre de la sucursal"),
            new FieldDef("ciudad", "Ciudad", false, FieldType.TEXTO, List.of("ciudad", "localidad", "distrito"), ""),
            new FieldDef("direccion", "Dirección", false, FieldType.TEXTO, List.of("direccion", "domicilio"), ""))),

    PROVEEDORES("Proveedores", List.of(
            new FieldDef("nombre", "Proveedor", true, FieldType.TEXTO,
                    List.of("proveedor", "nombre", "razon social", "nombre proveedor", "fabricante"),
                    "Nombre del proveedor (se usa para relacionarlo con los productos)"),
            new FieldDef("contacto", "Contacto", false, FieldType.TEXTO, List.of("contacto", "vendedor", "ejecutivo"), ""),
            new FieldDef("telefono", "Teléfono", false, FieldType.TEXTO, List.of("telefono", "celular", "fono"), ""),
            new FieldDef("email", "Correo", false, FieldType.TEXTO, List.of("email", "correo", "e mail", "mail"), ""),
            new FieldDef("lead_time", "Tiempo de entrega (días)", false, FieldType.ENTERO,
                    List.of("lead time", "tiempo entrega", "tiempo de entrega", "dias entrega", "plazo entrega",
                            "tiempo reposicion", "leadtime"), "Se usa si el producto no tiene tiempo propio"),
            new FieldDef("ciclo", "Ciclo de compra (días)", false, FieldType.ENTERO,
                    List.of("ciclo compra", "ciclo de compra", "frecuencia pedido", "frecuencia de pedido",
                            "periodo revision", "ciclo"), "Cada cuántos días se le hace un pedido"))),

    PRODUCTOS("Productos", List.of(
            new FieldDef("codigo", "Código", true, FieldType.TEXTO,
                    List.of("codigo", "sku", "cod", "codigo producto", "id producto", "item", "referencia",
                            "cod articulo", "codigo articulo", "cod producto", "codigo interno", "cod item"),
                    "Código único (SKU)"),
            new FieldDef("nombre", "Nombre", true, FieldType.TEXTO,
                    List.of("nombre", "producto", "descripcion", "nombre producto", "articulo",
                            "descripcion articulo", "descripcion producto"), "Nombre del producto"),
            new FieldDef("categoria", "Categoría", false, FieldType.TEXTO,
                    List.of("categoria", "familia", "linea", "rubro", "grupo", "subfamilia"), ""),
            new FieldDef("proveedor", "Proveedor", false, FieldType.TEXTO, List.of("proveedor", "marca", "fabricante"), ""),
            new FieldDef("precio", "Precio de venta", true, FieldType.NUMERO,
                    List.of("precio", "precio venta", "pvp", "precio unitario", "precio de venta", "precio lista",
                            "precio publico", "p venta"), ""),
            new FieldDef("costo", "Costo", true, FieldType.NUMERO,
                    List.of("costo", "costo unitario", "precio compra", "precio de compra", "coste", "costo promedio",
                            "costo prom", "ultimo costo", "costo ultimo"), ""),
            new FieldDef("stock_minimo", "Stock mínimo", false, FieldType.ENTERO,
                    List.of("stock minimo", "minimo", "stock min", "min"), ""),
            new FieldDef("stock_maximo", "Stock máximo", false, FieldType.ENTERO,
                    List.of("stock maximo", "maximo", "stock max", "max"), ""),
            new FieldDef("lead_time", "Tiempo de reposición (días)", false, FieldType.ENTERO,
                    List.of("tiempo reposicion", "tiempo de reposicion", "lead time", "dias reposicion",
                            "tiempo reposicion dias", "tiempo de reposicion dias", "leadtime"), ""))),

    INVENTARIO("Inventario", List.of(
            new FieldDef("producto", "Código de producto", true, FieldType.TEXTO, Sinonimos.CODIGO,
                    "Si el código no está en el catálogo se busca por código de barras y por nombre"),
            new FieldDef("sucursal", "Sucursal", false, FieldType.TEXTO,
                    List.of("sucursal", "codigo sucursal", "tienda", "almacen", "local", "bodega", "deposito",
                            "store", "warehouse", "location", "branch"),
                    "Si se omite se usa la sucursal PRINCIPAL"),
            new FieldDef("stock", "Stock", true, FieldType.ENTERO,
                    List.of("stock", "cantidad", "existencias", "existencia", "stock actual", "unidades", "saldo",
                            "saldo actual", "stock fisico", "disponible", "cantidad disponible", "on hand",
                            "quantity on hand", "qty on hand", "inventory", "inventario", "stock disponible"),
                    "Los decimales se redondean a unidades enteras"),
            new FieldDef("nombre_producto", "Nombre del producto", false, FieldType.TEXTO, Sinonimos.NOMBRE,
                    "Permite reconocer el producto por su nombre si el código no está en el catálogo"),
            new FieldDef("codigo_barras", "Código de barras", false, FieldType.TEXTO, Sinonimos.BARRAS,
                    "Segunda forma de reconocer el producto"))),

    VENTAS("Ventas históricas", List.of(
            new FieldDef("fecha", "Fecha", true, FieldType.FECHA,
                    List.of("fecha", "fecha venta", "dia", "fecha de venta", "fecha emision", "fecha de emision",
                            "fecha doc", "fecha documento", "fecha factura", "fecha comprobante", "fecha hora",
                            "date", "sale date", "order date", "invoice date", "transaction date", "sales date"), ""),
            new FieldDef("producto", "Código de producto", true, FieldType.TEXTO, Sinonimos.CODIGO,
                    "Si el código no está en el catálogo se busca por código de barras y por nombre"),
            new FieldDef("sucursal", "Sucursal", false, FieldType.TEXTO,
                    List.of("sucursal", "codigo sucursal", "tienda", "local", "almacen", "punto de venta", "pdv",
                            "store", "branch", "location", "warehouse"),
                    "Si se omite se usa la sucursal PRINCIPAL"),
            new FieldDef("cantidad", "Cantidad", true, FieldType.NUMERO,
                    List.of("cantidad", "unidades", "qty", "cant", "unidades vendidas", "cantidad vendida",
                            "cant vendida", "quantity", "units", "units sold", "qty sold", "piezas"),
                    "Las cantidades negativas se registran como devoluciones"),
            new FieldDef("precio", "Precio unitario", false, FieldType.NUMERO,
                    List.of("precio", "precio unitario", "precio x unidad", "precio por unidad", "pvp", "precio venta",
                            "valor unitario", "p unit", "precio unit", "pu", "unit price", "price", "precio promedio"),
                    "Si hay total, se usa el total para respetar descuentos"),
            new FieldDef("total", "Total", false, FieldType.NUMERO,
                    List.of("total", "importe", "monto", "venta total", "subtotal", "importe total", "total venta",
                            "valor venta", "venta neta", "amount", "net amount", "total amount", "sales", "net sales",
                            "line total", "importe neto"), ""),
            new FieldDef("referencia", "Referencia / documento", false, FieldType.TEXTO,
                    List.of("referencia", "documento", "factura", "boleta", "comprobante", "nro documento", "nro doc",
                            "numero documento", "serie numero", "nro comprobante", "n doc", "n documento",
                            "n comprobante", "no doc", "num doc", "numero doc", "ticket", "invoice", "receipt",
                            "invoice number", "order", "order number"), ""),
            new FieldDef("nombre_producto", "Nombre del producto", false, FieldType.TEXTO, Sinonimos.NOMBRE,
                    "Permite reconocer el producto por su nombre y crearlo si no existe"),
            new FieldDef("categoria", "Categoría del producto", false, FieldType.TEXTO,
                    List.of("categoria", "familia", "linea", "rubro", "category"),
                    "Solo se usa al crear productos nuevos desde las ventas"),
            new FieldDef("costo", "Costo unitario", false, FieldType.NUMERO,
                    List.of("costo", "costo unitario", "costo promedio", "costo unit", "cost", "unit cost"),
                    "Solo se usa al crear productos nuevos desde las ventas"),
            new FieldDef("codigo_barras", "Código de barras", false, FieldType.TEXTO, Sinonimos.BARRAS,
                    "Segunda forma de reconocer el producto"),
            new FieldDef("descuento", "Descuento", false, FieldType.TEXTO,
                    List.of("descuento", "dscto", "dsct", "desc", "rebaja", "discount"),
                    "Importe de la línea o porcentaje (10 %); se usa si no hay total"))),

    COMPRAS("Órdenes de compra", List.of(
            new FieldDef("fecha", "Fecha de pedido", true, FieldType.FECHA,
                    List.of("fecha pedido", "fecha", "fecha oc", "fecha orden", "fecha emision", "fecha de emision",
                            "fecha de pedido", "fecha documento", "fecha compra", "fecha de compra", "purchase date",
                            "order date", "date"), ""),
            new FieldDef("producto", "Código de producto", true, FieldType.TEXTO, Sinonimos.CODIGO,
                    "Si el código no está en el catálogo se busca por código de barras y por nombre"),
            new FieldDef("sucursal", "Sucursal", false, FieldType.TEXTO,
                    List.of("sucursal", "codigo sucursal", "tienda", "almacen", "local", "almacen destino", "store",
                            "warehouse"),
                    "Si se omite se usa la sucursal PRINCIPAL"),
            new FieldDef("cantidad", "Cantidad", true, FieldType.ENTERO,
                    List.of("cantidad", "unidades", "qty", "cant", "cantidad pedida", "cant pedida", "quantity",
                            "cantidad comprada"), "Los decimales se redondean a unidades enteras"),
            new FieldDef("costo", "Costo unitario", false, FieldType.NUMERO,
                    List.of("costo unitario", "costo", "costo unit", "precio compra", "precio unitario", "p unit",
                            "valor unitario", "unit cost", "cost"),
                    "Si se omite se calcula desde el total o se usa el costo del producto"),
            new FieldDef("total", "Total", false, FieldType.NUMERO,
                    List.of("total", "importe", "monto", "importe total", "total compra", "amount"), ""),
            new FieldDef("proveedor", "Proveedor", false, FieldType.TEXTO,
                    List.of("proveedor", "razon social", "nombre proveedor", "supplier", "vendor"),
                    "Si se omite se usa el del producto"),
            new FieldDef("estado", "Estado", false, FieldType.TEXTO,
                    List.of("estado", "situacion", "status"),
                    "Pendiente / en tránsito, Recibida o Anulada (vacío = recibida)"),
            new FieldDef("fecha_esperada", "Fecha esperada de llegada", false, FieldType.FECHA,
                    List.of("fecha esperada", "fecha entrega", "fecha estimada", "eta", "fecha llegada",
                            "fecha prometida"), ""),
            new FieldDef("fecha_recepcion", "Fecha de recepción", false, FieldType.FECHA,
                    List.of("fecha recepcion", "fecha de recepcion", "fecha ingreso", "fecha llegada real",
                            "recepcion"), ""),
            new FieldDef("documento", "Nro. de orden / documento", false, FieldType.TEXTO,
                    List.of("orden", "nro orden", "numero orden", "oc", "nro oc", "documento", "nro documento",
                            "orden de compra", "purchase order", "po"), ""),
            new FieldDef("nombre_producto", "Nombre del producto", false, FieldType.TEXTO, Sinonimos.NOMBRE,
                    "Permite reconocer el producto por su nombre si el código no está en el catálogo"),
            new FieldDef("codigo_barras", "Código de barras", false, FieldType.TEXTO, Sinonimos.BARRAS,
                    "Segunda forma de reconocer el producto"))),

    PRESUPUESTOS("Presupuestos", List.of(
            new FieldDef("periodo", "Periodo (AAAA-MM)", true, FieldType.PERIODO,
                    List.of("periodo", "mes", "fecha", "anio mes"), ""),
            new FieldDef("sucursal", "Sucursal", false, FieldType.TEXTO,
                    List.of("sucursal", "codigo sucursal", "tienda"), "Vacío = presupuesto general"),
            new FieldDef("monto", "Monto", true, FieldType.NUMERO,
                    List.of("monto", "presupuesto", "importe", "valor"), ""),
            new FieldDef("descripcion", "Descripción", false, FieldType.TEXTO,
                    List.of("descripcion", "detalle", "observacion", "nota"), "")));

    /**
     * Sinónimos compartidos. El orden es la preferencia: si el reporte tiene SKU y código de barras, el SKU es el
     * código principal y el de barras la segunda opción; "Producto" queda al final porque suele ser el nombre.
     */
    private static final class Sinonimos {
        static final List<String> CODIGO = List.of("codigo producto", "cod producto", "codigo articulo", "cod articulo",
                "sku", "codigo", "cod", "codigo interno", "cod interno", "item code", "product code", "product id",
                "codigo de barra", "codigo de barras", "codigo barra", "codigo barras", "cod barra", "cod barras",
                "ean", "upc", "barcode", "item", "producto");
        static final List<String> NOMBRE = List.of("producto", "nombre producto", "nombre del producto", "descripcion",
                "descripcion producto", "descripcion articulo", "articulo", "nombre", "product", "product name",
                "description", "item description", "item name");
        static final List<String> BARRAS = List.of("codigo de barra", "codigo de barras", "codigo barra",
                "codigo barras", "cod barra", "cod barras", "ean", "ean13", "upc", "barcode", "gtin");
    }


    private final String etiqueta;
    private final List<FieldDef> campos;

    ImportType(String etiqueta, List<FieldDef> campos) {
        this.etiqueta = etiqueta;
        this.campos = campos;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public List<FieldDef> campos() {
        return campos;
    }

    /**
     * Tipo de una hoja por su nombre y, si el nombre no lo indica (Hoja1, Sheet1, reporte_2025) o sus columnas no
     * corresponden, por sus encabezados.
     */
    public static ImportType detectar(String nombreHoja, List<String> encabezados) {
        ImportType porNombre = detectar(nombreHoja);
        if (porNombre != null && completo(porNombre, encabezados)) {
            return porNombre;
        }
        ImportType porEncabezados = porEncabezados(encabezados);
        return porEncabezados != null ? porEncabezados : porNombre;
    }

    /** Verdadero si todas las columnas obligatorias del tipo se reconocen en los encabezados. */
    public static boolean completo(ImportType t, List<String> encabezados) {
        java.util.Map<String, Integer> m = ColumnMapper.sugerir(t, encabezados);
        return t.campos().stream().filter(FieldDef::obligatorio).allMatch(f -> m.containsKey(f.clave()));
    }

    /** Tipo deducido solo de los encabezados (nulo si no se reconoce ninguno). */
    public static ImportType porEncabezados(List<String> encabezados) {
        List<String> n = encabezados.stream().map(ColumnMapper::normalizar).toList();
        java.util.function.Predicate<String> hay = palabra -> n.stream()
                .anyMatch(h -> (" " + h + " ").contains(" " + palabra + " "));
        boolean pareceCompra = hay.test("proveedor") || hay.test("compra") || hay.test("pedido") || hay.test("oc")
                || hay.test("supplier") || hay.test("vendor") || hay.test("purchase");
        boolean pareceInventario = hay.test("stock") || hay.test("existencia") || hay.test("existencias")
                || hay.test("saldo") || hay.test("on hand") || hay.test("inventario");
        boolean conFecha = ColumnMapper.sugerir(VENTAS, encabezados).containsKey("fecha");
        if (pareceCompra && completo(COMPRAS, encabezados)) {
            return COMPRAS;
        }
        if (!conFecha && completo(PRODUCTOS, encabezados)) {
            return PRODUCTOS;
        }
        if (pareceInventario && completo(INVENTARIO, encabezados)) {
            return INVENTARIO;
        }
        if (completo(VENTAS, encabezados)) {
            return VENTAS;
        }
        if (completo(INVENTARIO, encabezados)) {
            return INVENTARIO;
        }
        return null;
    }

    /** Sugiere el tipo de importación a partir del nombre de la hoja (o del archivo CSV). */
    public static ImportType detectar(String nombreHoja) {
        String n = ColumnMapper.normalizar(nombreHoja);
        // "inventario" contiene la subcadena "venta": se evalúa primero. "presupuesto de compras" antes que compras.
        if (n.contains("inventario") || n.contains("stock") || n.contains("existencia")) {
            return INVENTARIO;
        }
        if (n.contains("presupuesto")) {
            return PRESUPUESTOS;
        }
        if (n.contains("venta")) {
            return VENTAS;
        }
        if (n.contains("proveedor")) {
            return PROVEEDORES;
        }
        if (n.contains("compra") || n.contains("orden") || n.contains("pedido")) {
            return COMPRAS;
        }
        if (n.contains("sucursal") || n.contains("tienda") || n.contains("local")) {
            return SUCURSALES;
        }
        if (n.contains("producto") || n.contains("articulo") || n.contains("catalogo")) {
            return PRODUCTOS;
        }
        return null;
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
