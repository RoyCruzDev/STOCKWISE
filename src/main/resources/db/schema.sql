-- Esquema STOCKWISE (SQLite). Se ejecuta en cada inicio; todas las sentencias son idempotentes.

CREATE TABLE IF NOT EXISTS configuracion (
    clave TEXT PRIMARY KEY,
    valor TEXT
);

CREATE TABLE IF NOT EXISTS categorias (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nombre TEXT NOT NULL UNIQUE COLLATE NOCASE
);

CREATE TABLE IF NOT EXISTS proveedores (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nombre TEXT NOT NULL UNIQUE COLLATE NOCASE,
    contacto TEXT,
    telefono TEXT,
    email TEXT,
    lead_time_dias INTEGER
);

CREATE TABLE IF NOT EXISTS sucursales (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    codigo TEXT NOT NULL UNIQUE COLLATE NOCASE,
    nombre TEXT NOT NULL,
    ciudad TEXT,
    direccion TEXT,
    activa INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS productos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    codigo TEXT NOT NULL UNIQUE COLLATE NOCASE,
    nombre TEXT NOT NULL,
    categoria_id INTEGER REFERENCES categorias(id) ON DELETE SET NULL,
    proveedor_id INTEGER REFERENCES proveedores(id) ON DELETE SET NULL,
    precio_venta REAL NOT NULL DEFAULT 0 CHECK (precio_venta >= 0),
    costo REAL NOT NULL DEFAULT 0 CHECK (costo >= 0),
    stock_minimo INTEGER NOT NULL DEFAULT 0 CHECK (stock_minimo >= 0),
    stock_maximo INTEGER NOT NULL DEFAULT 0 CHECK (stock_maximo >= 0),
    lead_time_dias INTEGER,
    activo INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS inventario (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    producto_id INTEGER NOT NULL REFERENCES productos(id) ON DELETE CASCADE,
    sucursal_id INTEGER NOT NULL REFERENCES sucursales(id) ON DELETE CASCADE,
    stock INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),
    fecha_actualizacion TEXT NOT NULL,
    UNIQUE (producto_id, sucursal_id)
);

CREATE TABLE IF NOT EXISTS ventas (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha TEXT NOT NULL,
    producto_id INTEGER NOT NULL REFERENCES productos(id) ON DELETE CASCADE,
    sucursal_id INTEGER NOT NULL REFERENCES sucursales(id) ON DELETE CASCADE,
    cantidad REAL NOT NULL CHECK (cantidad > 0),
    precio_unitario REAL NOT NULL CHECK (precio_unitario >= 0),
    total REAL NOT NULL,
    referencia TEXT,
    huella TEXT NOT NULL UNIQUE
);
CREATE INDEX IF NOT EXISTS idx_ventas_fecha ON ventas(fecha);
CREATE INDEX IF NOT EXISTS idx_ventas_producto ON ventas(producto_id, fecha);
CREATE INDEX IF NOT EXISTS idx_ventas_sucursal ON ventas(sucursal_id, fecha);

CREATE TABLE IF NOT EXISTS compras (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha TEXT NOT NULL,
    producto_id INTEGER NOT NULL REFERENCES productos(id) ON DELETE CASCADE,
    sucursal_id INTEGER NOT NULL REFERENCES sucursales(id) ON DELETE CASCADE,
    proveedor_id INTEGER REFERENCES proveedores(id) ON DELETE SET NULL,
    cantidad INTEGER NOT NULL CHECK (cantidad > 0),
    costo_unitario REAL NOT NULL CHECK (costo_unitario >= 0),
    total REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_compras_fecha ON compras(fecha);

CREATE TABLE IF NOT EXISTS movimientos_inventario (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha TEXT NOT NULL,
    producto_id INTEGER NOT NULL REFERENCES productos(id) ON DELETE CASCADE,
    sucursal_id INTEGER NOT NULL REFERENCES sucursales(id) ON DELETE CASCADE,
    tipo TEXT NOT NULL,
    cantidad INTEGER NOT NULL,
    stock_resultante INTEGER NOT NULL,
    nota TEXT
);
CREATE INDEX IF NOT EXISTS idx_mov_producto ON movimientos_inventario(producto_id, fecha);

CREATE TABLE IF NOT EXISTS presupuestos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    periodo TEXT NOT NULL,
    sucursal_id INTEGER REFERENCES sucursales(id) ON DELETE CASCADE,
    monto REAL NOT NULL CHECK (monto >= 0),
    descripcion TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_presupuesto_periodo ON presupuestos(periodo, IFNULL(sucursal_id, 0));

CREATE TABLE IF NOT EXISTS predicciones (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    producto_id INTEGER NOT NULL REFERENCES productos(id) ON DELETE CASCADE,
    fecha_generacion TEXT NOT NULL,
    metodo TEXT NOT NULL,
    periodo TEXT NOT NULL,
    valor REAL NOT NULL,
    mae REAL,
    mape REAL,
    confianza TEXT
);
CREATE INDEX IF NOT EXISTS idx_pred_producto ON predicciones(producto_id);

CREATE TABLE IF NOT EXISTS recomendaciones (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha_generacion TEXT NOT NULL,
    tipo TEXT NOT NULL,
    prioridad TEXT NOT NULL,
    producto_id INTEGER REFERENCES productos(id) ON DELETE CASCADE,
    sucursal_id INTEGER REFERENCES sucursales(id) ON DELETE CASCADE,
    sucursal_destino_id INTEGER REFERENCES sucursales(id) ON DELETE CASCADE,
    problema TEXT NOT NULL,
    accion TEXT NOT NULL,
    justificacion TEXT NOT NULL,
    beneficio REAL,
    estado TEXT NOT NULL DEFAULT 'PENDIENTE'
);

-- ---------------------------------------------------------------- Planificación avanzada (v1.1)
-- Las columnas nuevas de tablas existentes (compras, proveedores) se agregan por migración en
-- SchemaInitializer para conservar los datos de bases creadas con versiones anteriores.

-- Decisión estratégica por producto (una por producto y corrida). Se conserva el historial para medir
-- el impacto real de las decisiones (ROI) cuando llegan datos nuevos.
CREATE TABLE IF NOT EXISTS decisiones (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha_generacion TEXT NOT NULL,
    fecha_corte TEXT NOT NULL,
    producto_id INTEGER NOT NULL REFERENCES productos(id) ON DELETE CASCADE,
    decision TEXT NOT NULL,
    prioridad TEXT NOT NULL,
    patron_demanda TEXT,
    modelo TEXT,
    demanda_mensual REAL,
    stock REAL,
    en_transito REAL,
    stock_seguridad REAL,
    punto_reorden REAL,
    nivel_objetivo REAL,
    cantidad_sugerida INTEGER,
    costo_unitario REAL,
    precio_venta REAL,
    impacto_estimado REAL,
    motivo TEXT NOT NULL,
    accion TEXT NOT NULL,
    estado TEXT NOT NULL DEFAULT 'PENDIENTE'
);
CREATE INDEX IF NOT EXISTS idx_decisiones_producto ON decisiones(producto_id, fecha_corte);

-- Stock diario reconstruido a partir del inventario actual, ventas, compras recibidas y movimientos.
-- Un stock negativo indica datos incompletos (faltan compras o ajustes) y se conserva para diagnosticarlo.
CREATE TABLE IF NOT EXISTS stock_historico (
    producto_id INTEGER NOT NULL REFERENCES productos(id) ON DELETE CASCADE,
    sucursal_id INTEGER NOT NULL REFERENCES sucursales(id) ON DELETE CASCADE,
    fecha TEXT NOT NULL,
    stock REAL NOT NULL,
    entradas REAL NOT NULL DEFAULT 0,
    salidas REAL NOT NULL DEFAULT 0,
    PRIMARY KEY (producto_id, sucursal_id, fecha)
);

-- ---------------------------------------------------------------- Proyectos, historial y análisis (v3)

-- Cada archivo/hoja importado. Los registros transaccionales (ventas, compras, devoluciones) quedan vinculados
-- a las importaciones que los contenían (importacion_registros): al eliminar una importación solo se borran los
-- registros que no pertenecen a ninguna otra.
CREATE TABLE IF NOT EXISTS importaciones (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha TEXT NOT NULL,
    archivo TEXT NOT NULL,
    hoja TEXT,
    tipo TEXT NOT NULL,
    periodo_desde TEXT,
    periodo_hasta TEXT,
    filas INTEGER NOT NULL DEFAULT 0,
    insertados INTEGER NOT NULL DEFAULT 0,
    actualizados INTEGER NOT NULL DEFAULT 0,
    duplicados INTEGER NOT NULL DEFAULT 0,
    errores INTEGER NOT NULL DEFAULT 0,
    omitidos INTEGER NOT NULL DEFAULT 0,
    estructura TEXT,
    estado TEXT NOT NULL DEFAULT 'COMPLETADA'
);

CREATE TABLE IF NOT EXISTS importacion_registros (
    importacion_id INTEGER NOT NULL REFERENCES importaciones(id) ON DELETE CASCADE,
    tabla TEXT NOT NULL,
    registro_id INTEGER NOT NULL,
    nuevo INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (importacion_id, tabla, registro_id)
);
CREATE INDEX IF NOT EXISTS idx_imp_reg_registro ON importacion_registros(tabla, registro_id);

-- Devoluciones / notas de crédito: se descuentan de la demanda (ventas netas) en las series de predicción.
CREATE TABLE IF NOT EXISTS devoluciones (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha TEXT NOT NULL,
    producto_id INTEGER NOT NULL REFERENCES productos(id) ON DELETE CASCADE,
    sucursal_id INTEGER NOT NULL REFERENCES sucursales(id) ON DELETE CASCADE,
    cantidad REAL NOT NULL CHECK (cantidad > 0),
    total REAL NOT NULL DEFAULT 0,
    referencia TEXT,
    huella TEXT NOT NULL UNIQUE
);
CREATE INDEX IF NOT EXISTS idx_devoluciones_producto ON devoluciones(producto_id, fecha);

-- Corrida de análisis completa. Sus resultados por producto están en "decisiones" (analisis_id).
-- Eliminar un análisis borra solo sus resultados, nunca los datos históricos.
CREATE TABLE IF NOT EXISTS analisis (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha TEXT NOT NULL,
    fecha_corte TEXT NOT NULL,
    estado TEXT NOT NULL,
    duracion_ms INTEGER,
    productos INTEGER,
    con_pronostico INTEGER,
    ventas_desde TEXT,
    ventas_hasta TEXT,
    registros_ventas INTEGER,
    presupuesto REAL,
    compra_sugerida REAL,
    compra_aprobada REAL,
    wape REAL,
    sesgo REAL,
    fva REAL,
    notas TEXT
);
