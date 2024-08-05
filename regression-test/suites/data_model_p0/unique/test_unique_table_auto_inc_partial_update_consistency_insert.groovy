// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

suite("test_unique_table_auto_inc_partial_update_correct_insert") {

    def backends = sql_return_maparray('show backends')
    def replicaNum = 0
    def targetBackend = null
    for (def be : backends) {
        def alive = be.Alive.toBoolean()
        def decommissioned = be.SystemDecommissioned.toBoolean()
        if (alive && !decommissioned) {
            replicaNum++
            targetBackend = be
        }
    }
    assertTrue(replicaNum > 0)

    def check_data_correct = { def tableName ->
        def old_result = sql "select id from ${tableName} order by id;"
        logger.info("first result: " + old_result)
        for (int i = 1; i<30; ++i){
            def new_result = sql "select id from ${tableName} order by id;"
            logger.info("new result: " + new_result)
            for (int j = 0; j<old_result.size();++j){
                if (old_result[j][0]!=new_result[j][0]){
                    logger.info("table name: " + tableName)
                    logger.info("old result: " + old_result)
                    logger.info("new result: " + new_result)
                    assertTrue(false)
                }
            }
            old_result = new_result
        }
    }
    
    // test for partial update, auto inc col is key
    def table1 = "unique_auto_inc_col_key_partial_update_insert"
    sql "drop table if exists ${table1}"
    sql """
        CREATE TABLE IF NOT EXISTS `${table1}` (
          `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT "用户 ID",
          `name` varchar(65533) NOT NULL COMMENT "用户姓名",
          `value` int(11) NOT NULL COMMENT "用户得分"
        ) ENGINE=OLAP
        UNIQUE KEY(`id`)
        COMMENT "OLAP"
        DISTRIBUTED BY HASH(`id`) BUCKETS 3
        PROPERTIES (
        "replication_num" = "${replicaNum}",
        "in_memory" = "false",
        "storage_format" = "V2",
        "enable_unique_key_merge_on_write" = "true"
        )
    """

    // Bob, 100
    // Alice, 200
    // Tom, 300
    // Test, 400
    // Carter, 500
    // Smith, 600
    // Beata, 700
    // Doris, 800
    // Nereids, 900
    sql "insert into ${table1} (name, value) values ('Bob',100),('Alice',200),('Tom',300),('Test',400),('Carter',500),('Smith',600),('Beata',700),('Doris',800),('Nereids',900)"

    qt_select1_1 "select name, value from ${table1} order by name, value;"
    qt_select1_2 "select id, count(*) from ${table1} group by id having count(*) > 1;"
    check_data_correct(table1)
    
    sql "set enable_unique_key_partial_update=true;"
    sql "set enable_insert_strict=false;"

    // 1, 123
    // 3, 323
    // 5, 523
    // 7, 723
    // 9, 923
    sql "insert into ${table1} (id, value) values (1,123),(3,323),(5,523),(7,723),(9,923)"
    qt_select1_3 "select name, value from ${table1} order by name, value;"
    qt_select1_4 "select id, count(*) from ${table1} group by id having count(*) > 1;"
    check_data_correct(table1)
    sql "drop table if exists ${table1};"

    // test for partial update, auto inc col is value, update auto inc col
    def table2 = "unique_auto_inc_col_value_partial_update_insert"
    sql "drop table if exists ${table2}"
    sql """
        CREATE TABLE IF NOT EXISTS `${table2}` (
          `name` varchar(65533) NOT NULL COMMENT "用户姓名",
          `value` int(11) NOT NULL COMMENT "用户得分",
          `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT "用户 ID"
        ) ENGINE=OLAP
        UNIQUE KEY(`name`)
        COMMENT "OLAP"
        DISTRIBUTED BY HASH(`name`) BUCKETS 3
        PROPERTIES (
        "replication_num" = "${replicaNum}",
        "in_memory" = "false",
        "storage_format" = "V2",
        "enable_unique_key_merge_on_write" = "true"
        )
    """
    
    sql "set enable_unique_key_partial_update=true;"
    sql "set enable_insert_strict=false;"


    // Bob, 100
    // Alice, 200
    // Tom, 300
    // Test, 400
    // Carter, 500
    // Smith, 600
    // Beata, 700
    // Doris, 800
    // Nereids, 900
    sql "insert into ${table2} (name, value) values ('Bob',100)"
    sql "insert into ${table2} (name, value) values ('Alice',200)"
    sql "insert into ${table2} (name, value) values ('Tom',300)"
    sql "insert into ${table2} (name, value) values ('Test',400)"
    sql "insert into ${table2} (name, value) values ('Carter',500)"
    sql "insert into ${table2} (name, value) values ('Smith',600)"
    sql "insert into ${table2} (name, value) values ('Beata',700)"
    sql "insert into ${table2} (name, value) values ('Doris',800)"
    sql "insert into ${table2} (name, value) values ('Nereids',900)"
    qt_select2_1 "select name, value from ${table2} order by name, value;"
    qt_select2_2 "select id, count(*) from ${table2} group by id having count(*) > 1;"
    check_data_correct(table2)

    sql "set enable_unique_key_partial_update=true;"
    sql "set enable_insert_strict=false;"
    // Bob, 9990
    // Tom, 9992
    // Carter, 9994
    // Beata, 9996
    // Nereids, 9998
    sql "insert into ${table2} (name, id) values ('Bob',9990),('Tom',9992),('Carter',9994),('Beata',9996),('Nereids',9998)"
    qt_select2_3 "select name, value from ${table2} order by name, value;"
    qt_select2_4 "select id, count(*) from ${table2} group by id having count(*) > 1;"
    check_data_correct(table2)
    sql "drop table if exists ${table2};"

    // test for partial update, auto inc col is value, update other col
    def table3 = "unique_auto_inc_col_value_partial_update_insert"
    sql "drop table if exists ${table3}"
    sql """
        CREATE TABLE IF NOT EXISTS `${table3}` (
          `name` varchar(65533) NOT NULL COMMENT "用户姓名",
          `value` int(11) NOT NULL COMMENT "用户得分",
          `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT "用户 ID"
        ) ENGINE=OLAP
        UNIQUE KEY(`name`)
        COMMENT "OLAP"
        DISTRIBUTED BY HASH(`name`) BUCKETS 3
        PROPERTIES (
        "replication_num" = "${replicaNum}",
        "in_memory" = "false",
        "storage_format" = "V2",
        "enable_unique_key_merge_on_write" = "true"
        )
    """
    sql "set enable_unique_key_partial_update=false;"
    sql "set enable_insert_strict=true;"

    // Bob, 100
    // Alice, 200
    // Tom, 300
    // Test, 400
    // Carter, 500
    // Smith, 600
    // Beata, 700
    // Doris, 800
    // Nereids, 900
    sql "insert into ${table2} (name, value) values ('Bob',100),('Alice',200),('Tom',300),('Test',400),('Carter',500),('Smith',600),('Beata',700),('Doris',800),('Nereids',900)"
    qt_select3_1 "select name, value from ${table3} order by name, value;"
    qt_select3_2 "select id, count(*) from ${table3} group by id having count(*) > 1;"
    check_data_correct(table3)

    sql "set enable_unique_key_partial_update=true;"
    sql "set enable_insert_strict=false;"
    // Bob, 9990
    // Tom, 9992
    // Carter, 9994
    // Beata, 9996
    // Nereids, 9998
    sql "insert into ${table2} (name, value) values ('Bob',9990)"
    sql "insert into ${table2} (name, value) values ('Tom',9992)"
    sql "insert into ${table2} (name, value) values ('Carter',9994)"
    sql "insert into ${table2} (name, value) values ('Beata',9996)"
    sql "insert into ${table2} (name, value) values ('Nereids',9998)"
    qt_select3_3 "select name, value from ${table3} order by name, value;"
    qt_select3_4 "select id, count(*) from ${table3} group by id having count(*) > 1;"
    check_data_correct(table3)

    sql "insert into ${table2} (name, value) values ('BBBBob',9990)"
    sql "insert into ${table2} (name, value) values ('TTTTom',9992)"
    sql "insert into ${table2} (name, value) values ('CCCCarter',9994)"
    sql "insert into ${table2} (name, value) values ('BBBBeata',9996)"
    sql "insert into ${table2} (name, value) values ('NNNNereids',9998)"
    qt_select3_5 "select name, value from ${table3} order by name, value;"
    qt_select3_6 "select id, count(*) from ${table3} group by id having count(*) > 1;"
    check_data_correct(table3)
    sql "drop table if exists ${table3};"
}

