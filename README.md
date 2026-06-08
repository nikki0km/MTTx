# Troc — Metamorphic Testing Framework for Database Transaction Isolation Levels

Troc is an automated bug detection tool for DBMS transaction isolation based on **Metamorphic Testing**. It randomly generates database schemas, populates tables with data, constructs concurrent transactions, and applies metamorphic relations to automatically uncover bugs in how DBMS engines implement transaction isolation.

## Prerequisites

- **JDK** 8 or higher
- **Maven** 3.6+
- A running target database instance (MySQL 5.7+ / MariaDB 10.2+ / TiDB 4.0+ )

## Build

```bash
mvn clean package
```

The build artifact is `target/test-1.0-SNAPSHOT.jar`. Dependencies are copied to `target/lib/`.

## Usage

```bash
java -jar target/test-1.0-SNAPSHOT.jar \
    --dbms     mysql \
    --host     127.0.0.1 \
    --port     3306 \
    --username root \
    --password root \
    --db       test \
    --table    troc \
    --mode     mt3
```

### Parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `--dbms` | Yes | Target database: `mysql` / `mariadb` / `tidb` / `postgres` |
| `--host` | Yes | Database host address |
| `--port` | Yes | Database port |
| `--username` | Yes | Database username |
| `--password` | Yes | Database password |
| `--db` | Yes | Test database name (will be auto-created and dropped — do NOT use a production database) |
| `--table` | Yes | Test table name prefix |
| `--mode` | Yes | Metamorphic mode: `mt1` / `mt2` / `mt3` / `mt4` |


## Bug List

| ID | DBMS    | Link | Status     | Isolation Bug | Notes |
|----|---------|------|------------|---------------|-------|
| 1  | MySQL   | [bug#119801](https://bugs.mysql.com/bug.php?id=119801) | Inconsistent | Yes | |
| 2  | MySQL   | [bug#119632](https://bugs.mysql.com/bug.php?id=119632) | Duplicate | Yes | [Same as bug#107066](https://bugs.mysql.com/bug.php?id=107066&thanks=4), auto-increment column |
| 3  | MySQL   | [bug#119648](https://bugs.mysql.com/bug.php?id=119648) | Not a Bug | Yes | [Same as bug#117835](https://bugs.mysql.com/bug.php?id=117835&thanks=4) |
| 4  | MySQL   | [bug#119649](https://bugs.mysql.com/bug.php?id=119649) | Duplicate | Yes | [Same as bug#117860](https://bugs.mysql.com/bug.php?id=117860) |
| 5  | MySQL   | [bug#119707](https://bugs.mysql.com/bug.php?id=119707) | Duplicate | Yes | Auto-increment column; found by changing serial to parallel execution |
| 6  | MySQL   | [bug#120129](https://bugs.mysql.com/bug.php?id=120129) | Unverified | Yes | Found by reordering execution; semi-consistent read not taking effect |
| 7  | MySQL   | [bug#120170](https://bugs.mysql.com/bug.php?id=120170&thanks=4) | Unverified | Yes | Found by reordering execution; lost update after lock released |
| 8  | MariaDB | [bug#38301](https://jira.mariadb.org/browse/MDEV-38301?filter=-2) | Verified | Yes | Changed initial table data \| 
| 9  | MariaDB | [bug#37318](https://jira.mariadb.org/browse/MDEV-37318) | Duplicate | Yes | [Same as bug#32898](https://jira.mariadb.org/browse/MDEV-32898) |
| 10 | MariaDB | [bug#39151](https://jira.mariadb.org/browse/MDEV-39151) | Unverified | Yes | Same root cause as MySQL bug#120129 \| 
| 11 | MariaDB | [bug#39203](https://jira.mariadb.org/browse/MDEV-39203) | Unverified | Yes | Found by reordering execution; lost update after lock released |
| 12 | TiDB    | [pingcap/tidb#65444](https://github.com/pingcap/tidb/issues/65444) | Duplicate | Yes | [Same as bug#36581](https://github.com/pingcap/tidb/issues/36581), already fixed in newer version |
| 13 | TiDB    | [pingcap/tidb#67213](https://github.com/pingcap/tidb/issues/67213) | Verified | Yes | Found by reordering execution |
| 14 | TiDB    | [bug#67398](https://github.com/pingcap/tidb/issues/67398) | Unverified | Yes | Found by reordering execution; lost update after lock released; unlike MySQL, DELETE operation also lost |
| 15 | TiDB    | [bug#67400](https://github.com/pingcap/tidb/issues/67400) | Duplicate | Yes | Auto-increment column |
