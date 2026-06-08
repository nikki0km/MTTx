
# Bug List

| ID | DBMS    | Link | Status    | Isolation Bug |  备注 |
|----|---------|------|-----------|---------------|---------|--------|
| 1  | MySQL   | [bug#119801](https://bugs.mysql.com/bug.php?id=119801) | 不一致  | Yes           | |
| 2  | MySQL   | [bug#119632](https://bugs.mysql.com/bug.php?id=119632) | 重复  | Yes           |[与bug#107066一样](https://bugs.mysql.com/bug.php?id=107066&thanks=4)，自增列  |
| 3  | MySQL   | [bug#119648](https://bugs.mysql.com/bug.php?id=119648) | 误报  | Yes           | [与bug#117835一样](https://bugs.mysql.com/bug.php?id=117835&thanks=4)   |
| 4  | MySQL   | [bug#119649](https://bugs.mysql.com/bug.php?id=119649) | 重复  | Yes           | [与bug#117860一样](https://bugs.mysql.com/bug.php?id=117860)  |
| 5  | MySQL   | [bug#119707](https://bugs.mysql.com/bug.php?id=119707) | 重复  | Yes           | 自增列，且是由串行化变并行找到的  |
| 6  | MySQL   | [bug#120129](https://bugs.mysql.com/bug.php?id=120129) | 未验证  | Yes           | 改变执行顺序找到，半一致读未生效 |
| 7 | MySQL    | [bug#120170](https://bugs.mysql.com/bug.php?id=120170&thanks=4) | 未验证 | Yes |改变执行顺序，阻塞解除后丢失更新 |
| 8  | MariaDB | [bug#38301](https://jira.mariadb.org/browse/MDEV-38301?filter=-2) | 验证  | Yes           |改变表初始数据|已回复|
| 9  | MariaDB | [bug#37318](https://jira.mariadb.org/browse/MDEV-37318) | 重复  | Yes           | [与bug#32898一样](https://jira.mariadb.org/browse/MDEV-32898)|
| 10  | MariaDB | [bug#39151](https://jira.mariadb.org/browse/MDEV-39151) | 未验证  | Yes           | 与mysql的120129 是同一个 |未回复|
| 11 | MariaDB    | [bug#39203](https://jira.mariadb.org/browse/MDEV-39203) | 未验证 | Yes |改变执行顺序，阻塞解除后丢失更新 |
| 12 | TiDB    | [pingcap/tidb#65444](https://github.com/pingcap/tidb/issues/65444) | 重复 | Yes |[与bug#36581一样，新版本中已经没有这个错](https://github.com/pingcap/tidb/issues/36581) |
| 13 | TiDB    | [pingcap/tidb#67213](https://github.com/pingcap/tidb/issues/67213) | 验证 | Yes |改变执行顺序 |
| 14 | TiDB    | [bug#67398](https://github.com/pingcap/tidb/issues/67398) | 未验证 | Yes |改变执行顺序，阻塞解除后丢失更新，且与mysql不同，delect操作丢失 |
| 15 | TiDB    | [bug#67400](https://github.com/pingcap/tidb/issues/67400) | 重复 | Yes |自增列  |
