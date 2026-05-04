---
name: data-pipeline-patterns
description: |
  Data pipeline and ETL patterns: PySpark/DataBricks pipeline structure, Delta Lake operations,
  incremental processing (watermarks, CDC), data quality frameworks (Great Expectations, dbt tests),
  SCD Type 2, star schema modeling, and pipeline orchestration patterns.
  Use when: building ETL/ELT pipelines, designing data warehouse models, implementing data quality checks,
  working with DataBricks/Spark, designing incremental loads, or implementing slowly changing dimensions.
  Triggers on: ETL, ELT, data pipeline, DataBricks, Spark, PySpark, Delta Lake, data warehouse,
  star schema, SCD, data quality, Great Expectations, dbt, incremental load, CDC.
---

# Data Pipeline Patterns

## Pipeline Project Structure

```
data-pipelines/
├── src/
│   ├── pipelines/                  # One module per pipeline
│   │   ├── orders/
│   │   │   ├── extract.py          # Source extraction logic
│   │   │   ├── transform.py        # Business transformations
│   │   │   ├── load.py             # Target loading logic
│   │   │   └── schema.py           # Input/output schema definitions
│   │   └── customers/
│   ├── common/
│   │   ├── spark_session.py        # SparkSession factory
│   │   ├── delta_utils.py          # Delta Lake helpers (merge, optimize)
│   │   ├── quality.py              # Data quality check framework
│   │   └── observability.py        # Metrics emission (DataDog)
│   └── config/
│       ├── dev.yaml
│       └── prod.yaml
├── tests/
│   ├── unit/                       # Test transforms with small DataFrames
│   └── fixtures/                   # Sample data files
├── dbt/                            # dbt project (if using dbt for warehouse transforms)
└── requirements.txt
```

**Rules:**
- Notebooks for exploration only. Production pipelines are Python modules, not notebooks.
- Config via YAML + environment variables. No hardcoded connection strings.
- Each pipeline is self-contained: extract, transform, load, schema.
- Tests use small DataFrames (10-100 rows) -- never full datasets.

## PySpark Pipeline Pattern

```python
from pyspark.sql import SparkSession, DataFrame
from pyspark.sql import functions as F
from delta.tables import DeltaTable

class OrdersPipeline:
    def __init__(self, spark: SparkSession, config: dict):
        self.spark = spark
        self.config = config

    def extract(self) -> DataFrame:
        return (self.spark.read.format("jdbc")
            .option("url", self.config["source_jdbc_url"])
            .option("dbtable", "(SELECT * FROM orders WHERE updated_at > :watermark) AS t")
            .option("fetchsize", 10000).load())

    def transform(self, raw: DataFrame) -> DataFrame:
        """Pure function -- no side effects."""
        return (raw
            .withColumn("order_date", F.to_date("created_at"))
            .withColumn("total_amount", F.col("quantity") * F.col("unit_price"))
            .withColumn("status_normalized", F.upper(F.trim("status")))
            .withColumn("_loaded_at", F.current_timestamp())
            .drop("raw_status", "legacy_field"))

    def load(self, transformed: DataFrame) -> None:
        """Delta Lake merge for idempotency."""
        path = self.config["target_path"]
        if DeltaTable.isDeltaTable(self.spark, path):
            target = DeltaTable.forPath(self.spark, path)
            (target.alias("t").merge(transformed.alias("s"), "t.order_id = s.order_id")
                .whenMatchedUpdateAll(condition="s._loaded_at > t._loaded_at")
                .whenNotMatchedInsertAll().execute())
        else:
            (transformed.write.format("delta").mode("overwrite")
                .partitionBy("order_date").save(path))

    def run(self) -> dict:
        raw = self.extract()
        raw_count = raw.count()
        transformed = self.transform(raw)
        self.load(transformed)
        return {"raw_count": raw_count, "loaded_count": transformed.count()}
```

### Transform Utilities

```python
# Schema validation -- fail fast on drift
def validate_schema(df: DataFrame, expected: StructType) -> DataFrame:
    missing = set(expected.fieldNames()) - set(df.columns)
    if missing:
        raise ValueError(f"Missing columns in source: {missing}")
    return df.select([F.col(f.name).cast(f.dataType) for f in expected.fields])

# Deduplication -- always dedupe before load
def deduplicate(df: DataFrame, key_cols: list, order_col: str) -> DataFrame:
    from pyspark.sql.window import Window
    window = Window.partitionBy(*key_cols).orderBy(F.col(order_col).desc())
    return (df.withColumn("_rn", F.row_number().over(window))
              .filter(F.col("_rn") == 1).drop("_rn"))
```

## Delta Lake Operations

### MERGE (Upsert) -- Default Load Pattern

```python
def delta_merge(spark, source_df, target_path, merge_keys, partition_cols=None):
    merge_cond = " AND ".join([f"t.{k} = s.{k}" for k in merge_keys])
    if DeltaTable.isDeltaTable(spark, target_path):
        target = DeltaTable.forPath(spark, target_path)
        (target.alias("t").merge(source_df.alias("s"), merge_cond)
            .whenMatchedUpdateAll().whenNotMatchedInsertAll().execute())
    else:
        writer = source_df.write.format("delta").mode("overwrite")
        if partition_cols:
            writer = writer.partitionBy(*partition_cols)
        writer.save(target_path)
```

### Maintenance

```python
# Optimize -- compact small files (run daily/weekly)
spark.sql(f"OPTIMIZE delta.`{path}` ZORDER BY (customer_id, order_date)")

# Vacuum -- remove old versions (run weekly)
spark.sql(f"VACUUM delta.`{path}` RETAIN 168 HOURS")  # 7 days

# Time travel
df_prev = spark.read.format("delta").option("timestampAsOf", "2026-04-12").load(path)

# Schema evolution
source_df.write.format("delta").mode("append").option("mergeSchema", "true").save(path)
```

## Incremental Processing

### Watermark Pattern

```python
def get_watermark(spark, table, pipeline):
    row = (spark.read.format("delta").load(table)
           .filter(F.col("pipeline") == pipeline)
           .select("last_processed_at").first())
    return row["last_processed_at"] if row else "1970-01-01T00:00:00Z"

def update_watermark(spark, table, pipeline, new_val):
    from pyspark.sql import Row
    df = spark.createDataFrame([Row(pipeline=pipeline, last_processed_at=new_val)])
    delta_merge(spark, df, table, ["pipeline"])

# Usage: extract WHERE updated_at > watermark, load, then update watermark
```

### CDC (Change Data Capture) Pattern

```python
def apply_cdc_events(spark, events_df, target_path, key_cols):
    merge_cond = " AND ".join([f"t.{k} = s.{k}" for k in key_cols])
    target = DeltaTable.forPath(spark, target_path)
    (target.alias("t").merge(events_df.alias("s"), merge_cond)
        .whenMatchedDelete(condition="s._cdc_op = 'DELETE'")
        .whenMatchedUpdateAll(condition="s._cdc_op = 'UPDATE'")
        .whenNotMatchedInsertAll(condition="s._cdc_op = 'INSERT'")
        .execute())
```

## Data Quality

### Great Expectations

```python
def validate_orders(df, context):
    validator = context.sources.pandas_default.read_dataframe(df.toPandas())
    validator.expect_column_values_to_not_be_null("order_id")
    validator.expect_column_values_to_be_unique("order_id")
    validator.expect_column_values_to_be_between("total_amount", min_value=0, max_value=1_000_000)
    validator.expect_column_values_to_match_regex("email", r"^[^@]+@[^@]+\.[^@]+$")
    result = validator.validate()
    return {"success": result.success, "failures": [r for r in result.results if not r.success]}
```

### dbt Tests

```yaml
# dbt/models/schema.yml
models:
  - name: fct_orders
    columns:
      - name: order_id
        tests: [not_null, unique]
      - name: customer_id
        tests:
          - not_null
          - relationships: {to: ref('dim_customers'), field: customer_id}
      - name: total_amount
        tests:
          - dbt_utils.accepted_range: {min_value: 0, max_value: 1000000}
```

## Star Schema & SCD Type 2

### Fact and Dimension Tables

```sql
-- Fact: measurable business events
CREATE TABLE fct_orders (
    order_sk        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        VARCHAR(50) NOT NULL,
    customer_sk     BIGINT REFERENCES dim_customers(customer_sk),
    product_sk      BIGINT REFERENCES dim_products(product_sk),
    date_sk         INT REFERENCES dim_dates(date_sk),
    quantity        INT NOT NULL,
    unit_price      DECIMAL(19,4) NOT NULL,
    total_amount    DECIMAL(19,4) NOT NULL,
    _loaded_at      TIMESTAMPTZ DEFAULT NOW()
);

-- SCD Type 2 dimension: tracks attribute history
CREATE TABLE dim_customers (
    customer_sk     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     VARCHAR(50) NOT NULL,       -- business key
    customer_name   VARCHAR(200),
    tier            VARCHAR(20),                -- tracked attribute
    region          VARCHAR(50),
    effective_from  TIMESTAMPTZ NOT NULL,
    effective_to    TIMESTAMPTZ DEFAULT '9999-12-31',
    is_current      BOOLEAN DEFAULT TRUE,
    _loaded_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_dim_cust_current ON dim_customers (customer_id) WHERE is_current = TRUE;
```

### SCD Type 2 Load (PySpark)

```python
def scd2_merge(spark, source_df, target_path, bk, tracked_cols):
    """Expire old records, insert new versions for changed attributes."""
    target = DeltaTable.forPath(spark, target_path)
    change_cond = " OR ".join([f"t.{c} != s.{c}" for c in tracked_cols])

    # Find changed records
    staged = (source_df.alias("s")
        .join(target.toDF().filter("is_current = true").alias("t"), on=bk, how="left")
        .filter(F.col(f"t.{bk}").isNull() | F.expr(change_cond))
        .select("s.*")
        .withColumn("effective_from", F.current_timestamp())
        .withColumn("effective_to", F.lit("9999-12-31").cast("timestamp"))
        .withColumn("is_current", F.lit(True)))

    if staged.count() == 0:
        return

    # Expire old current records
    expire_keys = staged.select(bk).distinct()
    (target.alias("t").merge(expire_keys.alias("e"),
        f"t.{bk} = e.{bk} AND t.is_current = true")
        .whenMatchedUpdate(set={"is_current": F.lit(False),
                                "effective_to": F.current_timestamp()}).execute())

    # Insert new versions
    staged.write.format("delta").mode("append").save(target_path)
```

## Pipeline Testing

```python
import pytest
from pyspark.sql import SparkSession

@pytest.fixture(scope="session")
def spark():
    return (SparkSession.builder.master("local[2]").appName("test")
            .config("spark.sql.shuffle.partitions", "2").getOrCreate())

def test_transform_calculates_total(spark):
    raw = spark.createDataFrame([
        {"order_id": "1", "quantity": 3, "unit_price": 10.0, "status": " pending "}
    ])
    result = transform_orders(raw)
    row = result.first()
    assert row["total_amount"] == 30.0
    assert row["status_normalized"] == "PENDING"

def test_deduplicate_keeps_latest(spark):
    df = spark.createDataFrame([
        {"order_id": "1", "updated_at": "2026-04-12T10:00:00", "status": "old"},
        {"order_id": "1", "updated_at": "2026-04-13T10:00:00", "status": "new"},
    ])
    result = deduplicate(df, ["order_id"], "updated_at")
    assert result.count() == 1
    assert result.first()["status"] == "new"
```

## Common Mistakes

1. **Notebooks in production** -- Production pipelines must be testable Python modules with version control and CI.
2. **Full reload when incremental is possible** -- Use watermarks or CDC. Full reloads waste compute as data grows.
3. **No idempotency** -- Running twice must produce the same result. Use MERGE, not INSERT. Deduplicate before load.
4. **Schema drift unhandled** -- Validate expected schema at extract. Fail fast, don't load garbage.
5. **Missing data quality checks** -- Validate between transform and load. Catch problems before downstream propagation.
6. **SCD Type 2 without effective dates** -- Every dimension change needs `effective_from`/`effective_to`.
7. **Over-partitioning** -- Partitioning by high-cardinality columns creates millions of tiny files. Partition by date.
8. **No pipeline observability** -- Emit: records processed, duration, quality pass/fail, cost (see observability skill).
